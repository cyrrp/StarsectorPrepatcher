package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Exact trade guard/fallback and same-class Cargo composition contract. */
public final class TradeMarketMutationTransformerTest {
    private static final String TARGET = TradeMarketMutationTransformer.TARGET;
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String ECONOMY_API =
            "com/fs/starfarer/api/campaign/econ/EconomyAPI";
    private static final String MARKET_DESC =
            "Lcom/fs/starfarer/campaign/econ/Market;";
    private static final String LEGACY_MARKET_FIELD = "if.new$class";
    private static final String REPAIRED_MARKET_FIELD = "if_new$class";
    private static final String GUARD_DESC =
            "(L" + ECONOMY_API + ";"
                    + "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;"
                    + "Lcom/fs/starfarer/api/campaign/PlayerMarketTransaction;)Z";
    private static final String CARGO_GUARD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Object;)Z";

    private TradeMarketMutationTransformerTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1,
                "Usage: TradeMarketMutationTransformerTest <starfarer_obf.jar>");
        inspectRuntimeFailOpenBoundaries();
        byte[] original = readClass(Path.of(args[0]), TARGET);
        byte[] repaired = IllegalObfuscatedMemberNameRepair.repair(TARGET, original);
        require(repaired != original, "trade fixture was not name-repaired");
        exerciseVariant(original, LEGACY_MARKET_FIELD);
        exerciseVariant(repaired, REPAIRED_MARKET_FIELD);
        assertFieldRejections(original);

        System.out.println("OK trade guard raw/repaired aliases preserve virtual doubleStep; "
                + "exact CFG, idempotence, rollback, BasicVerifier, and trade->Cargo composition");
    }

    private static void exerciseVariant(byte[] original, String expectedMarketField)
            throws Exception {
        TradeMarketMutationTransformer trade =
                new TradeMarketMutationTransformer(true, null);
        byte[] tradePatched = trade.transform(null, TARGET, null, null, original);
        require(tradePatched != null, "exact trade target was not patched: "
                + System.getProperty(TradeMarketMutationTransformer.statusProperty()));
        inspectTrade(tradePatched, expectedMarketField);
        verify(tradePatched);
        require(trade.transform(null, TARGET, null, null, tradePatched) == null,
                "trade idempotent reprocessing changed bytes");
        require("ALREADY_APPLIED".equals(System.getProperty(
                        TradeMarketMutationTransformer.statusProperty())),
                "trade idempotence status changed");

        byte[] future = changeCallName(original, ECONOMY_API,
                "doubleStep", "()V", "futureDoubleStep");
        byte[] futureBefore = future.clone();
        require(trade.transform(null, TARGET, null, null, future) == null,
                "changed trade step contract did not fail closed");
        require(Arrays.equals(future, futureBefore),
                "failed trade transformation mutated its input bytes");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        TradeMarketMutationTransformer.statusProperty())),
                "future trade fixture status is not SKIPPED_STRUCTURAL");

        // Actual registration order for the shared class: trade first, then
        // detached Cargo. Their method surfaces are independent, but the later
        // transformer must consume and preserve the earlier post-state.
        TradeMarketMutationTransformer orderedTrade =
                new TradeMarketMutationTransformer(true, null);
        byte[] orderedTradeBytes = orderedTrade.transform(
                null, TARGET, null, null, original);
        AoTDDetachedCargoContextTransformer cargo =
                new AoTDDetachedCargoContextTransformer(true, null);
        byte[] fullyPatched = cargo.transform(
                null, TARGET, null, null, orderedTradeBytes);
        require(fullyPatched != null,
                "Cargo transformer silently skipped the trade post-state");
        inspectTrade(fullyPatched, expectedMarketField);
        inspectCargo(fullyPatched);
        verify(fullyPatched);
        require(orderedTrade.transform(null, TARGET, null, null, fullyPatched) == null,
                "trade reprocessing changed the fully composed class");
        require(cargo.transform(null, TARGET, null, null, fullyPatched) == null,
                "Cargo reprocessing changed the fully composed class");

        byte[] cargoFuture = changeFakeMarketLiteral(orderedTradeBytes);
        byte[] cargoFutureBefore = cargoFuture.clone();
        require(cargo.transform(null, TARGET, null, null, cargoFuture) == null,
                "changed Cargo surface patched after the trade post-state");
        require(Arrays.equals(cargoFuture, cargoFutureBefore),
                "failed later transformer mutated the earlier trade post-state");
        inspectTrade(cargoFuture, expectedMarketField);
    }

    private static void inspectTrade(byte[] bytes, String expectedMarketField) {
        ClassNode node = read(bytes);
        FieldNode marker = field(node, "spp$patched$tradeMarketMutationRefresh");
        require(marker != null
                        && "StarsectorPrepatcher:trade-market-mutation-refresh-v2"
                        .equals(marker.value),
                "trade marker missing or stale");
        MethodNode method = method(node, "confirmTransaction", "()V");
        require(method.tryCatchBlocks.isEmpty(),
                "trade guard added an exception region");
        require(calls(method, RUNTIME,
                        "shouldHandleTradeMarketMutationEconomyStep", GUARD_DESC) == 1,
                "trade boolean guard count mismatch");
        require(calls(method, RUNTIME,
                        "applyTradeMarketMutationEconomyStep",
                        "(L" + ECONOMY_API + ";"
                                + "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;"
                                + "Lcom/fs/starfarer/api/campaign/PlayerMarketTransaction;)V") == 0,
                "obsolete void trade wrapper remains");
        require(calls(method, ECONOMY_API, "doubleStep", "()V") == 1,
                "original doubleStep fallback was removed or duplicated");
        require(calls(method, RUNTIME, "prepareTradeMarketMutation",
                        "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V") == 1,
                "trade preparation barrier count mismatch");

        MethodInsnNode guard = uniqueCall(method, RUNTIME,
                "shouldHandleTradeMarketMutationEconomyStep", GUARD_DESC);
        AbstractInsnNode branchInsn = nextMeaningful(guard);
        require(branchInsn instanceof JumpInsnNode branch
                        && branch.getOpcode() == Opcodes.IFNE,
                "trade guard does not branch around fallback");
        MethodInsnNode fallback = uniqueCall(
                method, ECONOMY_API, "doubleStep", "()V");
        AbstractInsnNode fallbackReceiver = previousMeaningful(fallback);
        AbstractInsnNode transaction = previousMeaningful(guard);
        AbstractInsnNode market = previousMeaningful(transaction);
        AbstractInsnNode owner = previousMeaningful(market);
        AbstractInsnNode guardEconomy = previousMeaningful(owner);
        require(market instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && TARGET.equals(field.owner)
                        && expectedMarketField.equals(field.name)
                        && MARKET_DESC.equals(field.desc),
                "trade guard does not use the selected market-field alias");
        require(fallbackReceiver instanceof VarInsnNode fallbackLoad
                        && fallbackLoad.getOpcode() == Opcodes.ALOAD
                        && guardEconomy instanceof VarInsnNode guardLoad
                        && guardLoad.getOpcode() == Opcodes.ALOAD
                        && fallbackLoad.var == guardLoad.var,
                "guard and fallback do not share the exact economy receiver");
        require(indexOf(method, branchInsn) < indexOf(method, fallback)
                        && indexOf(method, fallback)
                        < indexOf(method, ((JumpInsnNode) branchInsn).label),
                "trade guard target does not follow the virtual fallback");

        MethodInsnNode twConfirm = uniqueCall(method, TARGET, "twConfirm", "()V");
        MethodInsnNode report = uniqueCall(method,
                "com/fs/starfarer/campaign/CampaignEngine",
                "reportPlayerMarketTransaction",
                "(Lcom/fs/starfarer/api/campaign/PlayerMarketTransaction;)V");
        MethodInsnNode price = uniqueCall(method,
                "com/fs/starfarer/campaign/econ/Market", "updatePriceMult", "()V");
        require(indexOf(method, twConfirm) < indexOf(method, report)
                        && indexOf(method, report) < indexOf(method, guard)
                        && indexOf(method, guard) < indexOf(method, fallback)
                        && indexOf(method, fallback) < indexOf(method, price),
                "trade semantic call order changed");

        MethodInsnNode prepare = uniqueCall(method, RUNTIME,
                "prepareTradeMarketMutation",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
        AbstractInsnNode prepareMarket = previousMeaningful(prepare);
        require(prepareMarket instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && TARGET.equals(field.owner)
                        && expectedMarketField.equals(field.name)
                        && MARKET_DESC.equals(field.desc),
                "trade preparation does not use the selected market-field alias");
    }

    private static void inspectCargo(byte[] bytes) {
        ClassNode node = read(bytes);
        require(field(node, "spp$patched$aotdDetachedCargoContext") != null,
                "Cargo marker missing from composed class");
        int guards = 0;
        int fallbacks = 0;
        for (MethodNode method : node.methods) {
            guards += calls(method, RUNTIME,
                    "shouldSkipVanillaCargoEconomyStep", CARGO_GUARD_DESC);
            fallbacks += calls(method, ECONOMY_API, "tripleStep", "()V");
        }
        require(guards == 1, "Cargo guard count changed in composed class");
        require(fallbacks == 1, "Cargo tripleStep fallback count changed in composed class");
    }

    private static void inspectRuntimeFailOpenBoundaries() throws Exception {
        String resource = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge.class";
        byte[] bytes;
        try (var input = TradeMarketMutationTransformerTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            require(input != null, "missing runtime bridge class resource");
            bytes = input.readAllBytes();
        }
        ClassNode runtime = read(bytes);
        MethodNode trade = method(runtime,
                "shouldHandleTradeMarketMutationEconomyStep", GUARD_DESC);
        require(calls(trade, ECONOMY_API, "doubleStep", "()V") == 0,
                "runtime trade guard still owns the original doubleStep");
        requireThrowableCoveredCall(trade,
                "com/fs/starfarer/api/campaign/PlayerMarketTransaction",
                "getMarket", "()Lcom/fs/starfarer/api/campaign/econ/MarketAPI;");
        requireThrowableCoveredCall(trade,
                "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge",
                "collectTransactionCommodityIds",
                "(Lcom/fs/starfarer/api/campaign/PlayerMarketTransaction;)[Ljava/lang/String;");
        requireThrowableCoveredCall(trade,
                "com/fs/starfarer/api/Global", "getSector",
                "()Lcom/fs/starfarer/api/campaign/SectorAPI;");
        requireThrowableCoveredCall(trade,
                "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge",
                "containsMarketIdentity",
                "(Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                        + "Lcom/fs/starfarer/campaign/econ/Market;)Z");

        MethodNode shared = method(runtime,
                "shouldHandleVanillaUiMutationEconomyStep",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
        requireThrowableCoveredCall(shared,
                "com/fs/starfarer/api/Global", "getSector",
                "()Lcom/fs/starfarer/api/campaign/SectorAPI;");
        requireThrowableCoveredCall(shared,
                "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge",
                "containsMarketIdentity",
                "(Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                        + "Lcom/fs/starfarer/campaign/econ/Market;)Z");

        MethodNode marketOpen = method(runtime,
                "shouldHandleVanillaMarketOpenEconomyStep",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
        requireThrowableCoveredCall(marketOpen,
                "com/fs/starfarer/api/Global", "getSector",
                "()Lcom/fs/starfarer/api/campaign/SectorAPI;");
        requireThrowableCoveredCall(marketOpen,
                "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge",
                "containsMarketIdentity",
                "(Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;"
                        + "Lcom/fs/starfarer/campaign/econ/Market;)Z");
    }

    private static void requireThrowableCoveredCall(
            MethodNode method, String owner, String name, String desc) {
        int found = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !owner.equals(call.owner) || !name.equals(call.name)
                    || !desc.equals(call.desc)) {
                continue;
            }
            found++;
            int callIndex = indexOf(method, call);
            boolean covered = false;
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if ("java/lang/Throwable".equals(block.type)
                        && indexOf(method, block.start) <= callIndex
                        && callIndex < indexOf(method, block.end)) {
                    covered = true;
                    break;
                }
            }
            require(covered, owner + "." + name
                    + " is outside the Throwable fail-open boundary in " + method.name);
        }
        require(found > 0, "missing proof call " + owner + "." + name
                + " in " + method.name);
    }

    private static void assertFieldRejections(byte[] original) {
        assertRejected(renameMarketField(original, LEGACY_MARKET_FIELD,
                        "future_market_identity"),
                "unknown trade market-field alias was accepted");

        ClassNode wrongType = read(original);
        field(wrongType, LEGACY_MARKET_FIELD).desc = "Ljava/lang/Object;";
        assertRejected(write(wrongType), "wrong trade market-field descriptor was accepted");

        ClassNode ambiguous = read(original);
        FieldNode legacy = field(ambiguous, LEGACY_MARKET_FIELD);
        ambiguous.fields.add(new FieldNode(Opcodes.ASM9, legacy.access,
                REPAIRED_MARKET_FIELD, legacy.desc, legacy.signature, null));
        assertRejected(write(ambiguous), "ambiguous trade market-field aliases were accepted");
    }

    private static void assertRejected(byte[] bytes, String message) {
        byte[] before = bytes.clone();
        TradeMarketMutationTransformer transformer =
                new TradeMarketMutationTransformer(true, null);
        require(transformer.transform(null, TARGET, null, null, bytes) == null, message);
        require(Arrays.equals(bytes, before), "rejected trade bytes were mutated");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        TradeMarketMutationTransformer.statusProperty())),
                "trade market-field rejection status is not SKIPPED_STRUCTURAL");
    }

    private static byte[] renameMarketField(byte[] bytes, String from, String to) {
        ClassNode node = read(bytes);
        FieldNode declaration = field(node, from);
        require(declaration != null, "missing field alias " + from);
        declaration.name = to;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && TARGET.equals(field.owner) && from.equals(field.name)) {
                    field.name = to;
                }
            }
        }
        return write(node);
    }

    private static byte[] changeCallName(
            byte[] bytes, String owner, String name, String desc, String replacement) {
        ClassNode node = read(bytes);
        uniqueCall(method(node, "confirmTransaction", "()V"), owner, name, desc).name = replacement;
        return write(node);
    }

    private static byte[] changeFakeMarketLiteral(byte[] bytes) {
        ClassNode node = read(bytes);
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof LdcInsnNode ldc
                        && "fake_market".equals(ldc.cst)) {
                    ldc.cst = "future_fake_market";
                    changed++;
                }
            }
        }
        require(changed == 1, "fake_market fixture literal count changed: " + changed);
        return write(node);
    }

    private static byte[] readClass(Path jarPath, String internalName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "missing " + internalName);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        throw new AssertionError("missing method " + name + desc);
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                require(result == null, "duplicate call " + owner + "." + name);
                result = call;
            }
        }
        require(result != null, "missing call " + owner + "." + name);
        return result;
    }

    private static int calls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static int indexOf(MethodNode method, AbstractInsnNode target) {
        return method.instructions.indexOf(target);
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
