package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static com.starsector.prepatcher.agent.Contract.*;

/**
 * Exact-current-game semantic gate for filtered CommodityMarketData
 * reconstruction. It changes no bytecode; match proves the constructor
 * contract and mutate is a no-op. The runtime readiness publication remains
 * in the transformer envelope.
 */
final class CommodityMarketDataCommitContract implements StructuredPatch<CommodityMarketDataCommitContract.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
  private static final String CONSTRUCTOR = "(Ljava/lang/String;Ljava/lang/String;)V";

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
    if (!TARGET.equals(node.name)) { throw mismatch("unexpected owner " + node.name); }
    MethodNode constructor = null;
    for (MethodNode method : node.methods) {
      if ("<init>".equals(method.name) && CONSTRUCTOR.equals(method.desc)) {
        if (constructor != null) { throw mismatch("duplicate String/String constructor"); }
        constructor = method;
      }
    }
    if (constructor == null || (constructor.access & Opcodes.ACC_PUBLIC) == 0
        || (constructor.access & Opcodes.ACC_STATIC) != 0) {
      throw mismatch("public String/String constructor missing");
    }
    if (constructor.tryCatchBlocks != null && !constructor.tryCatchBlocks.isEmpty()) {
      throw mismatch("constructor gained exception regions");
    }
    requireCount(constructor, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/Global", "getSector",
        "()Lcom/fs/starfarer/api/campaign/SectorAPI;", 1, "Global.getSector");
    requireCount(constructor, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/SectorAPI", "getEconomy",
        "()Lcom/fs/starfarer/api/campaign/econ/EconomyAPI;", 1, "SectorAPI.getEconomy");
    requireCount(constructor, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/EconomyAPI",
        "getMarketsInGroup", "(Ljava/lang/String;)Ljava/util/List;", 1, "EconomyAPI.getMarketsInGroup");
    requireCount(constructor, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/MarketAPI",
        "getCommodityData", "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;", 2,
        "MarketAPI.getCommodityData");
    requireCallArgument(constructor, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/EconomyAPI",
        "getMarketsInGroup", "(Ljava/lang/String;)Ljava/util/List;", 2, "econ-group argument");
    requireEveryCallArgument(constructor, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/MarketAPI",
        "getCommodityData", "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI;", 1,
        "commodity-id argument");
    requireFieldAssignment(constructor, "commodityId", 1);
    requireFieldAssignment(constructor, "econGroup", 2);
    requireCount(constructor, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI",
        "updateMaxSupplyAndDemand", "()V", 1, "CommodityOnMarketAPI.updateMaxSupplyAndDemand");
    requireCount(constructor, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/econ/CommodityOnMarket",
        "setCommodityMarketData", "(Lcom/fs/starfarer/campaign/econ/reach/CommodityMarketData;)V", 1,
        "CommodityOnMarket.setCommodityMarketData");
    requireCount(constructor, Opcodes.INVOKEVIRTUAL, TARGET, "adjustMarketShare", "(Ljava/util/List;)V", 1,
        "adjustMarketShare");
  }

  private static void requireCount(MethodNode method, int opcode, String owner, String name, String desc, int expected,
      String label) {
    int actual = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
          && name.equals(call.name) && desc.equals(call.desc)) {
        actual++;
      }
    }
    if (actual != expected) { throw mismatch(label + " count changed: expected " + expected + ", found " + actual); }
  }

  private static void requireCallArgument(MethodNode method, int opcode, String owner, String name, String desc,
      int local, String label) {
    MethodInsnNode match = null;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
          && name.equals(call.name) && desc.equals(call.desc)) {
        if (match != null) throw mismatch(label + " call is not unique");
        match = call;
      }
    }
    if (match == null || !isAload(previousMeaningful(match), local)) {
      throw mismatch(label + " no longer flows from local " + local);
    }
  }

  private static void requireEveryCallArgument(MethodNode method, int opcode, String owner, String name, String desc,
      int local, String label) {
    int matches = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
          && name.equals(call.name) && desc.equals(call.desc)) {
        matches++;
        if (!isAload(previousMeaningful(call), local)) {
          throw mismatch(label + " no longer flows from local " + local);
        }
      }
    }
    if (matches == 0) throw mismatch(label + " calls missing");
  }

  private static void requireFieldAssignment(MethodNode method, String fieldName, int local) {
    FieldInsnNode match = null;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
          && TARGET.equals(field.owner) && fieldName.equals(field.name) && "Ljava/lang/String;".equals(field.desc)) {
        if (match != null) { throw mismatch(fieldName + " assignment is not unique"); }
        match = field;
      }
    }
    AbstractInsnNode value = previousMeaningful(match);
    AbstractInsnNode receiver = previousMeaningful(value);
    if (!isAload(value, local) || !isAload(receiver, 0)) { throw mismatch(fieldName + " assignment flow changed"); }
  }

  private static boolean isAload(AbstractInsnNode instruction, int local) {
    return instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD
        && variable.var == local;
  }

  private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
    AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
    while (current != null && current.getOpcode() < 0) current = current.getPrevious();
    return current;
  }
}
