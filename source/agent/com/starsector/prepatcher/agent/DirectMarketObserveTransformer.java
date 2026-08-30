package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observation-only transformer for direct calls made by mod bytecode to
 * {@code MarketAPI.advance(float)}.
 *
 * <p>The original synchronous call is replaced by a typed static wrapper with
 * the same receiver/argument behavior. The wrapper records aggregate telemetry
 * and invokes the original market immediately. No call is delayed, merged or
 * suppressed by this transformer.</p>
 */
public final class DirectMarketObserveTransformer implements ClassFileTransformer {
    private static final String HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherHooks";
    private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String MARKET_IMPL = "com/fs/starfarer/campaign/econ/Market";
    private static final String HOOK_NAME = "observeDirectMarketAdvance";
    private static final String HOOK_DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;FJLjava/lang/String;)V";
    private static final char FIELD_SEPARATOR = '\u001f';
    private static final String SEMANTIC_RISK_HEADER =
            "modId,modName,modDirectory,jarName,source,className,methodName,descriptor,"
                    + "componentType,riskCategory,calledOwner,calledMethod,bytecodeOffset";

    private final PrepatcherConfig config;
    private final ClassLoader runtimeLoader;
    private final Path modRoot;
    private final Method registerSiteMethod;
    private final AtomicInteger patchedClasses = new AtomicInteger();
    private final AtomicInteger patchedCallSites = new AtomicInteger();
    private final Set<String> warnedClasses = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Set<String> semanticRiskRows = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Object semanticRiskWriteLock = new Object();
    private final ConcurrentHashMap<String, ModIdentity> modIdentityCache =
            new ConcurrentHashMap<>();

    public DirectMarketObserveTransformer(PrepatcherConfig config,
                                          ClassLoader runtimeLoader,
                                          Path modRoot) {
        this.config = config;
        this.runtimeLoader = runtimeLoader;
        this.modRoot = modRoot == null ? null : modRoot.toAbsolutePath().normalize();
        this.registerSiteMethod = resolveRegisterSiteMethod(runtimeLoader);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!(config.directMarketObservation || config.marketScheduler
                || config.marketAdvanceSemanticRiskObserver)
                || classfileBuffer == null
                || !shouldInspect(loader, className, protectionDomain)) {
            return null;
        }
        if (!isSupportedClassVersion(classfileBuffer)) {
            return null;
        }

        try {
            ClassNode node = readClass(classfileBuffer);
            String source = sourceLabel(protectionDomain, loader);
            ModIdentity modIdentity = resolveModIdentity(protectionDomain, source);
            if (config.marketAdvanceSemanticRiskObserver) {
                observeMarketAdvanceSemanticRisks(node, source, modIdentity, loader);
            }
            // Observer-only mode is strictly static: generate the CSV and return
            // the original bytes without installing runtime call-site wrappers.
            if (!(config.directMarketObservation || config.marketScheduler)) return null;
            int originalCalls = countOriginalCalls(node);
            int existingHooks = countHookCalls(node);
            if (originalCalls == 0) return null;
            if (existingHooks != 0) {
                warnOnce(className, "direct-market observation hook already exists while original "
                        + "Market.advance call sites remain; leaving class untouched");
                return null;
            }

            List<MethodNode> changedMethods = new ArrayList<>();
            List<SiteRegistration> registrations = new ArrayList<>();
            int changed = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                int line = -1;
                int ordinal = 0;
                boolean methodChanged = false;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof LineNumberNode lineNode) {
                        line = lineNode.line;
                        continue;
                    }
                    if (!(insn instanceof MethodInsnNode call) || !isMarketAdvance(call)) continue;

                    String amountSource = classifyAmountSource(method, call);
                    String metadata = metadata(source, modIdentity,
                            node.name, method.name, method.desc,
                            line, ordinal, amountSource, call.owner, call.getOpcode());
                    long siteId = fnv1a64(metadata);
                    registrations.add(new SiteRegistration(siteId, metadata));

                    InsnList replacement = new InsnList();
                    replacement.add(new LdcInsnNode(siteId));
                    replacement.add(new LdcInsnNode(metadata));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HOOKS, HOOK_NAME, HOOK_DESC, false));
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                    methodChanged = true;
                    changed++;
                    ordinal++;
                }
                if (methodChanged) changedMethods.add(method);
            }

            if (changed != originalCalls) {
                warnOnce(className, "direct-market observation structural mismatch: expected "
                        + originalCalls + " call sites, transformed " + changed);
                return null;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] candidate = writer.toByteArray();
            verifyTouchedMethods(candidate, changedMethods);

            ClassNode reparsed = readClass(candidate);
            if (countOriginalCalls(reparsed) != 0 || countHookCalls(reparsed) != changed) {
                warnOnce(className, "direct-market observation postcondition mismatch");
                return null;
            }

            registerSites(registrations, className);
            int classes = patchedClasses.incrementAndGet();
            int sites = patchedCallSites.addAndGet(changed);
            System.setProperty("starsector.prepatcher.directMarketPatchedClasses",
                    Integer.toString(classes));
            System.setProperty("starsector.prepatcher.directMarketPatchedCallSites",
                    Integer.toString(sites));
            PrepatcherLog.info("DIRECT_MARKET_OBSERVE patched " + className
                    + " from " + source + " [modId=" + modIdentity.modId
                    + ", modName=" + modIdentity.modName + "]: callSites=" + changed
                    + ", loader=" + loaderName(loader));
            return candidate;
        } catch (Throwable failure) {
            warnOnce(className, "direct-market observation failed open: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
            return null;
        }
    }

    private static boolean isSupportedClassVersion(byte[] bytes) {
        if (bytes.length < 8) return false;
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        return major <= Opcodes.V27;
    }


    private void observeMarketAdvanceSemanticRisks(
            ClassNode node, String source, ModIdentity modIdentity, ClassLoader loader) {
        for (MethodNode method : node.methods) {
            if (!"advance".equals(method.name) || !"(F)V".equals(method.desc)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            String componentType = componentType(node, loader);
            if (componentType == null) continue;
            boolean intervalAdvance = false;
            boolean intervalElapsed = false;
            boolean amountObserved = false;
            boolean fieldWrite = false;
            boolean conditional = false;
            boolean backwardJump = false;
            List<MethodInsnNode> intervalSites = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof VarInsnNode var
                        && var.getOpcode() == Opcodes.FLOAD && var.var == 1) {
                    amountObserved = true;
                }
                if (insn instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD) fieldWrite = true;
                if (insn instanceof JumpInsnNode jump) {
                    int opcode = jump.getOpcode();
                    if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) conditional = true;
                    if (method.instructions.indexOf(jump.label)
                            <= method.instructions.indexOf(jump)) backwardJump = true;
                }
                if (!(insn instanceof MethodInsnNode call)) continue;
                int offset = method.instructions.indexOf(call);
                if (call.owner.equals("com/fs/starfarer/api/util/IntervalUtil")
                        && call.name.equals("advance") && call.desc.equals("(F)V")) {
                    intervalAdvance = true;
                    intervalSites.add(call);
                } else if (call.owner.equals("com/fs/starfarer/api/util/IntervalUtil")
                        && call.name.equals("intervalElapsed") && call.desc.equals("()Z")) {
                    intervalElapsed = true;
                    intervalSites.add(call);
                }
                String category = randomRiskCategory(call);
                if (category != null) {
                    writeSemanticRisk(source, modIdentity, node.name, method, componentType,
                            category, call.owner, call.name, offset);
                }
                if (isMarketStructureMutation(call)) {
                    writeSemanticRisk(source, modIdentity, node.name, method, componentType,
                            "MARKET_STRUCTURE_MUTATION", call.owner, call.name, offset);
                }
                if (call.name.equals("convertToDays") && call.desc.contains("F")) {
                    amountObserved = true;
                }
            }
            if (intervalAdvance && intervalElapsed) {
                for (MethodInsnNode site : intervalSites) {
                    writeSemanticRisk(source, modIdentity, node.name, method, componentType,
                            "INTERVAL_SINGLE_ELAPSE", site.owner, site.name,
                            method.instructions.indexOf(site));
                }
            }
            if (amountObserved && fieldWrite && conditional && !backwardJump) {
                int offset = firstConditionalOffset(method);
                writeSemanticRisk(source, modIdentity, node.name, method, componentType,
                        "SINGLE_THRESHOLD_TRANSITION", node.name,
                        "<single-threshold-transition>", offset);
            }
        }
    }

    private static String componentType(ClassNode node, ClassLoader loader) {
        if (hasHierarchyType(node, loader,
                "com/fs/starfarer/api/campaign/econ/Industry",
                "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry")) {
            return "Industry";
        }
        if (hasHierarchyType(node, loader,
                "com/fs/starfarer/api/campaign/econ/MarketConditionPlugin",
                "com/fs/starfarer/api/impl/campaign/econ/BaseMarketConditionPlugin")) {
            return "MarketConditionPlugin";
        }
        if (hasHierarchyType(node, loader,
                "com/fs/starfarer/api/campaign/econ/SubmarketPlugin",
                "com/fs/starfarer/api/impl/campaign/submarkets/BaseSubmarketPlugin")) {
            return "SubmarketPlugin";
        }
        return null;
    }

    private static boolean hasHierarchyType(ClassNode node, ClassLoader loader,
                                            String apiType, String vanillaBase) {
        ArrayList<String> pending = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (node.superName != null) pending.add(node.superName);
        pending.addAll(node.interfaces);
        for (int index = 0; index < pending.size(); index++) {
            String name = pending.get(index);
            if (name == null || !seen.add(name)) continue;
            if (name.equals(apiType) || name.equals(vanillaBase)) return true;
            if (loader == null || name.startsWith("java/")) continue;
            try (InputStream input = loader.getResourceAsStream(name + ".class")) {
                if (input == null) continue;
                ClassNode parent = new ClassNode(Opcodes.ASM8);
                new ClassReader(input).accept(parent,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (parent.superName != null) pending.add(parent.superName);
                pending.addAll(parent.interfaces);
            } catch (Throwable ignored) {
                // Static diagnostics fail open for an unreadable hierarchy edge.
            }
        }
        return false;
    }

    private static String randomRiskCategory(MethodInsnNode call) {
        if (call.owner.equals("java/lang/Math") && call.name.equals("random")) {
            return "RANDOM_ROLL";
        }
        if ((call.owner.equals("java/util/Random")
                || call.owner.equals("java/util/concurrent/ThreadLocalRandom"))
                && (call.name.equals("nextFloat") || call.name.equals("nextDouble")
                || call.name.equals("nextInt") || call.name.equals("nextLong")
                || call.name.equals("nextBoolean"))) {
            return "RANDOM_ROLL";
        }
        if (call.owner.endsWith("/WeightedRandomPicker") && call.name.equals("pick")) {
            return "RANDOM_SELECTION";
        }
        return null;
    }

    private static boolean isMarketStructureMutation(MethodInsnNode call) {
        String owner = call.owner;
        if (!(owner.equals("com/fs/starfarer/api/campaign/econ/MarketAPI")
                || owner.equals("com/fs/starfarer/campaign/econ/Market")
                || owner.equals("com/fs/starfarer/api/impl/campaign/econ/impl/ConstructionQueue"))) {
            return false;
        }
        return switch (call.name) {
            case "addCondition", "removeCondition", "addIndustry", "removeIndustry",
                    "addSubmarket", "removeSubmarket", "addToEnd", "setItems",
                    "removeItem", "moveUp", "moveDown", "moveToFront", "moveToBack" -> true;
            default -> false;
        };
    }

    private static int firstConditionalOffset(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof JumpInsnNode jump
                    && jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) {
                return method.instructions.indexOf(insn);
            }
        }
        return -1;
    }

    private void writeSemanticRisk(String source, ModIdentity modIdentity,
                                   String className, MethodNode method,
                                   String componentType, String category,
                                   String calledOwner, String calledMethod, int offset) {
        String key = source + '\u001f' + modIdentity.modId + '\u001f'
                + modIdentity.modDirectory + '\u001f' + className + '\u001f'
                + method.name + method.desc + '\u001f' + category + '\u001f'
                + calledOwner + '\u001f' + calledMethod + '\u001f' + offset;
        if (!semanticRiskRows.add(key)) return;
        if (modRoot == null) return;
        Path report = modRoot.resolve("logs").resolve("market-advance-semantic-risks.csv");
        String row = csv(modIdentity.modId) + ',' + csv(modIdentity.modName) + ','
                + csv(modIdentity.modDirectory) + ',' + csv(modIdentity.jarName) + ','
                + csv(source) + ',' + csv(className.replace('/', '.')) + ','
                + csv(method.name) + ',' + csv(method.desc) + ',' + csv(componentType) + ','
                + csv(category) + ',' + csv(calledOwner.replace('/', '.')) + ','
                + csv(calledMethod) + ',' + offset + System.lineSeparator();
        synchronized (semanticRiskWriteLock) {
            try {
                Files.createDirectories(report.getParent());
                ensureSemanticRiskHeader(report);
                Files.writeString(report, row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable failure) {
                warnOnce(className, "semantic-risk report write failed: " + failure.getMessage());
            }
        }
    }

    private void ensureSemanticRiskHeader(Path report) throws Exception {
        if (Files.exists(report)) {
            String firstLine;
            try (var reader = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
                firstLine = reader.readLine();
            }
            if (!SEMANTIC_RISK_HEADER.equals(firstLine)) {
                Path legacy = report.resolveSibling("market-advance-semantic-risks-legacy-"
                        + System.currentTimeMillis() + ".csv");
                Files.move(report, legacy, StandardCopyOption.REPLACE_EXISTING);
                PrepatcherLog.warn("Rotated legacy semantic-risk CSV with incompatible header to "
                        + legacy.toAbsolutePath());
            }
        }
        if (!Files.exists(report)) {
            Files.writeString(report, SEMANTIC_RISK_HEADER + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    private static String modIdFromSource(String source) {
        String normalized = source == null ? "" : source.replace('\\', '/');
        int marker = normalized.indexOf("/mods/");
        if (marker < 0 && normalized.startsWith("mods/")) marker = -1;
        int start = marker >= 0 ? marker + 6 : (normalized.startsWith("mods/") ? 5 : -1);
        if (start >= 0) {
            int end = normalized.indexOf('/', start);
            return end < 0 ? normalized.substring(start) : normalized.substring(start, end);
        }
        return "unknown";
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static Method resolveRegisterSiteMethod(ClassLoader runtimeLoader) {
        if (runtimeLoader == null) return null;
        try {
            Class<?> bridge = Class.forName(
                    "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge",
                    false, runtimeLoader);
            return bridge.getMethod(
                    "registerDirectMarketCallSite", long.class, String.class);
        } catch (Throwable failure) {
            PrepatcherLog.warn("DIRECT_MARKET_OBSERVE could not resolve eager call-site "
                    + "registration bridge; metadata will be registered lazily: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
            return null;
        }
    }

    private void registerSites(List<SiteRegistration> registrations, String className) {
        if (registerSiteMethod == null || registrations.isEmpty()) return;
        try {
            for (SiteRegistration registration : registrations) {
                registerSiteMethod.invoke(null, registration.siteId(), registration.metadata());
            }
        } catch (Throwable failure) {
            warnOnce(className, "eager call-site metadata registration failed; wrappers "
                    + "remain active and will register lazily: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private boolean shouldInspect(ClassLoader loader, String className,
                                  ProtectionDomain protectionDomain) {
        if (loader == null || className == null || className.isEmpty()) return false;
        if (className.equals("module-info") || className.endsWith("/module-info")) return false;
        if (className.startsWith("com/starsector/prepatcher/")
                || className.startsWith("com/fs/starfarer/api/StarsectorPrepatcher")) {
            return false;
        }
        if (className.startsWith("java/") || className.startsWith("javax/")
                || className.startsWith("jdk/") || className.startsWith("sun/")) {
            return false;
        }
        if (runtimeLoader != null && !isSameOrDescendant(loader, runtimeLoader)) return false;

        String source = sourceLocation(protectionDomain).toLowerCase(Locale.ROOT)
                .replace('\\', '/');
        if (source.contains("starsectorprepatcheragent.jar")
                || source.contains("starsectorprepatcherbootstrap.jar")
                || source.endsWith("/starfarer_obf.jar")
                || source.endsWith("/starfarer.api.jar")
                || source.endsWith("/fs.common_obf.jar")
                || source.endsWith("/fs.sound_obf.jar")) {
            return false;
        }

        // System-loader classes are game/library classes unless their code source
        // explicitly lives in the mods tree. Child script/plugin loaders are the
        // normal path for mod classes and are accepted even when CodeSource is null.
        if (loader == runtimeLoader && !source.contains("/mods/")) return false;
        return true;
    }

    private static boolean isSameOrDescendant(ClassLoader loader, ClassLoader ancestor) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static int countOriginalCalls(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && isMarketAdvance(call)) count++;
            }
        }
        return count;
    }

    private static int countHookCalls(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && HOOKS.equals(call.owner)
                        && HOOK_NAME.equals(call.name)
                        && HOOK_DESC.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isMarketAdvance(MethodInsnNode call) {
        if (!"advance".equals(call.name) || !"(F)V".equals(call.desc)) return false;
        if (MARKET_API.equals(call.owner)) {
            return call.getOpcode() == Opcodes.INVOKEINTERFACE
                    || call.getOpcode() == Opcodes.INVOKEVIRTUAL;
        }
        return MARKET_IMPL.equals(call.owner) && call.getOpcode() == Opcodes.INVOKEVIRTUAL;
    }

    private static String classifyAmountSource(MethodNode method, MethodInsnNode call) {
        AbstractInsnNode source = previousMeaningful(call.getPrevious());
        if (source == null) return "UNKNOWN";
        int opcode = source.getOpcode();
        if (source instanceof VarInsnNode variable && opcode == Opcodes.FLOAD) {
            int argument = argumentAtLocal(method.access, method.desc, variable.var);
            return argument >= 0 ? "ARG" + argument : "LOCAL" + variable.var;
        }
        if (source instanceof LdcInsnNode constant && constant.cst instanceof Float value) {
            return "CONST:" + Float.toString(value);
        }
        return switch (opcode) {
            case Opcodes.FCONST_0 -> "CONST:0.0";
            case Opcodes.FCONST_1 -> "CONST:1.0";
            case Opcodes.FCONST_2 -> "CONST:2.0";
            case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
                    Opcodes.FNEG, Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> "DERIVED";
            case Opcodes.GETFIELD, Opcodes.GETSTATIC -> "FIELD";
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE,
                    Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> "CALL_RESULT";
            default -> "OPCODE:" + opcode;
        };
    }

    private static int argumentAtLocal(int access, String descriptor, int wantedLocal) {
        int local = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++) {
            if (local == wantedLocal && arguments[i].getSort() == Type.FLOAT) return i;
            local += arguments[i].getSize();
        }
        return -1;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node;
        while (current instanceof LabelNode || current instanceof LineNumberNode
                || current instanceof FrameNode || current.getOpcode() < 0) {
            current = current.getPrevious();
            if (current == null) return null;
        }
        return current;
    }

    private String sourceLabel(ProtectionDomain protectionDomain, ClassLoader loader) {
        Path path = sourcePath(protectionDomain);
        if (path != null) {
            try {
                if (modRoot != null) {
                    Path gameRoot = modRoot.getParent() == null ? null : modRoot.getParent().getParent();
                    if (gameRoot != null && path.startsWith(gameRoot)) {
                        return sanitize(gameRoot.relativize(path).toString().replace('\\', '/'));
                    }
                }
                return sanitize(path.toString().replace('\\', '/'));
            } catch (Throwable ignored) {
                // Fall through to the cleaned external URL below.
            }
        }
        String raw = cleanSourceLocation(sourceLocation(protectionDomain));
        if (!raw.isEmpty()) return sanitize(raw);
        return "loader:" + loaderName(loader);
    }

    private ModIdentity resolveModIdentity(
            ProtectionDomain protectionDomain, String source) {
        Path sourcePath = sourcePath(protectionDomain);
        if (sourcePath == null || modRoot == null || modRoot.getParent() == null) {
            String fallback = modIdFromSource(source);
            return new ModIdentity(fallback, fallback, fallback,
                    sourceJarName(sourcePath, source));
        }
        Path modsRoot = modRoot.getParent().toAbsolutePath().normalize();
        if (!sourcePath.startsWith(modsRoot)) {
            String fallback = modIdFromSource(source);
            return new ModIdentity(fallback, fallback, fallback,
                    sourceJarName(sourcePath, source));
        }
        Path relative = modsRoot.relativize(sourcePath);
        if (relative.getNameCount() == 0) {
            return new ModIdentity("unknown", "unknown", "unknown",
                    sourceJarName(sourcePath, source));
        }
        String directory = relative.getName(0).toString();
        String jarName = sourceJarName(sourcePath, source);
        return modIdentityCache.computeIfAbsent(directory + FIELD_SEPARATOR + jarName,
                ignored -> readModIdentity(modsRoot.resolve(directory), directory,
                        jarName));
    }

    private static Path sourcePath(ProtectionDomain protectionDomain) {
        String raw = cleanSourceLocation(sourceLocation(protectionDomain));
        if (raw.isEmpty()) return null;
        try {
            URL url = new URL(raw);
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                String decoded = decodeFileUrlPath(url.getPath());
                if (java.io.File.separatorChar == '\\'
                        && decoded.length() >= 3 && decoded.charAt(0) == '/'
                        && Character.isLetter(decoded.charAt(1)) && decoded.charAt(2) == ':') {
                    decoded = decoded.substring(1);
                }
                String authority = url.getAuthority();
                if (authority != null && !authority.isBlank()
                        && !"localhost".equalsIgnoreCase(authority)) {
                    decoded = "//" + authority + (decoded.startsWith("/") ? decoded : "/" + decoded);
                }
                return Path.of(decoded).toAbsolutePath().normalize();
            }
            return Path.of(new URI(raw)).toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String cleanSourceLocation(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = raw;
        int archiveSuffix = cleaned.lastIndexOf("!/");
        if (archiveSuffix >= 0) cleaned = cleaned.substring(0, archiveSuffix);
        else if (cleaned.endsWith("!")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.regionMatches(true, 0, "jar:", 0, 4)) cleaned = cleaned.substring(4);
        return cleaned;
    }

    private static String decodeFileUrlPath(String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            // URLDecoder has the desired UTF-8 percent decoding but treats '+'
            // as form-space; quote literal plus signs before using it for a path.
            return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return path;
        }
    }

    private static ModIdentity readModIdentity(
            Path modDirectory, String directoryName, String jarName) {
        String id = directoryName;
        String name = directoryName;
        Path info = modDirectory.resolve("mod_info.json");
        try {
            String json = Files.readString(info, StandardCharsets.UTF_8);
            String rootId = rootStringProperty(json, "id");
            String rootName = rootStringProperty(json, "name");
            if (!rootId.isBlank()) id = rootId;
            if (!rootName.isBlank()) name = rootName;
        } catch (Throwable ignored) {
            // A missing or malformed mod_info.json falls back to the directory name.
        }
        return new ModIdentity(id, name, directoryName, jarName);
    }

    /** Reads a string property only from the root JSON object. */
    private static String rootStringProperty(String json, String wantedKey) {
        if (json == null || wantedKey == null) return "";
        int cursor = skipJsonWhitespace(json, 0);
        if (cursor < json.length() && json.charAt(cursor) == '\ufeff') {
            cursor = skipJsonWhitespace(json, cursor + 1);
        }
        if (cursor >= json.length() || json.charAt(cursor) != '{') return "";
        cursor++;
        while (true) {
            cursor = skipJsonWhitespace(json, cursor);
            if (cursor >= json.length() || json.charAt(cursor) == '}') return "";
            ParsedJsonString key = parseJsonString(json, cursor);
            if (key == null) return "";
            cursor = skipJsonWhitespace(json, key.next);
            if (cursor >= json.length() || json.charAt(cursor) != ':') return "";
            cursor = skipJsonWhitespace(json, cursor + 1);
            if (cursor >= json.length()) return "";
            if (json.charAt(cursor) == '"') {
                ParsedJsonString value = parseJsonString(json, cursor);
                if (value == null) return "";
                if (wantedKey.equals(key.value)) return value.value;
                cursor = value.next;
            } else {
                cursor = skipJsonValue(json, cursor);
                if (cursor < 0) return "";
            }
            cursor = skipJsonWhitespace(json, cursor);
            if (cursor >= json.length() || json.charAt(cursor) == '}') return "";
            if (json.charAt(cursor) != ',') return "";
            cursor++;
        }
    }

    /** Skips JSON whitespace plus the comment forms accepted by Starsector data files. */
    private static int skipJsonWhitespace(String json, int cursor) {
        while (cursor < json.length()) {
            char current = json.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (current == '#') {
                cursor = skipJsonLineComment(json, cursor + 1);
                continue;
            }
            if (current == '/' && cursor + 1 < json.length()) {
                char next = json.charAt(cursor + 1);
                if (next == '/') {
                    cursor = skipJsonLineComment(json, cursor + 2);
                    continue;
                }
                if (next == '*') {
                    int end = json.indexOf("*/", cursor + 2);
                    return end < 0 ? json.length() : skipJsonWhitespace(json, end + 2);
                }
            }
            break;
        }
        return cursor;
    }

    private static int skipJsonLineComment(String json, int cursor) {
        while (cursor < json.length()) {
            char current = json.charAt(cursor++);
            if (current == '\n' || current == '\r') break;
        }
        return cursor;
    }

    private static int skipJsonValue(String json, int cursor) {
        cursor = skipJsonWhitespace(json, cursor);
        if (cursor >= json.length()) return -1;
        char first = json.charAt(cursor);
        if (first == '"') {
            ParsedJsonString value = parseJsonString(json, cursor);
            return value == null ? -1 : value.next;
        }
        if (first == '{' || first == '[') {
            StringBuilder closers = new StringBuilder();
            closers.append(first == '{' ? '}' : ']');
            cursor++;
            while (cursor < json.length()) {
                int next = skipJsonWhitespace(json, cursor);
                if (next != cursor) {
                    cursor = next;
                    continue;
                }
                char current = json.charAt(cursor);
                if (current == '"') {
                    ParsedJsonString value = parseJsonString(json, cursor);
                    if (value == null) return -1;
                    cursor = value.next;
                    continue;
                }
                if (current == '{' || current == '[') {
                    closers.append(current == '{' ? '}' : ']');
                } else if (closers.length() > 0
                        && current == closers.charAt(closers.length() - 1)) {
                    closers.setLength(closers.length() - 1);
                    if (closers.length() == 0) return cursor + 1;
                }
                cursor++;
            }
            return -1;
        }
        while (cursor < json.length()) {
            int next = skipJsonWhitespace(json, cursor);
            if (next != cursor) {
                cursor = next;
                continue;
            }
            char current = json.charAt(cursor);
            if (current == ',' || current == '}' || current == ']') break;
            cursor++;
        }
        return cursor;
    }

    private static ParsedJsonString parseJsonString(String json, int quote) {
        if (quote >= json.length() || json.charAt(quote) != '"') return null;
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int cursor = quote + 1; cursor < json.length(); cursor++) {
            char current = json.charAt(cursor);
            if (!escaped) {
                if (current == '"') return new ParsedJsonString(value.toString(), cursor + 1);
                if (current == '\\') escaped = true;
                else value.append(current);
                continue;
            }
            escaped = false;
            switch (current) {
                case '"', '\\', '/' -> value.append(current);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (cursor + 4 >= json.length()) return null;
                    try {
                        value.append((char) Integer.parseInt(
                                json.substring(cursor + 1, cursor + 5), 16));
                    } catch (NumberFormatException invalid) {
                        return null;
                    }
                    cursor += 4;
                }
                default -> value.append(current);
            }
        }
        return null;
    }

    private record ParsedJsonString(String value, int next) {}

    private static String sourceJarName(Path sourcePath, String source) {
        String name = sourcePath == null || sourcePath.getFileName() == null
                ? "" : sourcePath.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            String normalized = cleanSourceLocation(source).replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String tail = slash < 0 ? normalized : normalized.substring(slash + 1);
            name = tail.toLowerCase(Locale.ROOT).endsWith(".jar") ? tail : "";
        }
        return name;
    }

    private static String sourceLocation(ProtectionDomain protectionDomain) {
        try {
            CodeSource codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            return location == null ? "" : location.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String metadata(String source, ModIdentity modIdentity,
                                   String className, String method,
                                   String descriptor, int line, int ordinal,
                                   String amountSource, String callOwner, int opcode) {
        return String.join(String.valueOf(FIELD_SEPARATOR),
                "2",
                sanitize(source),
                sanitize(modIdentity.modId),
                sanitize(modIdentity.modName),
                sanitize(modIdentity.modDirectory),
                sanitize(modIdentity.jarName),
                sanitize(className.replace('/', '.')),
                sanitize(method),
                sanitize(descriptor),
                Integer.toString(line),
                Integer.toString(ordinal),
                sanitize(amountSource),
                sanitize(callOwner.replace('/', '.')),
                Integer.toString(opcode));
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = null;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character != FIELD_SEPARATOR && character != '\r' && character != '\n') {
                if (result != null) result.append(character);
                continue;
            }
            if (result == null) {
                result = new StringBuilder(value.length());
                result.append(value, 0, i);
            }
            result.append(' ');
        }
        return result == null ? value : result.toString();
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            hash ^= character & 0xffL;
            hash *= 0x100000001b3L;
            hash ^= character >>> 8;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void verifyTouchedMethods(byte[] bytes, List<MethodNode> originalMethods) {
        Set<String> touched = new HashSet<>();
        for (MethodNode method : originalMethods) touched.add(method.name + method.desc);
        ClassNode node = readClass(bytes);
        for (MethodNode method : node.methods) {
            if (!touched.contains(method.name + method.desc)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException failure) {
                throw new IllegalStateException("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc + ": " + failure.getMessage(), failure);
            }
        }
    }

    private void warnOnce(String className, String message) {
        String key = className == null ? "<unknown>" : className;
        if (warnedClasses.add(key)) {
            PrepatcherLog.warn("DIRECT_MARKET_OBSERVE " + key + ": " + message);
        }
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private record SiteRegistration(long siteId, String metadata) {}

    private record ModIdentity(
            String modId, String modName, String modDirectory, String jarName) {}
}
