package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Economy surface of the vanilla market-open localization contract. Proves
 * the synchronous live-market step chain; changes no bytecode. The paired
 * ReachEconomy surface lives in VanillaMarketOpenReachContract.
 */
final class VanillaMarketOpenEconomyContract implements StructuredPatch<VanillaMarketOpenEconomyContract.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/econ/Economy";
  static final String REACH = "com/fs/starfarer/campaign/econ/reach/ReachEconomy";
  private static final String PARAMS = "com/fs/starfarer/campaign/econ/reach/MainWorkTask$EconWorkParams";
  private static final String PARAMS_DESC = "L" + PARAMS + ";";

  record Plan() {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    validate(node);
    return Match.plan(new Plan());
  }

  @Override
  public PatchReport mutate(Plan plan) {
    return new PatchReport();
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    validate(node);
  }

  private static void validate(ClassNode node) {
    if (!TARGET.equals(node.name)) { throw mismatch("unexpected Economy owner " + node.name); }
    String reachDesc = "L" + REACH + ";";
    FieldNode reachField = null;
    for (FieldNode field : node.fields) {
      if (!reachDesc.equals(field.desc)) continue;
      if (reachField != null) { throw mismatch("multiple ReachEconomy fields"); }
      reachField = field;
    }
    if (reachField == null || (reachField.access & Opcodes.ACC_STATIC) != 0) {
      throw mismatch("exact ReachEconomy instance field missing");
    }

    MethodNode getter = requireMethod(node, "getEconomy", "()" + reachDesc);
    requirePublicInstance(getter, "getEconomy");
    List<AbstractInsnNode> getterBody = meaningful(getter);
    if (getterBody.size() != 3) { throw mismatch("getEconomy instruction count changed"); }
    requireLoad(getterBody.get(0), Opcodes.ALOAD, 0, "getEconomy receiver");
    if (!(getterBody.get(1) instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETFIELD
        || !node.name.equals(read.owner) || !reachField.name.equals(read.name) || !reachDesc.equals(read.desc)
        || getterBody.get(2).getOpcode() != Opcodes.ARETURN) {
      throw mismatch("getEconomy no longer returns exact field");
    }

    MethodNode noArg = requireMethod(node, "nextStep", "()V");
    requirePublicInstance(noArg, "nextStep()V");
    List<AbstractInsnNode> noArgBody = meaningful(noArg);
    if (noArgBody.size() != 4) { throw mismatch("nextStep() delegate body changed"); }
    requireLoad(noArgBody.get(0), Opcodes.ALOAD, 0, "nextStep receiver");
    if (noArgBody.get(1).getOpcode() != Opcodes.ACONST_NULL || !(noArgBody.get(2) instanceof MethodInsnNode delegate)
        || delegate.getOpcode() != Opcodes.INVOKEVIRTUAL || !TARGET.equals(delegate.owner)
        || !"nextStep".equals(delegate.name) || !("(" + PARAMS_DESC + ")V").equals(delegate.desc)
        || noArgBody.get(3).getOpcode() != Opcodes.RETURN) {
      throw mismatch("nextStep() is not exact null-params delegate");
    }

    MethodNode step = requireMethod(node, "nextStep", "(" + PARAMS_DESC + ")V");
    requirePublicInstance(step, "nextStep(params)");
    requireNoTryCatch(step, "nextStep(params)");
    requireCount("Market.updatePrevStability",
        countCalls(step, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/econ/Market", "updatePrevStability", "()V"),
        1);
    requireCount("EconWorkParams allocation", countNew(step, PARAMS), 1);
    requireCount("withIncomeAndUpkeep writes", countFieldWrites(step, PARAMS, "withIncomeAndUpkeep", "Z"), 1);
    requireCount("withStockpileUpdate writes", countFieldWrites(step, PARAMS, "withStockpileUpdate", "Z"), 1);
    requireCount("withImmigration writes", countFieldWrites(step, PARAMS, "withImmigration", "Z"), 1);
    requireCount("ReachEconomy.nextStep",
        countCalls(step, Opcodes.INVOKEVIRTUAL, REACH, "nextStep", "(" + PARAMS_DESC + ")V"), 1);

    MethodNode stockpile =
        requireMethod(node, "forceStockpileUpdate", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    requirePublicInstance(stockpile, "forceStockpileUpdate");
    requireNoTryCatch(stockpile, "forceStockpileUpdate");
    requireCount("MarketAPI.getAllCommodities", countCalls(stockpile, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/MarketAPI", "getAllCommodities", "()Ljava/util/List;"), 1);
    requireCount("CommodityOnMarketAPI.isNonEcon", countCalls(stockpile, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI", "isNonEcon", "()Z"), 1);
    requireCount("MainWorkTask2.updateStockpileAndPrice", countCallsByName(stockpile, Opcodes.INVOKESTATIC,
        "com/fs/starfarer/campaign/econ/reach/MainWorkTask2", "updateStockpileAndPrice"), 1);
  }

  private static int countNew(MethodNode method, String type) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
          && type.equals(allocation.desc))
        count++;
    }
    return count;
  }

  private static int countFieldWrites(MethodNode method, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
          && owner.equals(field.owner) && name.equals(field.name) && desc.equals(field.desc))
        count++;
    }
    return count;
  }

  private static int countCalls(MethodNode method, int opcode, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
          && name.equals(call.name) && desc.equals(call.desc))
        count++;
    }
    return count;
  }

  private static int countCallsByName(MethodNode method, int opcode, String owner, String name) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
          && name.equals(call.name))
        count++;
    }
    return count;
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }

  private static void requireNoTryCatch(MethodNode method, String label) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch(label + " gained exception regions");
    }
  }

  private static void requirePublicInstance(MethodNode method, String label) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch(label + " access changed");
    }
  }

  private static void requireLoad(AbstractInsnNode instruction, int opcode, int local, String label) {
    if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != opcode || load.var != local) {
      throw mismatch(label + " changed");
    }
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    MethodNode result = null;
    for (MethodNode method : node.methods) {
      if (name.equals(method.name) && desc.equals(method.desc)) {
        if (result != null) { throw mismatch("duplicate method " + name + desc); }
        result = method;
      }
    }
    if (result == null) throw mismatch("missing method " + name + desc);
    return result;
  }

  private static List<AbstractInsnNode> meaningful(MethodNode method) {
    List<AbstractInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction.getOpcode() >= 0) result.add(instruction);
    }
    return result;
  }
}
