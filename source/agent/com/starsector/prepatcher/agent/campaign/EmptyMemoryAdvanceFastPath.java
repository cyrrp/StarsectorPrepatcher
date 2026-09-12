package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

public final class EmptyMemoryAdvanceFastPath implements StructuredPatch<EmptyMemoryAdvanceFastPath.Plan> {
  record Plan(String owner, MethodNode advance, FieldNode expireField, FieldNode requireField,
      MethodInsnNode pauseEngine, MethodInsnNode paused, MethodInsnNode workStart, MethodInsnNode getClock,
      MethodInsnNode convert, VarInsnNode convertedStore, MethodInsnNode expireIterator, MethodInsnNode values,
      MethodInsnNode requireIterator) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    FieldNode expireField = requireField(node, "expire", "Ljava/util/List;");
    FieldNode requireField = requireField(node, "require", "Ljava/util/LinkedHashMap;");

    int legacyExpireHooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "memoryExpireIterator",
        "(Ljava/util/List;)Ljava/util/Iterator;");
    int legacyRequireHooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "memoryRequireIterator",
        "(Ljava/util/Collection;)Ljava/util/Iterator;");
    if (legacyExpireHooks != 0 || legacyRequireHooks != 0) {
      return Match.incompatible("Memory.advance contains the retired iterator-hook implementation");
    }

    List<MethodInsnNode> expireIterators =
        calls(advance, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;");
    List<MethodInsnNode> requireIterators =
        calls(advance, Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;");
    requireCount("Memory.advance expire iterator", expireIterators.size(), 1);
    requireCount("Memory.advance require iterator", requireIterators.size(), 1);

    Frame<SourceValue>[] frames = sourceFrames(node.name, advance);
    FieldInsnNode expireSource = requireSourceField(receiverSource(advance, expireIterators.get(0), frames),
        Opcodes.GETFIELD, node.name, "Ljava/util/List;", "Memory.advance expire iterator source");
    if (!expireSource.name.equals(expireField.name)) {
      return Match.incompatible("Memory.advance expire iterator no longer reads expire");
    }
    MethodInsnNode values =
        requireSourceMethod(receiverSource(advance, requireIterators.get(0), frames), Opcodes.INVOKEVIRTUAL,
            "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;", "Memory.advance require values view");
    FieldInsnNode requireSource = requireSourceField(receiverSource(advance, values, frames), Opcodes.GETFIELD,
        node.name, LINKED_MAP_DESC, "Memory.advance require values source");
    if (!requireSource.name.equals(requireField.name)) {
      return Match.incompatible("Memory.advance require iterator no longer reads require");
    }

    int expireEmptyCalls = countCalls(advance, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z");
    int requireEmptyCalls = countCalls(advance, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z");
    boolean guarded = expireEmptyCalls == 1 && requireEmptyCalls == 1
        && hasMemoryEmptyGuard(node.name, advance, expireField.name, requireField.name);
    if ((expireEmptyCalls != 0 || requireEmptyCalls != 0) && !guarded) {
      return Match.incompatible("Memory.advance has a foreign/partial empty guard");
    }

    List<MethodInsnNode> getInstances = calls(advance, Opcodes.INVOKESTATIC, "com/fs/starfarer/campaign/CampaignEngine",
        "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;");
    requireCount("Memory.advance CampaignEngine.getInstance calls", getInstances.size(), 2);
    MethodInsnNode paused =
        only(calls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignEngine", "isPaused", "()Z"),
            "Memory.advance pause check");
    MethodInsnNode pauseSource = requireSourceMethod(receiverSource(advance, paused, frames), Opcodes.INVOKESTATIC,
        "com/fs/starfarer/campaign/CampaignEngine", "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;",
        "Memory.advance pause receiver");
    if (pauseSource != getInstances.get(0)) {
      return Match.incompatible("Memory.advance pause check moved after timed work");
    }
    MethodInsnNode getClock = only(calls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignEngine",
        "getClock", "()Lcom/fs/starfarer/campaign/CampaignClock;"), "Memory.advance clock lookup");
    MethodInsnNode clockSource = requireSourceMethod(receiverSource(advance, getClock, frames), Opcodes.INVOKESTATIC,
        "com/fs/starfarer/campaign/CampaignEngine", "getInstance", "()Lcom/fs/starfarer/campaign/CampaignEngine;",
        "Memory.advance clock receiver");
    MethodInsnNode workStart = getInstances.get(1);
    if (clockSource != workStart) {
      return Match.incompatible("Memory.advance timed work no longer starts from the second engine read");
    }
    MethodInsnNode convert =
        only(calls(advance, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignClock", "convertToDays", "(F)F"),
            "Memory.advance clock conversion");
    if (requireSourceMethod(receiverSource(advance, convert, frames), Opcodes.INVOKEVIRTUAL,
        "com/fs/starfarer/campaign/CampaignEngine", "getClock", "()Lcom/fs/starfarer/campaign/CampaignClock;",
        "Memory.advance conversion receiver") != getClock
        || !sourceIsLocal(argumentSource(advance, convert, frames, 0), Opcodes.FLOAD, 1)) {
      return Match.incompatible("Memory.advance convertToDays input changed");
    }
    AbstractInsnNode convertedStore = nextMeaningful(convert);
    if (!(convertedStore instanceof VarInsnNode store) || store.getOpcode() != Opcodes.FSTORE) {
      return Match.incompatible("Memory.advance clock conversion is not stored");
    }

    requireMemoryEmptyPathShape(node.name, advance, expireField.name, requireField.name, guarded, getInstances.get(0),
        paused, workStart, getClock, convert, store, expireIterators.get(0), values, requireIterators.get(0));
    if (guarded) { return Match.alreadyApplied("Memory.advance empty fast return is already present"); }
    return Match.plan(new Plan(node.name, advance, expireField, requireField, getInstances.get(0), paused, workStart,
        getClock, convert, store, expireIterators.get(0), values, requireIterators.get(0)));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    // The old implementation replaced both iterator() calls with static
    // helpers. Telemetry showed that the helper/config/isEmpty overhead was
    // greater than the saved empty-iterator allocations. Skip the complete
    // expiration/require section instead. Restoration and pause handling stay
    // byte-for-byte vanilla; only an unpaused Memory with both work queues
    // empty returns before clock conversion and iterator creation.
    LabelNode nonEmpty = new LabelNode();
    InsnList guard = new InsnList();
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.expireField().name, plan.expireField().desc));
    guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
    guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.requireField().name, plan.requireField().desc));
    guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z", false));
    guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
    guard.add(new InsnNode(Opcodes.RETURN));
    guard.add(nonEmpty);
    guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    plan.advance().instructions.insertBefore(plan.workStart(), guard);

    PatchReport report = new PatchReport();
    report.add("inline empty Memory.advance fast return", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    if (!hasMemoryEmptyGuard(plan.owner(), plan.advance(), plan.expireField().name, plan.requireField().name)) {
      throw mismatch("Memory.advance empty fast return postcondition failed");
    }
    requireMemoryEmptyPathShape(plan.owner(), plan.advance(), plan.expireField().name, plan.requireField().name, true,
        plan.pauseEngine(), plan.paused(), plan.workStart(), plan.getClock(), plan.convert(), plan.convertedStore(),
        plan.expireIterator(), plan.values(), plan.requireIterator());
  }

  private static boolean hasMemoryEmptyGuard(String owner, MethodNode method, String expireFieldName,
      String requireFieldName) {
    for (MethodInsnNode expireEmpty : calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z")) {
      AbstractInsnNode receiver = previousMeaningful(expireEmpty);
      if (!(receiver instanceof FieldInsnNode expireField) || expireField.getOpcode() != Opcodes.GETFIELD
          || !expireField.owner.equals(owner) || !expireField.name.equals(expireFieldName)
          || !expireField.desc.equals("Ljava/util/List;"))
        continue;
      AbstractInsnNode loadThis = previousMeaningful(expireField);
      if (!(loadThis instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) continue;
      AbstractInsnNode firstJumpInsn = nextMeaningful(expireEmpty);
      if (!(firstJumpInsn instanceof JumpInsnNode firstJump) || firstJump.getOpcode() != Opcodes.IFEQ) continue;

      AbstractInsnNode requireLoad = nextMeaningful(firstJump);
      if (!(requireLoad instanceof VarInsnNode requireThis) || requireThis.getOpcode() != Opcodes.ALOAD
          || requireThis.var != 0)
        continue;
      AbstractInsnNode requireFieldInsn = nextMeaningful(requireLoad);
      if (!(requireFieldInsn instanceof FieldInsnNode requireField) || requireField.getOpcode() != Opcodes.GETFIELD
          || !requireField.owner.equals(owner) || !requireField.name.equals(requireFieldName)
          || !requireField.desc.equals("Ljava/util/LinkedHashMap;"))
        continue;
      AbstractInsnNode requireEmptyInsn = nextMeaningful(requireField);
      if (!(requireEmptyInsn instanceof MethodInsnNode requireEmpty)
          || !callMatches(requireEmpty, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z"))
        continue;
      AbstractInsnNode secondJumpInsn = nextMeaningful(requireEmpty);
      if (!(secondJumpInsn instanceof JumpInsnNode secondJump) || secondJump.getOpcode() != Opcodes.IFEQ
          || secondJump.label != firstJump.label)
        continue;
      AbstractInsnNode emptyReturn = nextMeaningful(secondJump);
      if (emptyReturn == null || emptyReturn.getOpcode() != Opcodes.RETURN) continue;
      if (nextMeaningful(firstJump.label) != nextMeaningful(emptyReturn)) continue;
      return true;
    }
    return false;
  }

  private static void requireMemoryEmptyPathShape(String owner, MethodNode method, String expireFieldName,
      String requireFieldName, boolean guarded, MethodInsnNode pauseEngine, MethodInsnNode paused,
      MethodInsnNode workStart, MethodInsnNode getClock, MethodInsnNode convert, VarInsnNode convertedStore,
      MethodInsnNode expireIterator, MethodInsnNode values, MethodInsnNode requireIterator) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("Memory.advance gained exception handlers");
    }
    requireCount("Memory.advance returns", countOpcode(method, Opcodes.RETURN), guarded ? 3 : 2);

    AbstractInsnNode pauseJumpInsn = nextMeaningful(paused);
    AbstractInsnNode pauseReturn = nextMeaningful(pauseJumpInsn);
    if (nextMeaningful(pauseEngine) != paused || !(pauseJumpInsn instanceof JumpInsnNode pauseJump)
        || pauseJump.getOpcode() != Opcodes.IFEQ || pauseReturn == null || pauseReturn.getOpcode() != Opcodes.RETURN) {
      throw mismatch("Memory.advance pause gate changed structurally");
    }
    AbstractInsnNode workEntry = nextMeaningful(pauseReturn);
    if (nextMeaningful(pauseJump.label) != workEntry) {
      throw mismatch("Memory.advance pause branch no longer reaches timed work entry");
    }
    if (guarded) {
      AbstractInsnNode expireLoadInsn = workEntry;
      AbstractInsnNode expireFieldInsn = nextMeaningful(expireLoadInsn);
      AbstractInsnNode expireEmptyInsn = nextMeaningful(expireFieldInsn);
      AbstractInsnNode firstJumpInsn = nextMeaningful(expireEmptyInsn);
      AbstractInsnNode requireLoadInsn = nextMeaningful(firstJumpInsn);
      AbstractInsnNode requireFieldInsn = nextMeaningful(requireLoadInsn);
      AbstractInsnNode requireEmptyInsn = nextMeaningful(requireFieldInsn);
      AbstractInsnNode secondJumpInsn = nextMeaningful(requireEmptyInsn);
      AbstractInsnNode emptyReturn = nextMeaningful(secondJumpInsn);
      if (!(expireLoadInsn instanceof VarInsnNode expireLoad) || expireLoad.getOpcode() != Opcodes.ALOAD
          || expireLoad.var != 0 || !(expireFieldInsn instanceof FieldInsnNode expireField)
          || expireField.getOpcode() != Opcodes.GETFIELD || !expireField.owner.equals(owner)
          || !expireField.name.equals(expireFieldName) || !expireField.desc.equals(LIST_DESC)
          || !(expireEmptyInsn instanceof MethodInsnNode expireEmpty)
          || !callMatches(expireEmpty, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z")
          || !(firstJumpInsn instanceof JumpInsnNode firstJump) || firstJump.getOpcode() != Opcodes.IFEQ
          || !(requireLoadInsn instanceof VarInsnNode requireLoad) || requireLoad.getOpcode() != Opcodes.ALOAD
          || requireLoad.var != 0 || !(requireFieldInsn instanceof FieldInsnNode requireField)
          || requireField.getOpcode() != Opcodes.GETFIELD || !requireField.owner.equals(owner)
          || !requireField.name.equals(requireFieldName) || !requireField.desc.equals(LINKED_MAP_DESC)
          || !(requireEmptyInsn instanceof MethodInsnNode requireEmpty)
          || !callMatches(requireEmpty, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z")
          || !(secondJumpInsn instanceof JumpInsnNode secondJump) || secondJump.getOpcode() != Opcodes.IFEQ
          || firstJump.label != secondJump.label || emptyReturn == null || emptyReturn.getOpcode() != Opcodes.RETURN
          || nextMeaningful(firstJump.label) != workStart || nextMeaningful(emptyReturn) != workStart) {
        throw mismatch("Memory.advance empty guard is not the exact post-pause prefix");
      }
    } else if (workEntry != workStart) { throw mismatch("Memory.advance has foreign work before clock conversion"); }

    AbstractInsnNode deltaLoad = nextMeaningful(getClock);
    if (nextMeaningful(workStart) != getClock || !(deltaLoad instanceof VarInsnNode load)
        || load.getOpcode() != Opcodes.FLOAD || load.var != 1 || nextMeaningful(deltaLoad) != convert
        || nextMeaningful(convert) != convertedStore) {
      throw mismatch("Memory.advance clock conversion order changed");
    }

    AbstractInsnNode expireLoadInsn = nextMeaningful(convertedStore);
    AbstractInsnNode expireFieldInsn = nextMeaningful(expireLoadInsn);
    AbstractInsnNode expireStoreInsn = nextMeaningful(expireIterator);
    AbstractInsnNode expireGotoInsn = nextMeaningful(expireStoreInsn);
    if (!(expireLoadInsn instanceof VarInsnNode expireLoad) || expireLoad.getOpcode() != Opcodes.ALOAD
        || expireLoad.var != 0 || !(expireFieldInsn instanceof FieldInsnNode expireField)
        || expireField.getOpcode() != Opcodes.GETFIELD || !expireField.owner.equals(owner)
        || !expireField.name.equals(expireFieldName) || !expireField.desc.equals(LIST_DESC)
        || nextMeaningful(expireFieldInsn) != expireIterator || !(expireStoreInsn instanceof VarInsnNode expireStore)
        || expireStore.getOpcode() != Opcodes.ASTORE || !(expireGotoInsn instanceof JumpInsnNode expireGoto)
        || expireGoto.getOpcode() != Opcodes.GOTO) {
      throw mismatch("Memory.advance expire loop setup changed");
    }
    AbstractInsnNode expireBody = nextMeaningful(expireGoto);
    AbstractInsnNode expireTestLoadInsn = nextMeaningful(expireGoto.label);
    AbstractInsnNode expireHasNextInsn = nextMeaningful(expireTestLoadInsn);
    AbstractInsnNode expireLoopJumpInsn = nextMeaningful(expireHasNextInsn);
    if (!(expireTestLoadInsn instanceof VarInsnNode expireTestLoad) || expireTestLoad.getOpcode() != Opcodes.ALOAD
        || expireTestLoad.var != expireStore.var || !(expireHasNextInsn instanceof MethodInsnNode expireHasNext)
        || !callMatches(expireHasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
        || !(expireLoopJumpInsn instanceof JumpInsnNode expireLoopJump) || expireLoopJump.getOpcode() != Opcodes.IFNE
        || nextMeaningful(expireLoopJump.label) != expireBody) {
      throw mismatch("Memory.advance expire loop control flow changed");
    }

    AbstractInsnNode requireLoadInsn = nextMeaningful(expireLoopJump);
    AbstractInsnNode requireFieldInsn = nextMeaningful(requireLoadInsn);
    AbstractInsnNode requireStoreInsn = nextMeaningful(requireIterator);
    AbstractInsnNode requireGotoInsn = nextMeaningful(requireStoreInsn);
    if (!(requireLoadInsn instanceof VarInsnNode requireLoad) || requireLoad.getOpcode() != Opcodes.ALOAD
        || requireLoad.var != 0 || !(requireFieldInsn instanceof FieldInsnNode requireField)
        || requireField.getOpcode() != Opcodes.GETFIELD || !requireField.owner.equals(owner)
        || !requireField.name.equals(requireFieldName) || !requireField.desc.equals(LINKED_MAP_DESC)
        || nextMeaningful(requireFieldInsn) != values || nextMeaningful(values) != requireIterator
        || !(requireStoreInsn instanceof VarInsnNode requireStore) || requireStore.getOpcode() != Opcodes.ASTORE
        || !(requireGotoInsn instanceof JumpInsnNode requireGoto) || requireGoto.getOpcode() != Opcodes.GOTO) {
      throw mismatch("Memory.advance require loop setup changed");
    }
    AbstractInsnNode requireBody = nextMeaningful(requireGoto);
    AbstractInsnNode requireTestLoadInsn = nextMeaningful(requireGoto.label);
    AbstractInsnNode requireHasNextInsn = nextMeaningful(requireTestLoadInsn);
    AbstractInsnNode requireLoopJumpInsn = nextMeaningful(requireHasNextInsn);
    AbstractInsnNode terminalReturn = nextMeaningful(requireLoopJumpInsn);
    if (!(requireTestLoadInsn instanceof VarInsnNode requireTestLoad) || requireTestLoad.getOpcode() != Opcodes.ALOAD
        || requireTestLoad.var != requireStore.var || !(requireHasNextInsn instanceof MethodInsnNode requireHasNext)
        || !callMatches(requireHasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
        || !(requireLoopJumpInsn instanceof JumpInsnNode requireLoopJump) || requireLoopJump.getOpcode() != Opcodes.IFNE
        || nextMeaningful(requireLoopJump.label) != requireBody || terminalReturn == null
        || terminalReturn.getOpcode() != Opcodes.RETURN || nextMeaningful(terminalReturn) != null) {
      throw mismatch("Memory.advance empty-path terminal control flow changed");
    }
  }
}
