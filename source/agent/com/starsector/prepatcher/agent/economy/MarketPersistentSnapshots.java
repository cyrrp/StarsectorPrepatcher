package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.starsector.prepatcher.agent.bytecode.Allocations.*;
import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.economy.MarketAdvance.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.bytecode.AllocationPair;

final class MarketPersistentSnapshots implements StructuredPatch<MarketPersistentSnapshots.Plan> {
  private static final String MARKET_CONDITIONS_STATE_FIELD = "smo$marketConditionsSnapshotState";
  private static final String MARKET_INDUSTRIES_STATE_FIELD = "smo$marketIndustriesSnapshotState";
  private static final String MARKET_CONDITIONS_ACCESSOR = "smo$borrowPersistentConditionsSnapshot";
  private static final String MARKET_INDUSTRIES_ACCESSOR = "smo$borrowPersistentIndustriesSnapshot";

  private final int auditFrames;

  MarketPersistentSnapshots(int auditFrames) {
    this.auditFrames = auditFrames;
  }

  private record PersistentSnapshotPlan(MethodInsnNode constructor, AllocationPair allocation,
      AbstractInsnNode sourceReceiver, String accessor) {
  }

  private record MutatorMark(MethodNode method, AbstractInsnNode anchor, String stateField) {
  }

  record Plan(ClassNode node, MethodNode advance, MethodNode constructor, MethodNode readResolve,
      MethodNode cloneMethod, VarInsnNode cloneStore, List<PersistentSnapshotPlan> snapshots,
      List<MutatorMark> mutatorMarks) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode advance = marketAdvanceSemanticBody(node);
    MethodNode constructor = requireMethod(node, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;ILcom/fs/starfarer/campaign/econ/Economy;)V");
    MethodNode readResolve = requireMethod(node, "readResolve", "()Ljava/lang/Object;");
    MethodNode clone = requireMethod(node, "clone", "()Lcom/fs/starfarer/campaign/econ/Market;");

    boolean conditionsField =
        hasExactSyntheticField(node, MARKET_CONDITIONS_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess());
    boolean industriesField =
        hasExactSyntheticField(node, MARKET_INDUSTRIES_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess());
    boolean conditionsAccessor = hasExactPersistentAccessor(node, MARKET_CONDITIONS_ACCESSOR,
        "(Ljava/util/Collection;)Ljava/util/ArrayList;", false, MARKET_CONDITIONS_STATE_FIELD, auditFrames, 1);
    boolean industriesAccessor = hasExactPersistentAccessor(node, MARKET_INDUSTRIES_ACCESSOR,
        "(Ljava/util/Collection;)Ljava/util/ArrayList;", false, MARKET_INDUSTRIES_STATE_FIELD, auditFrames, 2);
    boolean any = conditionsField || industriesField || conditionsAccessor || industriesAccessor
        || countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, MARKET_CONDITIONS_ACCESSOR,
            "(Ljava/util/Collection;)Ljava/util/ArrayList;") > 0
        || countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name, MARKET_INDUSTRIES_ACCESSOR,
            "(Ljava/util/Collection;)Ljava/util/ArrayList;") > 0;
    if (any) {
      if (!conditionsField || !industriesField || !conditionsAccessor || !industriesAccessor) {
        return Match.incompatible("Market persistent snapshot fields/accessors are partial or foreign");
      }
      requireCount("Market persistent conditions snapshot call", countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
          MARKET_CONDITIONS_ACCESSOR, "(Ljava/util/Collection;)Ljava/util/ArrayList;"), 1);
      requireCount("Market persistent industries snapshot call", countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
          MARKET_INDUSTRIES_ACCESSOR, "(Ljava/util/Collection;)Ljava/util/ArrayList;"), 1);
      requireCount("Market retired ArrayList(Collection) snapshots", countImmediateArrayListIteratorSnapshots(advance),
          0);
      requireCount("Market constructor persistent-state initializers",
          countPersistentStateInitializers(constructor, node.name, MARKET_CONDITIONS_STATE_FIELD)
              + countPersistentStateInitializers(constructor, node.name, MARKET_INDUSTRIES_STATE_FIELD),
          2);
      requireCount("Market readResolve persistent-state initializers",
          countPersistentStateInitializers(readResolve, node.name, MARKET_CONDITIONS_STATE_FIELD)
              + countPersistentStateInitializers(readResolve, node.name, MARKET_INDUSTRIES_STATE_FIELD),
          2);
      requireCount("Market clone persistent-state initializers",
          countPersistentStateInitializers(clone, node.name, MARKET_CONDITIONS_STATE_FIELD)
              + countPersistentStateInitializers(clone, node.name, MARKET_INDUSTRIES_STATE_FIELD),
          2);
      requireMarketPersistentMutationMarks(node);
      return Match.alreadyApplied("Market persistent snapshot/epoch postcondition matches");
    }

    requireCount("Market original condition/industry snapshots", countImmediateArrayListIteratorSnapshots(advance), 2);
    requireCount("Market constructor conditions super constructor",
        calls(constructor, Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V").size(), 1);
    if (firstMeaningful(readResolve) == null) {
      return Match.incompatible("Market.readResolve industries has no executable body");
    }

    VarInsnNode cloneStore = null;
    for (AbstractInsnNode insn : clone.instructions.toArray()) {
      if (!(insn instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ASTORE || var.var != 1) continue;
      AbstractInsnNode previous = previousMeaningful(var);
      if (previous instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
          && cast.desc.equals(node.name)) {
        cloneStore = var;
        break;
      }
    }
    if (cloneStore == null) return Match.incompatible("Market.clone result local changed");

    List<PersistentSnapshotPlan> snapshots = planSnapshotRewrites(node.name, advance);
    List<MutatorMark> mutatorMarks = List.of(
        mutatorMark(node, "addCondition", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;", "java/util/List",
            "add", "(Ljava/lang/Object;)Z", MARKET_CONDITIONS_STATE_FIELD, "Market.addCondition(String,Object)"),
        mutatorMark(node, "addCondition", "(Lcom/fs/starfarer/api/campaign/econ/MarketConditionAPI;)V",
            "java/util/List", "add", "(Ljava/lang/Object;)Z", MARKET_CONDITIONS_STATE_FIELD,
            "Market.addCondition(MarketConditionAPI)"),
        mutatorMark(node, "removeCondition", "(Ljava/lang/String;)V", "java/util/Iterator", "remove", "()V",
            MARKET_CONDITIONS_STATE_FIELD, "Market.removeCondition"),
        mutatorMark(node, "removeSpecificCondition", "(Ljava/lang/String;)V", "java/util/Iterator", "remove", "()V",
            MARKET_CONDITIONS_STATE_FIELD, "Market.removeSpecificCondition"),
        mutatorMark(node, "addIndustry", "(Ljava/lang/String;Ljava/util/List;)V", "java/util/List", "add",
            "(Ljava/lang/Object;)Z", MARKET_INDUSTRIES_STATE_FIELD, "Market.addIndustry"),
        mutatorMark(node, "removeIndustry",
            "(Ljava/lang/String;Lcom/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode;Z)V",
            "java/util/Iterator", "remove", "()V", MARKET_INDUSTRIES_STATE_FIELD, "Market.removeIndustry"));
    return Match.plan(new Plan(node, advance, constructor, readResolve, clone, cloneStore, snapshots, mutatorMarks));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    ClassNode node = plan.node();
    node.fields.add(new FieldNode(Opcodes.ASM8, persistentStateAccess(), MARKET_CONDITIONS_STATE_FIELD,
        PERSISTENT_STATE_DESC, null, null));
    node.fields.add(new FieldNode(Opcodes.ASM8, persistentStateAccess(), MARKET_INDUSTRIES_STATE_FIELD,
        PERSISTENT_STATE_DESC, null, null));
    node.methods.add(newMarketPersistentAccessor(node.name, MARKET_CONDITIONS_ACCESSOR, MARKET_CONDITIONS_STATE_FIELD,
        auditFrames, 1));
    node.methods.add(newMarketPersistentAccessor(node.name, MARKET_INDUSTRIES_ACCESSOR, MARKET_INDUSTRIES_STATE_FIELD,
        auditFrames, 2));
    initializePersistentStateAfterSuper(node, plan.constructor(), MARKET_CONDITIONS_STATE_FIELD,
        "Market constructor conditions");
    initializePersistentStateAfterAnchor(plan.constructor(),
        lastPersistentStateInitializer(plan.constructor(), node.name, MARKET_CONDITIONS_STATE_FIELD), node.name,
        MARKET_INDUSTRIES_STATE_FIELD);
    initializePersistentStateAtEntry(plan.readResolve(), node.name, MARKET_INDUSTRIES_STATE_FIELD,
        "Market.readResolve industries");
    initializePersistentStateAtEntry(plan.readResolve(), node.name, MARKET_CONDITIONS_STATE_FIELD,
        "Market.readResolve conditions");

    InsnList cloneInit = new InsnList();
    cloneInit.add(persistentStateInitializer(node.name, MARKET_CONDITIONS_STATE_FIELD, 1));
    cloneInit.add(persistentStateInitializer(node.name, MARKET_INDUSTRIES_STATE_FIELD, 1));
    plan.cloneMethod().instructions.insert(plan.cloneStore(), cloneInit);

    applySnapshotRewrites(plan, node.name);
    for (MutatorMark mark : plan.mutatorMarks()) {
      insertPersistentMarkAfter(mark.method(), mark.anchor(), node.name, mark.stateField());
    }

    PatchReport report = new PatchReport();
    report.add("persistent Market condition/industry snapshots and epochs", 14);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    if (!hasExactSyntheticField(node, MARKET_CONDITIONS_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess())
        || !hasExactSyntheticField(node, MARKET_INDUSTRIES_STATE_FIELD, PERSISTENT_STATE_DESC, persistentStateAccess())
        || !hasExactPersistentAccessor(node, MARKET_CONDITIONS_ACCESSOR,
            "(Ljava/util/Collection;)Ljava/util/ArrayList;", false, MARKET_CONDITIONS_STATE_FIELD, auditFrames, 1)
        || !hasExactPersistentAccessor(node, MARKET_INDUSTRIES_ACCESSOR,
            "(Ljava/util/Collection;)Ljava/util/ArrayList;", false, MARKET_INDUSTRIES_STATE_FIELD, auditFrames, 2)) {
      throw mismatch("Market persistent snapshot fields/accessors are partial or foreign");
    }
    MethodNode advance = plan.advance();
    requireCount("Market persistent conditions snapshot call", countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
        MARKET_CONDITIONS_ACCESSOR, "(Ljava/util/Collection;)Ljava/util/ArrayList;"), 1);
    requireCount("Market persistent industries snapshot call", countCalls(advance, Opcodes.INVOKEVIRTUAL, node.name,
        MARKET_INDUSTRIES_ACCESSOR, "(Ljava/util/Collection;)Ljava/util/ArrayList;"), 1);
    requireCount("Market retired ArrayList(Collection) snapshots", countImmediateArrayListIteratorSnapshots(advance),
        0);
    requireCount("Market constructor persistent-state initializers",
        countPersistentStateInitializers(plan.constructor(), node.name, MARKET_CONDITIONS_STATE_FIELD)
            + countPersistentStateInitializers(plan.constructor(), node.name, MARKET_INDUSTRIES_STATE_FIELD),
        2);
    requireCount("Market readResolve persistent-state initializers",
        countPersistentStateInitializers(plan.readResolve(), node.name, MARKET_CONDITIONS_STATE_FIELD)
            + countPersistentStateInitializers(plan.readResolve(), node.name, MARKET_INDUSTRIES_STATE_FIELD),
        2);
    requireCount("Market clone persistent-state initializers",
        countPersistentStateInitializers(plan.cloneMethod(), node.name, MARKET_CONDITIONS_STATE_FIELD)
            + countPersistentStateInitializers(plan.cloneMethod(), node.name, MARKET_INDUSTRIES_STATE_FIELD),
        2);
    requireMarketPersistentMutationMarks(node);
  }

  private static MutatorMark mutatorMark(ClassNode node, String methodName, String methodDesc, String callOwner,
      String callName, String callDesc, String stateField, String label) {
    MethodNode method = requireMethod(node, methodName, methodDesc);
    MethodInsnNode call = only(calls(method, Opcodes.INVOKEINTERFACE, callOwner, callName, callDesc), label);
    AbstractInsnNode anchor = call;
    AbstractInsnNode next = nextMeaningful(call);
    if (next != null && next.getOpcode() == Opcodes.POP) anchor = next;
    return new MutatorMark(method, anchor, stateField);
  }

  private static List<PersistentSnapshotPlan> planSnapshotRewrites(String owner, MethodNode method) {
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    List<PersistentSnapshotPlan> plans = new ArrayList<>();
    for (MethodInsnNode constructor : calls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
        "(Ljava/util/Collection;)V")) {
      AbstractInsnNode next = nextMeaningful(constructor);
      if (!(next instanceof MethodInsnNode iterator)
          || !callMatches(iterator, Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;"))
        continue;
      SourceValue source = argumentSource(method, constructor, frames, 0);
      String accessor;
      AbstractInsnNode producer;
      if (sourceIsMethod(source, Opcodes.INVOKEVIRTUAL, owner, "getConditions", "()Ljava/util/List;")) {
        accessor = MARKET_CONDITIONS_ACCESSOR;
        producer = source.insns.iterator().next();
      } else {
        FieldInsnNode field = optionalSourceField(source, Opcodes.GETFIELD, owner, "Ljava/util/List;");
        if (field == null || !field.name.equals("industries")) {
          throw mismatch("Market.advance snapshot source is not conditions or industries");
        }
        accessor = MARKET_INDUSTRIES_ACCESSOR;
        producer = field;
      }
      AbstractInsnNode receiverLoad = previousMeaningful(producer);
      if (!(receiverLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
        throw mismatch("Market.advance snapshot source receiver is not this");
      }
      plans.add(new PersistentSnapshotPlan(constructor,
          allocationPair(method, constructor, frames, "java/util/ArrayList", "Market.advance persistent snapshot"),
          receiverLoad, accessor));
    }
    requireCount("Market.advance persistent snapshot plans", plans.size(), 2);
    Set<String> accessors = new HashSet<>();
    for (PersistentSnapshotPlan plan : plans) accessors.add(plan.accessor);
    if (!accessors.equals(Set.of(MARKET_CONDITIONS_ACCESSOR, MARKET_INDUSTRIES_ACCESSOR))) {
      throw mismatch("Market.advance persistent snapshot kinds are incomplete");
    }
    return plans;
  }

  private static void applySnapshotRewrites(Plan plan, String owner) {
    InsnList instructions = plan.advance().instructions;
    for (PersistentSnapshotPlan snapshot : plan.snapshots()) {
      instructions.remove(snapshot.allocation.allocation());
      instructions.remove(snapshot.allocation.duplicate());
      instructions.insertBefore(snapshot.sourceReceiver, new VarInsnNode(Opcodes.ALOAD, 0));
      MethodInsnNode call = snapshot.constructor;
      call.setOpcode(Opcodes.INVOKEVIRTUAL);
      call.owner = owner;
      call.name = snapshot.accessor;
      call.desc = "(Ljava/util/Collection;)Ljava/util/ArrayList;";
      call.itf = false;
    }
  }

  private static MethodNode newMarketPersistentAccessor(String owner, String name, String stateField, int auditFrames,
      int kind) {
    MethodNode method = new MethodNode(Opcodes.ASM8, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, name,
        "(Ljava/util/Collection;)Ljava/util/ArrayList;", null, null);
    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, stateField, PERSISTENT_STATE_DESC));
    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
    method.instructions.add(pushInt(auditFrames));
    method.instructions.add(pushInt(kind));
    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "borrowPersistentSnapshotFrames",
        "(Ljava/lang/Object;Ljava/util/Collection;II)Ljava/util/ArrayList;", false));
    method.instructions.add(new InsnNode(Opcodes.ARETURN));
    return method;
  }

  private static void requireMarketPersistentMutationMarks(ClassNode node) {
    requireCount("Market condition epoch marks",
        countPersistentMarks(
            requireMethod(node, "addCondition", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"), node.name,
            MARKET_CONDITIONS_STATE_FIELD)
            + countPersistentMarks(
                requireMethod(node, "addCondition", "(Lcom/fs/starfarer/api/campaign/econ/MarketConditionAPI;)V"),
                node.name, MARKET_CONDITIONS_STATE_FIELD)
            + countPersistentMarks(requireMethod(node, "removeCondition", "(Ljava/lang/String;)V"), node.name,
                MARKET_CONDITIONS_STATE_FIELD)
            + countPersistentMarks(requireMethod(node, "removeSpecificCondition", "(Ljava/lang/String;)V"), node.name,
                MARKET_CONDITIONS_STATE_FIELD),
        4);
    requireCount("Market industry epoch marks",
        countPersistentMarks(requireMethod(node, "addIndustry", "(Ljava/lang/String;Ljava/util/List;)V"), node.name,
            MARKET_INDUSTRIES_STATE_FIELD)
            + countPersistentMarks(
                requireMethod(node, "removeIndustry",
                    "(Ljava/lang/String;Lcom/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode;Z)V"),
                node.name, MARKET_INDUSTRIES_STATE_FIELD),
        2);
  }
}
