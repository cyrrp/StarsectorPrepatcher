package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

public final class CampaignCacheLifecycle implements StructuredPatch<CampaignCacheLifecycle.Plan> {
  private static final String ENGINE_DESC = "L" + CAMPAIGN_ENGINE + ";";
  private static final String BEGIN_DESC = "(Ljava/lang/Object;)J";
  private static final String COMPLETE_DESC = "(Ljava/lang/Object;J)V";

  record Plan(MethodNode reset, MethodNode set, MethodNode advance, FieldInsnNode resetWrite, FieldInsnNode setWrite,
      List<AbstractInsnNode> resetReturns, List<AbstractInsnNode> setReturns) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode reset = requireMethod(node, "resetInstance", "()V");
    MethodNode set = requireMethod(node, "setInstance", "(" + ENGINE_DESC + ")V");
    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    int maintenanceHooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "campaignCacheMaintenanceTick", "()V");
    if ((reset.access & Opcodes.ACC_STATIC) == 0 || (set.access & Opcodes.ACC_STATIC) == 0) {
      throw mismatch("CampaignEngine lifecycle methods are not static");
    }
    if ((reset.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
        || (set.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("CampaignEngine lifecycle methods have no patchable code");
    }

    FieldNode singleton = null;
    for (FieldNode field : node.fields) {
      if ((field.access & Opcodes.ACC_STATIC) == 0 || !field.desc.equals(ENGINE_DESC)) continue;
      if (singleton != null) { throw mismatch("CampaignEngine has multiple static self-typed fields"); }
      singleton = field;
    }
    if (singleton == null) throw mismatch("CampaignEngine singleton field was not found");
    FieldInsnNode resetWrite = requireSingletonWrite(reset, node.name, singleton.name, ENGINE_DESC, "resetInstance");
    FieldInsnNode setWrite = requireSingletonWrite(set, node.name, singleton.name, ENGINE_DESC, "setInstance");
    Frame<SourceValue> resetFrame = frameBefore(reset, resetWrite, sourceFrames(node.name, reset));
    Frame<SourceValue> setFrame = frameBefore(set, setWrite, sourceFrames(node.name, set));
    if (resetFrame.getStackSize() < 1
        || !sourceIsOpcode(resetFrame.getStack(resetFrame.getStackSize() - 1), Opcodes.ACONST_NULL)) {
      throw mismatch("resetInstance singleton write is not produced by null");
    }
    if (setFrame.getStackSize() < 1
        || !sourceIsLocal(setFrame.getStack(setFrame.getStackSize() - 1), Opcodes.ALOAD, argumentLocal(set, 0))) {
      throw mismatch("setInstance singleton write is not produced by its engine argument");
    }

    List<AbstractInsnNode> resetReturns = campaignReturns(reset);
    List<AbstractInsnNode> setReturns = campaignReturns(set);
    int resetBegins = countCalls(reset, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", BEGIN_DESC);
    int setBegins = countCalls(set, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", BEGIN_DESC);
    int resetCompletes = countCalls(reset, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", COMPLETE_DESC);
    int setCompletes = countCalls(set, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", COMPLETE_DESC);
    if (resetBegins == 1 && setBegins == 1 && resetCompletes == resetReturns.size() && setCompletes == setReturns.size()
        && maintenanceHooks == 1) {
      int resetToken = requireCampaignBeginPlacement(reset, resetWrite, false);
      int setToken = requireCampaignBeginPlacement(set, setWrite, true);
      requireCampaignCompletionPlacement(reset, resetReturns, false, resetToken);
      requireCampaignCompletionPlacement(set, setReturns, true, setToken);
      requireCampaignTokenInitializer(reset, resetToken);
      requireCampaignTokenInitializer(set, setToken);
      return Match.alreadyApplied("CampaignEngine lifecycle cache hooks are already present");
    }
    if (resetBegins != 0 || setBegins != 0 || resetCompletes != 0 || setCompletes != 0 || maintenanceHooks != 0) {
      return Match.incompatible("CampaignEngine lifecycle/maintenance hooks are partial or ambiguous: resetBegin="
          + resetBegins + ", setBegin=" + setBegins + ", resetComplete=" + resetCompletes + ", setComplete="
          + setCompletes + ", maintenance=" + maintenanceHooks);
    }
    return Match.plan(new Plan(reset, set, advance, resetWrite, setWrite, resetReturns, setReturns));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode reset = plan.reset();
    MethodNode set = plan.set();
    plan.advance().instructions
        .insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "campaignCacheMaintenanceTick", "()V", false));

    int resetToken = reset.maxLocals;
    reset.maxLocals += 2;
    InsnList resetInitializer = new InsnList();
    resetInitializer.add(new LdcInsnNode(Long.valueOf(-1L)));
    resetInitializer.add(new VarInsnNode(Opcodes.LSTORE, resetToken));
    reset.instructions.insert(resetInitializer);
    InsnList resetPrefix = new InsnList();
    resetPrefix.add(new InsnNode(Opcodes.ACONST_NULL));
    resetPrefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", BEGIN_DESC, false));
    resetPrefix.add(new VarInsnNode(Opcodes.LSTORE, resetToken));
    reset.instructions.insertBefore(plan.resetWrite(), resetPrefix);
    for (AbstractInsnNode returnInsn : plan.resetReturns()) {
      InsnList completion = new InsnList();
      completion.add(new InsnNode(Opcodes.ACONST_NULL));
      completion.add(new VarInsnNode(Opcodes.LLOAD, resetToken));
      completion
          .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", COMPLETE_DESC, false));
      reset.instructions.insertBefore(returnInsn, completion);
    }

    int setToken = set.maxLocals;
    set.maxLocals += 2;
    InsnList setInitializer = new InsnList();
    setInitializer.add(new LdcInsnNode(Long.valueOf(-1L)));
    setInitializer.add(new VarInsnNode(Opcodes.LSTORE, setToken));
    set.instructions.insert(setInitializer);
    InsnList setPrefix = new InsnList();
    setPrefix.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(set, 0)));
    setPrefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", BEGIN_DESC, false));
    setPrefix.add(new VarInsnNode(Opcodes.LSTORE, setToken));
    set.instructions.insertBefore(plan.setWrite(), setPrefix);
    for (AbstractInsnNode returnInsn : plan.setReturns()) {
      InsnList completion = new InsnList();
      completion.add(new VarInsnNode(Opcodes.ALOAD, argumentLocal(set, 0)));
      completion.add(new VarInsnNode(Opcodes.LLOAD, setToken));
      completion
          .add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", COMPLETE_DESC, false));
      set.instructions.insertBefore(returnInsn, completion);
    }

    PatchReport report = new PatchReport();
    report.add("campaign cache two-phase boundaries", 2 + plan.resetReturns().size() + plan.setReturns().size());
    report.add("campaign-thread cache maintenance tick", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    int resetToken = requireCampaignBeginPlacement(plan.reset(), plan.resetWrite(), false);
    int setToken = requireCampaignBeginPlacement(plan.set(), plan.setWrite(), true);
    requireCampaignCompletionPlacement(plan.reset(), plan.resetReturns(), false, resetToken);
    requireCampaignCompletionPlacement(plan.set(), plan.setReturns(), true, setToken);
    requireCampaignTokenInitializer(plan.reset(), resetToken);
    requireCampaignTokenInitializer(plan.set(), setToken);
    requireCount("CampaignEngine campaign maintenance hooks",
        countCalls(plan.advance(), Opcodes.INVOKESTATIC, HOOKS, "campaignCacheMaintenanceTick", "()V"), 1);
  }

  private static int requireCampaignBeginPlacement(MethodNode method, FieldInsnNode write, boolean engineArgument) {
    AbstractInsnNode storeInsn = previousMeaningful(write);
    if (!(storeInsn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.LSTORE) {
      throw mismatch(method.name + " lifecycle transition token is not stored before publication");
    }
    AbstractInsnNode hookInsn = previousMeaningful(store);
    MethodInsnNode hook = asMethod(hookInsn, method.name + " lifecycle hook");
    requireCall(hook, Opcodes.INVOKESTATIC, HOOKS, "beginCampaignEngineChange", BEGIN_DESC,
        method.name + " lifecycle hook");
    AbstractInsnNode argument = previousMeaningful(hook);
    if (engineArgument) {
      requireVar(argument, Opcodes.ALOAD, argumentLocal(method, 0), method.name + " lifecycle engine argument");
    } else if (argument == null || argument.getOpcode() != Opcodes.ACONST_NULL) {
      throw mismatch(method.name + " lifecycle reset argument is not null");
    }
    return store.var;
  }

  private static void requireCampaignCompletionPlacement(MethodNode method, List<AbstractInsnNode> returns,
      boolean engineArgument, int tokenLocal) {
    for (AbstractInsnNode returnInsn : returns) {
      MethodInsnNode hook = asMethod(previousMeaningful(returnInsn), method.name + " lifecycle completion hook");
      requireCall(hook, Opcodes.INVOKESTATIC, HOOKS, "completeCampaignEngineChange", COMPLETE_DESC,
          method.name + " lifecycle completion hook");
      AbstractInsnNode token = previousMeaningful(hook);
      requireVar(token, Opcodes.LLOAD, tokenLocal, method.name + " lifecycle completion token");
      AbstractInsnNode argument = previousMeaningful(token);
      if (engineArgument) {
        requireVar(argument, Opcodes.ALOAD, argumentLocal(method, 0),
            method.name + " lifecycle completion engine argument");
      } else if (argument == null || argument.getOpcode() != Opcodes.ACONST_NULL) {
        throw mismatch(method.name + " lifecycle completion reset argument is not null");
      }
    }
  }

  private static void requireCampaignTokenInitializer(MethodNode method, int tokenLocal) {
    AbstractInsnNode first = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getOpcode() >= 0) {
        first = insn;
        break;
      }
    }
    if (!(first instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Long value) || value.longValue() != -1L) {
      throw mismatch(method.name + " lifecycle transition token initializer changed");
    }
    requireVar(nextMeaningful(first), Opcodes.LSTORE, tokenLocal,
        method.name + " lifecycle transition token initializer");
  }

  private static FieldInsnNode requireSingletonWrite(MethodNode method, String owner, String name, String desc,
      String label) {
    FieldInsnNode result = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC || !field.owner.equals(owner)
          || !field.name.equals(name) || !field.desc.equals(desc))
        continue;
      if (result != null) throw mismatch(label + " has multiple singleton writes");
      result = field;
    }
    if (result == null) throw mismatch(label + " singleton write was not found");
    return result;
  }
}
