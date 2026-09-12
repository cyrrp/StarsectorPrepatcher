package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Adds notifications to the two authoritative CampaignEngine system-list mutations.
 * Deferred removal already funnels through removeStarSystem(), so no advance-method
 * surface is required and this remains independent from the market-scheduler patch.
 */
final class CoreWorldsTopologyHooks implements StructuredPatch<CoreWorldsTopologyHooks.Plan> {
  record Plan(MethodNode create, AbstractInsnNode createReturn, MethodNode remove, AbstractInsnNode removeReturn,
      int systemLocal, int removeArgument, String hookDesc) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    String sectorDesc = "Lcom/fs/starfarer/api/campaign/SectorAPI;";
    String systemDesc = "Lcom/fs/starfarer/api/campaign/StarSystemAPI;";
    String hookDesc = "(" + sectorDesc + systemDesc + ")V";
    MethodNode create = requireMethod(node, "createStarSystem", "(Ljava/lang/String;)" + systemDesc);
    MethodNode remove = requireMethod(node, "removeStarSystem", "(" + systemDesc + ")V");
    if ((create.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (remove.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      return Match.incompatible("CampaignEngine core-worlds topology methods have no code");
    }

    AbstractInsnNode createReturn = onlyInsn(opcodeReturns(create, Opcodes.ARETURN, "CampaignEngine.createStarSystem"),
        "CampaignEngine.createStarSystem ARETURN");
    AbstractInsnNode removeReturn = onlyInsn(campaignReturns(remove), "CampaignEngine.removeStarSystem RETURN");
    int systemLocal = requireCoreWorldsCreateSystemLocal(node.name, create);
    int removeArgument = argumentLocal(remove, 0);
    requireCoreWorldsRemoveShape(node.name, remove, removeArgument, removeReturn);

    int createHooks = countCalls(create, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemAdded", hookDesc);
    int removeHooks = countCalls(remove, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemRemoved", hookDesc);
    if (createHooks == 1 && removeHooks == 1) {
      validateCoreWorldsCreateHook(create, createReturn, hookDesc, systemLocal);
      validateCoreWorldsVoidHook(remove, removeReturn, "starSystemRemoved", hookDesc, removeArgument);
      return Match.alreadyApplied("CampaignEngine core-worlds topology hooks match");
    }
    if (createHooks != 0 || removeHooks != 0) {
      return Match.incompatible("CampaignEngine core-worlds topology hook state is partial: create=" + createHooks
          + "/1, remove=" + removeHooks + "/1");
    }
    return Match.plan(new Plan(create, createReturn, remove, removeReturn, systemLocal, removeArgument, hookDesc));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    // ARETURN already has the return object below the hook arguments on the operand stack.
    // Calling the hook immediately before ARETURN consumes only the two newly pushed values
    // and leaves the original return value untouched.
    InsnList createHook = new InsnList();
    createHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    createHook.add(new VarInsnNode(Opcodes.ALOAD, plan.systemLocal()));
    createHook
        .add(new MethodInsnNode(Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemAdded", plan.hookDesc(), false));
    plan.create().instructions.insertBefore(plan.createReturn(), createHook);

    InsnList removeHook = new InsnList();
    removeHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
    removeHook.add(new VarInsnNode(Opcodes.ALOAD, plan.removeArgument()));
    removeHook.add(
        new MethodInsnNode(Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemRemoved", plan.hookDesc(), false));
    plan.remove().instructions.insertBefore(plan.removeReturn(), removeHook);

    PatchReport report = new PatchReport();
    report.add("CampaignEngine core-worlds system membership epilogues", 2);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    validateCoreWorldsCreateHook(plan.create(), plan.createReturn(), plan.hookDesc(), plan.systemLocal());
    validateCoreWorldsVoidHook(plan.remove(), plan.removeReturn(), "starSystemRemoved", plan.hookDesc(),
        plan.removeArgument());
  }

  private static int requireCoreWorldsCreateSystemLocal(String owner, MethodNode method) {
    TypeInsnNode allocation = null;
    int allocations = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
          && type.desc.equals("com/fs/starfarer/campaign/StarSystem")) {
        allocation = type;
        allocations++;
      }
    }
    requireCount("CampaignEngine.createStarSystem StarSystem allocations", allocations, 1);
    MethodInsnNode constructor = only(
        calls(method, Opcodes.INVOKESPECIAL, "com/fs/starfarer/campaign/StarSystem", "<init>", "(Ljava/lang/String;)V"),
        "CampaignEngine.createStarSystem constructor");
    requireVar(previousMeaningful(constructor), Opcodes.ALOAD, argumentLocal(method, 0),
        "CampaignEngine.createStarSystem constructor name argument");
    AbstractInsnNode duplicate = previousMeaningful(previousMeaningful(constructor));
    if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP || previousMeaningful(duplicate) != allocation) {
      throw mismatch("CampaignEngine.createStarSystem allocation/constructor sequence changed");
    }
    AbstractInsnNode storeInsn = nextMeaningful(constructor);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("CampaignEngine.createStarSystem result is not stored in a local");
    }
    int systemLocal = store.var;
    requireCount("CampaignEngine.createStarSystem system-local stores", countStores(method, systemLocal), 1);

    MethodInsnNode addition =
        only(calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"),
            "CampaignEngine.createStarSystem List.add");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    if (!sourceIsFieldDesc(receiverSource(method, addition, frames), "Ljava/util/List;")) {
      throw mismatch("CampaignEngine.createStarSystem add receiver is not a List field");
    }
    int addedLocal = requireLoadedLocal(argumentSource(method, addition, frames, 0), Opcodes.ALOAD,
        "CampaignEngine.createStarSystem added system");
    if (addedLocal != systemLocal) { throw mismatch("CampaignEngine.createStarSystem adds a different local"); }
    // Return materialization is deliberately not constrained. The hook uses the exact local
    // proven to be inserted into CampaignEngine.starSystems, while preserving whatever
    // compatible expression the method places on the ARETURN stack.
    return systemLocal;
  }

  private static void requireCoreWorldsRemoveShape(String owner, MethodNode method, int systemLocal,
      AbstractInsnNode returnInsn) {
    MethodInsnNode removal =
        only(calls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z"),
            "CampaignEngine.removeStarSystem List.remove");
    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    if (!sourceIsFieldDesc(receiverSource(method, removal, frames), "Ljava/util/List;")) {
      throw mismatch("CampaignEngine.removeStarSystem remove receiver is not a List field");
    }
    int removedLocal = requireLoadedLocal(argumentSource(method, removal, frames, 0), Opcodes.ALOAD,
        "CampaignEngine.removeStarSystem argument");
    if (removedLocal != systemLocal) { throw mismatch("CampaignEngine.removeStarSystem removes a different local"); }
    AbstractInsnNode discard = nextMeaningful(removal);
    if (discard == null || discard.getOpcode() != Opcodes.POP) {
      throw mismatch("CampaignEngine.removeStarSystem removal result is not discarded");
    }
    // Compatible builds may perform unrelated bookkeeping after the authoritative list
    // removal. The single proven normal RETURN remains the safe notification boundary.
  }

  private static void validateCoreWorldsCreateHook(MethodNode method, AbstractInsnNode returnInsn, String hookDesc,
      int systemLocal) {
    MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), "CampaignEngine.createStarSystem core-worlds hook");
    requireCall(hook, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, "starSystemAdded", hookDesc,
        "CampaignEngine.createStarSystem core-worlds hook");
    requireVar(previousMeaningful(hook), Opcodes.ALOAD, systemLocal,
        "CampaignEngine.createStarSystem hook system argument");
    requireVar(previousMeaningful(previousMeaningful(hook)), Opcodes.ALOAD, 0,
        "CampaignEngine.createStarSystem hook sector argument");
  }

  private static void validateCoreWorldsVoidHook(MethodNode method, AbstractInsnNode returnInsn, String hookName,
      String hookDesc, int systemLocal) {
    MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), method.name + " core-worlds hook");
    requireCall(hook, Opcodes.INVOKESTATIC, CORE_WORLDS_RUNTIME, hookName, hookDesc, method.name + " core-worlds hook");
    requireVar(previousMeaningful(hook), Opcodes.ALOAD, systemLocal, method.name + " core-worlds system argument");
    requireVar(previousMeaningful(previousMeaningful(hook)), Opcodes.ALOAD, 0,
        method.name + " core-worlds sector argument");
  }
}
