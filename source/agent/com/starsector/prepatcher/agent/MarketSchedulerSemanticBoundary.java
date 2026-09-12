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
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.MarketAdvance.*;
import static com.starsector.prepatcher.agent.MarketScheduler.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Adds the invocation context and exact construction-mutation barriers as
 * the scheduler-semantics component of the unified Market.advance plan.
 */
final class MarketSchedulerSemanticBoundary implements StructuredPatch<MarketSchedulerSemanticBoundary.Plan> {
  private static final String[][] MUTATORS = {{"addIndustry", "(Ljava/lang/String;)V"},
      {"addIndustry", "(Ljava/lang/String;Ljava/util/List;)V"},
      {"removeIndustry", "(Ljava/lang/String;Lcom/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode;Z)V"}};

  private record Site(MethodNode method, AbstractInsnNode first) {
  }

  record Plan(ClassNode node, MethodNode advance, MethodNode getQueue, AbstractInsnNode queueReturn,
      List<Site> mutators) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode body = optionalMethod(node, MARKET_ADVANCE_PLAN_BODY, "(F)V");
    int beginCalls = countCallsInClass(node, HOOKS, "beginMarketAdvanceInvocation", "(" + MARKET_API_DESC + ")V");
    int endCalls = countCallsInClass(node, HOOKS, "endMarketAdvanceInvocation", "()V");
    int queueOwnerCalls = countCallsInClass(node, HOOKS, "registerConstructionQueueOwner",
        "(" + CONSTRUCTION_QUEUE_DESC + MARKET_API_DESC + ")" + CONSTRUCTION_QUEUE_DESC);
    int mutationCalls =
        countCallsInClass(node, HOOKS, "flushPendingMarketBeforeMutation", "(" + MARKET_API_DESC + ")V");
    PatchState registration = marketSchedulerRegistrationState(node, MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS,
        "Market scheduler semantic boundary");
    if (body != null || beginCalls != 0 || endCalls != 0 || queueOwnerCalls != 0 || mutationCalls != 0
        || registration != PatchState.ORIGINAL) {
      requireMarketSchedulerSemanticBoundaryShape(node);
      return Match.alreadyApplied("Market scheduler semantic-boundary postcondition matches");
    }

    MethodNode advance = requireMethod(node, "advance", "(F)V");
    MethodNode getQueue = requireMethod(node, "getConstructionQueue", "()" + CONSTRUCTION_QUEUE_DESC);
    List<AbstractInsnNode> returns = new ArrayList<>();
    for (AbstractInsnNode insn : getQueue.instructions.toArray()) {
      if (insn.getOpcode() == Opcodes.ARETURN) returns.add(insn);
    }
    requireCount("Market.getConstructionQueue returns", returns.size(), 1);
    List<Site> mutators = new ArrayList<>();
    for (String[] spec : MUTATORS) {
      MethodNode method = requireMethod(node, spec[0], spec[1]);
      AbstractInsnNode first = firstMeaningful(method);
      if (first == null) return Match.incompatible("Market." + spec[0] + spec[1] + " has no code");
      mutators.add(new Site(method, first));
    }
    return Match.plan(new Plan(node, advance, getQueue, returns.get(0), mutators));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode wrapper = renameForPrivateOriginal(plan.node(), plan.advance(), MARKET_ADVANCE_PLAN_BODY);
    installMarketAdvanceInvocationWrapper(plan.node().name, wrapper);

    InsnList register = new InsnList();
    register.add(new VarInsnNode(Opcodes.ALOAD, 0));
    register.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "registerConstructionQueueOwner",
        "(" + CONSTRUCTION_QUEUE_DESC + MARKET_API_DESC + ")" + CONSTRUCTION_QUEUE_DESC, false));
    plan.getQueue().instructions.insertBefore(plan.queueReturn(), register);
    for (Site site : plan.mutators()) {
      InsnList barrier = new InsnList();
      barrier.add(new VarInsnNode(Opcodes.ALOAD, 0));
      barrier.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "flushPendingMarketBeforeMutation",
          "(" + MARKET_API_DESC + ")V", false));
      site.method().instructions.insertBefore(site.first(), barrier);
    }
    installMarketSchedulerRegistration(plan.node(), MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS,
        "Market scheduler semantic boundary");
    PatchReport report = new PatchReport();
    report.add("Market.advance batch invocation context", 1);
    report.add("construction queue owner registration", 1);
    report.add("Market construction mutation barriers", 3);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireMarketSchedulerSemanticBoundaryShape(node);
  }

  private static void installMarketAdvanceInvocationWrapper(String owner, MethodNode wrapper) {
    LabelNode start = new LabelNode();
    LabelNode end = new LabelNode();
    LabelNode handler = new LabelNode();
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginMarketAdvanceInvocation",
        "(" + MARKET_API_DESC + ")V", false));
    code.add(start);
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.FLOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, MARKET_ADVANCE_PLAN_BODY, "(F)V", false));
    code.add(end);
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "endMarketAdvanceInvocation", "()V", false));
    code.add(new InsnNode(Opcodes.RETURN));
    code.add(handler);
    code.add(
        new FrameNode(Opcodes.F_FULL, 2, new Object[]{owner, Opcodes.FLOAT}, 1, new Object[]{"java/lang/Throwable"}));
    code.add(new VarInsnNode(Opcodes.ASTORE, 2));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "endMarketAdvanceInvocation", "()V", false));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new InsnNode(Opcodes.ATHROW));
    replaceMethodBody(wrapper, code, 3);
    wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
  }

  private static void requireMarketSchedulerSemanticBoundaryShape(ClassNode node) {
    MethodNode wrapper = requireMethod(node, "advance", "(F)V");
    MethodNode body = requireMethod(node, MARKET_ADVANCE_PLAN_BODY, "(F)V");
    if ((body.access & Opcodes.ACC_PRIVATE) == 0 || (body.access & Opcodes.ACC_SYNTHETIC) == 0) {
      throw mismatch("Market scheduler semantic body is not private synthetic");
    }
    requireCount("Market invocation begin",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "beginMarketAdvanceInvocation", "(" + MARKET_API_DESC + ")V"),
        1);
    requireCount("Market invocation end",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "endMarketAdvanceInvocation", "()V"), 2);
    requireCount("Market invocation body call",
        countCalls(wrapper, Opcodes.INVOKESPECIAL, node.name, MARKET_ADVANCE_PLAN_BODY, "(F)V"), 1);
    requireCount("Market wrapper self recursion", countCalls(wrapper, -1, node.name, "advance", "(F)V"), 0);
    requireCount("Market invocation try/finally", wrapper.tryCatchBlocks.size(), 1);
    MethodNode getQueue = requireMethod(node, "getConstructionQueue", "()" + CONSTRUCTION_QUEUE_DESC);
    requireCount("construction queue owner registration",
        countCalls(getQueue, Opcodes.INVOKESTATIC, HOOKS, "registerConstructionQueueOwner",
            "(" + CONSTRUCTION_QUEUE_DESC + MARKET_API_DESC + ")" + CONSTRUCTION_QUEUE_DESC),
        1);
    requireState("Market scheduler semantic registration", marketSchedulerRegistrationState(node,
        MARKET_SCHEDULER_COMPONENT_MARKET_SEMANTICS, "Market scheduler semantic boundary"), PatchState.PATCHED);
    for (String[] method : new String[][]{{"addIndustry", "(Ljava/lang/String;)V"},
        {"addIndustry", "(Ljava/lang/String;Ljava/util/List;)V"}, {"removeIndustry",
            "(Ljava/lang/String;Lcom/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode;Z)V"}}) {
      MethodNode mutator = requireMethod(node, method[0], method[1]);
      requireCount("Market mutation barrier " + method[0] + method[1], countCalls(mutator, Opcodes.INVOKESTATIC, HOOKS,
          "flushPendingMarketBeforeMutation", "(" + MARKET_API_DESC + ")V"), 1);
    }
  }
}
