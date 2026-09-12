package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Shared structural matchers for the incremental strategic-jump index family. The
 * destination-first patch owns the guarded hostile-market score shape; the destination-index
 * patch consumes that post-state through the same inspectors so both sides prove identical
 * structure.
 */
final class StrategicJump {
  static final String STRATEGIC_JUMP_METHOD_DESC = "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;"
      + "Lcom/fs/starfarer/api/campaign/LocationAPI;)" + "Lcom/fs/starfarer/campaign/ai/StrategicModule$Oo;";
  static final String STRATEGIC_JUMP_UPDATE_METHOD_DESC = "(Lcom/fs/starfarer/api/campaign/LocationAPI;F)V";
  static final String HOSTILE_MARKETS_DESC =
      "(Lcom/fs/starfarer/api/campaign/FactionAPI;" + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I";
  static final String UNSAFE_FLEETS_DESC =
      "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;" + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I";
  static final int STRATEGIC_JUMP_UNCOMPUTED = Integer.MIN_VALUE;

  static StrategicJumpCommon inspectStrategicJumpCommon(String owner, MethodNode method) {
    requireCount("StrategicModule hostile-market calls", countCalls(method, Opcodes.INVOKESTATIC,
        "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_MARKETS_DESC), 1);
    requireCount("StrategicModule destination list calls", countCalls(method, Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/JumpPoint", "getDestinations", "()Ljava/util/List;"), 1);
    requireCount("StrategicModule unsafe-fleet calls",
        countCalls(method, Opcodes.INVOKESTATIC, owner, "getNumUnsafeFleetsAround", UNSAFE_FLEETS_DESC), 1);
    requireCount("StrategicModule candidate sorting", countCalls(method, Opcodes.INVOKESTATIC, "java/util/Collections",
        "sort", "(Ljava/util/List;Ljava/util/Comparator;)V"), 1);

    MethodInsnNode destinations = calls(method, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/JumpPoint",
        "getDestinations", "()Ljava/util/List;").get(0);
    VarInsnNode jumpLoad =
        requireVar(previousMeaningful(destinations), Opcodes.ALOAD, "StrategicModule jump-point receiver");
    int jumpPointLocal = jumpLoad.var;

    MethodInsnNode listIterator = asMethod(nextMeaningful(destinations), "StrategicModule destination iterator");
    requireCall(listIterator, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;",
        "StrategicModule destination iterator");
    VarInsnNode iteratorStore =
        requireVar(nextMeaningful(listIterator), Opcodes.ASTORE, "StrategicModule destination iterator local");

    MethodInsnNode iteratorNext = null;
    for (AbstractInsnNode cursor = nextMeaningful(iteratorStore); cursor != null; cursor = nextMeaningful(cursor)) {
      if (!(cursor instanceof MethodInsnNode call)
          || !callMatches(call, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;"))
        continue;
      VarInsnNode receiver =
          requireVar(previousMeaningful(call), Opcodes.ALOAD, "StrategicModule destination Iterator.next receiver");
      if (receiver.var == iteratorStore.var) {
        iteratorNext = call;
        break;
      }
    }
    if (iteratorNext == null) throw mismatch("destination Iterator.next call is missing");
    AbstractInsnNode cast = nextMeaningful(iteratorNext);
    if (!(cast instanceof TypeInsnNode type) || cast.getOpcode() != Opcodes.CHECKCAST
        || !type.desc.equals("com/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination")) {
      throw mismatch("destination Iterator.next result cast changed");
    }
    VarInsnNode destinationStore =
        requireVar(nextMeaningful(cast), Opcodes.ASTORE, "StrategicModule destination local");
    int destinationLocal = destinationStore.var;

    VarInsnNode nullReceiver =
        requireVar(nextMeaningful(destinationStore), Opcodes.ALOAD, "StrategicModule null destination receiver");
    if (nullReceiver.var != destinationLocal) { throw mismatch("null destination check reads a different local"); }
    MethodInsnNode nullGet = asMethod(nextMeaningful(nullReceiver), "StrategicModule null destination getter");
    requireJumpDestinationGetter(nullGet);
    JumpInsnNode nullJump =
        requireJump(nextMeaningful(nullGet), Opcodes.IFNULL, "StrategicModule null destination branch");

    VarInsnNode exactReceiver =
        requireVar(nextMeaningful(nullJump), Opcodes.ALOAD, "StrategicModule exact destination receiver");
    if (exactReceiver.var != destinationLocal) { throw mismatch("exact destination check reads a different local"); }
    MethodInsnNode exactGet = asMethod(nextMeaningful(exactReceiver), "StrategicModule exact destination getter");
    requireJumpDestinationGetter(exactGet);
    MethodInsnNode exactLocation = asMethod(nextMeaningful(exactGet), "StrategicModule exact destination location");
    requireContainingLocationGetter(exactLocation);
    VarInsnNode targetLoad =
        requireVar(nextMeaningful(exactLocation), Opcodes.ALOAD, "StrategicModule requested location local");
    if (targetLoad.var != 1) { throw mismatch("requested LocationAPI is not read from static argument local 1"); }
    JumpInsnNode exactJump =
        requireJump(nextMeaningful(targetLoad), Opcodes.IF_ACMPEQ, "StrategicModule exact destination branch");

    VarInsnNode fallbackLoad =
        requireVar(nextMeaningful(exactJump), Opcodes.ILOAD, "StrategicModule hyperspace fallback flag");
    JumpInsnNode fallbackDisabled =
        requireJump(nextMeaningful(fallbackLoad), Opcodes.IFEQ, "StrategicModule fallback-disabled branch");
    requireVar(nextMeaningful(fallbackDisabled), Opcodes.ALOAD, "StrategicModule hyperspace local");
    VarInsnNode fallbackReceiver = requireVar(nextMeaningful(nextMeaningful(fallbackDisabled)), Opcodes.ALOAD,
        "StrategicModule fallback destination receiver");
    if (fallbackReceiver.var != destinationLocal) {
      throw mismatch("fallback destination check reads a different local");
    }
    MethodInsnNode fallbackGet =
        asMethod(nextMeaningful(fallbackReceiver), "StrategicModule fallback destination getter");
    requireJumpDestinationGetter(fallbackGet);
    MethodInsnNode fallbackLocation =
        asMethod(nextMeaningful(fallbackGet), "StrategicModule fallback destination location");
    requireContainingLocationGetter(fallbackLocation);
    JumpInsnNode fallbackJump =
        requireJump(nextMeaningful(fallbackLocation), Opcodes.IF_ACMPNE, "StrategicModule fallback destination branch");

    if (nullJump.label != fallbackDisabled.label || nullJump.label != fallbackJump.label) {
      throw mismatch("destination rejection branches no longer share one continue target");
    }
    AbstractInsnNode acceptedEntry = nextMeaningful(exactJump.label);
    if (acceptedEntry == null || acceptedEntry != nextMeaningful(fallbackJump)) {
      throw mismatch("exact and fallback destination branches do not converge");
    }
    AbstractInsnNode candidateStart = acceptedEntry;
    if (!(candidateStart instanceof VarInsnNode candidateLoad) || candidateLoad.getOpcode() != Opcodes.ALOAD
        || candidateLoad.var != jumpPointLocal
        || !(nextMeaningful(candidateLoad) instanceof MethodInsnNode candidateCall)
        || !callMatches(candidateCall, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/JumpPoint", "getLocation",
            "()Lorg/lwjgl/util/vector/Vector2f;")) {
      candidateStart = null;
      for (AbstractInsnNode cursor = acceptedEntry; cursor != null && cursor != nullJump.label; cursor =
          nextMeaningful(cursor)) {
        if (!(cursor instanceof VarInsnNode candidateLoad) || candidateLoad.getOpcode() != Opcodes.ALOAD
            || candidateLoad.var != jumpPointLocal)
          continue;
        AbstractInsnNode next = nextMeaningful(candidateLoad);
        if (next instanceof MethodInsnNode candidateCall && callMatches(candidateCall, Opcodes.INVOKEVIRTUAL,
            "com/fs/starfarer/campaign/JumpPoint", "getLocation", "()Lorg/lwjgl/util/vector/Vector2f;")) {
          candidateStart = candidateLoad;
          break;
        }
      }
      if (candidateStart == null) { throw mismatch("accepted destination path no longer reaches jump-point distance"); }
    }

    MethodInsnNode unsafeCall =
        calls(method, Opcodes.INVOKESTATIC, owner, "getNumUnsafeFleetsAround", UNSAFE_FLEETS_DESC).get(0);
    if (method.instructions.indexOf(unsafeCall) <= method.instructions.indexOf(candidateStart)
        || method.instructions.indexOf(unsafeCall) >= method.instructions.indexOf(nullJump.label)) {
      throw mismatch("unsafe-fleet score moved outside the accepted destination path");
    }
    VarInsnNode unsafeLoad =
        requireVar(nextMeaningful(unsafeCall), Opcodes.ILOAD, "StrategicModule hostile-market candidate score");
    AbstractInsnNode sum = nextMeaningful(unsafeLoad);
    if (sum == null || sum.getOpcode() != Opcodes.IADD) {
      throw mismatch("hostile-market score is not added to the unsafe-fleet score");
    }
    AbstractInsnNode scoreStore = nextMeaningful(sum);
    if (!(scoreStore instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
        || !field.desc.equals("I")) {
      throw mismatch("combined unsafe score is not stored in the candidate int field");
    }

    int fleetLocal = 0;
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    Frame<SourceValue> unsafeFrame = frameBefore(method, unsafeCall, frames);
    if (unsafeFrame.getStackSize() < 3
        || !sourceIsLocal(unsafeFrame.getStack(unsafeFrame.getStackSize() - 3), Opcodes.ALOAD, fleetLocal)
        || !sourceIsLocal(unsafeFrame.getStack(unsafeFrame.getStackSize() - 2), Opcodes.ALOAD, jumpPointLocal)
        || !sourceIsFloatConstant(unsafeFrame.getStack(unsafeFrame.getStackSize() - 1), 750f)) {
      throw mismatch("unsafe-fleet call arguments changed");
    }

    return new StrategicJumpCommon(method, destinations, acceptedEntry, candidateStart, jumpPointLocal, fleetLocal,
        unsafeLoad.var);
  }

  static StrategicJumpOriginal inspectStrategicJumpOriginal(StrategicJumpCommon common, boolean required) {
    MethodNode method = common.method();
    MethodInsnNode hostile = calls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
        "getNumHostileMarkets", HOSTILE_MARKETS_DESC).get(0);
    if (method.instructions.indexOf(hostile) > method.instructions.indexOf(common.destinations())) {
      if (required) throw mismatch("hostile-market call is not in the vanilla eager position");
      return null;
    }

    AbstractInsnNode radiusInsn = previousMeaningful(hostile);
    if (!(radiusInsn instanceof LdcInsnNode radius) || !isFloat(radius, 4000f)) {
      if (required) throw mismatch("hostile-market radius changed");
      return null;
    }
    VarInsnNode jumpLoad =
        requireVar(previousMeaningful(radius), Opcodes.ALOAD, "StrategicModule eager jump-point argument");
    MethodInsnNode faction = asMethod(previousMeaningful(jumpLoad), "StrategicModule eager faction getter");
    VarInsnNode fleetLoad =
        requireVar(previousMeaningful(faction), Opcodes.ALOAD, "StrategicModule eager fleet receiver");
    if (jumpLoad.var != common.jumpPointLocal() || fleetLoad.var != common.fleetLocal()
        || !callMatches(faction, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getFaction", "()Lcom/fs/starfarer/api/campaign/FactionAPI;")) {
      if (required) throw mismatch("hostile-market eager arguments changed");
      return null;
    }
    VarInsnNode firstStore =
        requireVar(nextMeaningful(hostile), Opcodes.ISTORE, "StrategicModule eager hostile score local");
    VarInsnNode multiplyLoad =
        requireVar(nextMeaningful(firstStore), Opcodes.ILOAD, "StrategicModule eager hostile score multiplier load");
    AbstractInsnNode two = nextMeaningful(multiplyLoad);
    AbstractInsnNode multiply = nextMeaningful(two);
    VarInsnNode secondStore =
        requireVar(nextMeaningful(multiply), Opcodes.ISTORE, "StrategicModule eager hostile score result");
    if (firstStore.var != common.unsafeLocal() || multiplyLoad.var != common.unsafeLocal()
        || secondStore.var != common.unsafeLocal() || !isIntegerLdc(two, 2) || multiply == null
        || multiply.getOpcode() != Opcodes.IMUL
        || nextMeaningful(secondStore) != previousMeaningful(common.destinations())) {
      if (required) throw mismatch("hostile-market eager score sequence changed");
      return null;
    }
    return new StrategicJumpOriginal(fleetLoad, secondStore);
  }

  static StrategicJumpLazy inspectStrategicJumpLazy(StrategicJumpCommon common, boolean required) {
    MethodNode method = common.method();
    MethodInsnNode hostile = calls(method, Opcodes.INVOKESTATIC, "com/fs/starfarer/api/util/Misc",
        "getNumHostileMarkets", HOSTILE_MARKETS_DESC).get(0);
    if (method.instructions.indexOf(hostile) < method.instructions.indexOf(common.destinations())) {
      if (required) throw mismatch("hostile-market call remains before getDestinations");
      return null;
    }

    VarInsnNode destinationReceiver = requireVar(previousMeaningful(common.destinations()), Opcodes.ALOAD,
        "StrategicModule lazy destination receiver");
    VarInsnNode sentinelStore =
        requireVar(previousMeaningful(destinationReceiver), Opcodes.ISTORE, "StrategicModule lazy sentinel local");
    AbstractInsnNode sentinel = previousMeaningful(sentinelStore);
    if (destinationReceiver.var != common.jumpPointLocal() || sentinelStore.var != common.unsafeLocal()
        || !isIntegerLdc(sentinel, STRATEGIC_JUMP_UNCOMPUTED)) {
      if (required) throw mismatch("lazy hostile-market sentinel initialization changed");
      return null;
    }

    AbstractInsnNode radiusInsn = previousMeaningful(hostile);
    if (!(radiusInsn instanceof LdcInsnNode radius) || !isFloat(radius, 4000f)) {
      if (required) throw mismatch("lazy hostile-market radius changed");
      return null;
    }
    VarInsnNode jumpLoad =
        requireVar(previousMeaningful(radius), Opcodes.ALOAD, "StrategicModule lazy jump-point argument");
    MethodInsnNode faction = asMethod(previousMeaningful(jumpLoad), "StrategicModule lazy faction getter");
    VarInsnNode fleetLoad =
        requireVar(previousMeaningful(faction), Opcodes.ALOAD, "StrategicModule lazy fleet receiver");
    JumpInsnNode guard =
        requireJump(previousMeaningful(fleetLoad), Opcodes.IF_ICMPNE, "StrategicModule lazy score guard");
    AbstractInsnNode guardSentinel = previousMeaningful(guard);
    VarInsnNode guardLoad =
        requireVar(previousMeaningful(guardSentinel), Opcodes.ILOAD, "StrategicModule lazy score guard local");
    if (jumpLoad.var != common.jumpPointLocal() || fleetLoad.var != common.fleetLocal()
        || guardLoad.var != common.unsafeLocal() || !isIntegerLdc(guardSentinel, STRATEGIC_JUMP_UNCOMPUTED)
        || guardLoad != common.acceptedEntry() || nextMeaningful(guard.label) != common.candidateStart()
        || !callMatches(faction, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/CampaignFleetAPI",
            "getFaction", "()Lcom/fs/starfarer/api/campaign/FactionAPI;")) {
      if (required) throw mismatch("lazy hostile-market guard or arguments changed");
      return null;
    }

    AbstractInsnNode two = nextMeaningful(hostile);
    AbstractInsnNode multiply = nextMeaningful(two);
    VarInsnNode store =
        requireVar(nextMeaningful(multiply), Opcodes.ISTORE, "StrategicModule lazy hostile score result");
    if (!isIntegerLdc(two, 2) || multiply == null || multiply.getOpcode() != Opcodes.IMUL
        || store.var != common.unsafeLocal() || nextMeaningful(store) != common.candidateStart()) {
      if (required) throw mismatch("lazy hostile-market score sequence changed");
      return null;
    }
    return new StrategicJumpLazy(sentinelStore, guard, store);
  }

  private static void requireJumpDestinationGetter(MethodInsnNode call) {
    requireCall(call, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/campaign/JumpPointAPI$JumpDestination",
        "getDestination", "()Lcom/fs/starfarer/api/campaign/SectorEntityToken;",
        "StrategicModule JumpDestination.getDestination");
  }

  private static void requireContainingLocationGetter(MethodInsnNode call) {
    requireCall(call, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/SectorEntityToken",
        "getContainingLocation", "()Lcom/fs/starfarer/api/campaign/LocationAPI;",
        "StrategicModule destination containing location");
  }

}
