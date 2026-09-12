package com.starsector.prepatcher.agent.campaign;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Flow.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Replaces the unconditional readdChangeListeners() sweep in
 * CampaignEngine.advance with the incremental runtime hook. This member runs
 * inside CampaignListenerPlan: it rewrites the listener boundary in place on
 * the same plan candidate the entity-index repair anchors to.
 */
final class CampaignListenerThrottle implements StructuredPatch<CampaignListenerThrottle.Plan> {
  private static final String HYPERSPACE_DESC = "Lcom/fs/starfarer/campaign/Hyperspace;";

  record Plan(String owner, MethodNode advance, MethodInsnNode call, String systemsField, String hyperspaceField) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode refresh = requireMethod(node, "readdChangeListeners", "()V");
    if (containsWrite(refresh)) { throw mismatch("readdChangeListeners contains field/static writes"); }
    Frame<SourceValue>[] refreshFrames = sourceFrames(node.name, refresh);
    List<MethodInsnNode> listenerCalls =
        calls(refresh, Opcodes.INVOKEVIRTUAL, "com/fs/util/container/repo/ObjectRepository", "setListener", null);
    requireCount("readdChangeListeners setListener calls", listenerCalls.size(), 2);

    MethodInsnNode systemIterator =
        only(calls(refresh, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;"),
            "starSystems iterator");
    FieldInsnNode systemsField = requireSourceField(receiverSource(refresh, systemIterator, refreshFrames),
        Opcodes.GETFIELD, node.name, LIST_DESC, "CampaignEngine starSystems field");
    MethodInsnNode getObjects = only(calls(refresh, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/Hyperspace",
        "getObjects", "()Lcom/fs/util/container/repo/ObjectRepository;"), "hyperspace repository access");
    FieldInsnNode hyperspaceField = requireSourceField(receiverSource(refresh, getObjects, refreshFrames),
        Opcodes.GETFIELD, node.name, HYPERSPACE_DESC, "CampaignEngine hyperspace field");

    MethodNode advance = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V";
    List<MethodInsnNode> original = calls(advance, Opcodes.INVOKEVIRTUAL, node.name, "readdChangeListeners", "()V");
    int hooks = countCalls(advance, Opcodes.INVOKESTATIC, HOOKS, "readdChangeListenersIfNeeded", hookDesc);
    requireUnpatchedOrAlready("CampaignEngine.advance listener refresh", original.size(), 1, hooks, 1);
    MethodInsnNode call = only(original, "CampaignEngine.advance listener refresh");
    Frame<SourceValue>[] advanceFrames = sourceFrames(node.name, advance);
    if (!sourceIsLocal(receiverSource(advance, call, advanceFrames), Opcodes.ALOAD, 0)) {
      throw mismatch("CampaignEngine.advance listener receiver is not this");
    }
    return Match.plan(new Plan(node.name, advance, call, systemsField.name, hyperspaceField.name));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    String hookDesc = "(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V";
    plan.advance().instructions.insertBefore(plan.call(), new VarInsnNode(Opcodes.ALOAD, 0));
    plan.advance().instructions.insertBefore(plan.call(),
        new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.systemsField(), LIST_DESC));
    plan.advance().instructions.insertBefore(plan.call(), new VarInsnNode(Opcodes.ALOAD, 0));
    plan.advance().instructions.insertBefore(plan.call(),
        new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), plan.hyperspaceField(), HYPERSPACE_DESC));
    makeStatic(plan.call(), HOOKS, "readdChangeListenersIfNeeded", hookDesc);

    PatchReport report = new PatchReport();
    report.add("incremental campaign repository-listener refresh", 1);
    return report;
  }
}
