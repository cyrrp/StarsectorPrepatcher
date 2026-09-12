package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Replaces Market.advance()'s unconditional full commodity-maintenance loop
 * with an active-set runtime. The original four stat advances and event-mod
 * reapplication remain available as a fail-open path in the hook.
 */
final class MarketCommodityTemporalFastPath implements StructuredPatch<MarketCommodityTemporalFastPath.Plan> {
  private static final String STATE_FIELD = "smo$commodityTemporalState";
  private static final int STATE_ACCESS = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
  private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;F)Ljava/lang/Object;";

  private record MarketCommodityLoop(AbstractInsnNode start, AbstractInsnNode end, int daysLocal) {
  }

  record Plan(ClassNode node, MethodNode advance, MarketCommodityLoop loop) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = marketAdvanceSemanticBody(node);

    FieldNode existing = null;
    for (FieldNode field : node.fields) {
      if (!field.name.equals(STATE_FIELD)) continue;
      if (existing != null) return Match.incompatible("duplicate Market commodity-temporal state field");
      existing = field;
    }
    if (existing != null) {
      if (!existing.desc.equals("Ljava/lang/Object;") || existing.access != STATE_ACCESS || existing.signature != null
          || existing.value != null) {
        return Match.incompatible("Market commodity-temporal state field is foreign");
      }
      requireOptimizedMarketCommodityTemporalShape(node, advance, STATE_FIELD, HOOK_DESC);
      return Match.alreadyApplied("Market commodity temporal fast-path postcondition matches");
    }
    if (countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketCommodityTemporalState", HOOK_DESC) != 0) {
      return Match.incompatible("foreign Market commodity-temporal hook without owned state field");
    }

    MarketCommodityLoop loop = requireOriginalMarketCommodityLoop(node, advance);
    return Match.plan(new Plan(node, advance, loop));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    MethodNode advance = plan.advance();
    MarketCommodityLoop loop = plan.loop();
    node.fields.add(new FieldNode(Opcodes.ASM8, STATE_ACCESS, STATE_FIELD, "Ljava/lang/Object;", null, null));

    InsnList replacement = new InsnList();
    replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // PUTFIELD receiver
    replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // hook market
    replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
    replacement.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, STATE_FIELD, "Ljava/lang/Object;"));
    replacement.add(new VarInsnNode(Opcodes.FLOAD, loop.daysLocal));
    replacement
        .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "advanceMarketCommodityTemporalState", HOOK_DESC, false));
    replacement.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, STATE_FIELD, "Ljava/lang/Object;"));
    // Keep a cursor into the unchanged industry loop. The first frame after
    // the removed commodity loop was encoded as SAME relative to the
    // commodity-loop iterator frame. Once that predecessor disappears it
    // must become an explicit full frame, or the JVM verifier sees local 5
    // as TOP at the industry-loop ALOAD.
    AbstractInsnNode afterLoop = loop.end.getNext();
    advance.instructions.insertBefore(loop.start, replacement);
    removeInstructionRange(advance, loop.start, loop.end, "Market commodity temporal loop");
    repairMarketPostCommodityFrame(node, advance, afterLoop);

    // The removed loop owned local-variable ranges for slots 4/5. They are
    // debug metadata only, and retaining labels no longer in the method would
    // produce malformed classfiles. Runtime semantics are unaffected.
    if (advance.localVariables != null) advance.localVariables.clear();
    if (advance.visibleLocalVariableAnnotations != null) {
      advance.visibleLocalVariableAnnotations.clear();
    }
    if (advance.invisibleLocalVariableAnnotations != null) {
      advance.invisibleLocalVariableAnnotations.clear();
    }

    PatchReport report = new PatchReport();
    report.add("commodity temporal active-set loop", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireOptimizedMarketCommodityTemporalShape(node, plan.advance(), STATE_FIELD, HOOK_DESC);
  }

  private static MarketCommodityLoop requireOriginalMarketCommodityLoop(ClassNode node, MethodNode method) {
    final String commodity = "com/fs/starfarer/campaign/econ/CommodityOnMarket";
    final String stat = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    List<AbstractInsnNode> code = meaningfulInstructions(method);
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i + 29 < code.size(); i++) {
      if (!(code.get(i + 1) instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKEVIRTUAL, node.name, "getCommodities", "()Ljava/util/List;"))
        continue;
      starts.add(i);
    }
    requireCount("Market commodity-maintenance loop candidates", starts.size(), 1);
    int i = starts.get(0);
    requireVar(code.get(i), Opcodes.ALOAD, 0, "commodity-loop Market owner");
    requireCall(asMethod(code.get(i + 1), "commodity list getter"), Opcodes.INVOKEVIRTUAL, node.name, "getCommodities",
        "()Ljava/util/List;", "commodity list getter");
    requireCall(asMethod(code.get(i + 2), "commodity iterator"), Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
        "()Ljava/util/Iterator;", "commodity iterator");
    if (!(code.get(i + 3) instanceof VarInsnNode iteratorStore) || iteratorStore.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("commodity iterator local changed");
    }
    int iteratorLocal = iteratorStore.var;
    if (!(code.get(i + 4) instanceof JumpInsnNode initialJump) || initialJump.getOpcode() != Opcodes.GOTO) {
      throw mismatch("commodity loop initial jump changed");
    }
    requireVar(code.get(i + 5), Opcodes.ALOAD, iteratorLocal, "commodity next iterator");
    requireCall(asMethod(code.get(i + 6), "commodity iterator next"), Opcodes.INVOKEINTERFACE, "java/util/Iterator",
        "next", "()Ljava/lang/Object;", "commodity iterator next");
    if (!(code.get(i + 7) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
        || !cast.desc.equals(commodity)) {
      throw mismatch("commodity loop cast changed");
    }
    if (!(code.get(i + 8) instanceof VarInsnNode commodityStore) || commodityStore.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("commodity local changed");
    }
    int commodityLocal = commodityStore.var;
    String[] getters = {"getAvailableStat", "getTradeMod", "getTradeModPlus", "getTradeModMinus"};
    int daysLocal = -1;
    int cursor = i + 9;
    for (String getter : getters) {
      requireVar(code.get(cursor++), Opcodes.ALOAD, commodityLocal, "commodity stat owner " + getter);
      requireCall(asMethod(code.get(cursor++), "commodity stat getter " + getter), Opcodes.INVOKEVIRTUAL, commodity,
          getter, "()L" + stat + ";", "commodity stat getter " + getter);
      AbstractInsnNode days = code.get(cursor++);
      if (!(days instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD) {
        throw mismatch("commodity stat days local changed for " + getter);
      }
      if (daysLocal < 0) daysLocal = load.var;
      else if (daysLocal != load.var) throw mismatch("commodity stat days locals diverged");
      requireCall(asMethod(code.get(cursor++), "commodity stat advance " + getter), Opcodes.INVOKEVIRTUAL, stat,
          "advance", "(F)V", "commodity stat advance " + getter);
    }
    requireVar(code.get(cursor++), Opcodes.ALOAD, commodityLocal, "commodity event-mod owner");
    requireCall(asMethod(code.get(cursor++), "commodity event-mod reapply"), Opcodes.INVOKEVIRTUAL, commodity,
        "reapplyEventMod", "()V", "commodity event-mod reapply");
    requireVar(code.get(cursor++), Opcodes.ALOAD, iteratorLocal, "commodity hasNext iterator");
    requireCall(asMethod(code.get(cursor++), "commodity iterator hasNext"), Opcodes.INVOKEINTERFACE,
        "java/util/Iterator", "hasNext", "()Z", "commodity iterator hasNext");
    if (!(code.get(cursor) instanceof JumpInsnNode back) || back.getOpcode() != Opcodes.IFNE) {
      throw mismatch("commodity loop back-edge changed");
    }
    requireCount("Market commodity-maintenance instruction count", cursor - i + 1, 30);
    if (nextMeaningful(initialJump.label) != code.get(i + 27) || nextMeaningful(back.label) != code.get(i + 5)) {
      throw mismatch("commodity loop control-flow targets changed");
    }
    return new MarketCommodityLoop(code.get(i), code.get(cursor), daysLocal);
  }

  private static void repairMarketPostCommodityFrame(ClassNode node, MethodNode method, AbstractInsnNode cursor) {
    FrameNode frame = null;
    for (AbstractInsnNode current = cursor; current != null; current = current.getNext()) {
      if (current instanceof FrameNode candidate) {
        frame = candidate;
        break;
      }
    }
    if (frame == null || frame.type != Opcodes.F_SAME) {
      throw mismatch("Market post-commodity industry-loop frame changed");
    }
    FrameNode full = new FrameNode(Opcodes.F_FULL, 6,
        new Object[]{node.name, Opcodes.FLOAT, Opcodes.FLOAT, Opcodes.FLOAT, Opcodes.TOP, "java/util/Iterator"}, 0,
        new Object[0]);
    method.instructions.set(frame, full);
  }

  private static void requireOptimizedMarketCommodityTemporalShape(ClassNode node, MethodNode method, String stateField,
      String hookDesc) {
    requireCount("Market commodity temporal hook",
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketCommodityTemporalState", hookDesc), 1);
    requireCount("Market commodity getter after fast path",
        countCalls(method, Opcodes.INVOKEVIRTUAL, node.name, "getCommodities", "()Ljava/util/List;"), 0);
    final String commodity = "com/fs/starfarer/campaign/econ/CommodityOnMarket";
    final String stat = "com/fs/starfarer/api/combat/MutableStatWithTempMods";
    for (String getter : List.of("getAvailableStat", "getTradeMod", "getTradeModPlus", "getTradeModMinus")) {
      requireCount("Market fast path residual " + getter,
          countCalls(method, Opcodes.INVOKEVIRTUAL, commodity, getter, "()L" + stat + ";"), 0);
    }
    requireCount("Market fast path residual reapplyEventMod",
        countCalls(method, Opcodes.INVOKEVIRTUAL, commodity, "reapplyEventMod", "()V"), 0);
    // The market power stat remains the one legitimate per-frame stat advance.
    requireCount("Market fast path remaining MutableStatWithTempMods.advance",
        countCalls(method, Opcodes.INVOKEVIRTUAL, stat, "advance", "(F)V"), 1);

    MethodInsnNode hook =
        calls(method, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketCommodityTemporalState", hookDesc).get(0);
    AbstractInsnNode days = previousMeaningful(hook);
    AbstractInsnNode stateGet = previousMeaningful(days);
    AbstractInsnNode stateOwner = previousMeaningful(stateGet);
    AbstractInsnNode marketArg = previousMeaningful(stateOwner);
    AbstractInsnNode putOwner = previousMeaningful(marketArg);
    AbstractInsnNode statePut = nextMeaningful(hook);
    if (!(days instanceof VarInsnNode daysLoad) || daysLoad.getOpcode() != Opcodes.FLOAD
        || !(stateOwner instanceof VarInsnNode stateLoad) || stateLoad.getOpcode() != Opcodes.ALOAD
        || stateLoad.var != 0 || !(marketArg instanceof VarInsnNode marketLoad)
        || marketLoad.getOpcode() != Opcodes.ALOAD || marketLoad.var != 0
        || !(putOwner instanceof VarInsnNode receiverLoad) || receiverLoad.getOpcode() != Opcodes.ALOAD
        || receiverLoad.var != 0) {
      throw mismatch("Market commodity temporal hook argument sequence changed");
    }
    requireField(stateGet, Opcodes.GETFIELD, node.name, stateField, "Ljava/lang/Object;",
        "Market commodity state read");
    requireField(statePut, Opcodes.PUTFIELD, node.name, stateField, "Ljava/lang/Object;",
        "Market commodity state write");
  }
}
