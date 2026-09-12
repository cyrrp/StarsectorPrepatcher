package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class CampaignEngineMarketSchedulerTick implements StructuredPatch<CampaignEngineMarketSchedulerTick.Plan> {
  record Plan(ClassNode node, MethodNode advance, MethodNode fastForwardSetter) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    requireCampaignLifecycleForMarketScheduler(node);
    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    MethodNode fastForwardSetter = requireMethod(node, "setFastForwardIteration", "(Z)V");
    PatchState scope = marketSchedulerTickScopeState(node.name, advance, "CampaignEngine.advance market scheduler");
    PatchState batchBoundary =
        marketSchedulerBatchBoundaryState(fastForwardSetter, "CampaignEngine.setFastForwardIteration market scheduler");
    PatchState registration =
        marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_ENGINE, "CampaignEngine market scheduler");
    if (scope == PatchState.PATCHED && batchBoundary == PatchState.PATCHED) {
      if (registration != PatchState.PATCHED) {
        return Match.incompatible("CampaignEngine market scheduler boundary lacks component registration");
      }
      return Match.alreadyApplied("CampaignEngine market scheduler tick/render-batch postcondition matches");
    }
    if (scope != batchBoundary || registration == PatchState.PATCHED) {
      return Match.incompatible("CampaignEngine market scheduler tick/render-batch boundary is partial");
    }
    return Match.plan(new Plan(node, advance, fastForwardSetter));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    installMarketSchedulerTickScope(plan.node().name, plan.advance(), "CampaignEngine.advance market scheduler");
    installMarketSchedulerBatchBoundary(plan.fastForwardSetter(),
        "CampaignEngine.setFastForwardIteration market scheduler");
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_ENGINE,
        "CampaignEngine market scheduler");
    PatchReport report = new PatchReport();
    report.add("full CampaignEngine scheduler simulation-tick boundary", 1);
    report.add("CampaignEngine render-batch boundary", 1);
    report.add("market scheduler engine/lifecycle component registration", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    if (marketSchedulerTickScopeState(node.name, plan.advance(),
        "CampaignEngine.advance market scheduler") != PatchState.PATCHED
        || marketSchedulerBatchBoundaryState(plan.fastForwardSetter(),
            "CampaignEngine.setFastForwardIteration market scheduler") != PatchState.PATCHED
        || marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_ENGINE,
            "CampaignEngine market scheduler") != PatchState.PATCHED) {
      throw mismatch("CampaignEngine market scheduler boundary postcondition is incomplete");
    }
  }

  private static void requireCampaignLifecycleForMarketScheduler(ClassNode node) {
    String engineDesc = "L" + CAMPAIGN_ENGINE + ";";
    String beginDesc = "(Ljava/lang/Object;)J";
    String completeDesc = "(Ljava/lang/Object;J)V";
    MethodNode reset = requireMethod(node, "resetInstance", "()V");
    MethodNode set = requireMethod(node, "setInstance", "(" + engineDesc + ")V");
    int resetBegins = countCalls(reset, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc);
    int setBegins = countCalls(set, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", beginDesc);
    int resetCompletes = countCalls(reset, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc);
    int setCompletes = countCalls(set, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", completeDesc);
    if (resetBegins != 1 || setBegins != 1 || resetCompletes != campaignReturns(reset).size()
        || setCompletes != campaignReturns(set).size()) {
      throw mismatch("market scheduler requires the complete CampaignEngine lifecycle boundary");
    }
  }

  private static PatchState marketSchedulerBatchBoundaryState(MethodNode method, String label) {
    String desc = "(Z)V";
    List<MethodInsnNode> hooks =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, "marketSchedulerFastForwardIterationChanged", desc);
    if (hooks.isEmpty()) return PatchState.ORIGINAL;
    requireCount(label + " hooks", hooks.size(), 1);
    MethodInsnNode hook = hooks.get(0);
    AbstractInsnNode source = previousMeaningful(hook);
    AbstractInsnNode next = nextMeaningful(hook);
    if (!(source instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD || load.var != 1 || next == null
        || next.getOpcode() != Opcodes.RETURN) {
      throw mismatch(label + " hook is not the final use of the boolean argument");
    }
    requireCount(label + " returns", countReturnOpcodes(method), 1);
    return PatchState.PATCHED;
  }

  private static void installMarketSchedulerBatchBoundary(MethodNode method, String label) {
    if (marketSchedulerBatchBoundaryState(method, label) != PatchState.ORIGINAL) {
      throw mismatch(label + " is not original");
    }
    AbstractInsnNode returnInsn = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() != Opcodes.RETURN) continue;
      if (returnInsn != null) throw mismatch(label + " has multiple returns");
      returnInsn = insn;
    }
    if (returnInsn == null) throw mismatch(label + " has no return");
    InsnList hook = new InsnList();
    hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
    hook.add(
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "marketSchedulerFastForwardIterationChanged", "(Z)V", false));
    method.instructions.insertBefore(returnInsn, hook);
    if (marketSchedulerBatchBoundaryState(method, label) != PatchState.PATCHED) {
      throw mismatch(label + " installation postcondition failed");
    }
  }

  private static PatchState marketSchedulerTickScopeState(String owner, MethodNode method, String label) {
    int begins = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "beginMarketSchedulerTick", "()V");
    int ends = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "endMarketSchedulerTick", "()V");
    if (begins == 0 && ends == 0) return PatchState.ORIGINAL;
    requireCount(label + " begin hooks", begins, 1);
    requireCount(label + " end hooks", ends, countReturnOpcodes(method) + 1);
    MethodInsnNode begin =
        only(calls(method, Opcodes.INVOKESTATIC, HOOKS, "beginMarketSchedulerTick", "()V"), label + " begin hook");
    if (firstMeaningful(method) != begin) { throw mismatch(label + " does not begin at method entry"); }
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!isReturnOpcode(insn.getOpcode())) continue;
      AbstractInsnNode previous = previousMeaningful(insn);
      if (!(previous instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "endMarketSchedulerTick", "()V")) {
        throw mismatch(label + " return is not protected by endMarketSchedulerTick");
      }
    }
    int handlers = 0;
    for (TryCatchBlockNode block : method.tryCatchBlocks) {
      if (block.type != null) continue;
      AbstractInsnNode handlerStart = nextMeaningful(block.handler);
      if (!(handlerStart instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "endMarketSchedulerTick", "()V"))
        continue;
      AbstractInsnNode rethrow = nextMeaningful(call);
      if (rethrow != null && rethrow.getOpcode() == Opcodes.ATHROW) handlers++;
    }
    requireCount(label + " catch-all", handlers, 1);
    return PatchState.PATCHED;
  }

  private static void installMarketSchedulerTickScope(String owner, MethodNode method, String label) {
    if (marketSchedulerTickScopeState(owner, method, label) != PatchState.ORIGINAL) {
      throw mismatch(label + " is not original");
    }
    if (method.instructions == null || method.instructions.getFirst() == null || method.name.equals("<init>")) {
      throw mismatch(label + " has no patchable code");
    }
    AbstractInsnNode oldFirst = method.instructions.getFirst();
    LabelNode entry = new LabelNode();
    LabelNode start = new LabelNode();
    method.instructions.insertBefore(oldFirst, entry);
    MethodInsnNode begin = new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginMarketSchedulerTick", "()V", false);
    method.instructions.insert(entry, begin);
    method.instructions.insert(begin, start);

    int returns = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!isReturnOpcode(insn.getOpcode())) continue;
      method.instructions.insertBefore(insn,
          new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "endMarketSchedulerTick", "()V", false));
      returns++;
    }
    if (returns == 0) throw mismatch(label + " has no normal return");

    LabelNode end = new LabelNode();
    LabelNode handler = new LabelNode();
    method.instructions.add(end);
    method.instructions.add(handler);
    Object[] initialLocals = initialFrameLocals(owner, method);
    method.instructions.add(
        new FrameNode(Opcodes.F_FULL, initialLocals.length, initialLocals, 1, new Object[]{"java/lang/Throwable"}));
    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "endMarketSchedulerTick", "()V", false));
    method.instructions.add(new InsnNode(Opcodes.ATHROW));
    method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    if (marketSchedulerTickScopeState(owner, method, label) != PatchState.PATCHED) {
      throw mismatch(label + " installation postcondition failed");
    }
  }
}
