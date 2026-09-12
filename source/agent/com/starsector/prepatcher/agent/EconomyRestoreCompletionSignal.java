package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.Contract.*;

/**
 * Publishes one loader-neutral signal after Starsector has restored every
 * industry and reapplied every market. The hook is deliberately outside both
 * loops and therefore cannot expose a partially restored market to the AoTD
 * scheduler.
 */
final class EconomyRestoreCompletionSignal implements StructuredPatch<EconomyRestoreCompletionSignal.Plan> {
  static final String TARGET = "com/fs/starfarer/api/impl/campaign/CoreLifecyclePluginImpl";
  static final String METHOD = "econPostSaveRestore";
  static final String METHOD_DESC = "()V";

  private static final String RUNTIME_INTERNAL = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
  private static final String HOOK = "publishAoTDEconomyRestoreComplete";
  private static final String HOOK_DESC = "()V";

  record Plan(MethodNode method, AbstractInsnNode successfulReturn) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    if (!TARGET.equals(node.name)) { throw mismatch("unexpected owner " + node.name); }
    MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
    Shape shape = validate(method);
    if (shape.hook != null) { return Match.alreadyApplied("restore-completion hook already present"); }
    return Match.plan(new Plan(method, shape.successfulReturn));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.method().instructions.insertBefore(plan.successfulReturn(),
        new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, HOOK, HOOK_DESC, false));
    PatchReport report = new PatchReport();
    report.add("economy-restore completion hook", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    Shape post = validate(plan.method());
    if (post.hook == null || previousMeaningful(post.successfulReturn) != post.hook) {
      throw mismatch("restore-completion postcondition failed");
    }
  }

  private record Shape(AbstractInsnNode successfulReturn, MethodInsnNode hook) {
  }

  private static Shape validate(MethodNode method) {
    int requiredAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    if ((method.access & requiredAccess) != requiredAccess
        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("econPostSaveRestore is not concrete public static");
    }
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("econPostSaveRestore gained exception regions");
    }

    MethodInsnNode postRestore = uniqueCall(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/Industry", "doPostSaveRestore", "()V");
    MethodInsnNode reapplyConditions = uniqueCall(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/MarketAPI", "reapplyConditions", "()V");
    MethodInsnNode reapplyIndustries = uniqueCall(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/MarketAPI", "reapplyIndustries", "()V");
    requireCallCount(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/Global", "getSector",
        "()Lcom/fs/starfarer/api/campaign/SectorAPI;", 2);
    requireCallCount(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/SectorAPI", "getEconomy",
        "()Lcom/fs/starfarer/api/campaign/econ/EconomyAPI;", 2);
    requireCallCount(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/EconomyAPI", "getMarketsCopy",
        "()Ljava/util/List;", 2);
    requireCallCount(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/MarketAPI", "getIndustries",
        "()Ljava/util/List;", 1);

    AbstractInsnNode successfulReturn = null;
    MethodInsnNode hook = null;
    int returns = 0;
    int hooks = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction.getOpcode() == Opcodes.RETURN) {
        returns++;
        successfulReturn = instruction;
      }
      if (isCall(instruction, Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, HOOK, HOOK_DESC)) {
        hooks++;
        hook = (MethodInsnNode) instruction;
      }
    }
    if (returns != 1) { throw mismatch("successful RETURN count changed: expected 1, found " + returns); }
    if (hooks > 1) { throw mismatch("restore-completion hook count changed: expected at most 1, found " + hooks); }

    AbstractInsnNode beforeReturn = previousMeaningful(successfulReturn);
    if (hook != null) {
      if (beforeReturn != hook) { throw mismatch("existing restore-completion hook is not immediately before RETURN"); }
      beforeReturn = previousMeaningful(hook);
    }
    if (!(beforeReturn instanceof JumpInsnNode tailJump) || tailJump.getOpcode() != Opcodes.IFNE) {
      throw mismatch("successful tail no longer follows the market-loop IFNE");
    }
    AbstractInsnNode beforeTailJump = previousMeaningful(tailJump);
    if (!isCall(beforeTailJump, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")) {
      throw mismatch("tail IFNE no longer consumes Iterator.hasNext()");
    }

    int postRestoreIndex = method.instructions.indexOf(postRestore);
    int conditionsIndex = method.instructions.indexOf(reapplyConditions);
    int industriesIndex = method.instructions.indexOf(reapplyIndustries);
    int tailIndex = method.instructions.indexOf(tailJump);
    int returnIndex = method.instructions.indexOf(successfulReturn);
    if (!(postRestoreIndex < conditionsIndex && conditionsIndex < industriesIndex && industriesIndex < tailIndex
        && tailIndex < returnIndex)) {
      throw mismatch("restore/reapply/successful-return ordering changed");
    }
    int loopTargetIndex = method.instructions.indexOf(tailJump.label);
    if (loopTargetIndex < 0 || loopTargetIndex >= conditionsIndex || loopTargetIndex <= postRestoreIndex) {
      throw mismatch("final market-loop target changed");
    }
    return new Shape(successfulReturn, hook);
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    MethodNode found = null;
    for (MethodNode method : node.methods) {
      if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
      if (found != null) throw mismatch("duplicate " + name + desc);
      found = method;
    }
    if (found == null) throw mismatch("missing " + name + desc);
    return found;
  }

  private static MethodInsnNode uniqueCall(MethodNode method, int opcode, String owner, String name, String desc) {
    MethodInsnNode found = null;
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (!isCall(instruction, opcode, owner, name, desc)) continue;
      count++;
      found = (MethodInsnNode) instruction;
    }
    if (count != 1) { throw mismatch(name + desc + " count changed: expected 1, found " + count); }
    return found;
  }

  private static void requireCallCount(MethodNode method, int opcode, String owner, String name, String desc,
      int expected) {
    int actual = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (isCall(instruction, opcode, owner, name, desc)) actual++;
    }
    if (actual != expected) {
      throw mismatch(name + desc + " count changed: expected " + expected + ", found " + actual);
    }
  }

  private static boolean isCall(AbstractInsnNode instruction, int opcode, String owner, String name, String desc) {
    return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
        && name.equals(call.name) && desc.equals(call.desc);
  }

  private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
    AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
    while (current != null && current.getOpcode() < 0) current = current.getPrevious();
    return current;
  }
}
