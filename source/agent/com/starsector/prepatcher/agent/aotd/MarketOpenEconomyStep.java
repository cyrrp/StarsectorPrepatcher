package com.starsector.prepatcher.agent.aotd;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.instructionIndex;
import static com.starsector.prepatcher.agent.bytecode.Queries.nextMeaningful;
import static com.starsector.prepatcher.agent.bytecode.Queries.previousMeaningful;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Guards the exact market-open Economy.nextStep call with an explicit runtime
 * action. Shared by the planet-condition market-open surface and the AoTD
 * market-open context through one marker.
 */
final class MarketOpenEconomyStep implements StructuredPatch<MarketOpenEconomyStep.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/CampaignEngine";
  private static final String METHOD = "reportPlayerOpenedMarket";
  private static final String DESC = "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V";
  private static final String LEGACY_RAW = "spp$raw$reportPlayerOpenedMarket";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
  private static final String MARKER = "spp$patched$aotdMarketOpenContext";
  private static final String MARKER_VALUE = "StarsectorPrepatcher:aotd-market-open-explicit-guard-v4";
  private static final String MARKET_OPEN_GUARD = "shouldHandleVanillaMarketOpenEconomyStep";
  private static final String MARKET_OPEN_GUARD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

  record Plan(MethodNode method, MethodInsnNode nextStepCall, List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    FieldNode marker = field(node, MARKER);
    if (marker != null) {
      requireMarker(marker);
      requirePatchedShape(node);
      return Match.alreadyApplied("market-open context postcondition matches");
    }
    if (method(node, LEGACY_RAW, DESC) != null) { throw mismatch("legacy raw helper exists without owned marker"); }
    MethodNode original = requireMethod(node, METHOD, DESC);
    requireOriginalShape(original);
    MethodInsnNode nextStepCall = null;
    for (AbstractInsnNode instruction : original.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
          && "com/fs/starfarer/campaign/econ/Economy".equals(call.owner) && "nextStep".equals(call.name)
          && "()V".equals(call.desc)) {
        if (nextStepCall != null) { throw mismatch("multiple Economy.nextStep calls"); }
        nextStepCall = call;
      }
    }
    if (nextStepCall == null) { throw mismatch("missing Economy.nextStep call"); }
    return Match.plan(new Plan(original, nextStepCall, node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode method = plan.method();
    MethodInsnNode nextStepCall = plan.nextStepCall();
    int economyLocal = method.maxLocals;
    method.maxLocals = economyLocal + 1;
    LabelNode afterStep = new LabelNode();
    InsnList guard = new InsnList();
    // The Economy receiver is already on the stack at this point.
    guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, MARKET_OPEN_GUARD, MARKET_OPEN_GUARD_DESC, false));
    guard.add(new JumpInsnNode(Opcodes.IFNE, afterStep));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    method.instructions.insertBefore(nextStepCall, guard);
    method.instructions.insert(nextStepCall, afterStep);

    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, MARKER,
            "Ljava/lang/String;", null, MARKER_VALUE));
    PatchReport report = new PatchReport();
    report.add("market-open economy-step guard", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedShape(node);
  }

  private static void requireOriginalShape(MethodNode method) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("reportPlayerOpenedMarket access changed");
    }
    requireOriginalBodyShape(method);
  }

  private static void requireOriginalBodyShape(MethodNode method) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("reportPlayerOpenedMarket gained exception regions");
    }
    requireCount("Economy.nextStep calls",
        countCalls(method, "com/fs/starfarer/campaign/econ/Economy", "nextStep", "()V"), 1);
    requireCount("setCurrentlyOpenMarket calls",
        countCalls(method, TARGET, "setCurrentlyOpenMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V"), 1);
    requireCount("market-open guards", countCalls(method, RUNTIME, MARKET_OPEN_GUARD, MARKET_OPEN_GUARD_DESC), 0);

    int nextStep = instructionIndex(method, "com/fs/starfarer/campaign/econ/Economy", "nextStep", "()V");
    int publish =
        instructionIndex(method, TARGET, "setCurrentlyOpenMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    if (nextStep < 0 || publish < 0 || nextStep >= publish) {
      throw mismatch("vanilla nextStep-before-currentlyOpenMarket ordering changed");
    }
  }

  private static void requireGuardedShape(MethodNode method) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("guarded reportPlayerOpenedMarket access changed");
    }
    requireCount("guarded exception regions", method.tryCatchBlocks.size(), 0);
    requireCount("guarded nextStep calls",
        countCalls(method, "com/fs/starfarer/campaign/econ/Economy", "nextStep", "()V"), 1);
    requireCount("guarded market-open guards", countCalls(method, RUNTIME, MARKET_OPEN_GUARD, MARKET_OPEN_GUARD_DESC),
        1);
    requireCount("guarded setCurrentlyOpenMarket calls",
        countCalls(method, TARGET, "setCurrentlyOpenMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V"), 1);
    int nextStep = instructionIndex(method, "com/fs/starfarer/campaign/econ/Economy", "nextStep", "()V");
    int publish =
        instructionIndex(method, TARGET, "setCurrentlyOpenMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    if (nextStep < 0 || publish < 0 || nextStep >= publish) {
      throw mismatch("guarded nextStep-before-currentlyOpenMarket ordering changed");
    }
    MethodInsnNode guard = null;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
          && RUNTIME.equals(call.owner) && MARKET_OPEN_GUARD.equals(call.name)
          && MARKET_OPEN_GUARD_DESC.equals(call.desc)) {
        guard = call;
        break;
      }
    }
    if (guard == null) throw mismatch("market-open guard missing");
    AbstractInsnNode marketLoad = previousMeaningful(guard);
    AbstractInsnNode economyLoad = previousMeaningful(marketLoad);
    if (!(marketLoad instanceof VarInsnNode marketVar) || marketVar.getOpcode() != Opcodes.ALOAD
        || marketVar.var != 1) {
      throw mismatch("market-open guard market argument changed");
    }
    if (!(economyLoad instanceof VarInsnNode economyVar) || economyVar.getOpcode() != Opcodes.ALOAD) {
      throw mismatch("market-open guard economy argument changed");
    }
    AbstractInsnNode branch = nextMeaningful(guard);
    if (!(branch instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNE) {
      throw mismatch("market-open guard branch changed");
    }
    MethodInsnNode fallback = null;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
          && "com/fs/starfarer/campaign/econ/Economy".equals(call.owner) && "nextStep".equals(call.name)
          && "()V".equals(call.desc)) {
        fallback = call;
        break;
      }
    }
    if (fallback == null) throw mismatch("market-open fallback missing");
    AbstractInsnNode fallbackReceiver = previousMeaningful(fallback);
    if (!(fallbackReceiver instanceof VarInsnNode receiverLoad) || receiverLoad.getOpcode() != Opcodes.ALOAD
        || receiverLoad.var != economyVar.var) {
      throw mismatch("market-open fallback receiver changed");
    }
    if (instructionIndex(method, branch) >= instructionIndex(method, fallback)
        || instructionIndex(method, fallback) >= instructionIndex(method, jump.label)) {
      throw mismatch("market-open guard does not enclose only nextStep fallback");
    }
  }

  private static void requirePatchedShape(ClassNode node) {
    requireMarker(field(node, MARKER));
    if (method(node, LEGACY_RAW, DESC) != null) { throw mismatch("guarded class retained legacy raw helper"); }
    requireGuardedShape(requireMethod(node, METHOD, DESC));
  }


  private static int countCalls(MethodNode method, String owner, String name, String desc) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        count++;
    }
    return count;
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }

  private static MethodNode method(ClassNode node, String name, String desc) {
    for (MethodNode method : node.methods) if (name.equals(method.name) && desc.equals(method.desc)) return method;
    return null;
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    MethodNode method = method(node, name, desc);
    if (method == null) throw mismatch("missing method " + name + desc);
    return method;
  }

  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
    return null;
  }

  private static void requireMarker(FieldNode marker) {
    if (marker == null
        || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
        || !"Ljava/lang/String;".equals(marker.desc) || !MARKER_VALUE.equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }
}
