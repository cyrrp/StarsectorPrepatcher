package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads the compiled agent classes and, for each patch.* key, lists the name
 * anchors its detection depends on: target classes, peer class names, member
 * names and descriptors, each classified obfuscated-looking vs readable and
 * game vs mod-internal. Raw material for the rename-fixture report; the
 * classification rules are heuristics documented there.
 *
 * <p>Strings are collected from LDC constants of every method reachable from a
 * key's entry points through agent-internal calls. Descriptors contribute their
 * embedded object names. Membership in the loaded game jars decides impl vs api.
 *
 * <p>Usage: PatchAnchorInventory <agent-raw-classes dir> <starsector-core dir> <out file>
 */
public final class PatchAnchorInventory {
    private static final String AGENT_ROOT = "com/starsector/prepatcher/";
    private static final String PT = "com/starsector/prepatcher/agent/PrepatcherTransformer";
    private static final String FF =
            "com/starsector/prepatcher/agent/FastForwardPresentationTransformer";

    private PatchAnchorInventory() {}

    private record MethodRef(String owner, String name) {}

    private static final Map<String, List<MethodRef>> KEY_ENTRIES = new LinkedHashMap<>();
    private static final Map<String, String> KEY_TARGETS = new LinkedHashMap<>();

    /** Dispatch outside PrepatcherTransformer: patch key -> entry methods. */
    static {
        String roi = "com/starsector/prepatcher/agent/ReadOnlyUiEconomyStepTransformer";
        wholeFile("commandTabNoGlobalEconomyStep", roi);
        KEY_TARGETS.put("commandTabNoGlobalEconomyStep",
                "com/fs/starfarer/campaign/command/F");
        wholeFile("commodityDetailNoGlobalEconomyStep", roi);
        KEY_TARGETS.put("commodityDetailNoGlobalEconomyStep",
                "com/fs/starfarer/campaign/ui/marketinfo/cdd/CommodityDetailDialogV2;"
                        + "com/fs/starfarer/campaign/ui/marketinfo/CommodityDetailDialog");
        wholeFile("marketDefensesNoGlobalEconomyStep", roi);
        KEY_TARGETS.put("marketDefensesNoGlobalEconomyStep",
                "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/MarketCMD;"
                        + "com/fs/starfarer/api/impl/campaign/rulecmd/salvage/Nex_MarketCMD");

        wholeFile("uiMarketMutationRefresh",
                "com/starsector/prepatcher/agent/MarketOverviewMutationTransformer",
                "com/starsector/prepatcher/agent/TradeMarketMutationTransformer",
                "com/starsector/prepatcher/agent/IndustryMarketMutationTransformer",
                "com/starsector/prepatcher/agent/CommodityMarketDataContractTransformer");
        KEY_TARGETS.put("uiMarketMutationRefresh",
                "com/fs/starfarer/campaign/ui/marketinfo/s;com/fs/starfarer/campaign/ui/class;"
                        + "industry dialog+panel (see file);"
                        + "com/fs/starfarer/campaign/econ/reach/CommodityMarketData");

        wholeFile("campaignCargoNoGlobalEconomyStep",
                "com/starsector/prepatcher/agent/VanillaDetachedCargoEconomyContractTransformer",
                "com/starsector/prepatcher/agent/AoTDDetachedCargoContextTransformer");
        KEY_TARGETS.put("campaignCargoNoGlobalEconomyStep",
                "com/fs/starfarer/campaign/econ/Economy;com/fs/starfarer/campaign/ui/class");
        KEY_ENTRIES.put("lootTransferNoGlobalEconomyStep",
                KEY_ENTRIES.get("campaignCargoNoGlobalEconomyStep"));
        KEY_TARGETS.put("lootTransferNoGlobalEconomyStep",
                "com/fs/starfarer/campaign/econ/Economy;com/fs/starfarer/campaign/ui/class");

        wholeFile("planetConditionMarketOpenNoGlobalEconomyStep",
                "com/starsector/prepatcher/agent/AoTDMarketOpenContextTransformer");
        KEY_TARGETS.put("planetConditionMarketOpenNoGlobalEconomyStep",
                "com/fs/starfarer/campaign/CampaignEngine");

        wholeFile("vanillaMarketOpenLocalization",
                "com/starsector/prepatcher/agent/VanillaMarketOpenLocalizationContractTransformer",
                "com/starsector/prepatcher/agent/AoTDMarketOpenContextTransformer",
                "com/starsector/prepatcher/agent/AoTDDetachedCargoContextTransformer");
        KEY_TARGETS.put("vanillaMarketOpenLocalization",
                "com/fs/starfarer/campaign/econ/Economy;"
                        + "com/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                        + "com/fs/starfarer/campaign/CampaignEngine;"
                        + "com/fs/starfarer/campaign/ui/class");

        wholeFile("aotdEconomyRestoreCoordination",
                "com/starsector/prepatcher/agent/AoTDEconomyRestoreCompletionTransformer");
        KEY_TARGETS.put("aotdEconomyRestoreCoordination",
                "com/fs/starfarer/api/impl/campaign/CoreLifecyclePluginImpl");

        wholeFile("marketScheduler@AoTDForkCompatibilityTransformer",
                "com/starsector/prepatcher/agent/AoTDForkCompatibilityTransformer");
        KEY_TARGETS.put("marketScheduler@AoTDForkCompatibilityTransformer",
                "data/kaysaar/aotd/tot/industries/AoTDConstructionSite (AoTD fork)");

        wholeFile("marketScheduler@DirectMarketObserveTransformer",
                "com/starsector/prepatcher/agent/DirectMarketObserveTransformer");
        KEY_TARGETS.put("marketScheduler@DirectMarketObserveTransformer",
                "com/fs/starfarer/campaign/econ/Market (+ any market subclass)");
    }

    private static void wholeFile(String key, String... classNames) {
        List<MethodRef> entries = KEY_ENTRIES.computeIfAbsent(key, k -> new ArrayList<>());
        for (String c : classNames) entries.add(new MethodRef(c, "*"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "Usage: PatchAnchorInventory <agent-raw-classes> <starsector-core>"
                            + " <repo-root> <out>");
        }
        Path agentDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path core = Path.of(args[1]).toAbsolutePath().normalize();
        Path repoRoot = Path.of(args[2]).toAbsolutePath().normalize();
        Path outPath = Path.of(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(outPath.getParent());
        PrintStream out = new PrintStream(Files.newOutputStream(outPath), false, "UTF-8");

        Set<String> implNames = new LinkedHashSet<>();
        Set<String> apiNames = new LinkedHashSet<>();
        for (String jar : new String[]{"starfarer_obf.jar", "fs.common_obf.jar",
                "fs.sound_obf.jar", "starfarer.api.jar"}) {
            try (JarFile jf = new JarFile(core.resolve(jar).toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                    String internal = name.substring(0, name.length() - 6);
                    if (jar.equals("starfarer.api.jar")) apiNames.add(internal);
                    else implNames.add(internal);
                }
            }
        }

        Map<String, ClassNode> agentClasses = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(agentDir)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassNode node = new ClassNode(Opcodes.ASM9);
                new ClassReader(Files.readAllBytes(p)).accept(node, 0);
                agentClasses.put(node.name, node);
            }
        }
        out.println("AGENT_CLASSES " + agentClasses.size()
                + " IMPL " + implNames.size() + " API " + apiNames.size());

        // ----- Auto-extract PrepatcherTransformer dispatch -----
        ClassNode pt = agentClasses.get(PT);
        MethodNode transform = pt.methods.stream()
                .filter(m -> m.name.equals("transform")).findFirst().orElseThrow();
        Map<String, List<MethodRef>> patchIdEntries = new LinkedHashMap<>();
        List<String> windowStrings = new ArrayList<>();
        List<MethodRef> windowHandles = new ArrayList<>();
        List<String> windowConstructed = new ArrayList<>();
        for (AbstractInsnNode insn : transform.instructions.toArray()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                windowStrings.add(s);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle h) {
                        windowHandles.add(new MethodRef(h.getOwner(), h.getName()));
                    }
                }
            } else if (insn instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                        && call.owner.startsWith(PT + "$")
                        && isStructuredPatch(agentClasses.get(call.owner))) {
                    windowConstructed.add(call.owner);
                } else if ((call.name.equals("apply") || call.name.equals("applyHyperspace"))
                        && call.owner.equals(PT)) {
                    // apply(state, patchId, enabled, action): patchId is the last
                    // string before the call; applyHyperspace additionally carries a
                    // trailing label string.
                    int patchIdIndex = call.name.equals("apply")
                            ? windowStrings.size() - 1 : windowStrings.size() - 2;
                    String patchId = patchIdIndex >= 0 ? windowStrings.get(patchIdIndex) : "?";
                    List<MethodRef> entries = patchIdEntries.computeIfAbsent(
                            patchId, k -> new ArrayList<>());
                    entries.addAll(windowHandles);
                    for (String constructed : windowConstructed) {
                        entries.add(new MethodRef(constructed, "match"));
                        entries.add(new MethodRef(constructed, "mutate"));
                        entries.add(new MethodRef(constructed, "verify"));
                    }
                    windowStrings.clear();
                    windowHandles.clear();
                    windowConstructed.clear();
                }
            }
        }
        out.println("== PrepatcherTransformer dispatch (patchId -> entry methods) ==");
        for (Map.Entry<String, List<MethodRef>> e : patchIdEntries.entrySet()) {
            out.println("DISPATCH " + e.getKey()
                    + " -> " + e.getValue().stream()
                    .map(r -> r.owner.substring(r.owner.lastIndexOf('/') + 1) + "." + r.name)
                    .distinct().toList());
        }

        // ----- Source-side case and gate extraction -----
        // javac emits all String-switch comparisons before the bodies, so case
        // labels cannot be recovered from bytecode position. Parse the switch
        // in source instead: case identifiers resolve through the compiled
        // classes' String constant fields.
        Map<String, String> fieldConsts = new LinkedHashMap<>();
        for (ClassNode cn : agentClasses.values()) {
            for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
                if (f.value instanceof String s) {
                    fieldConsts.putIfAbsent(cn.name + "." + f.name, s);
                }
            }
        }
        String ptSource = Files.readString(repoRoot.resolve(
                "source/agent/com/starsector/prepatcher/agent/PrepatcherTransformer.java"));
        // Helper predicates: private boolean xxxEnabled() { return config.a || ...; }
        Map<String, List<String>> helperFields = new LinkedHashMap<>();
        java.util.regex.Matcher hm = java.util.regex.Pattern.compile(
                "private boolean (\\w+)\\(\\) \\{(.*?)\\}", java.util.regex.Pattern.DOTALL)
                .matcher(ptSource);
        while (hm.find()) {
            List<String> fields = new ArrayList<>();
            java.util.regex.Matcher fm = java.util.regex.Pattern
                    .compile("config\\.(\\w+)").matcher(hm.group(2));
            while (fm.find()) fields.add(fm.group(1));
            java.util.regex.Matcher nested = java.util.regex.Pattern
                    .compile("(\\w+)\\(\\)").matcher(hm.group(2));
            while (nested.find()) {
                String n = nested.group(1);
                if (!n.equals(hm.group(1))) fields.add("helper:" + n);
            }
            helperFields.put(hm.group(1), fields);
        }
        // Expand helper: chains transitively.
        Map<String, Set<String>> helperExpanded = new LinkedHashMap<>();
        for (String helper : helperFields.keySet()) {
            Set<String> acc = new LinkedHashSet<>();
            Deque<String> work = new ArrayDeque<>(List.of(helper));
            while (!work.isEmpty()) {
                for (String f : helperFields.getOrDefault(work.poll(), List.of())) {
                    if (f.startsWith("helper:")) work.add(f.substring(7));
                    else acc.add(f);
                }
            }
            helperExpanded.put(helper, acc);
        }

        Map<String, Set<String>> patchIdToCases = new LinkedHashMap<>();
        Map<String, Set<String>> patchIdToConfig = new LinkedHashMap<>();
        int switchAt = ptSource.indexOf("switch (className)");
        String switchBody = ptSource.substring(switchAt,
                ptSource.indexOf("default ->", switchAt));
        java.util.regex.Matcher caseSplit = java.util.regex.Pattern.compile(
                "case\\s+([^-]*?)\\s*->").matcher(switchBody);
        List<int[]> caseRanges = new ArrayList<>();
        List<String[]> caseLabels = new ArrayList<>();
        while (caseSplit.find()) {
            caseRanges.add(new int[]{caseSplit.end()});
            caseLabels.add(caseSplit.group(1).split("\\s*,\\s*"));
        }
        for (int i = 0; i < caseRanges.size(); i++) {
            int bodyStart = caseRanges.get(i)[0];
            int bodyEnd = i + 1 < caseRanges.size()
                    ? caseRanges.get(i + 1)[0] : switchBody.length();
            String body = switchBody.substring(bodyStart, bodyEnd);
            Set<String> caseClasses = new LinkedHashSet<>();
            for (String label : caseLabels.get(i)) {
                String resolved = resolveCaseLabel(label.trim(), fieldConsts);
                if (resolved != null) caseClasses.add(resolved);
            }
            java.util.regex.Matcher am = java.util.regex.Pattern.compile(
                    "apply(?:Hyperspace)?\\(state,\\s*(\"[^\"]+\"|[\\w.$]+)")
                    .matcher(body);
            while (am.find()) {
                String patchId = am.group(1).startsWith("\"")
                        ? am.group(1).substring(1, am.group(1).length() - 1)
                        : fieldConsts.getOrDefault(PT + "." + am.group(1), am.group(1));
                patchIdToCases.computeIfAbsent(patchId, k -> new LinkedHashSet<>())
                        .addAll(caseClasses);
                int stmtEnd = body.indexOf(';', am.start());
                String stmt = stmtEnd > am.start()
                        ? body.substring(am.start(), stmtEnd) : body.substring(am.start());
                Set<String> gates = patchIdToConfig.computeIfAbsent(
                        patchId, k -> new LinkedHashSet<>());
                java.util.regex.Matcher cm = java.util.regex.Pattern
                        .compile("config\\.(\\w+)").matcher(stmt);
                while (cm.find()) gates.add(cm.group(1));
                java.util.regex.Matcher em = java.util.regex.Pattern
                        .compile("(\\w+Enabled)\\(\\)").matcher(stmt);
                while (em.find()) {
                    gates.add("helper:" + em.group(1));
                    for (String f : helperExpanded.getOrDefault(em.group(1), Set.of())) {
                        gates.add("~" + f); // helper-derived, distinguishable from direct
                    }
                }
            }
        }
        out.println("== patchId -> switch cases (target classes) ==");
        for (Map.Entry<String, Set<String>> e : patchIdToCases.entrySet()) {
            out.println("CASE " + e.getKey() + " = " + e.getValue());
        }
        out.println("== patchId -> config fields (gates) ==");
        for (Map.Entry<String, Set<String>> e : patchIdToConfig.entrySet()) {
            out.println("GATE " + e.getKey() + " = " + e.getValue());
        }
        out.println("== config helpers ==");
        for (Map.Entry<String, Set<String>> e : helperExpanded.entrySet()) {
            out.println("HELPER " + e.getKey() + " = " + e.getValue());
        }

        // ----- FastForward plan extraction (source-side; the declarative table is
        // regular enough that parsing plans.put/component/site expressions is more
        // faithful than simulating clinit stack motion) -----
        Map<String, Integer> ffBitConsts = new LinkedHashMap<>();
        for (org.objectweb.asm.tree.FieldNode f : agentClasses.get(FF).fields) {
            if (f.value instanceof Integer i) ffBitConsts.put(f.name, i);
        }
        String ffSource = Files.readString(repoRoot.resolve(
                "source/agent/com/starsector/prepatcher/agent/"
                        + "FastForwardPresentationTransformer.java"));
        List<FfPlan> plans = extractFastForwardPlans(ffSource, ffBitConsts);
        Map<Integer, Set<String>> bitToTargets = new LinkedHashMap<>();
        Map<Integer, List<String[]>> bitToSites = new LinkedHashMap<>();
        for (FfPlan row : plans) {
            for (int bit : row.bits) {
                bitToTargets.computeIfAbsent(bit, k -> new LinkedHashSet<>())
                        .addAll(row.classNames);
            }
            for (SiteRow s : row.sites) {
                bitToSites.computeIfAbsent(s.bit, k -> new ArrayList<>()).add(s.strings);
            }
        }
        out.println("== FastForward plans ==");
        for (Map.Entry<Integer, Set<String>> e : bitToTargets.entrySet()) {
            out.println("FFBIT " + e.getKey() + " key=" + bitToKey().get(e.getKey())
                    + " targets=" + e.getValue());
        }
        for (Map.Entry<Integer, List<String[]>> e : bitToSites.entrySet()) {
            out.println("FFKEY " + bitToKey().getOrDefault(e.getKey(), "bit" + e.getKey()));
            for (String[] s : e.getValue()) {
                out.println("  SITE targetMethod=" + s[0] + " targetDesc=" + s[1]
                        + " originalOwner=" + s[2] + " originalName=" + s[3]
                        + " originalDesc=" + s[4] + " wrapper=" + s[5]
                        + " wrapperDesc=" + s[6] + " count=" + s[7]);
            }
        }
        // Shared inspection machinery anchors apply to every fastForward key.
        Set<String> ffShared = collectStrings(agentClasses, List.of(
                new MethodRef(FF, "transform"),
                new MethodRef(FF, "inspect"),
                new MethodRef(FF, "apply")));
        out.println("FFSHARED " + ffShared);

        // ----- Per-key anchor collection -----
        // hookNames: member names declared on our own runtime/hook classes —
        // they are invoked from patched code but are our names, so a game rename
        // cannot disturb them.
        Set<String> hookNames = new LinkedHashSet<>();
        for (ClassNode cn : agentClasses.values()) {
            if (cn.name.startsWith("com/fs/starfarer/")
                    || cn.name.contains("StarsectorPrepatcher")) {
                for (MethodNode m : cn.methods) {
                    if (!m.name.startsWith("<")) hookNames.add(m.name);
                }
                for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
                    hookNames.add(f.name);
                }
            }
        }
        Set<String> configFields = new LinkedHashSet<>();
        for (Set<String> gates : patchIdToConfig.values()) {
            for (String g : gates) {
                if (!g.startsWith("helper:")) {
                    configFields.add(g.startsWith("~") ? g.substring(1) : g);
                }
            }
        }
        configFields.addAll(patchIdEntries.keySet()); // patchId strings, not members
        out.println("HOOK_NAMES " + hookNames.size());

        out.println("== Per-key anchors ==");
        Map<String, List<MethodRef>> allKeys = new LinkedHashMap<>(KEY_ENTRIES);
        for (Map.Entry<String, List<MethodRef>> e : patchIdEntries.entrySet()) {
            allKeys.computeIfAbsent("patchId:" + e.getKey(), k -> new ArrayList<>())
                    .addAll(e.getValue());
        }
        Map<String, Map<String, Set<String>>> perKeyStrings = new LinkedHashMap<>();
        Map<String, String> perKeyTargets = new LinkedHashMap<>();
        for (Map.Entry<String, List<MethodRef>> e : allKeys.entrySet()) {
            String key = e.getKey();
            String targets = KEY_TARGETS.get(key);
            if (key.startsWith("patchId:")) {
                targets = String.join(";",
                        patchIdToCases.getOrDefault(key.substring(8), Set.of()));
            }
            Map<String, Set<String>> methodStrings =
                    collectStringsPerMethod(agentClasses, e.getValue());
            perKeyStrings.put(key, methodStrings);
            perKeyTargets.put(key, targets);
            emitKeyReport(out, key, targets, methodStrings, implNames, apiNames,
                    hookNames, configFields);
        }

        // ----- Aggregate to shipped patch.* keys -----
        // key(field) -> patchIds gated by it; then union anchors. The two
        // composite patchIds gated only by the shared *Enabled() helpers
        // (campaignCacheLifecycle, saveMethodPlan) are shared infrastructure —
        // they are reported as SHARED rows listing their contributing keys,
        // not folded into each contributor.
        Set<String> sharedPatchIds = Set.of("campaignCacheLifecycle", "saveMethodPlan");
        Map<String, Set<String>> keyToPatchIds = new LinkedHashMap<>();
        Map<String, Set<String>> sharedContributors = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : patchIdToConfig.entrySet()) {
            for (String raw : e.getValue()) {
                if (raw.startsWith("helper:")) continue;
                boolean helperDerived = raw.startsWith("~");
                String field = helperDerived ? raw.substring(1) : raw;
                if (sharedPatchIds.contains(e.getKey())) {
                    // helper-derived contributors only (these patchIds have no
                    // direct config field); record as shared, not per-key
                    sharedContributors.computeIfAbsent(e.getKey(),
                            k -> new LinkedHashSet<>()).add(field);
                    continue;
                }
                keyToPatchIds.computeIfAbsent(field, k -> new LinkedHashSet<>())
                        .add(e.getKey());
            }
        }
        for (Map.Entry<String, Set<String>> e : sharedContributors.entrySet()) {
            out.println("SHARED " + e.getKey() + " contributors=" + e.getValue());
        }
        Set<String> shippedKeys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(repoRoot.resolve("prepatcher.properties"))) {
            java.util.regex.Matcher km = java.util.regex.Pattern
                    .compile("^patch\\.(\\w+)\\s*=").matcher(line.trim());
            if (km.find()) shippedKeys.add(km.group(1));
        }
        out.println("== Aggregated rows (shipped patch.* keys) ==");
        out.println("SHIPPED " + shippedKeys.size());
        for (String key : shippedKeys) {
            Buckets agg = new Buckets();
            Set<String> targets = new LinkedHashSet<>();
            // patchIds gated by this key
            for (String patchId : keyToPatchIds.getOrDefault(key, Set.of())) {
                mergeKeyStrings(perKeyStrings.getOrDefault("patchId:" + patchId,
                        Map.of()), agg, implNames, apiNames, hookNames, configFields);
                targets.addAll(patchIdToCases.getOrDefault(patchId, Set.of()));
            }
            // external transformer entries keyed by config field name
            if (KEY_ENTRIES.containsKey(key)) {
                mergeKeyStrings(perKeyStrings.getOrDefault(key, Map.of()), agg,
                        implNames, apiNames, hookNames, configFields);
                for (String t : KEY_TARGETS.getOrDefault(key, "").split(";")) {
                    if (!t.isEmpty()) targets.add(t);
                }
            }
            // marketScheduler key also owns the two external market transformers
            if (key.equals("marketScheduler")) {
                for (String extra : List.of(
                        "marketScheduler@AoTDForkCompatibilityTransformer",
                        "marketScheduler@DirectMarketObserveTransformer")) {
                    mergeKeyStrings(perKeyStrings.getOrDefault(extra, Map.of()), agg,
                            implNames, apiNames, hookNames, configFields);
                    for (String t : KEY_TARGETS.getOrDefault(extra, "").split(";")) {
                        if (!t.isEmpty()) targets.add(t);
                    }
                }
            }
            // FastForward bit keys
            if (key.equals("fastForwardPresentation")) {
                for (List<String[]> rows : bitToSites.values()) {
                    for (String[] s : rows) classifyFfRow(s, agg, implNames, apiNames,
                            hookNames, configFields);
                }
                for (Set<String> t : bitToTargets.values()) targets.addAll(t);
            } else if (key.startsWith("fastForward")) {
                int bit = bitForKey(key);
                for (String[] s : bitToSites.getOrDefault(bit, List.of())) {
                    classifyFfRow(s, agg, implNames, apiNames, hookNames, configFields);
                }
                targets.addAll(bitToTargets.getOrDefault(bit, Set.of()));
            }
            // shared inspection machinery is reported once, on the master key:
            // per-bit rows carry only their own plan targets and site anchors
            if (key.equals("fastForwardPresentation")) {
                for (String s : ffShared) {
                    classify(s, implNames, apiNames, hookNames, configFields, agg);
                }
            }
            out.println("ROW patch." + key);
            out.println("  TARGETS " + targets);
            out.println("  IMPL_CLASS " + agg.impl);
            out.println("  API_CLASS " + agg.api);
            out.println("  OTHER_CLASS " + agg.otherInternal);
            out.println("  MEMBER_OBF " + agg.memberObf);
            out.println("  MEMBER_READ " + agg.memberRead);
            out.println("  MEMBER_HOOK " + agg.memberHook.size()
                    + " JDK " + agg.memberJdk.size() + " DESC " + agg.descs.size()
                    + " SELF " + agg.self.size() + " LABEL " + agg.labels.size()
                    + " THIRD_PARTY " + agg.thirdParty.size());
        }
        out.flush();
    }

    private static void mergeKeyStrings(Map<String, Set<String>> methodStrings,
                                        Buckets agg, Set<String> implNames,
                                        Set<String> apiNames, Set<String> hookNames,
                                        Set<String> configFields) {
        for (Set<String> strings : methodStrings.values()) {
            for (String s : strings) {
                classify(s, implNames, apiNames, hookNames, configFields, agg);
            }
        }
    }

    /** Classify one FastForward site row: [method,desc,owner,name,odesc,wrapper,wdesc,count].
     *  Member slots are attributed by position: method/originalName are game
     *  member names (owner = plan target / originalOwner); wrapperName is ours. */
    private static void classifyFfRow(String[] s, Buckets agg, Set<String> implNames,
                                      Set<String> apiNames, Set<String> hookNames,
                                      Set<String> configFields) {
        classify(s[2], implNames, apiNames, hookNames, configFields, agg); // owner
        for (int i : new int[]{1, 4, 6}) { // descriptors
            classify(s[i], implNames, apiNames, hookNames, configFields, agg);
        }
        for (int i : new int[]{0, 3}) { // game member names
            if (isObfuscatedToken(s[i])) agg.memberObf.add(s[i]);
            else agg.memberRead.add(s[i]);
        }
        agg.memberHook.add(s[5]); // wrapper name — our own method
    }

    private static int bitForKey(String key) {
        for (Map.Entry<Integer, String> e : bitToKey().entrySet()) {
            if (e.getValue().equals(key)) return e.getKey();
        }
        return -1;
    }

    /** Resolves a switch case label to a class internal name via constant fields. */
    private static String resolveCaseLabel(String label, Map<String, String> fieldConsts) {
        if (label.startsWith("\"") && label.endsWith("\"")) {
            return label.substring(1, label.length() - 1);
        }
        int dot = label.lastIndexOf('.');
        if (dot > 0) { // HyperspacePatches.BASE_TILED etc.
            String owner = label.substring(0, dot).replace('.', '/');
            for (Map.Entry<String, String> e : fieldConsts.entrySet()) {
                if (e.getKey().endsWith("/" + owner.substring(owner.lastIndexOf('/') + 1)
                        + "." + label.substring(dot + 1))
                        || e.getKey().equals(AGENT_ROOT + "agent/" + owner + "."
                        + label.substring(dot + 1))) {
                    return e.getValue();
                }
            }
            // Direct match on unqualified owner tail.
            String key = "com/starsector/prepatcher/agent/" + owner + "."
                    + label.substring(dot + 1);
            return fieldConsts.get(key);
        }
        return fieldConsts.get(PT + "." + label);
    }

    private static boolean isStructuredPatch(ClassNode node) {
        return node != null && node.interfaces != null
                && node.interfaces.contains(PT + "$StructuredPatch");
    }

    private record SiteRow(int bit, String[] strings) {}
    private record FfPlan(List<String> classNames, List<Integer> bits,
                          List<SiteRow> sites) {}

    /** Index of the ')' matching the '(' at openIdx; skips string literals. */
    private static int matchParen(String src, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') {
                i++;
                while (i < src.length() && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return src.length();
    }

    /** All "..." literals in the text, in order. */
    private static List<String> stringLiterals(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Parses the declarative plans table in FastForwardPresentationTransformer's
     * static block: plans.put("name", planExpr) plus for-loops over
     * List.of("a","b",...) that put(name, planExpr). Inside each plan expression,
     * component(BIT, ...) / new Component(BIT, ...) contributes a bit; site(...)
     * and the lowPass/suppressMusic/playLoop/emitter factories contribute call
     * sites. Bit names resolve through the class's int constant fields.
     */
    private static List<FfPlan> extractFastForwardPlans(String src,
                                                      Map<String, Integer> bitConsts) {
        int start = src.indexOf("new LinkedHashMap<>()");
        int end = src.indexOf("TARGET_CLASSES =", start);
        String block = src.substring(start, end);
        List<FfPlan> rows = new ArrayList<>();
        int i = 0;
        while (i < block.length()) {
            int forAt = block.indexOf("for (String name", i);
            int putAt = block.indexOf("plans.put(", i);
            if (putAt < 0 && forAt < 0) break;
            if (forAt >= 0 && (putAt < 0 || forAt < putAt)) {
                int namesOpen = block.indexOf("List.of(", forAt) + "List.of".length();
                int namesClose = matchParen(block, namesOpen);
                List<String> names = stringLiterals(
                        block.substring(namesOpen + 1, namesClose));
                int putIn = block.indexOf("plans.put(name,", namesClose);
                int planStart = block.indexOf(',', putIn + 5) + 1;
                int planEnd = exprEnd(block, planStart);
                rows.add(parseFfPlan(names, block.substring(planStart, planEnd),
                        bitConsts));
                i = planEnd;
            } else {
                int nameStart = putAt + "plans.put(".length();
                int nameEnd = block.indexOf(',', nameStart);
                List<String> names = stringLiterals(
                        block.substring(nameStart, nameEnd));
                int planEnd = exprEnd(block, nameEnd + 1);
                rows.add(parseFfPlan(names,
                        block.substring(nameEnd + 1, planEnd), bitConsts));
                i = planEnd;
            }
        }
        return rows;
    }

    /** End of a Java expression: scan until paren depth goes negative or a ';'
     *  at depth 0; string literals skipped. */
    private static int exprEnd(String src, int from) {
        int depth = 0;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') {
                i++;
                while (i < src.length() && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return i;
                depth--;
            } else if (c == ';' && depth == 0) return i;
        }
        return src.length();
    }

    private static FfPlan parseFfPlan(List<String> names, String plan,
                                      Map<String, Integer> bitConsts) {
        List<Integer> bits = new ArrayList<>();
        List<SiteRow> sites = new ArrayList<>();
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                "(?:component|new Component)\\(\\s*(\\w+)").matcher(plan);
        List<int[]> compSpans = new ArrayList<>();
        List<String> compBits = new ArrayList<>();
        while (cm.find()) {
            int open = plan.indexOf('(', cm.start());
            compSpans.add(new int[]{cm.start(), matchParen(plan, open)});
            compBits.add(cm.group(1));
        }
        for (int c = 0; c < compSpans.size(); c++) {
            String bitName = compBits.get(c);
            int bit = bitConsts.getOrDefault(bitName, -1);
            bits.add(bit);
            String region = plan.substring(compSpans.get(c)[0], compSpans.get(c)[1]);
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
                    "(site|lowPass|suppressMusic|playLoop|emitter)\\(").matcher(region);
            while (sm.find()) {
                int open = region.indexOf('(', sm.start());
                String argText = region.substring(open + 1,
                        matchParen(region, open));
                List<String> strs = stringLiterals(argText);
                java.util.regex.Matcher countM = java.util.regex.Pattern
                        .compile(",\\s*(\\d+)\\s*$").matcher(argText);
                String count = countM.find() ? countM.group(1) : "?";
                String[] row = switch (sm.group(1)) {
                    case "site" -> new String[]{
                            strs.get(0), strs.get(1), strs.get(2), strs.get(3),
                            strs.get(4), strs.get(5), strs.get(6), count};
                    case "lowPass" -> new String[]{
                            strs.get(0), strs.get(1),
                            "com/fs/starfarer/api/SoundPlayerAPI",
                            "applyLowPassFilter", "(FF)V", "applyLowPassFilter",
                            "(Lcom/fs/starfarer/api/SoundPlayerAPI;FF)V", count};
                    case "suppressMusic" -> new String[]{
                            strs.get(0), strs.get(1),
                            "com/fs/starfarer/api/campaign/CampaignUIAPI",
                            "suppressMusic", "(F)V", "suppressMusic",
                            "(Lcom/fs/starfarer/api/campaign/CampaignUIAPI;F)V",
                            count};
                    case "playLoop" -> new String[]{
                            strs.get(0), strs.get(1),
                            "com/fs/starfarer/api/SoundPlayerAPI", "playLoop",
                            "(Ljava/lang/String;Ljava/lang/Object;FF"
                                    + "Lorg/lwjgl/util/vector/Vector2f;"
                                    + "Lorg/lwjgl/util/vector/Vector2f;)V",
                            "playLoop",
                            "(Lcom/fs/starfarer/api/SoundPlayerAPI;"
                                    + "Ljava/lang/String;Ljava/lang/Object;FF"
                                    + "Lorg/lwjgl/util/vector/Vector2f;"
                                    + "Lorg/lwjgl/util/vector/Vector2f;)V",
                            count};
                    default -> new String[]{ // emitter
                            strs.get(0), strs.get(1),
                            "com/fs/starfarer/api/util/IntervalUtil",
                            "advance", "(F)V", "advanceEmitterInterval",
                            "(Lcom/fs/starfarer/api/util/IntervalUtil;F)V", count};
                };
                sites.add(new SiteRow(bit, row));
            }
        }
        return new FfPlan(names, bits, sites);
    }

    private static Map<Integer, String> bitToKey() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(1, "fastForwardFrameMarker");
        m.put(2, "fastForwardActionIndicators");
        m.put(4, "fastForwardLocationVisuals");
        m.put(8, "fastForwardFloatingText");
        m.put(16, "fastForwardFleetView");
        m.put(32, "fastForwardFleetPresentation");
        m.put(64, "fastForwardSensorIndicators");
        m.put(128, "fastForwardCelestialVisuals");
        m.put(256, "fastForwardAuroraAnimation");
        m.put(512, "fastForwardContinuousSound");
        m.put(1024, "fastForwardGateJitter");
        m.put(2048, "fastForwardGlobalAnimations");
        m.put(4096, "fastForwardSensorFaders");
        m.put(8192, "fastForwardSlipstreamParticles");
        m.put(16384, "fastForwardParticleEmitters");
        return m;
    }

    /** All LDC/Type strings in every method reached from the entries. */
    private static Set<String> collectStrings(Map<String, ClassNode> agentClasses,
                                              List<MethodRef> entries) {
        return new LinkedHashSet<>(
                collectStringsPerMethod(agentClasses, entries).values().stream()
                        .flatMap(Set::stream).toList());
    }

    private static Map<String, Set<String>> collectStringsPerMethod(
            Map<String, ClassNode> agentClasses, List<MethodRef> entries) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<MethodRef> queue = new ArrayDeque<>(entries);
        Map<String, Set<String>> methodStrings = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            MethodRef ref = queue.poll();
            ClassNode owner = agentClasses.get(ref.owner);
            if (owner == null) continue;
            for (MethodNode m : owner.methods) {
                if (!ref.name.equals("*") && !m.name.equals(ref.name)) continue;
                String sig = owner.name + "." + m.name + m.desc;
                if (!visited.add(sig)) continue;
                String shortName = owner.name.substring(owner.name.lastIndexOf('/') + 1)
                        + "." + m.name;
                Set<String> strings = methodStrings.computeIfAbsent(shortName,
                        k -> new LinkedHashSet<>());
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof LdcInsnNode ldc) {
                        if (ldc.cst instanceof String s) strings.add(s);
                        if (ldc.cst instanceof Type t) {
                            if (t.getSort() == Type.OBJECT) strings.add(t.getInternalName());
                            if (t.getSort() == Type.ARRAY) {
                                strings.add(t.getElementType().getInternalName());
                            }
                        }
                    } else if (insn instanceof MethodInsnNode call
                            && call.owner.startsWith(AGENT_ROOT)) {
                        queue.add(new MethodRef(call.owner, call.name));
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle h && h.getOwner().startsWith(AGENT_ROOT)) {
                                queue.add(new MethodRef(h.getOwner(), h.getName()));
                            }
                        }
                    }
                }
            }
        }
        return methodStrings;
    }

    /** Bucket sets one key's strings are classified into. */
    private static final class Buckets {
        final Set<String> impl = new LinkedHashSet<>();
        final Set<String> api = new LinkedHashSet<>();
        final Set<String> otherInternal = new LinkedHashSet<>();
        final Set<String> self = new LinkedHashSet<>();
        final Set<String> descs = new LinkedHashSet<>();
        final Set<String> memberObf = new LinkedHashSet<>();
        final Set<String> memberRead = new LinkedHashSet<>();
        final Set<String> memberHook = new LinkedHashSet<>();
        final Set<String> memberJdk = new LinkedHashSet<>();
        final Set<String> labels = new LinkedHashSet<>();
        final Set<String> thirdParty = new LinkedHashSet<>();
    }

    private static void emitKeyReport(PrintStream out, String key, String targets,
                                      Map<String, Set<String>> methodStrings,
                                      Set<String> implNames, Set<String> apiNames,
                                      Set<String> hookNames, Set<String> configFields) {
        Buckets b = new Buckets();
        for (Set<String> strings : methodStrings.values()) {
            for (String s : strings) classify(s, implNames, apiNames, hookNames,
                    configFields, b);
        }
        out.println("KEY " + key);
        out.println("  TARGETS " + targets);
        out.println("  IMPL_CLASS " + b.impl);
        out.println("  API_CLASS " + b.api);
        out.println("  OTHER_CLASS " + b.otherInternal);
        out.println("  DESC " + b.descs);
        out.println("  MEMBER_OBF " + b.memberObf);
        out.println("  MEMBER_READ " + b.memberRead);
        out.println("  MEMBER_HOOK " + b.memberHook);
        out.println("  MEMBER_JDK " + b.memberJdk);
        out.println("  SELF " + b.self.size() + " LABEL " + b.labels.size()
                + " THIRD_PARTY " + b.thirdParty.size());
        out.println("  METHODS " + methodStrings.keySet());
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "null", "true", "false");

    /** Obfuscation-alphabet names: o/O/0/? soup, single letters, digits, keywords. */
    private static boolean isObfuscatedToken(String s) {
        if (s.matches("[oO0?]+") && s.length() >= 2) return true;
        if (s.matches("[a-zA-Z]")) return true;
        if (s.matches("[0-9]+")) return true;
        if (JAVA_KEYWORDS.contains(s)) return true;
        if (s.matches("[?]*[oO0-9?]*")) return true;
        return false;
    }

    private static boolean isToken(String s) {
        if (s.isEmpty() || s.length() > 300) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '?'
                    || c == '<' || c == '>')) return false;
        }
        return true;
    }

    private static void classify(String s, Set<String> implNames, Set<String> apiNames,
                                 Set<String> hookNames, Set<String> configFields,
                                 Buckets b) {
        if (s.startsWith(AGENT_ROOT) || s.contains("StarsectorPrepatcher")
                || s.startsWith("smo$") || s.startsWith("spp$") || s.startsWith("patch.")
                || s.startsWith("starsector.prepatcher")) {
            b.self.add(s);
            return;
        }
        // Packed call-site specs: "opcode|owner|name|desc|flags" — bind owner to
        // member so hook-owned and JDK-owned names are not mistaken for game
        // member anchors.
        if (s.contains("|") && s.matches("\\d+\\|.*")) {
            String[] parts = s.split("\\|");
            String owner = parts.length > 1 ? parts[1] : "";
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (part.matches("\\d+") || part.equals("receiver")) continue;
                if (i == 2 && !part.isEmpty()) { // member name slot
                    if (owner.contains("StarsectorPrepatcher")
                            || owner.startsWith(AGENT_ROOT)
                            || hookNames.contains(part)) {
                        b.memberHook.add(part);
                    } else if (owner.startsWith("java/") || owner.startsWith("javax/")) {
                        b.memberJdk.add(part);
                    } else if (isObfuscatedToken(part)) {
                        b.memberObf.add(part);
                    } else {
                        b.memberRead.add(part);
                    }
                    continue;
                }
                classify(part, implNames, apiNames, hookNames, configFields, b);
            }
            return;
        }
        if (implNames.contains(s)) {
            b.impl.add(s);
            return;
        }
        if (apiNames.contains(s)) {
            b.api.add(s);
            return;
        }
        if (s.startsWith("java/") || s.startsWith("javax/") || s.startsWith("jdk/")
                || s.startsWith("sun/") || s.startsWith("com/sun/")
                || s.startsWith("org/lwjgl/") || s.startsWith("org/json/")
                || s.startsWith("org/apache/") || s.startsWith("org/objectweb/")
                || s.startsWith("data/kaysaar/") || s.startsWith("exerelin/")) {
            (s.startsWith("data/kaysaar/") || s.startsWith("exerelin/")
                    ? b.otherInternal : b.thirdParty).add(s);
            return;
        }
        boolean descriptorShape = s.startsWith("(") || s.startsWith("[")
                || (s.startsWith("L") && s.endsWith(";"))
                || s.matches("[BCDFIJSZV]+");
        if (descriptorShape) {
            b.descs.add(s);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("L([^;<>()]+);").matcher(s);
            while (m.find()) {
                String inner = m.group(1);
                if (implNames.contains(inner)) b.impl.add(inner);
                else if (apiNames.contains(inner)) b.api.add(inner);
                else if (inner.startsWith("java/") || inner.startsWith("org/lwjgl/")) {
                    b.thirdParty.add(inner);
                } else if (!inner.startsWith(AGENT_ROOT)) {
                    b.otherInternal.add(inner);
                }
            }
            return;
        }
        if (s.contains("/") && !s.contains(" ")) {
            b.otherInternal.add(s);
            return;
        }
        if (isToken(s)) {
            if (configFields.contains(s) || s.matches("[A-Z][A-Z_]{2,}")) {
                b.labels.add(s); // config-field / status constants, not name anchors
            } else if (hookNames.contains(s)) {
                b.memberHook.add(s);
            } else if (isObfuscatedToken(s)) {
                b.memberObf.add(s);
            } else {
                b.memberRead.add(s);
            }
            return;
        }
        b.labels.add(s);
    }
}
