package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

/**
 * Publishes one loader-neutral signal after Starsector has restored every industry and reapplied
 * every market. The hook is deliberately outside both loops and therefore cannot expose a
 * partially restored market to the AoTD scheduler.
 */
final class AoTDEconomyRestoreCompletionTransformer implements ClassFileTransformer {
    static final String TARGET =
            "com/fs/starfarer/api/impl/campaign/CoreLifecyclePluginImpl";
    static final String METHOD = "econPostSaveRestore";
    static final String METHOD_DESC = "()V";

    private static final String RUNTIME_INTERNAL =
            "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
    private static final String RUNTIME_BINARY =
            "com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge";
    private static final String HOOK = "publishAoTDEconomyRestoreComplete";
    private static final String HOOK_DESC = "()V";
    private static final String STATUS_PROPERTY =
            "starsector.prepatcher.aotdEconomyRestoreCompletionPatch";

    private final boolean enabled;
    private final ClassLoader runtimeLoader;

    AoTDEconomyRestoreCompletionTransformer(boolean enabled, ClassLoader runtimeLoader) {
        this.enabled = enabled;
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        if (!enabled) {
            record("DISABLED");
            publish(false, "disabled by patch.aotdEconomyRestoreCoordination");
            return null;
        }
        if (!runtimeVisibleFrom(loader)) {
            reject("SKIPPED_LOADER", "target loader cannot see runtime bridge");
            return null;
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);
            if (!TARGET.equals(node.name)) {
                throw new StructuralMismatch("unexpected owner " + node.name);
            }

            MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
            Match match = validate(method);
            if (match.hook != null) {
                if (!publish(true, null)) {
                    record("SKIPPED_RUNTIME");
                    return null;
                }
                record("ALREADY_PATCHED");
                return null;
            }

            method.instructions.insertBefore(match.successfulReturn,
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            RUNTIME_INTERNAL,
                            HOOK,
                            HOOK_DESC,
                            false));
            Match post = validate(method);
            if (post.hook == null
                    || previousMeaningful(post.successfulReturn) != post.hook) {
                throw new StructuralMismatch("restore-completion postcondition failed");
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] transformed = writer.toByteArray();
            if (!publish(true, null)) {
                record("SKIPPED_RUNTIME");
                return null;
            }
            record("APPLIED");
            PrepatcherLog.info("APPLIED AoTD economy-restore completion hook");
            return transformed;
        } catch (StructuralMismatch mismatch) {
            reject("SKIPPED_STRUCTURAL", mismatch.getMessage());
            return null;
        } catch (Throwable failure) {
            publish(false, failure.getClass().getName());
            record("SKIPPED_ERROR");
            PrepatcherLog.error(
                    "SKIPPED_ERROR AoTD economy-restore completion hook; "
                            + "restore capability remains unavailable",
                    failure);
            return null;
        }
    }

    private static Match validate(MethodNode method) {
        int requiredAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        if ((method.access & requiredAccess) != requiredAccess
                || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new StructuralMismatch("econPostSaveRestore is not concrete public static");
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            throw new StructuralMismatch("econPostSaveRestore gained exception regions");
        }

        MethodInsnNode postRestore = uniqueCall(
                method,
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/Industry",
                "doPostSaveRestore",
                "()V");
        MethodInsnNode reapplyConditions = uniqueCall(
                method,
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "reapplyConditions",
                "()V");
        MethodInsnNode reapplyIndustries = uniqueCall(
                method,
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "reapplyIndustries",
                "()V");
        requireCallCount(
                method,
                Opcodes.INVOKESTATIC,
                "com/fs/starfarer/api/Global",
                "getSector",
                "()Lcom/fs/starfarer/api/campaign/SectorAPI;",
                2);
        requireCallCount(
                method,
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/SectorAPI",
                "getEconomy",
                "()Lcom/fs/starfarer/api/campaign/econ/EconomyAPI;",
                2);
        requireCallCount(
                method,
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/EconomyAPI",
                "getMarketsCopy",
                "()Ljava/util/List;",
                2);
        requireCallCount(
                method,
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "getIndustries",
                "()Ljava/util/List;",
                1);

        AbstractInsnNode successfulReturn = null;
        MethodInsnNode hook = null;
        int returns = 0;
        int hooks = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                returns++;
                successfulReturn = instruction;
            }
            if (isCall(instruction, Opcodes.INVOKESTATIC,
                    RUNTIME_INTERNAL, HOOK, HOOK_DESC)) {
                hooks++;
                hook = (MethodInsnNode) instruction;
            }
        }
        if (returns != 1) {
            throw new StructuralMismatch(
                    "successful RETURN count changed: expected 1, found " + returns);
        }
        if (hooks > 1) {
            throw new StructuralMismatch(
                    "restore-completion hook count changed: expected at most 1, found " + hooks);
        }

        AbstractInsnNode beforeReturn = previousMeaningful(successfulReturn);
        if (hook != null) {
            if (beforeReturn != hook) {
                throw new StructuralMismatch(
                        "existing restore-completion hook is not immediately before RETURN");
            }
            beforeReturn = previousMeaningful(hook);
        }
        if (!(beforeReturn instanceof JumpInsnNode tailJump)
                || tailJump.getOpcode() != Opcodes.IFNE) {
            throw new StructuralMismatch("successful tail no longer follows the market-loop IFNE");
        }
        AbstractInsnNode beforeTailJump = previousMeaningful(tailJump);
        if (!isCall(beforeTailJump, Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "hasNext", "()Z")) {
            throw new StructuralMismatch("tail IFNE no longer consumes Iterator.hasNext()");
        }

        int postRestoreIndex = method.instructions.indexOf(postRestore);
        int conditionsIndex = method.instructions.indexOf(reapplyConditions);
        int industriesIndex = method.instructions.indexOf(reapplyIndustries);
        int tailIndex = method.instructions.indexOf(tailJump);
        int returnIndex = method.instructions.indexOf(successfulReturn);
        if (!(postRestoreIndex < conditionsIndex
                && conditionsIndex < industriesIndex
                && industriesIndex < tailIndex
                && tailIndex < returnIndex)) {
            throw new StructuralMismatch(
                    "restore/reapply/successful-return ordering changed");
        }
        int loopTargetIndex = method.instructions.indexOf(tailJump.label);
        if (loopTargetIndex < 0
                || loopTargetIndex >= conditionsIndex
                || loopTargetIndex <= postRestoreIndex) {
            throw new StructuralMismatch("final market-loop target changed");
        }
        return new Match(successfulReturn, hook);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            if (found != null) throw new StructuralMismatch("duplicate " + name + desc);
            found = method;
        }
        if (found == null) throw new StructuralMismatch("missing " + name + desc);
        return found;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, int opcode, String owner, String name, String desc) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!isCall(instruction, opcode, owner, name, desc)) continue;
            count++;
            found = (MethodInsnNode) instruction;
        }
        if (count != 1) {
            throw new StructuralMismatch(name + desc
                    + " count changed: expected 1, found " + count);
        }
        return found;
    }

    private static void requireCallCount(
            MethodNode method,
            int opcode,
            String owner,
            String name,
            String desc,
            int expected) {
        int actual = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (isCall(instruction, opcode, owner, name, desc)) actual++;
        }
        if (actual != expected) {
            throw new StructuralMismatch(name + desc
                    + " count changed: expected " + expected + ", found " + actual);
        }
    }

    private static boolean isCall(
            AbstractInsnNode instruction,
            int opcode,
            String owner,
            String name,
            String desc) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == opcode
                && owner.equals(call.owner)
                && name.equals(call.name)
                && desc.equals(call.desc);
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private void reject(String status, String reason) {
        publish(false, reason);
        record(status);
        PrepatcherLog.warn(status + " AoTD economy-restore completion hook: " + reason
                + "; restore capability remains unavailable");
    }

    private boolean publish(boolean operational, String reason) {
        if (runtimeLoader == null) return true;
        try {
            Class<?> bridge = Class.forName(RUNTIME_BINARY, false, runtimeLoader);
            Method method = bridge.getMethod(
                    "setAoTDEconomyRestoreCompletionContract",
                    boolean.class,
                    String.class);
            method.invoke(null, operational, reason);
            return true;
        } catch (Throwable failure) {
            PrepatcherLog.warn("Could not publish AoTD economy-restore contract: "
                    + failure.getClass().getName());
            return false;
        }
    }

    private boolean runtimeVisibleFrom(ClassLoader loader) {
        if (runtimeLoader == null) return true;
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == runtimeLoader) return true;
        }
        return false;
    }

    static String statusProperty() {
        return STATUS_PROPERTY;
    }

    private static void record(String status) {
        System.setProperty(STATUS_PROPERTY, status);
    }

    private record Match(AbstractInsnNode successfulReturn, MethodInsnNode hook) {}

    private static final class StructuralMismatch extends RuntimeException {
        StructuralMismatch(String message) {
            super(message);
        }
    }
}
