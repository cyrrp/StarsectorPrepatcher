package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.instructionIndex;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Guards the exact vanilla market-transaction doubleStep call with a runtime
 * fail-open predicate, and installs a pre-twConfirm preparation barrier. The
 * original virtual call remains in bytecode and runs whenever transaction
 * inspection or the targeted refresh cannot be proven safe.
 */
final class TradeMarketMutationRefresh implements StructuredPatch<TradeMarketMutationRefresh.Plan> {
  private static final String TARGET = "com/fs/starfarer/campaign/ui/class";
  private static final String METHOD = "confirmTransaction";
  private static final String METHOD_DESC = "()V";
  private static final String MARKET = "com/fs/starfarer/campaign/econ/Market";
  private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
  private static final String ECONOMY_API = "com/fs/starfarer/api/campaign/econ/EconomyAPI";
  private static final String TRANSACTION = "com/fs/starfarer/api/campaign/PlayerMarketTransaction";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
  private static final String LEGACY_MARKET_FIELD = "if.new$class";
  private static final String REPAIRED_MARKET_FIELD = "if_new$class";
  private static final String MARKET_DESC = "L" + MARKET + ";";
  private static final String GUARD = "shouldHandleTradeMarketMutationEconomyStep";
  private static final String GUARD_DESC = "(L" + ECONOMY_API + ";L" + MARKET_API + ";L" + TRANSACTION + ";)Z";
  private static final String MARKER = "spp$patched$tradeMarketMutationRefresh";
  private static final String MARKER_VALUE = "StarsectorPrepatcher:trade-market-mutation-refresh-v2";
  private static final String PREPARE = "prepareTradeMarketMutation";
  private static final String PREPARE_DESC = "(L" + MARKET_API + ";)V";
  private static final String TRADE_UI = "com/fs/starfarer/campaign/ui/class";
  private static final String TW_CONFIRM = "twConfirm";

  record Plan(MethodNode method, MethodInsnNode twConfirm, MethodInsnNode doubleStep, String marketField,
      List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String marketField = requireMarketFieldName(node);
    FieldNode marker = field(node, MARKER);
    if (marker != null) {
      requireMarker(marker);
      requirePatchedShape(node, marketField);
      return Match.alreadyApplied("trade market-mutation refresh postcondition matches");
    }
    requireOriginalShape(node, marketField);
    MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
    return Match.plan(new Plan(method, uniqueCall(method, TRADE_UI, TW_CONFIRM, "()V"),
        uniqueCall(method, ECONOMY_API, "doubleStep", "()V"), marketField, node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    String marketField = plan.marketField();

    InsnList prepare = new InsnList();
    prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
    prepare.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, marketField, MARKET_DESC));
    prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, PREPARE, PREPARE_DESC, false));
    method.instructions.insertBefore(plan.twConfirm(), prepare);

    int economyLocal = method.maxLocals;
    method.maxLocals = economyLocal + 1;
    LabelNode afterStep = new LabelNode();
    InsnList guard = new InsnList();
    guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, marketField, MARKET_DESC));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, GUARD, GUARD_DESC, false));
    guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    method.instructions.insertBefore(plan.doubleStep(), guard);
    method.instructions.insert(plan.doubleStep(), afterStep);

    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, MARKER,
            "Ljava/lang/String;", null, MARKER_VALUE));
    PatchReport report = new PatchReport();
    report.add("trade mutation preparation barrier", 1);
    report.add("trade doubleStep guard", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedShape(node, plan.marketField());
  }

  private static void requireOriginalShape(ClassNode node, String marketField) {
    if (field(node, MARKER) != null) { throw mismatch("unexpected patch marker"); }
    requireResolvedMarketField(node, marketField);
    MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
    if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("confirmTransaction is not concrete instance method");
    }
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("confirmTransaction gained exception regions");
    }
    requireCount("EconomyAPI.doubleStep", calls(method, ECONOMY_API, "doubleStep", "()V").size(), 1);
    requireCount("twConfirm", calls(method, TRADE_UI, TW_CONFIRM, "()V").size(), 1);
    requireCount("transaction construction",
        calls(method, TRANSACTION, "<init>",
            "(L" + MARKET_API + ";" + "Lcom/fs/starfarer/api/campaign/econ/SubmarketAPI;"
                + "Lcom/fs/starfarer/api/campaign/CampaignUIAPI$CoreUITradeMode;)V")
            .size(),
        1);
    requireCount("transaction report", calls(method, "com/fs/starfarer/campaign/CampaignEngine",
        "reportPlayerMarketTransaction", "(L" + TRANSACTION + ";)V").size(), 1);
    requireCount("Market.updatePriceMult", calls(method, MARKET, "updatePriceMult", "()V").size(), 1);
    if (!hasAloadImmediatelyBeforeTransactionReport(method, 1)) {
      throw mismatch("PlayerMarketTransaction local slot changed");
    }
    MethodInsnNode twConfirm = uniqueCall(method, TRADE_UI, TW_CONFIRM, "()V");
    MethodInsnNode report = uniqueCall(method, "com/fs/starfarer/campaign/CampaignEngine",
        "reportPlayerMarketTransaction", "(L" + TRANSACTION + ";)V");
    MethodInsnNode doubleStep = uniqueCall(method, ECONOMY_API, "doubleStep", "()V");
    if (doubleStep.getOpcode() != Opcodes.INVOKEINTERFACE || !doubleStep.itf) {
      throw mismatch("doubleStep invocation kind changed");
    }
    MethodInsnNode price = uniqueCall(method, MARKET, "updatePriceMult", "()V");
    if (!(instructionIndex(method, twConfirm) < instructionIndex(method, report)
        && instructionIndex(method, report) < instructionIndex(method, doubleStep)
        && instructionIndex(method, doubleStep) < instructionIndex(method, price))) {
      throw mismatch("twConfirm/report/doubleStep/updatePriceMult order changed");
    }
  }

  private static boolean hasAloadImmediatelyBeforeTransactionReport(MethodNode method, int expectedLocal) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (!(instruction instanceof MethodInsnNode call)
          || !"com/fs/starfarer/campaign/CampaignEngine".equals(call.owner)
          || !"reportPlayerMarketTransaction".equals(call.name)) {
        continue;
      }
      AbstractInsnNode previous = previousMeaningful(instruction);
      return previous instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == expectedLocal;
    }
    return false;
  }

  private static void requirePatchedShape(ClassNode node, String marketField) {
    requireResolvedMarketField(node, marketField);
    FieldNode marker = field(node, MARKER);
    requireMarker(marker);
    MethodNode method = requireMethod(node, METHOD, METHOD_DESC);
    requireCount("preserved EconomyAPI.doubleStep", calls(method, ECONOMY_API, "doubleStep", "()V").size(), 1);
    requireCount("runtime trade guard", calls(method, RUNTIME, GUARD, GUARD_DESC).size(), 1);
    requireCount("runtime trade pre-mutation barrier", calls(method, RUNTIME, PREPARE, PREPARE_DESC).size(), 1);
    requireCount("twConfirm", calls(method, TRADE_UI, TW_CONFIRM, "()V").size(), 1);
    requireCount("Market.updatePriceMult", calls(method, MARKET, "updatePriceMult", "()V").size(), 1);

    MethodInsnNode prepare = uniqueCall(method, RUNTIME, PREPARE, PREPARE_DESC);
    MethodInsnNode twConfirm = uniqueCall(method, TRADE_UI, TW_CONFIRM, "()V");
    if (nextMeaningful(prepare) != twConfirm) { throw mismatch("trade barrier is not immediately before twConfirm"); }
    AbstractInsnNode marketReadInsn = previousMeaningful(prepare);
    AbstractInsnNode thisLoadInsn = previousMeaningful(marketReadInsn);
    if (!(marketReadInsn instanceof FieldInsnNode marketRead) || marketRead.getOpcode() != Opcodes.GETFIELD
        || !TARGET.equals(marketRead.owner) || !marketField.equals(marketRead.name)
        || !MARKET_DESC.equals(marketRead.desc) || !(thisLoadInsn instanceof VarInsnNode thisLoad)
        || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0) {
      throw mismatch("trade barrier market identity arguments changed");
    }

    MethodInsnNode guard = uniqueCall(method, RUNTIME, GUARD, GUARD_DESC);
    AbstractInsnNode transactionLoadInsn = previousMeaningful(guard);
    marketReadInsn = previousMeaningful(transactionLoadInsn);
    thisLoadInsn = previousMeaningful(marketReadInsn);
    AbstractInsnNode economyLoadInsn = previousMeaningful(thisLoadInsn);
    if (!(transactionLoadInsn instanceof VarInsnNode transactionLoad) || transactionLoad.getOpcode() != Opcodes.ALOAD
        || transactionLoad.var != 1 || !(marketReadInsn instanceof FieldInsnNode guardMarketRead)
        || guardMarketRead.getOpcode() != Opcodes.GETFIELD || !TARGET.equals(guardMarketRead.owner)
        || !marketField.equals(guardMarketRead.name) || !MARKET_DESC.equals(guardMarketRead.desc)
        || !(thisLoadInsn instanceof VarInsnNode guardThisLoad) || guardThisLoad.getOpcode() != Opcodes.ALOAD
        || guardThisLoad.var != 0 || !(economyLoadInsn instanceof VarInsnNode economyLoad)
        || economyLoad.getOpcode() != Opcodes.ALOAD) {
      throw mismatch("trade guard identity arguments changed");
    }
    AbstractInsnNode branchInsn = nextMeaningful(guard);
    if (!(branchInsn instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFNE) {
      throw mismatch("trade guard branch changed");
    }
    MethodInsnNode fallback = uniqueCall(method, ECONOMY_API, "doubleStep", "()V");
    AbstractInsnNode fallbackReceiverInsn = previousMeaningful(fallback);
    if (!(fallbackReceiverInsn instanceof VarInsnNode fallbackReceiver) || fallbackReceiver.getOpcode() != Opcodes.ALOAD
        || fallbackReceiver.var != economyLoad.var
        || instructionIndex(method, branchInsn) >= instructionIndex(method, fallback)
        || instructionIndex(method, fallback) >= instructionIndex(method, branch.label)) {
      throw mismatch("trade global fallback receiver/branch changed");
    }
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


  private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String desc) {
    ArrayList<MethodInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc)) {
        result.add(call);
      }
    }
    return result;
  }

  private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String desc) {
    List<MethodInsnNode> result = calls(method, owner, name, desc);
    requireCount(owner + "." + name, result.size(), 1);
    return result.get(0);
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    MethodNode result = null;
    for (MethodNode method : node.methods) {
      if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
      if (result != null) throw mismatch("duplicate method " + name + desc);
      result = method;
    }
    if (result == null) throw mismatch("missing method " + name + desc);
    return result;
  }

  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
    return null;
  }

  private static String requireMarketFieldName(ClassNode node) {
    FieldNode match = null;
    for (FieldNode candidate : node.fields) {
      if (!LEGACY_MARKET_FIELD.equals(candidate.name) && !REPAIRED_MARKET_FIELD.equals(candidate.name)) {
        continue;
      }
      if (match != null) { throw mismatch("market field aliases are ambiguous"); }
      match = candidate;
    }
    if (match == null) { throw mismatch("exact market field changed"); }
    if (!MARKET_DESC.equals(match.desc) || (match.access & Opcodes.ACC_STATIC) != 0) {
      throw mismatch("exact market field changed");
    }
    return match.name;
  }

  private static void requireResolvedMarketField(ClassNode node, String name) {
    String resolved = requireMarketFieldName(node);
    if (!resolved.equals(name)) { throw mismatch("market field alias changed during transformation"); }
  }

  private static void requireMarker(FieldNode marker) {
    if (marker == null || !"Ljava/lang/String;".equals(marker.desc) || !MARKER_VALUE.equals(marker.value)
        || (marker.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)) != (Opcodes.ACC_STATIC
            | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)) {
      throw mismatch("patch marker changed");
    }
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " count changed: expected " + expected + ", found " + actual); }
  }
}
