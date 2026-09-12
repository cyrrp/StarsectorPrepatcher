package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.economy.EconomyGroupIndex.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Markers.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.economy.MarketScheduler.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.bytecode.Contract;
import com.starsector.prepatcher.agent.bytecode.Queries;
import com.starsector.prepatcher.agent.bytecode.Flow;
import com.starsector.prepatcher.agent.bytecode.Mutations;
import com.starsector.prepatcher.agent.bytecode.Allocations;
import com.starsector.prepatcher.agent.bytecode.AllocationSpec;
import com.starsector.prepatcher.agent.bytecode.SnapshotAllocationPlan;
import com.starsector.prepatcher.agent.bytecode.ClassIO;
import com.starsector.prepatcher.agent.bytecode.Markers;
import com.starsector.prepatcher.agent.bytecode.Names;
import com.starsector.prepatcher.agent.bytecode.OwnedTemplates;
import com.starsector.prepatcher.agent.economy.MarketAdvance;
import com.starsector.prepatcher.agent.economy.MarketScheduler;
import com.starsector.prepatcher.agent.economy.EconomyGroupIndex;
import com.starsector.prepatcher.agent.economy.PersistentState;


public final class EntityScriptSnapshotReuse implements StructuredPatch<EntityScriptSnapshotReuse.Plan> {
  record Plan(String owner, MethodNode runScripts, SnapshotAllocationPlan snapshot, String scriptsField) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode runScripts = requireMethod(node, "runScripts", "(F)V");
    List<MethodInsnNode> empties = calls(runScripts, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z");
    AllocationSpec spec = new AllocationSpec("java/util/ArrayList", "(Ljava/util/Collection;)V",
        "borrowEntityScriptSnapshot", "(Ljava/util/Collection;)Ljava/util/List;", 1);
    List<SnapshotAllocationPlan> plans = snapshotAllocationPlans(node.name, runScripts, spec);
    requireCount("BaseCampaignEntity.runScripts defensive snapshot", plans.size(), 1);
    requireCount("BaseCampaignEntity.runScripts retired snapshot hook",
        countCalls(runScripts, Opcodes.INVOKESTATIC, HOOKS, spec.hookName(), spec.hookDesc()), 0);

    SnapshotAllocationPlan snapshot = plans.get(0);
    MethodInsnNode constructor = snapshot.constructor();
    Frame<SourceValue>[] frames = sourceFrames(node.name, runScripts);
    FieldInsnNode scriptsField = requireSourceField(argumentSource(runScripts, constructor, frames, 0),
        Opcodes.GETFIELD, node.name, LIST_DESC, "BaseCampaignEntity.runScripts scripts field");
    if (!scriptsField.name.equals("scripts")) {
      return Match.incompatible("BaseCampaignEntity.runScripts snapshot no longer reads scripts");
    }
    requireSnapshotUseContract(node.name, runScripts, snapshot.store(), 1,
        "BaseCampaignEntity.runScripts defensive snapshot");

    boolean guarded = empties.size() == 1 && hasEntityScriptEmptyGuard(node.name, runScripts, scriptsField.name);
    if (!empties.isEmpty() && !guarded) {
      return Match.incompatible("BaseCampaignEntity.runScripts has a foreign/partial empty guard");
    }
    requireEntityScriptEmptyPathShape(node.name, runScripts, snapshot, scriptsField.name, guarded);
    if (guarded) { return Match.alreadyApplied("BaseCampaignEntity.runScripts empty fast path is already present"); }
    return Match.plan(new Plan(node.name, runScripts, snapshot, scriptsField.name));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    // Most campaign entities have no scripts. The previous reusable-snapshot
    // implementation still opened and cleared a large generic scratch frame for
    // every one of those entities. Return before any scope/allocation instead;
    // the uncommon non-empty path is left byte-for-byte vanilla and therefore
    // keeps a fresh mutation-safe ArrayList snapshot.
    MethodNode runScripts = plan.runScripts();
    AbstractInsnNode oldFirst = runScripts.instructions.getFirst();
    LabelNode nonEmpty = new LabelNode();
    InsnList guard = new InsnList();
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.scriptsField(), LIST_DESC));
    guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
    guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
    guard.add(new InsnNode(Opcodes.RETURN));
    guard.add(nonEmpty);
    guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    runScripts.instructions.insertBefore(oldFirst, guard);

    PatchReport report = new PatchReport();
    report.add("empty entity-script fast path", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("BaseCampaignEntity.runScripts empty guard",
        countCalls(plan.runScripts(), Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z"), 1);
    if (!hasEntityScriptEmptyGuard(plan.owner(), plan.runScripts(), plan.scriptsField())) {
      throw mismatch("BaseCampaignEntity.runScripts empty guard postcondition failed");
    }
    requireEntityScriptEmptyPathShape(plan.owner(), plan.runScripts(), plan.snapshot(), plan.scriptsField(), true);
  }

  private static boolean hasEntityScriptEmptyGuard(String owner, MethodNode method, String fieldName) {
    AbstractInsnNode first = method.instructions.getFirst();
    while (first != null && first.getOpcode() < 0) first = first.getNext();
    if (!(first instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return false;
    AbstractInsnNode fieldInsn = nextMeaningful(load);
    if (!(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(owner) || !field.name.equals(fieldName) || !field.desc.equals(LIST_DESC))
      return false;
    AbstractInsnNode emptyInsn = nextMeaningful(field);
    if (!(emptyInsn instanceof MethodInsnNode empty)
        || !callMatches(empty, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z"))
      return false;
    AbstractInsnNode jumpInsn = nextMeaningful(empty);
    if (!(jumpInsn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) { return false; }
    AbstractInsnNode emptyReturn = nextMeaningful(jump);
    if (emptyReturn == null || emptyReturn.getOpcode() != Opcodes.RETURN) return false;
    return nextMeaningful(jump.label) == nextMeaningful(emptyReturn);
  }

  private static void requireEntityScriptEmptyPathShape(String owner, MethodNode method, SnapshotAllocationPlan plan,
      String scriptsFieldName, boolean guarded) {
    if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
      throw mismatch("BaseCampaignEntity.runScripts gained exception handlers");
    }
    requireCount("BaseCampaignEntity.runScripts returns", countOpcode(method, Opcodes.RETURN), guarded ? 2 : 1);

    AbstractInsnNode entry = firstMeaningful(method);
    AbstractInsnNode bodyStart = entry;
    if (guarded) {
      if (!(entry instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
        throw mismatch("BaseCampaignEntity.runScripts guard is not at method entry");
      }
      AbstractInsnNode fieldInsn = nextMeaningful(load);
      AbstractInsnNode emptyInsn = nextMeaningful(fieldInsn);
      AbstractInsnNode jumpInsn = nextMeaningful(emptyInsn);
      AbstractInsnNode returnInsn = nextMeaningful(jumpInsn);
      if (!(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals(owner) || !field.name.equals(scriptsFieldName) || !field.desc.equals(LIST_DESC)
          || !(emptyInsn instanceof MethodInsnNode empty)
          || !callMatches(empty, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z")
          || !(jumpInsn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ || returnInsn == null
          || returnInsn.getOpcode() != Opcodes.RETURN) {
        throw mismatch("BaseCampaignEntity.runScripts entry guard changed structurally");
      }
      bodyStart = nextMeaningful(returnInsn);
      if (nextMeaningful(jump.label) != bodyStart) {
        throw mismatch("BaseCampaignEntity.runScripts guard no longer rejoins vanilla entry");
      }
    }

    if (bodyStart != plan.allocation() || nextMeaningful(plan.allocation()) != plan.duplicate()) {
      throw mismatch("BaseCampaignEntity.runScripts has a foreign entry prologue");
    }
    AbstractInsnNode loadThis = nextMeaningful(plan.duplicate());
    AbstractInsnNode fieldInsn = nextMeaningful(loadThis);
    if (!(loadThis instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0
        || !(fieldInsn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(owner) || !field.name.equals(scriptsFieldName) || !field.desc.equals(LIST_DESC)
        || nextMeaningful(fieldInsn) != plan.constructor() || nextMeaningful(plan.constructor()) != plan.store()) {
      throw mismatch("BaseCampaignEntity.runScripts snapshot entry sequence changed");
    }
    AbstractInsnNode snapshotLoad = nextMeaningful(plan.store());
    AbstractInsnNode iteratorInsn = nextMeaningful(snapshotLoad);
    AbstractInsnNode iteratorStoreInsn = nextMeaningful(iteratorInsn);
    AbstractInsnNode initialJumpInsn = nextMeaningful(iteratorStoreInsn);
    if (!(snapshotLoad instanceof VarInsnNode snapshotVar) || snapshotVar.getOpcode() != Opcodes.ALOAD
        || snapshotVar.var != plan.store().var || !(iteratorInsn instanceof MethodInsnNode iterator)
        || !callMatches(iterator, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;")
        || !(iteratorStoreInsn instanceof VarInsnNode iteratorStore) || iteratorStore.getOpcode() != Opcodes.ASTORE
        || !(initialJumpInsn instanceof JumpInsnNode initialJump) || initialJump.getOpcode() != Opcodes.GOTO) {
      throw mismatch("BaseCampaignEntity.runScripts snapshot loop setup changed");
    }
    AbstractInsnNode body = nextMeaningful(initialJump);
    AbstractInsnNode testLoadInsn = nextMeaningful(initialJump.label);
    AbstractInsnNode hasNextInsn = nextMeaningful(testLoadInsn);
    AbstractInsnNode loopJumpInsn = nextMeaningful(hasNextInsn);
    AbstractInsnNode terminalReturn = nextMeaningful(loopJumpInsn);
    if (!(testLoadInsn instanceof VarInsnNode testLoad) || testLoad.getOpcode() != Opcodes.ALOAD
        || testLoad.var != iteratorStore.var || !(hasNextInsn instanceof MethodInsnNode hasNext)
        || !callMatches(hasNext, Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z")
        || !(loopJumpInsn instanceof JumpInsnNode loopJump) || loopJump.getOpcode() != Opcodes.IFNE
        || nextMeaningful(loopJump.label) != body || terminalReturn == null
        || terminalReturn.getOpcode() != Opcodes.RETURN || nextMeaningful(terminalReturn) != null) {
      throw mismatch("BaseCampaignEntity.runScripts empty-path control flow changed");
    }
  }

}

