package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;


import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * ReachEconomy surface of the vanilla market-open localization contract.
 * Proves the task-phase structure of nextStep(params); changes no bytecode.
 * The paired Economy surface lives in VanillaMarketOpenEconomyContract.
 */
final class VanillaMarketOpenReachContract implements StructuredPatch<VanillaMarketOpenReachContract.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/econ/reach/ReachEconomy";
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
    if (!TARGET.equals(node.name)) { throw mismatch("unexpected ReachEconomy owner " + node.name); }
    MethodNode step = requireMethod(node, "nextStep", "(" + PARAMS_DESC + ")V");
    requirePublicInstance(step, "ReachEconomy.nextStep");
    requireNoTryCatch(step, "ReachEconomy.nextStep");

    requireCount("admin stats refresh", countCallsByName(step, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/characters/MutableCharacterStatsAPI", "refreshCharacterStatsEffects"), 1);
    requireCount("admin governed-market refresh", countCallsByName(step, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/characters/MutableCharacterStatsAPI", "refreshGovernedOutpostEffects"), 1);

    String main = "com/fs/starfarer/campaign/econ/reach/MainWorkTask2";
    String again = "com/fs/starfarer/campaign/econ/reach/UpdateMarketsAgainTask";
    String immigration = "com/fs/starfarer/campaign/econ/reach/ImmigrationTask";
    String finish = "com/fs/starfarer/campaign/econ/reach/FinishEconomyUpdateTask";
    requireCount("MainWorkTask2 allocation", countNew(step, main), 1);
    requireCount("UpdateMarketsAgainTask allocation", countNew(step, again), 1);
    requireCount("ImmigrationTask allocation", countNew(step, immigration), 1);
    requireCount("FinishEconomyUpdateTask allocation", countNew(step, finish), 1);
    requireCount("MainWorkTask2 constructor", countCallsByName(step, Opcodes.INVOKESPECIAL, main, "<init>"), 1);
    requireCount("UpdateMarketsAgainTask constructor", countCallsByName(step, Opcodes.INVOKESPECIAL, again, "<init>"),
        1);
    requireCount("ImmigrationTask constructor", countCallsByName(step, Opcodes.INVOKESPECIAL, immigration, "<init>"),
        1);
    requireCount("FinishEconomyUpdateTask constructor", countCallsByName(step, Opcodes.INVOKESPECIAL, finish, "<init>"),
        1);
    requireCount("MultiFrameTask.doNextBatch loops", countCallsByName(step, Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/econ/contract/iter/MultiFrameTask", "doNextBatch"), 4);
    requireCount("MultiFrameTask.isDone loops", countCallsByName(step, Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/econ/contract/iter/MultiFrameTask", "isDone"), 4);
    requireCount("withImmigration read", countFieldReads(step, PARAMS, "withImmigration", "Z"), 1);
    requireCount("forceNonUIStep read", countFieldReads(step, PARAMS, "forceNonUIStep", "Z"), 1);

    int mainIndex = firstNewIndex(step, main);
    int againIndex = firstNewIndex(step, again);
    int immigrationIndex = firstNewIndex(step, immigration);
    int finishIndex = firstNewIndex(step, finish);
    if (!(mainIndex < againIndex && againIndex < immigrationIndex && immigrationIndex < finishIndex)) {
      throw mismatch("ReachEconomy task phase order changed");
    }
  }

  private static int firstNewIndex(MethodNode method, String type) {
    int index = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction.getOpcode() < 0) continue;
      if (instruction instanceof TypeInsnNode allocation && instruction.getOpcode() == Opcodes.NEW
          && type.equals(allocation.desc)) {
        return index;
      }
      index++;
    }
    return Integer.MAX_VALUE;
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

  private static int countFieldReads(MethodNode method, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
          && owner.equals(field.owner) && name.equals(field.name) && desc.equals(field.desc))
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
}
