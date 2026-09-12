package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.economy.PersistentState.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.PatchState;

public final class EconomyGroupIndex {
  public static final int ECONOMY_GROUP_INDEX_COMPONENT_REACH_ECONOMY = 1;
  public static final int ECONOMY_GROUP_INDEX_COMPONENT_MARKET = 2;
  static final String ECONOMY_GROUP_INDEX_STATE_FIELD = "spp$econGroupIndexState";
  static final String ECONOMY_GROUP_INDEX_RAW_GETTER = "spp$rawGetMarketsInGroup";

  static PatchState economyGroupIndexRegistrationState(ClassNode node, int component, String label) {
    List<MethodNode> initializers = methods(node, "<clinit>", "()V");
    if (initializers.size() > 1) throw mismatch(label + " has duplicate class initializers");
    if (initializers.isEmpty()) return PatchState.ORIGINAL;
    MethodNode initializer = initializers.get(0);
    int totalHooks = countCalls(initializer, Opcodes.INVOKESTATIC, HOOKS, "registerEconomyGroupIndexComponent", "(I)V");
    if (totalHooks == 0) return PatchState.ORIGINAL;
    int returns = 0;
    int matching = 0;
    for (AbstractInsnNode insn : initializer.instructions.toArray()) {
      if (insn.getOpcode() != Opcodes.RETURN) continue;
      returns++;
      int scanned = 0;
      for (AbstractInsnNode cursor = insn.getPrevious(); cursor != null && scanned < 6; cursor = cursor.getPrevious()) {
        if (cursor.getOpcode() < 0) continue;
        scanned++;
        if (!(cursor instanceof MethodInsnNode call)
            || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "registerEconomyGroupIndexComponent", "(I)V"))
          continue;
        if (!isIntegerLdc(previousMeaningful(call), component)) {
          throw mismatch(label + " registration component changed");
        }
        matching++;
        break;
      }
    }
    if (returns > 0 && totalHooks == returns && matching == returns) { return PatchState.PATCHED; }
    throw mismatch(label + " registration is partial or missing from a normal exit");
  }

  static void installEconomyGroupIndexRegistration(ClassNode node, int component, String label) {
    if (economyGroupIndexRegistrationState(node, component, label) != PatchState.ORIGINAL) {
      throw mismatch(label + " registration is not original");
    }
    List<MethodNode> initializers = methods(node, "<clinit>", "()V");
    MethodNode initializer;
    if (initializers.isEmpty()) {
      initializer = new MethodNode(Opcodes.ASM8, Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      initializer.instructions.add(new InsnNode(Opcodes.RETURN));
      node.methods.add(initializer);
    } else {
      initializer = initializers.get(0);
    }
    List<AbstractInsnNode> returns = new ArrayList<>();
    for (AbstractInsnNode insn : initializer.instructions.toArray()) {
      if (insn.getOpcode() == Opcodes.RETURN) returns.add(insn);
    }
    if (returns.isEmpty()) throw mismatch(label + " class initializer has no normal return");
    for (AbstractInsnNode exit : returns) {
      AbstractInsnNode insertionPoint = exit;
      AbstractInsnNode previous = previousMeaningful(exit);
      if (previous instanceof MethodInsnNode call
          && callMatches(call, Opcodes.INVOKESTATIC, HOOKS, "registerMarketSchedulerComponent", "(I)V")) {
        AbstractInsnNode schedulerComponent = previousMeaningful(call);
        if (schedulerComponent == null) { throw mismatch(label + " cannot compose with scheduler registration"); }
        insertionPoint = schedulerComponent;
      }
      InsnList registration = new InsnList();
      registration.add(pushInt(component));
      registration
          .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "registerEconomyGroupIndexComponent", "(I)V", false));
      initializer.instructions.insertBefore(insertionPoint, registration);
    }
    if (economyGroupIndexRegistrationState(node, component, label) != PatchState.PATCHED) {
      throw mismatch(label + " registration postcondition failed");
    }
  }

  static MethodNode requireEconomyGroupIndexWrapperSource(ClassNode node, String desc, String hookDesc) {
    List<MethodNode> publicMethods = methods(node, "getMarketsInGroup", desc);
    List<MethodNode> rawMethods = methods(node, ECONOMY_GROUP_INDEX_RAW_GETTER, desc);
    int hookCount = countCalls(node, Opcodes.INVOKESTATIC, HOOKS, "borrowMarketsInGroupIndexed", hookDesc);
    if (publicMethods.size() == 1 && rawMethods.size() == 1 && hookCount == 1) {
      MethodNode wrapper = publicMethods.get(0);
      MethodNode raw = rawMethods.get(0);
      int expectedAccess = wrapper.access;
      expectedAccess &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
      expectedAccess |= Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
      if (raw.access != expectedAccess) { throw mismatch("ReachEconomy raw getMarketsInGroup access changed"); }
      return raw;
    }
    if (publicMethods.size() != 1 || !rawMethods.isEmpty() || hookCount != 0) {
      throw mismatch("ReachEconomy getMarketsInGroup wrapper collision/mixed state: public=" + publicMethods.size()
          + ", raw=" + rawMethods.size() + ", hooks=" + hookCount);
    }
    return publicMethods.get(0);
  }

  static void requireEconomyGroupRawGetterShape(String owner, MethodNode method) {
    requireCount("ReachEconomy raw group ArrayList allocation", countNew(method, "java/util/ArrayList"), 1);
    requireCount("ReachEconomy raw group markets field",
        countFields(method, Opcodes.GETFIELD, owner, "markets", "Ljava/util/List;"), 1);
    requireCount("ReachEconomy raw group iterator",
        countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;"), 1);
    requireCount("ReachEconomy raw isInGroup", countCalls(method, Opcodes.INVOKEVIRTUAL, owner, "isInGroup",
        "(Ljava/lang/String;" + "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)Z"), 1);
    requireCount("ReachEconomy raw group add",
        countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"), 1);
  }

  static void installEconomyGroupIndexWrapper(String owner, MethodNode wrapper, String hookDesc) {
    LabelNode stateReady = new LabelNode();
    LabelNode fallback = new LabelNode();
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, ECONOMY_GROUP_INDEX_STATE_FIELD, PERSISTENT_STATE_DESC));
    code.add(new VarInsnNode(Opcodes.ASTORE, 2));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new JumpInsnNode(Opcodes.IFNONNULL, stateReady));
    code.add(
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "newPersistentSnapshotState", "()Ljava/lang/Object;", false));
    code.add(new VarInsnNode(Opcodes.ASTORE, 2));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, ECONOMY_GROUP_INDEX_STATE_FIELD, PERSISTENT_STATE_DESC));
    code.add(stateReady);
    code.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{owner, "java/lang/String", "java/lang/Object"}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "borrowMarketsInGroupIndexed", hookDesc, false));
    code.add(new VarInsnNode(Opcodes.ASTORE, 3));
    code.add(new VarInsnNode(Opcodes.ALOAD, 3));
    code.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
    code.add(new VarInsnNode(Opcodes.ALOAD, 3));
    code.add(new InsnNode(Opcodes.ARETURN));
    code.add(fallback);
    code.add(new FrameNode(Opcodes.F_FULL, 4,
        new Object[]{owner, "java/lang/String", "java/lang/Object", "java/util/List"}, 0, null));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
    code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, ECONOMY_GROUP_INDEX_RAW_GETTER,
        "(Ljava/lang/String;)Ljava/util/List;", false));
    code.add(new InsnNode(Opcodes.ARETURN));
    replaceMethodBody(wrapper, code, 4);
  }

  static void requireEconomyGroupIndexWrapperPostcondition(ClassNode node, String desc, String hookDesc) {
    requireCount("ReachEconomy public group getter", methods(node, "getMarketsInGroup", desc).size(), 1);
    List<MethodNode> raw = methods(node, ECONOMY_GROUP_INDEX_RAW_GETTER, desc);
    requireCount("ReachEconomy raw group getter", raw.size(), 1);
    if ((raw.get(0).access & Opcodes.ACC_PRIVATE) == 0 || (raw.get(0).access & Opcodes.ACC_SYNTHETIC) == 0) {
      throw mismatch("ReachEconomy raw group getter is not private synthetic");
    }
    MethodNode wrapper = methods(node, "getMarketsInGroup", desc).get(0);
    requireCount("ReachEconomy group-index hook",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "borrowMarketsInGroupIndexed", hookDesc), 1);
    requireCount("ReachEconomy group-index state allocation",
        countCalls(wrapper, Opcodes.INVOKESTATIC, HOOKS, "newPersistentSnapshotState", "()Ljava/lang/Object;"), 1);
    requireCount("ReachEconomy raw fallback",
        countCalls(wrapper, Opcodes.INVOKESPECIAL, node.name, ECONOMY_GROUP_INDEX_RAW_GETTER, desc), 1);
    requireCount("ReachEconomy group-index state read",
        countFields(wrapper, Opcodes.GETFIELD, node.name, ECONOMY_GROUP_INDEX_STATE_FIELD, PERSISTENT_STATE_DESC), 1);
    requireCount("ReachEconomy group-index state write",
        countFields(wrapper, Opcodes.PUTFIELD, node.name, ECONOMY_GROUP_INDEX_STATE_FIELD, PERSISTENT_STATE_DESC), 1);
    requireCount("ReachEconomy wrapper ARETURNs", countOpcode(wrapper, Opcodes.ARETURN), 2);
  }

  static PatchState economyGroupMutationState(ClassNode node, String methodName) {
    MethodNode method = requireMethod(node, methodName, "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V");
    int hooks =
        countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged", "(Ljava/lang/Object;)V");
    if (hooks == 0) return PatchState.ORIGINAL;
    if (hooks != 1)
      throw mismatch(node.name + "." + methodName + " has duplicate economy-group owner invalidation hooks");
    String listMethod = methodName.equals("addMarket") ? "add" : "remove";
    MethodInsnNode mutation =
        only(calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", listMethod, "(Ljava/lang/Object;)Z"),
            node.name + "." + methodName + " list mutation");
    AbstractInsnNode pop = nextMeaningful(mutation);
    AbstractInsnNode ownerLoad = nextMeaningful(pop);
    AbstractInsnNode stateRead = nextMeaningful(ownerLoad);
    AbstractInsnNode hook = nextMeaningful(stateRead);
    if (pop == null || pop.getOpcode() != Opcodes.POP || !(ownerLoad instanceof VarInsnNode load)
        || load.getOpcode() != Opcodes.ALOAD || load.var != 0 || !(stateRead instanceof FieldInsnNode field)
        || field.getOpcode() != Opcodes.GETFIELD || !field.owner.equals(node.name)
        || !field.name.equals(ECONOMY_GROUP_INDEX_STATE_FIELD) || !field.desc.equals(PERSISTENT_STATE_DESC)
        || !(hook instanceof MethodInsnNode call) || !callMatches(call, Opcodes.INVOKESTATIC, HOOKS,
            "markEconomyGroupStructureChanged", "(Ljava/lang/Object;)V")) {
      throw mismatch(node.name + "." + methodName + " owner invalidation is not directly after the list mutation");
    }
    return PatchState.PATCHED;
  }

  /**
   * Proves the original mutation shape and returns the POP instruction the
   * owner-invalidation hook must be inserted after.
   */
  static AbstractInsnNode economyGroupMutationPopAnchor(ClassNode node, MethodNode method, String methodName) {
    String listMethod = methodName.equals("addMarket") ? "add" : "remove";
    MethodInsnNode mutation =
        only(calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", listMethod, "(Ljava/lang/Object;)Z"),
            node.name + "." + methodName + " list mutation");
    AbstractInsnNode pop = nextMeaningful(mutation);
    if (pop == null || pop.getOpcode() != Opcodes.POP) {
      throw mismatch(node.name + "." + methodName + " no longer discards the list-mutation result");
    }
    return pop;
  }

  static void insertEconomyGroupInvalidation(MethodNode method, String owner, AbstractInsnNode pop) {
    InsnList invalidate = new InsnList();
    invalidate.add(new VarInsnNode(Opcodes.ALOAD, 0));
    invalidate.add(new FieldInsnNode(Opcodes.GETFIELD, owner, ECONOMY_GROUP_INDEX_STATE_FIELD, PERSISTENT_STATE_DESC));
    invalidate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged",
        "(Ljava/lang/Object;)V", false));
    method.instructions.insert(pop, invalidate);
  }

  static void requireMarketEconGroupMutationShape(String owner, MethodNode method, boolean patched) {
    requireCount("Market.setEconGroup field write",
        countFields(method, Opcodes.PUTFIELD, owner, "econGroup", "Ljava/lang/String;"), 1);
    int hooks = countCalls(method, Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged", "()V");
    requireCount("Market.setEconGroup epoch hooks", hooks, patched ? 1 : 0);
    FieldInsnNode write = only(fields(method, Opcodes.PUTFIELD, owner, "econGroup", "Ljava/lang/String;"),
        "Market.setEconGroup field write");
    AbstractInsnNode next = nextMeaningful(write);
    if (patched) {
      if (!(next instanceof MethodInsnNode hook)
          || !callMatches(hook, Opcodes.INVOKESTATIC, HOOKS, "markEconomyGroupStructureChanged", "()V")) {
        throw mismatch("Market.setEconGroup epoch hook moved from the field write");
      }
    } else if (next == null || next.getOpcode() != Opcodes.RETURN) {
      throw mismatch("Market.setEconGroup original return shape changed");
    }
  }

}
