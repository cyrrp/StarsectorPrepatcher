package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class LocalResourcesTooltipSnapshot implements StructuredPatch<LocalResourcesTooltipSnapshot.Plan> {
  private static final String LOCAL_RESOURCES_TOOLTIP_RAW = "spp$rawCreateTooltipAfterDescription";
  private static final String TOOLTIP_DESC = "Lcom/fs/starfarer/api/ui/TooltipMakerAPI;";
  private static final String METHOD_DESC = "(" + TOOLTIP_DESC + "Z)V";
  private static final String HOOK_DESC = "(L" + LOCAL_RESOURCES_SUBMARKET + ";" + TOOLTIP_DESC + "Z)Z";

  record Plan(ClassNode node, MethodNode original) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    List<MethodNode> publicMethods = methods(node, "createTooltipAfterDescription", METHOD_DESC);
    List<MethodNode> rawMethods = methods(node, LOCAL_RESOURCES_TOOLTIP_RAW, METHOD_DESC);
    int hookCalls = countCalls(node, Opcodes.INVOKESTATIC, HOOKS, "renderLocalResourcesTooltipSnapshot", HOOK_DESC);

    if (publicMethods.size() == 1 && rawMethods.size() == 1 && hookCalls == 1) {
      MethodNode wrapper = publicMethods.get(0);
      MethodNode raw = rawMethods.get(0);
      requireLocalResourcesTooltipRawShape(node.name, raw);
      requireLocalResourcesTooltipWrapper(node.name, wrapper, HOOK_DESC);
      return Match.alreadyApplied("Local Resources tooltip uses a one-call stockpile snapshot");
    }
    if (publicMethods.size() != 1 || !rawMethods.isEmpty() || hookCalls != 0) {
      return Match.incompatible("Local Resources tooltip wrapper collision/mixed state: public=" + publicMethods.size()
          + ", raw=" + rawMethods.size() + ", hooks=" + hookCalls);
    }

    MethodNode original = publicMethods.get(0);
    requireLocalResourcesTooltipRawShape(node.name, original);
    return Match.plan(new Plan(node, original));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode wrapper = renameForPrivateOriginal(plan.node(), plan.original(), LOCAL_RESOURCES_TOOLTIP_RAW);
    installLocalResourcesTooltipWrapper(plan.node().name, wrapper, HOOK_DESC);

    PatchReport report = new PatchReport();
    report.add("one-call Local Resources tooltip stockpile snapshot", 1);
    report.add("unknown-subclass raw tooltip fallback", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireLocalResourcesTooltipRawShape(node.name, requireMethod(node, LOCAL_RESOURCES_TOOLTIP_RAW, METHOD_DESC));
    requireLocalResourcesTooltipWrapper(node.name, requireMethod(node, "createTooltipAfterDescription", METHOD_DESC),
        HOOK_DESC);
  }

  private static void requireLocalResourcesTooltipRawShape(String owner, MethodNode method) {
    if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("Local Resources tooltip access changed");
    }
    requireCount("Local Resources tooltip Collections.sort", countCalls(method, Opcodes.INVOKESTATIC,
        "java/util/Collections", "sort", "(Ljava/util/List;Ljava/util/Comparator;)V"), 1);
    requireCount("Local Resources tooltip comparator allocation", countNew(method, owner + "$1"), 1);
    String commodityDesc = "Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;";
    requireCount("Local Resources tooltip render getStockpileLimit",
        countCalls(method, Opcodes.INVOKEVIRTUAL, owner, "getStockpileLimit", "(" + commodityDesc + ")I"), 1);
    requireCount("Local Resources tooltip shouldHaveCommodity",
        countCalls(method, Opcodes.INVOKEVIRTUAL, owner, "shouldHaveCommodity", "(" + commodityDesc + ")Z"), 1);
    requireCount("Local Resources tooltip add-rate multiplier",
        countCalls(method, Opcodes.INVOKEVIRTUAL, owner, "getStockpilingAddRateMult", "(" + commodityDesc + ")F"), 1);
    requireCount("Local Resources tooltip beginGridFlipped", countCalls(method, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/ui/TooltipMakerAPI", "beginGridFlipped", "(FIFF)V"), 1);
    requireCount("Local Resources tooltip addToGrid",
        countCalls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/ui/TooltipMakerAPI", "addToGrid",
            "(IILjava/lang/String;Ljava/lang/String;)Ljava/lang/Object;"),
        1);
  }

  private static void installLocalResourcesTooltipWrapper(String owner, MethodNode wrapper, String hookDesc) {
    LabelNode rawFallback = new LabelNode();
    InsnList code = wrapper.instructions;
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
    code.add(new VarInsnNode(Opcodes.ILOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "renderLocalResourcesTooltipSnapshot", hookDesc, false));
    code.add(new JumpInsnNode(Opcodes.IFEQ, rawFallback));
    code.add(new InsnNode(Opcodes.RETURN));
    code.add(rawFallback);
    // Java 27 verifies byte arrays returned from ClassFileLoadHook even when
    // legacy verification flags are disabled.  The wrapper has a branch target,
    // so it must carry the unchanged-locals/empty-stack frame explicitly.
    code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
    code.add(new VarInsnNode(Opcodes.ILOAD, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, LOCAL_RESOURCES_TOOLTIP_RAW, wrapper.desc, false));
    code.add(new InsnNode(Opcodes.RETURN));
    wrapper.maxLocals = 3;
    wrapper.maxStack = 3;
  }

  private static void requireLocalResourcesTooltipWrapper(String owner, MethodNode wrapper, String hookDesc) {
    requireCount("Local Resources tooltip wrapper hook",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "renderLocalResourcesTooltipSnapshot", hookDesc), 1);
    requireCount("Local Resources tooltip raw fallback",
        countCalls(wrapper, Opcodes.INVOKESPECIAL, owner, LOCAL_RESOURCES_TOOLTIP_RAW, wrapper.desc), 1);
    requireCount("Local Resources tooltip wrapper returns", countOpcode(wrapper, Opcodes.RETURN), 2);
    requireCount("Local Resources tooltip wrapper branches", countOpcode(wrapper, Opcodes.IFEQ), 1);

    JumpInsnNode fallback = null;
    for (AbstractInsnNode instruction = wrapper.instructions.getFirst(); instruction != null; instruction =
        instruction.getNext()) {
      if (instruction.getOpcode() == Opcodes.IFEQ) {
        fallback = (JumpInsnNode) instruction;
        break;
      }
    }
    AbstractInsnNode frameInstruction = fallback == null ? null : fallback.label.getNext();
    if (!(frameInstruction instanceof FrameNode frame) || frame.type != Opcodes.F_SAME
        || (frame.local != null && !frame.local.isEmpty()) || (frame.stack != null && !frame.stack.isEmpty())) {
      throw mismatch("Local Resources raw fallback must begin with an exact F_SAME frame");
    }
  }
}
