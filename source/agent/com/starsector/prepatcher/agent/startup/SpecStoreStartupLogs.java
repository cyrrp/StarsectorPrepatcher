package com.starsector.prepatcher.agent.startup;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

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
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;
import com.starsector.prepatcher.agent.patch.StructuredPatch;


public final class SpecStoreStartupLogs implements StructuredPatch<SpecStoreStartupLogs.Plan> {
  private record Site(MethodNode method, MethodInsnNode call) {
  }

  record Plan(List<Site> sites) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    List<Site> sites = new ArrayList<>();
    for (MethodNode method : node.methods) {
      for (MethodInsnNode call : calls(method, Opcodes.INVOKEVIRTUAL, "org/apache/log4j/Logger", "info",
          "(Ljava/lang/Object;)V")) {
        sites.add(new Site(method, call));
      }
    }
    int hooks = countCategoryHooks(node, "suppressStartupInfo", "(Ljava/lang/Object;Ljava/lang/Object;I)V", 4);
    if (sites.isEmpty() && hooks == 53) {
      return Match.alreadyApplied("SpecStore INFO suppression postcondition matches");
    }
    requireCount("SpecStore Logger.info sites", sites.size(), 53);
    requireCount("SpecStore suppression hooks", hooks, 0);
    return Match.plan(new Plan(sites));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    for (Site site : plan.sites()) {
      // The logger receiver and already-built message remain on the stack.
      // Add only the category argument immediately before the replacement call.
      site.method().instructions.insertBefore(site.call(), new LdcInsnNode(4));
      site.call().owner = HOOKS;
      site.call().name = "suppressStartupInfo";
      site.call().desc = "(Ljava/lang/Object;Ljava/lang/Object;I)V";
      site.call().setOpcode(Opcodes.INVOKESTATIC);
      site.call().itf = false;
    }
    PatchReport report = new PatchReport();
    report.add("specInfoCalls", plan.sites().size());
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("SpecStore suppression hooks",
        countCategoryHooks(node, "suppressStartupInfo", "(Ljava/lang/Object;Ljava/lang/Object;I)V", 4), 53);
  }
}

