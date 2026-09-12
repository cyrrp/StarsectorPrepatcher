package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;
import java.util.Set;

import com.starsector.prepatcher.agent.PrepatcherTransformer.Match;
import com.starsector.prepatcher.agent.PrepatcherTransformer.PatchReport;
import com.starsector.prepatcher.agent.PrepatcherTransformer.StructuredPatch;

import static com.starsector.prepatcher.agent.Allocations.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.Names.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Wraps H.updateSystemNebulas so repeated advance calls reuse the cached
 * (systemNebulas, constellationLabels, nebulaStars) lists; the original body becomes the
 * private synthetic recompute. The wrapper passes the three output lists to the hook in
 * the order the original body proved them, so match must identify each List.add target
 * semantically and prove every list is cleared exactly once.
 */
final class SystemNebulaUpdateCache implements StructuredPatch<SystemNebulaUpdateCache.Plan> {
  private static final String DESC = "()V";
  private static final String RENAMED = "smo$originalUpdateSystemNebulas";
  private static final String HOOK = "updateSystemNebulasCached";
  private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V";

  /** The proven original body and the three output list fields, in hook order. */
  record Plan(String owner, List<MethodNode> classMethods, MethodNode original, List<String> fields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    boolean wrapperAlready = methods(node, RENAMED, DESC).size() == 1;
    MethodNode original = requireWrapperSource(node, "updateSystemNebulas", DESC, RENAMED, HOOK, HOOK_DESC);
    Frame<SourceValue>[] frames = sourceFrames(node.name, original);
    requireCount("updateSystemNebulas semantic call count", totalMethodCalls(original), 57);

    String createNebulaDesc = "(" + STAR_SYSTEM_DESC + ")Lcom/fs/starfarer/campaign/CampaignTerrain;";
    String createLabelDesc = "(Lcom/fs/starfarer/api/impl/campaign/procgen/Constellation;)"
        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;";
    requireMethod(node, "createNebula", createNebulaDesc);
    requireMethod(node, "createConstellationLabel", createLabelDesc);
    int nebulaLocal = requireStoredCallResultLocal(original, Opcodes.INVOKESTATIC, node.name, "createNebula",
        createNebulaDesc, "system nebula result");
    int labelLocal = requireStoredCallResultLocal(original, Opcodes.INVOKESTATIC, node.name, "createConstellationLabel",
        createLabelDesc, "constellation label result");

    String systemNebulas = null;
    String constellationLabels = null;
    String nebulaStars = null;
    List<MethodInsnNode> adds =
        calls(original, Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z");
    requireCount("updateSystemNebulas List.add sites", adds.size(), 3);
    for (MethodInsnNode add : adds) {
      FieldInsnNode field = requireSourceField(receiverSource(original, add, frames), Opcodes.GETFIELD, node.name,
          LIST_DESC, "system-nebula output list");
      SourceValue value = argumentSource(original, add, frames, 0);
      if (sourceIsLocal(value, Opcodes.ALOAD, nebulaLocal)) {
        systemNebulas = uniqueRole(systemNebulas, field.name, "system nebula list");
      } else if (sourceIsLocal(value, Opcodes.ALOAD, labelLocal)) {
        constellationLabels = uniqueRole(constellationLabels, field.name, "constellation label list");
      } else if (hasCallImmediatelyBefore(add, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/campaign/StarSystemAPI",
          "getStar", "()Lcom/fs/starfarer/api/campaign/PlanetAPI;", 4)) {
            nebulaStars = uniqueRole(nebulaStars, field.name, "nebula-star list");
          } else {
            return Match.incompatible("unable to assign semantic role to List.add in updateSystemNebulas");
          }
    }
    if (systemNebulas == null || constellationLabels == null || nebulaStars == null
        || Set.of(systemNebulas, constellationLabels, nebulaStars).size() != 3) {
      return Match.incompatible("system-nebula output lists are missing or not distinct");
    }
    for (String field : List.of(systemNebulas, constellationLabels, nebulaStars)) {
      int clears = countCallsWithFieldReceiver(node.name, original, frames, Opcodes.INVOKEINTERFACE, "java/util/List",
          "clear", "()V", field, LIST_DESC);
      requireCount("clear of system-nebula list " + field, clears, 1);
    }
    List<String> fields = List.of(systemNebulas, constellationLabels, nebulaStars);
    if (wrapperAlready) {
      requireSystemNebulaWrapper(node, DESC, fields, HOOK_DESC);
      return Match.alreadyApplied("updateSystemNebulas wrapper, original semantics, and field wiring match");
    }
    return Match.plan(new Plan(node.name, node.methods, original, fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode original = plan.original();
    int originalAccess = original.access;
    String signature = original.signature;
    String[] exceptions = exceptions(original);
    original.name = RENAMED;
    makePrivateSynthetic(original);
    MethodNode wrapper =
        new MethodNode(Opcodes.ASM8, originalAccess, "updateSystemNebulas", DESC, signature, exceptions);
    moveWrapperMetadata(original, wrapper, "updateSystemNebulas");
    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    for (String field : plan.fields()) {
      wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
      wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, plan.owner(), field, LIST_DESC));
    }
    wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, HOOK, HOOK_DESC, false));
    wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
    plan.classMethods().add(wrapper);

    PatchReport report = new PatchReport();
    report.add("system-nebula preprocessing cache", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireWrapperPostcondition(node, "updateSystemNebulas", DESC, RENAMED, HOOK, HOOK_DESC);
    requireSystemNebulaWrapper(node, DESC, plan.fields(), HOOK_DESC);
  }

  private static void requireSystemNebulaWrapper(ClassNode node, String desc, List<String> fields, String hookDesc) {
    MethodNode wrapper = requireMethod(node, "updateSystemNebulas", desc);
    List<AbstractInsnNode> code = meaningfulInstructions(wrapper);
    requireCount("updateSystemNebulas wrapper instruction count", code.size(), 9);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "nebula wrapper owner");
    for (int i = 0; i < fields.size(); i++) {
      requireVar(code.get(1 + i * 2), Opcodes.ALOAD, 0, "nebula wrapper field owner " + i);
      requireField(code.get(2 + i * 2), Opcodes.GETFIELD, node.name, fields.get(i), LIST_DESC,
          "nebula wrapper field " + i);
    }
    requireCall(asMethod(code.get(7), "nebula wrapper hook"), Opcodes.INVOKESTATIC, HOOKS, HOOK, hookDesc,
        "nebula wrapper hook");
    if (code.get(8).getOpcode() != Opcodes.RETURN) throw mismatch("nebula wrapper return changed");
  }
}
