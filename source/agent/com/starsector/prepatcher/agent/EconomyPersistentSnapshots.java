package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.PersistentState.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces hot per-frame defensive copies with owner-local,
 * copy-on-write snapshots. A transformed mutator increments the structure
 * epoch immediately; direct live-list edits are detected by a periodic
 * identity/order audit. Old snapshots are never cleared, so a nested advance
 * cannot invalidate an outer iterator.
 */
final class EconomyPersistentSnapshots implements StructuredPatch<EconomyPersistentSnapshots.Plan> {
  private final int auditMs;

  EconomyPersistentSnapshots(int auditMs) {
    this.auditMs = auditMs;
  }

  record Plan(ClassNode node, MethodNode advance, MethodNode paused, MethodNode constructor, MethodNode readResolve,
      MethodNode addMarket, AbstractInsnNode addAnchor, MethodNode removeMarket, AbstractInsnNode removeAnchor,
      MethodNode setEcon, FieldInsnNode econWrite) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = requireMethod(node, "advance", "(F)V");
    MethodNode paused = requireMethod(node, "advanceMarketConditionsWhenPaused", "(F)V");
    MethodNode constructor = requireMethod(node, "<init>", "(Z)V");
    MethodNode readResolve = requireMethod(node, "readResolve", "()Ljava/lang/Object;");
    MethodNode addMarket = requireMethod(node, "addMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)V");
    MethodNode removeMarket = requireMethod(node, "removeMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    MethodNode setEcon = requireMethod(node, "setEcon", "(Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;)V");

    boolean fieldPresent =
        hasExactSyntheticField(node, ECONOMY_PERSISTENT_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess());
    boolean accessorPresent = hasExactPersistentAccessor(node, ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;", true,
        ECONOMY_PERSISTENT_STATE_FIELD, auditMs, 0);
    boolean any = fieldPresent || accessorPresent
        || countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;") > 0
        || countCalls(paused, Opcodes.INVOKEVIRTUAL, node.name, ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;") > 0
        || countPersistentStateInitializers(constructor, node.name, ECONOMY_PERSISTENT_STATE_FIELD) > 0
        || countPersistentStateInitializers(readResolve, node.name, ECONOMY_PERSISTENT_STATE_FIELD) > 0
        || countPersistentMarks(addMarket, node.name, ECONOMY_PERSISTENT_STATE_FIELD) > 0
        || countPersistentMarks(removeMarket, node.name, ECONOMY_PERSISTENT_STATE_FIELD) > 0
        || countPersistentMarks(setEcon, node.name, ECONOMY_PERSISTENT_STATE_FIELD) > 0;
    if (any) {
      if (!fieldPresent || !accessorPresent) {
        return Match.incompatible("Economy persistent snapshot fields/accessor are partial or foreign");
      }
      requireEconomyPersistentPostcondition(node, advance, paused, constructor, readResolve, addMarket, removeMarket,
          setEcon);
      return Match.alreadyApplied("Economy persistent snapshot/epoch postcondition matches");
    }

    requireOriginalEconomyMarketSnapshot(advance, "Economy.advance market snapshot");
    requireOriginalEconomyMarketSnapshot(paused, "Economy paused market snapshot");
    AbstractInsnNode addAnchor =
        persistentMarkAnchor(addMarket, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/econ/reach/ReachEconomy",
            "addMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V", "Economy.addMarket");
    AbstractInsnNode removeAnchor =
        persistentMarkAnchor(removeMarket, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/econ/reach/ReachEconomy",
            "removeMarket", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V", "Economy.removeMarket");
    FieldInsnNode econWrite = onlyField(setEcon, Opcodes.PUTFIELD, node.name, "econ",
        "Lcom/fs/starfarer/campaign/econ/reach/ReachEconomy;", "Economy.setEcon field write");
    return Match.plan(new Plan(node, advance, paused, constructor, readResolve, addMarket, addAnchor, removeMarket,
        removeAnchor, setEcon, econWrite));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    node.fields.add(new FieldNode(Opcodes.ASM8, persistentStateAccess(), ECONOMY_PERSISTENT_STATE_FIELD,
        PERSISTENT_STATE_DESC, null, null));
    node.methods.add(newEconomyPersistentAccessor(node.name));
    initializePersistentStateAfterSuper(node, plan.constructor(), ECONOMY_PERSISTENT_STATE_FIELD,
        "Economy constructor");
    initializePersistentStateAtEntry(plan.readResolve(), node.name, ECONOMY_PERSISTENT_STATE_FIELD,
        "Economy.readResolve");
    replaceEconomyMarketSnapshotWithPersistentAccessor(plan.advance(), node.name, "Economy.advance market snapshot");
    replaceEconomyMarketSnapshotWithPersistentAccessor(plan.paused(), node.name, "Economy paused market snapshot");
    insertPersistentMarkAfter(plan.addMarket(), plan.addAnchor(), node.name, ECONOMY_PERSISTENT_STATE_FIELD);
    insertPersistentMarkAfter(plan.removeMarket(), plan.removeAnchor(), node.name, ECONOMY_PERSISTENT_STATE_FIELD);
    insertPersistentMarkAfter(plan.setEcon(), plan.econWrite(), node.name, ECONOMY_PERSISTENT_STATE_FIELD);
    PatchReport report = new PatchReport();
    report.add("persistent Economy market snapshots and structure epoch", 8);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    if (!hasExactSyntheticField(node, ECONOMY_PERSISTENT_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess())
        || !hasExactPersistentAccessor(node, ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;", true,
            ECONOMY_PERSISTENT_STATE_FIELD, auditMs, 0)) {
      throw mismatch("Economy persistent snapshot fields/accessor are partial or foreign");
    }
    requireEconomyPersistentPostcondition(node, plan.advance(), plan.paused(), plan.constructor(), plan.readResolve(),
        plan.addMarket(), plan.removeMarket(), plan.setEcon());
  }

  private void requireEconomyPersistentPostcondition(ClassNode node, MethodNode advance, MethodNode paused,
      MethodNode constructor, MethodNode readResolve, MethodNode addMarket, MethodNode removeMarket,
      MethodNode setEcon) {
    requireCount("Economy.advance persistent market snapshot",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;"), 1);
    requireCount("Economy paused persistent market snapshot",
        countCalls(paused, Opcodes.INVOKEVIRTUAL, node.name, ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;"), 1);
    requireCount("Economy.advance retired getMarketsCopy",
        countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, "getMarketsCopy", "()Ljava/util/List;"), 0);
    requireCount("Economy paused retired getMarketsCopy",
        countCalls(paused, Opcodes.INVOKEVIRTUAL, node.name, "getMarketsCopy", "()Ljava/util/List;"), 0);
    requireCount("Economy constructor persistent-state init",
        countPersistentStateInitializers(constructor, node.name, ECONOMY_PERSISTENT_STATE_FIELD), 1);
    requireCount("Economy readResolve persistent-state init",
        countPersistentStateInitializers(readResolve, node.name, ECONOMY_PERSISTENT_STATE_FIELD), 1);
    requireCount("Economy addMarket epoch mark",
        countPersistentMarks(addMarket, node.name, ECONOMY_PERSISTENT_STATE_FIELD), 1);
    requireCount("Economy removeMarket epoch mark",
        countPersistentMarks(removeMarket, node.name, ECONOMY_PERSISTENT_STATE_FIELD), 1);
    requireCount("Economy setEcon epoch mark", countPersistentMarks(setEcon, node.name, ECONOMY_PERSISTENT_STATE_FIELD),
        1);
  }

  private static AbstractInsnNode persistentMarkAnchor(MethodNode method, int opcode, String callOwner, String callName,
      String callDesc, String label) {
    MethodInsnNode call = only(calls(method, opcode, callOwner, callName, callDesc), label);
    AbstractInsnNode anchor = call;
    AbstractInsnNode next = nextMeaningful(call);
    if (next != null && next.getOpcode() == Opcodes.POP) anchor = next;
    return anchor;
  }

  private MethodNode newEconomyPersistentAccessor(String owner) {
    MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
        ECONOMY_PERSISTENT_ACCESSOR, "()Ljava/util/List;", null, null);
    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    method.instructions
        .add(new FieldInsnNode(Opcodes.GETFIELD, owner, ECONOMY_PERSISTENT_STATE_FIELD, PERSISTENT_STATE_DESC));
    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    method.instructions
        .add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getMarkets", "()Ljava/util/List;", false));
    method.instructions.add(pushInt(auditMs));
    method.instructions.add(pushInt(0));
    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "borrowPersistentSnapshotTimed",
        "(Ljava/lang/Object;Ljava/util/Collection;II)Ljava/util/ArrayList;", false));
    method.instructions.add(new InsnNode(Opcodes.ARETURN));
    return method;
  }

  private static void requireOriginalEconomyMarketSnapshot(MethodNode method, String label) {
    List<MethodInsnNode> calls = calls(method, Opcodes.INVOKEVIRTUAL, ECONOMY, "getMarketsCopy", "()Ljava/util/List;");
    requireCount(label, calls.size(), 1);
    MethodInsnNode call = calls.get(0);
    Frame<SourceValue>[] frames = sourceFrames(ECONOMY, method);
    if (!sourceIsLocal(receiverSource(method, call, frames), Opcodes.ALOAD, 0)) {
      throw mismatch(label + " receiver is not this");
    }
    MethodInsnNode iterator = asMethod(nextMeaningful(call), label + " iterator");
    requireCall(iterator, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;",
        label + " iterator");
  }

  private static void replaceEconomyMarketSnapshotWithPersistentAccessor(MethodNode method, String owner,
      String label) {
    MethodInsnNode call =
        only(calls(method, Opcodes.INVOKEVIRTUAL, owner, "getMarketsCopy", "()Ljava/util/List;"), label);
    call.owner = owner;
    call.name = ECONOMY_PERSISTENT_ACCESSOR;
    call.desc = "()Ljava/util/List;";
    call.itf = false;
  }
}
