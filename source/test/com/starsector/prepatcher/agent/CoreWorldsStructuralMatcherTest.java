package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Structural regression for the three-class incremental core-worlds contract. */
public final class CoreWorldsStructuralMatcherTest {
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime";
    private static final String PATCH_ID = "coreWorldsExtentCache";
    private static final String ENGINE = PrepatcherTransformer.CAMPAIGN_ENGINE;
    private static final String LOCATION = PrepatcherTransformer.BASE_LOCATION;
    private static final String CORE_SCRIPT = PrepatcherTransformer.CORE_SCRIPT;
    private static final String SYSTEM_DESC =
            "Lcom/fs/starfarer/api/campaign/StarSystemAPI;";
    private static final String SECTOR_DESC =
            "Lcom/fs/starfarer/api/campaign/SectorAPI;";
    private static final String LOCATION_DESC =
            "Lcom/fs/starfarer/api/campaign/LocationAPI;";

    private CoreWorldsStructuralMatcherTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: CoreWorldsStructuralMatcherTest <config>"
                            + " <starfarer.api.jar> <starfarer_obf.jar>");
        }
        PrepatcherConfig config = PrepatcherConfig.load(Path.of(args[0]));
        Path apiJar = Path.of(args[1]);
        Path obfJar = Path.of(args[2]);

        testCoreScript(config, readClass(apiJar, CORE_SCRIPT));
        testCampaignEngine(config, readClass(obfJar, ENGINE));
        testBaseLocation(config, readClass(obfJar, LOCATION));

        System.out.println("OK core-worlds structural CoreScript+CampaignEngine+BaseLocation"
                + " vanilla/idempotent/unrelated/local-remap/ambiguous/non-terminal"
                + "/foreign-or-partial-hook/mutator-shape-fail-closed");
    }

    private static void testCoreScript(PrepatcherConfig config, byte[] original) {
        byte[] patched = transform(config, CORE_SCRIPT, original);
        require(patched != null, "vanilla CoreScript was not patched");
        requireStatus(CORE_SCRIPT, "APPLIED");
        assertCoreScriptPatched(patched, 2);
        clearStatus(CORE_SCRIPT);
        require(transform(config, CORE_SCRIPT, patched) == null,
                "CoreScript second pass was not idempotent");
        requireStatus(CORE_SCRIPT, "ALREADY_APPLIED");

        byte[] unrelated = mutateCoreScript(original, false, true);
        clearStatus(CORE_SCRIPT);
        byte[] unrelatedPatched = transform(config, CORE_SCRIPT, unrelated);
        require(unrelatedPatched != null,
                "CoreScript unrelated NOP changed compatibility (hash gating)");
        requireStatus(CORE_SCRIPT, "APPLIED");
        assertCoreScriptPatched(unrelatedPatched, 2);

        byte[] remapped = mutateCoreScript(original, true, false);
        clearStatus(CORE_SCRIPT);
        byte[] remappedPatched = transform(config, CORE_SCRIPT, remapped);
        require(remappedPatched != null, "CoreScript sector-local remap was rejected");
        requireStatus(CORE_SCRIPT, "APPLIED");
        assertCoreScriptPatched(remappedPatched, 4);

        assertCoreScriptRejected(config, duplicateCoreWorldsCall(original),
                "ambiguous duplicate core-worlds call was patched");
        assertCoreScriptRejected(config, moveCoreWorldsAwayFromTerminalBoundary(original),
                "non-terminal core-worlds call was patched");
        assertCoreScriptRejected(config, installForeignCoreScriptHook(original),
                "foreign CoreScript hook without ownership marker was accepted");
    }

    private static void testCampaignEngine(PrepatcherConfig config, byte[] original) {
        clearStatus(ENGINE);
        byte[] patched = transform(config, ENGINE, original);
        require(patched != null, "vanilla CampaignEngine was not patched");
        requireStatus(ENGINE, "APPLIED");
        assertCampaignEnginePatched(patched);

        clearStatus(ENGINE);
        require(transform(config, ENGINE, patched) == null,
                "CampaignEngine second pass was not idempotent");
        requireStatus(ENGINE, "ALREADY_APPLIED");

        byte[] unrelated = addNop(original, "createStarSystem",
                "(Ljava/lang/String;)" + SYSTEM_DESC);
        clearStatus(ENGINE);
        byte[] unrelatedPatched = transform(config, ENGINE, unrelated);
        require(unrelatedPatched != null,
                "CampaignEngine unrelated NOP changed core-worlds compatibility");
        requireStatus(ENGINE, "APPLIED");
        assertCampaignEnginePatched(unrelatedPatched);

        clearStatus(ENGINE);
        transform(config, ENGINE, installPartialCampaignEngineHook(original));
        requireStatus(ENGINE, "SKIPPED_STRUCTURAL");

        clearStatus(ENGINE);
        transform(config, ENGINE, breakCampaignEngineMutationShape(original));
        requireStatus(ENGINE, "SKIPPED_STRUCTURAL");
    }

    private static void testBaseLocation(PrepatcherConfig config, byte[] original) {
        clearStatus(LOCATION);
        byte[] patched = transform(config, LOCATION, original);
        require(patched != null, "vanilla BaseLocation was not patched");
        requireStatus(LOCATION, "APPLIED");
        assertBaseLocationPatched(patched);

        clearStatus(LOCATION);
        require(transform(config, LOCATION, patched) == null,
                "BaseLocation second pass was not idempotent");
        requireStatus(LOCATION, "ALREADY_APPLIED");

        byte[] unrelated = addNop(original, "addTag", "(Ljava/lang/String;)V");
        clearStatus(LOCATION);
        byte[] unrelatedPatched = transform(config, LOCATION, unrelated);
        require(unrelatedPatched != null,
                "BaseLocation unrelated NOP changed core-worlds compatibility");
        requireStatus(LOCATION, "APPLIED");
        assertBaseLocationPatched(unrelatedPatched);

        clearStatus(LOCATION);
        transform(config, LOCATION, installPartialBaseLocationHook(original));
        requireStatus(LOCATION, "SKIPPED_STRUCTURAL");

        clearStatus(LOCATION);
        transform(config, LOCATION, breakBaseLocationMutationShape(original));
        requireStatus(LOCATION, "SKIPPED_STRUCTURAL");
    }

    private static void assertCoreScriptRejected(
            PrepatcherConfig config, byte[] bytes, String message) {
        clearStatus(CORE_SCRIPT);
        byte[] transformed = transform(config, CORE_SCRIPT, bytes);
        require(transformed == null, message);
        requireStatus(CORE_SCRIPT, "SKIPPED_STRUCTURAL");
    }

    private static byte[] transform(PrepatcherConfig config, String target, byte[] bytes) {
        return new PrepatcherTransformer(config).transform(null, target, null, null, bytes);
    }

    private static void assertCoreScriptPatched(byte[] bytes, int expectedLocal) {
        ClassNode node = read(bytes);
        MethodNode advance = method(node, "advance", "(F)V");
        int original = 0;
        int hooks = 0;
        for (AbstractInsnNode insn : advance.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("com/fs/starfarer/api/util/Misc")
                    && call.name.equals("computeCoreWorldsExtent")
                    && call.desc.equals("()V")) original++;
            if (call.owner.equals(RUNTIME) && call.name.equals("update")) {
                hooks++;
                AbstractInsnNode previous = previousMeaningful(call);
                require(previous instanceof VarInsnNode load
                                && load.getOpcode() == Opcodes.ALOAD
                                && load.var == expectedLocal,
                        "CoreScript hook did not load derived sector local " + expectedLocal);
            }
        }
        require(original == 0 && hooks == 1,
                "unexpected CoreScript call inventory original=" + original + " hooks=" + hooks);
    }

    private static void assertCampaignEnginePatched(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode create = method(node, "createStarSystem",
                "(Ljava/lang/String;)" + SYSTEM_DESC);
        MethodNode remove = method(node, "removeStarSystem", "(" + SYSTEM_DESC + ")V");
        String hookDesc = "(" + SECTOR_DESC + SYSTEM_DESC + ")V";
        require(countCalls(create, "starSystemAdded", hookDesc) == 1,
                "CampaignEngine create hook count mismatch");
        require(countCalls(remove, "starSystemRemoved", hookDesc) == 1,
                "CampaignEngine remove hook count mismatch");

        AbstractInsnNode createReturn = onlyReturn(create, Opcodes.ARETURN);
        MethodInsnNode createHook = requireCall(previousMeaningful(createReturn),
                "starSystemAdded", hookDesc, "CampaignEngine create hook");
        VarInsnNode createSystem = requireVar(previousMeaningful(createHook), Opcodes.ALOAD,
                "CampaignEngine create hook system");
        VarInsnNode createSector = requireVar(previousMeaningful(createSystem), Opcodes.ALOAD,
                "CampaignEngine create hook sector");
        require(createSector.var == 0,
                "CampaignEngine create hook sector expected local 0 found "
                        + createSector.var);
        VarInsnNode returnLoad = requireVar(previousMeaningful(createSector), Opcodes.ALOAD,
                "CampaignEngine create return load");
        require(createSystem.var == returnLoad.var,
                "CampaignEngine create hook uses a different system local");

        AbstractInsnNode removeReturn = onlyReturn(remove, Opcodes.RETURN);
        MethodInsnNode removeHook = requireCall(previousMeaningful(removeReturn),
                "starSystemRemoved", hookDesc, "CampaignEngine remove hook");
        requireVar(previousMeaningful(removeHook), Opcodes.ALOAD, 1,
                "CampaignEngine remove hook system");
        requireVar(previousMeaningful(previousMeaningful(removeHook)), Opcodes.ALOAD, 0,
                "CampaignEngine remove hook sector");
    }

    private static void assertBaseLocationPatched(byte[] bytes) {
        ClassNode node = read(bytes);
        String tagDesc = "(" + LOCATION_DESC + "Ljava/lang/String;)V";
        String clearDesc = "(" + LOCATION_DESC + ")V";
        MethodNode add = method(node, "addTag", "(Ljava/lang/String;)V");
        MethodNode remove = method(node, "removeTag", "(Ljava/lang/String;)V");
        MethodNode clear = method(node, "clearTags", "()V");
        require(countCalls(add, "locationTagAdded", tagDesc) == 1,
                "BaseLocation addTag hook count mismatch");
        require(countCalls(remove, "locationTagRemoved", tagDesc) == 2,
                "BaseLocation removeTag hook count mismatch");
        require(countCalls(clear, "locationTagsCleared", clearDesc) == 1,
                "BaseLocation clearTags hook count mismatch");
        assertTagReturns(add, "locationTagAdded", tagDesc, true);
        assertTagReturns(remove, "locationTagRemoved", tagDesc, true);
        assertTagReturns(clear, "locationTagsCleared", clearDesc, false);
    }

    private static void assertTagReturns(
            MethodNode method, String hookName, String hookDesc, boolean stringArgument) {
        for (AbstractInsnNode returnInsn : returns(method, Opcodes.RETURN)) {
            MethodInsnNode hook = requireCall(previousMeaningful(returnInsn),
                    hookName, hookDesc, method.name + " hook");
            AbstractInsnNode locationLoad;
            if (stringArgument) {
                requireVar(previousMeaningful(hook), Opcodes.ALOAD, 1,
                        method.name + " tag argument");
                locationLoad = previousMeaningful(previousMeaningful(hook));
            } else {
                locationLoad = previousMeaningful(hook);
            }
            requireVar(locationLoad, Opcodes.ALOAD, 0, method.name + " location argument");
        }
    }

    private static byte[] mutateCoreScript(byte[] source, boolean remapLocal, boolean addNop) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        if (remapLocal) {
            for (AbstractInsnNode insn : advance.instructions.toArray()) {
                if (insn instanceof VarInsnNode var && var.var == 2
                        && (var.getOpcode() == Opcodes.ALOAD
                        || var.getOpcode() == Opcodes.ASTORE)) {
                    var.var = 4;
                }
            }
            advance.maxLocals = Math.max(advance.maxLocals, 5);
        }
        if (addNop) advance.instructions.insert(new InsnNode(Opcodes.NOP));
        return write(node);
    }

    private static byte[] duplicateCoreWorldsCall(byte[] source) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        MethodInsnNode call = coreWorldsCall(advance);
        advance.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                call.owner, call.name, call.desc, false));
        return write(node);
    }

    private static byte[] moveCoreWorldsAwayFromTerminalBoundary(byte[] source) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        advance.instructions.insert(coreWorldsCall(advance), new InsnNode(Opcodes.NOP));
        return write(node);
    }

    private static byte[] installForeignCoreScriptHook(byte[] source) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        MethodInsnNode call = coreWorldsCall(advance);
        int sectorLocal = sectorLocal(advance);
        advance.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, sectorLocal));
        call.owner = RUNTIME;
        call.name = "update";
        call.desc = "(" + SECTOR_DESC + ")V";
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.itf = false;
        return write(node);
    }

    private static byte[] installPartialCampaignEngineHook(byte[] source) {
        ClassNode node = read(source);
        MethodNode create = method(node, "createStarSystem",
                "(Ljava/lang/String;)" + SYSTEM_DESC);
        AbstractInsnNode returnInsn = onlyReturn(create, Opcodes.ARETURN);
        VarInsnNode returnLoad = requireVar(previousMeaningful(returnInsn), Opcodes.ALOAD,
                "partial CampaignEngine return load");
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, returnLoad.var));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "starSystemAdded", "(" + SECTOR_DESC + SYSTEM_DESC + ")V", false));
        create.instructions.insertBefore(returnLoad, hook);
        return write(node);
    }

    private static byte[] breakCampaignEngineMutationShape(byte[] source) {
        ClassNode node = read(source);
        MethodNode create = method(node, "createStarSystem",
                "(Ljava/lang/String;)" + SYSTEM_DESC);
        MethodInsnNode add = onlyCall(create, Opcodes.INVOKEINTERFACE,
                "java/util/List", "add", "(Ljava/lang/Object;)Z");
        add.name = "contains";
        return write(node);
    }

    private static byte[] installPartialBaseLocationHook(byte[] source) {
        ClassNode node = read(source);
        MethodNode add = method(node, "addTag", "(Ljava/lang/String;)V");
        AbstractInsnNode returnInsn = onlyReturn(add, Opcodes.RETURN);
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "locationTagAdded", "(" + LOCATION_DESC + "Ljava/lang/String;)V", false));
        add.instructions.insertBefore(returnInsn, hook);
        return write(node);
    }

    private static byte[] breakBaseLocationMutationShape(byte[] source) {
        ClassNode node = read(source);
        MethodNode add = method(node, "addTag", "(Ljava/lang/String;)V");
        MethodInsnNode mutation = onlyCall(add, Opcodes.INVOKEVIRTUAL,
                "java/util/HashSet", "add", "(Ljava/lang/Object;)Z");
        mutation.name = "contains";
        return write(node);
    }

    private static byte[] addNop(byte[] source, String name, String desc) {
        ClassNode node = read(source);
        method(node, name, desc).instructions.insert(new InsnNode(Opcodes.NOP));
        return write(node);
    }

    private static MethodInsnNode coreWorldsCall(MethodNode method) {
        return onlyCall(method, Opcodes.INVOKESTATIC,
                "com/fs/starfarer/api/util/Misc", "computeCoreWorldsExtent", "()V");
    }

    private static int sectorLocal(MethodNode method) {
        MethodInsnNode call = onlyCall(method, Opcodes.INVOKESTATIC,
                "com/fs/starfarer/api/Global", "getSector", "()" + SECTOR_DESC);
        AbstractInsnNode next = nextMeaningful(call);
        require(next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE,
                "Global.getSector result was not stored");
        return ((VarInsnNode) next).var;
    }

    private static MethodInsnNode onlyCall(MethodNode method, int opcode,
            String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != opcode || !call.owner.equals(owner)
                    || !call.name.equals(name) || !call.desc.equals(desc)) continue;
            require(found == null, "duplicate call " + owner + "." + name + desc);
            found = call;
        }
        require(found != null, "missing call " + owner + "." + name + desc);
        return found;
    }

    private static int countCalls(MethodNode method, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(RUNTIME)
                    && call.name.equals(name) && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static MethodInsnNode requireCall(
            AbstractInsnNode insn, String name, String desc, String label) {
        require(insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(RUNTIME)
                        && call.name.equals(name) && call.desc.equals(desc),
                label + " mismatch: " + insn);
        return (MethodInsnNode) insn;
    }

    private static VarInsnNode requireVar(
            AbstractInsnNode insn, int opcode, String label) {
        require(insn instanceof VarInsnNode var && var.getOpcode() == opcode,
                label + " mismatch: " + insn);
        return (VarInsnNode) insn;
    }

    private static void requireVar(
            AbstractInsnNode insn, int opcode, int local, String label) {
        VarInsnNode var = requireVar(insn, opcode, label);
        require(var.var == local, label + " expected local " + local + " found " + var.var);
    }

    private static AbstractInsnNode onlyReturn(MethodNode method, int opcode) {
        List<AbstractInsnNode> returns = returns(method, opcode);
        require(returns.size() == 1,
                method.name + method.desc + " expected one return, found " + returns.size());
        return returns.get(0);
    }

    private static List<AbstractInsnNode> returns(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == opcode) result.add(insn);
        }
        return result;
    }

    private static byte[] readClass(Path jar, String internalName) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry(internalName + ".class");
            require(entry != null, "class missing from JAR: " + internalName);
            try (InputStream input = file.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
            require(found == null, "duplicate method " + name + desc);
            found = method;
        }
        require(found != null, "method missing " + name + desc);
        return found;
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

    private static String statusKey(String target) {
        return "starsector.prepatcher.patchStatus."
                + target.replace('/', '.') + "." + PATCH_ID;
    }

    private static void clearStatus(String target) {
        System.clearProperty(statusKey(target));
    }

    private static void requireStatus(String target, String expected) {
        String actual = System.getProperty(statusKey(target));
        require(expected.equals(actual),
                target + " core-worlds status expected=" + expected + " actual=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
