package com.starsector.prepatcher.agent.uieconomy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/**
 * Dialog surface of the industry market-mutation patch. The five proven
 * dialogDismissed branches publish a one-shot mutation context through
 * runtime wrappers. This class is half of an atomic group with
 * IndustryOverviewMutationGuard; the group bookkeeping lives in
 * IndustryMarketMutationTransformer.
 */
final class IndustryMarketMutations implements StructuredPatch<IndustryMarketMutations.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/ui/marketinfo/b";
  private static final String INDUSTRY_PANEL = "com/fs/starfarer/campaign/ui/marketinfo/IndustryListPanel";

  private static final String DIALOG_DISMISSED = "dialogDismissed";
  private static final String DIALOG_DISMISSED_DESC = "(Lcom/fs/starfarer/ui/oo0O;I)V";
  private static final String RECREATE = "recreateOverview";
  private static final String RECREATE_DESC = "()V";

  private static final String INDUSTRY = "com/fs/starfarer/api/campaign/econ/Industry";
  private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
  private static final String MODE = "com/fs/starfarer/api/campaign/econ/MarketAPI$MarketInteractionMode";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";

  private static final String START = "applyVanillaIndustryStartUpgrading";
  private static final String DOWNGRADE = "applyVanillaIndustryDowngrade";
  private static final String CANCEL = "applyVanillaIndustryCancelUpgrade";
  private static final String REMOVE = "applyVanillaIndustryRemoval";
  private static final String INDUSTRY_WRAPPER_DESC = "(L" + INDUSTRY + ";)V";
  private static final String REMOVE_DESC = "(L" + MARKET_API + ";Ljava/lang/String;L" + MODE + ";Z)V";

  private static final String DIALOG_MARKER = "spp$patched$industryMarketMutations";
  private static final String DIALOG_MARKER_VALUE = "StarsectorPrepatcher:industry-market-mutations-v1";

  record Plan(MethodNode method, List<MethodInsnNode> start, List<MethodInsnNode> downgrade,
      List<MethodInsnNode> cancel, List<MethodInsnNode> remove, List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    FieldNode marker = field(node, DIALOG_MARKER);
    if (marker != null) {
      requireMarker(marker);
      requirePatchedDialog(node);
      return Match.alreadyApplied("industry market-mutation dialog postcondition matches");
    }
    requireNoMarker(marker);
    MethodNode method = requireMethod(node, DIALOG_DISMISSED, DIALOG_DISMISSED_DESC);
    requireConcreteInstance(method);
    requireCount("startUpgrading", calls(method, INDUSTRY, "startUpgrading", "()V").size(), 1);
    requireCount("downgrade", calls(method, INDUSTRY, "downgrade", "()V").size(), 1);
    requireCount("cancelUpgrade", calls(method, INDUSTRY, "cancelUpgrade", "()V").size(), 1);
    requireCount("removeIndustry",
        calls(method, MARKET_API, "removeIndustry", "(Ljava/lang/String;L" + MODE + ";Z)V").size(), 2);
    // All five exact mutations must feed the shared overview refresh.
    requireCount("recreateOverview", calls(method, INDUSTRY_PANEL, RECREATE, RECREATE_DESC).size(), 7);
    return Match.plan(new Plan(method, calls(method, INDUSTRY, "startUpgrading", "()V"),
        calls(method, INDUSTRY, "downgrade", "()V"), calls(method, INDUSTRY, "cancelUpgrade", "()V"),
        calls(method, MARKET_API, "removeIndustry", "(Ljava/lang/String;L" + MODE + ";Z)V"), node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (MethodInsnNode call : plan.start()) replaceWithRuntime(call, START, INDUSTRY_WRAPPER_DESC);
    for (MethodInsnNode call : plan.downgrade()) replaceWithRuntime(call, DOWNGRADE, INDUSTRY_WRAPPER_DESC);
    for (MethodInsnNode call : plan.cancel()) replaceWithRuntime(call, CANCEL, INDUSTRY_WRAPPER_DESC);
    for (MethodInsnNode call : plan.remove()) replaceWithRuntime(call, REMOVE, REMOVE_DESC);
    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, DIALOG_MARKER,
            "Ljava/lang/String;", null, DIALOG_MARKER_VALUE));
    PatchReport report = new PatchReport();
    report.add("industry mutation wrappers", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedDialog(node);
  }

  private static void replaceWithRuntime(MethodInsnNode call, String runtimeName, String runtimeDesc) {
    call.setOpcode(Opcodes.INVOKESTATIC);
    call.owner = RUNTIME;
    call.name = runtimeName;
    call.desc = runtimeDesc;
    call.itf = false;
  }

  private static void requirePatchedDialog(ClassNode node) {
    requireMarker(field(node, DIALOG_MARKER));
    MethodNode method = requireMethod(node, DIALOG_DISMISSED, DIALOG_DISMISSED_DESC);
    requireCount("remaining startUpgrading", calls(method, INDUSTRY, "startUpgrading", "()V").size(), 0);
    requireCount("remaining downgrade", calls(method, INDUSTRY, "downgrade", "()V").size(), 0);
    requireCount("remaining cancelUpgrade", calls(method, INDUSTRY, "cancelUpgrade", "()V").size(), 0);
    requireCount("remaining removeIndustry",
        calls(method, MARKET_API, "removeIndustry", "(Ljava/lang/String;L" + MODE + ";Z)V").size(), 0);
    requireCount("start wrappers", calls(method, RUNTIME, START, INDUSTRY_WRAPPER_DESC).size(), 1);
    requireCount("downgrade wrappers", calls(method, RUNTIME, DOWNGRADE, INDUSTRY_WRAPPER_DESC).size(), 1);
    requireCount("cancel wrappers", calls(method, RUNTIME, CANCEL, INDUSTRY_WRAPPER_DESC).size(), 1);
    requireCount("remove wrappers", calls(method, RUNTIME, REMOVE, REMOVE_DESC).size(), 2);
    requireCount("preserved recreateOverview", calls(method, INDUSTRY_PANEL, RECREATE, RECREATE_DESC).size(), 7);
  }

  private static void requireConcreteInstance(MethodNode method) {
    if ((method.access & Opcodes.ACC_PUBLIC) == 0
        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch(DIALOG_DISMISSED + " access changed");
    }
  }

  private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String desc) {
    List<MethodInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
          && desc.equals(call.desc))
        result.add(call);
    }
    return result;
  }

  private static MethodNode requireMethod(ClassNode node, String name, String desc) {
    for (MethodNode method : node.methods) {
      if (name.equals(method.name) && desc.equals(method.desc)) return method;
    }
    throw mismatch("missing method " + name + desc);
  }

  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) if (name.equals(field.name)) return field;
    return null;
  }

  private static void requireNoMarker(FieldNode marker) {
    if (marker != null) throw mismatch("owned marker already exists");
  }

  private static void requireMarker(FieldNode marker) {
    if (marker == null
        || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
        || !"Ljava/lang/String;".equals(marker.desc) || !DIALOG_MARKER_VALUE.equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }
}
