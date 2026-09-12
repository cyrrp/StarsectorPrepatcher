package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

/**
 * Routes rare core create/remove event calls through the immediate debt synchronizer. These
 * sites are not periodic scheduler sources: they retain their original callback count and only
 * consume any older pending scheduler amount before the original zero-amount callback.
 */
public final class CoreMarketEventSynchronization implements StructuredPatch<CoreMarketEventSynchronization.Plan> {
  private enum ReceiverShape {
    LOCAL, THIS_FIELD
  }

  private record CoreMarketCallSpec(String methodName, String methodDesc, ReceiverShape receiverShape,
      int receiverLocal) {
  }

  private record Site(MethodNode method, MethodInsnNode call) {
  }

  record Plan(ClassNode node, List<CoreMarketCallSpec> specs, List<Site> sites) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    List<CoreMarketCallSpec> specs;
    switch (node.name) {
      case PLANET_SURVEY_PANEL ->
        specs = List.of(new CoreMarketCallSpec("new", "(Ljava/lang/String;Z)V", ReceiverShape.LOCAL, 4));
      case LUDDIC_PATH_BASE_INTEL, PIRATE_BASE_INTEL ->
        specs = List.of(new CoreMarketCallSpec("notifyEnding", "()V", ReceiverShape.THIS_FIELD, -1));
      case DECIV_TRACKER -> specs = List.of(
          new CoreMarketCallSpec("decivilize", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;ZZ)V",
              ReceiverShape.LOCAL, 0),
          new CoreMarketCallSpec("removeColony", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;Z)V",
              ReceiverShape.LOCAL, 0));
      case PK_CMD -> specs = List.of(new CoreMarketCallSpec("convertSentinelToColony",
          "(Lcom/fs/starfarer/api/campaign/TextPanelAPI;" + "Lcom/fs/starfarer/api/campaign/CargoAPI;)Z",
          ReceiverShape.LOCAL, 4));
      default -> {
        return Match.incompatible("unsupported core market event owner " + node.name);
      }
    }

    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String hookDesc = "(L" + market + ";FI)V";
    int originalTotal = 0;
    int hookTotal = 0;
    for (CoreMarketCallSpec spec : specs) {
      MethodNode method = requireMethod(node, spec.methodName, spec.methodDesc);
      List<MethodInsnNode> originals = calls(method, Opcodes.INVOKEINTERFACE, market, "advance", "(F)V");
      List<MethodInsnNode> hooks = calls(method, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketSynchronized", hookDesc);
      originalTotal += originals.size();
      hookTotal += hooks.size();
    }

    boolean originalState = originalTotal == specs.size() && hookTotal == 0;
    boolean patchedState = originalTotal == 0 && hookTotal == specs.size();
    if (!originalState && !patchedState) {
      return Match.incompatible(node.name + " core market event calls are missing, duplicated, or partial");
    }

    List<Site> sites = new ArrayList<>();
    for (CoreMarketCallSpec spec : specs) {
      MethodNode method = requireMethod(node, spec.methodName, spec.methodDesc);
      MethodInsnNode call =
          (patchedState ? calls(method, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketSynchronized", hookDesc)
              : calls(method, Opcodes.INVOKEINTERFACE, market, "advance", "(F)V")).get(0);
      requireCoreMarketEventOperands(node, call, spec, patchedState);
      sites.add(new Site(method, call));
    }

    if (patchedState) {
      return Match.alreadyApplied(node.name + " core market event synchronization postcondition matches");
    }
    return Match.plan(new Plan(node, specs, sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String hookDesc = "(L" + market + ";FI)V";
    for (Site site : plan.sites()) {
      site.method().instructions.insertBefore(site.call(), new InsnNode(Opcodes.ICONST_2));
      site.method().instructions.set(site.call(),
          new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "advanceMarketSynchronized", hookDesc, false));
    }
    PatchReport report = new PatchReport();
    report.add("core market event calls synchronized with scheduler debt", plan.specs().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    String market = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    String hookDesc = "(L" + market + ";FI)V";
    requireCount(node.name + " synchronized core event calls",
        countCalls(node, Opcodes.INVOKESTATIC, HOOKS, "advanceMarketSynchronized", hookDesc), plan.specs().size());
    requireCount(node.name + " retired direct core Market.advance calls",
        countCalls(node, Opcodes.INVOKEINTERFACE, market, "advance", "(F)V"), 0);
  }

  private static void requireCoreMarketEventOperands(ClassNode node, MethodInsnNode call, CoreMarketCallSpec spec,
      boolean patched) {
    AbstractInsnNode source = patched ? previousMeaningful(call) : null;
    if (patched && !isIntegerLdc(source, 2)) {
      throw mismatch(node.name + "." + spec.methodName + " synchronized source is not CORE_EVENT");
    }
    AbstractInsnNode amount = previousMeaningful(patched ? source : call);
    if (amount == null || amount.getOpcode() != Opcodes.FCONST_0) {
      throw mismatch(node.name + "." + spec.methodName + " core market event amount is no longer constant zero");
    }
    AbstractInsnNode receiver = previousMeaningful(amount);
    if (spec.receiverShape == ReceiverShape.LOCAL) {
      if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
          || load.var != spec.receiverLocal) {
        throw mismatch(node.name + "." + spec.methodName + " core market event receiver local changed");
      }
      return;
    }
    if (!(receiver instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(node.name) || !field.desc.equals("Lcom/fs/starfarer/api/campaign/econ/MarketAPI;")) {
      throw mismatch(node.name + "." + spec.methodName + " core market event receiver field changed");
    }
    AbstractInsnNode owner = previousMeaningful(field);
    if (!(owner instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
      throw mismatch(node.name + "." + spec.methodName + " core market event receiver owner changed");
    }
  }
}
