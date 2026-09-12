package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Replaces CommodityMarketData.getMarketSharePercentPerFaction's per-faction
 * loop with a single linear pass, keeping the original method as the raw
 * private fallback behind the market-share runtime eligibility guard.
 */
final class MarketShareLinearAggregation implements StructuredPatch<MarketShareLinearAggregation.Plan> {
  private static final String RAW_METHOD = "spp$commodityMarketDataRawMarketSharePerFaction";
  private static final String METHOD_DESC = "()Ljava/util/Map;";
  private static final String TEMPLATE_OWNER = "com/starsector/prepatcher/agent/templates/CommodityMarketShareTemplate";
  private static final String TEMPLATE_RESOURCE = "/" + TEMPLATE_OWNER + ".class";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherMarketShareRuntime";
  private static final String ELIGIBILITY_METHOD = "isEligible";
  private static final String ELIGIBILITY_DESC = "(Ljava/lang/Object;)Z";
  private static final String FACTION_API = "com/fs/starfarer/api/campaign/FactionAPI";

  record Plan(List<MethodNode> classMethods, MethodNode wrapper, MethodNode installed, int originalAccess,
      String originalSignature, String[] originalExceptions) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode wrapper = requireMethod(node, "getMarketSharePercentPerFaction", METHOD_DESC);
    List<MethodNode> rawMethods = methods(node, RAW_METHOD, METHOD_DESC);
    if (!rawMethods.isEmpty()) {
      requireCount("market-share raw method", rawMethods.size(), 1);
      requirePostcondition(node, wrapper, rawMethods.get(0));
      return Match.alreadyApplied("linear market-share aggregation postcondition matches");
    }

    if ((wrapper.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || wrapper.instructions == null
        || wrapper.instructions.size() == 0) {
      return Match.incompatible("CommodityMarketData.getMarketSharePercentPerFaction has no concrete code");
    }
    requireCount("original market-share getMarkets call",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name, "getMarkets", "()Ljava/util/List;"), 1);
    requireCount("original market-share single-faction call",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 1);
    requireCount("original market-share containsKey call",
        countCalls(wrapper, Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z"), 1);
    requireCount("original market-share Map.put call", countCalls(wrapper, Opcodes.INVOKEINTERFACE, "java/util/Map",
        "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), 1);
    requireCount("original market-share LinkedHashMap allocation",
        countTypeInstructions(wrapper, Opcodes.NEW, "java/util/LinkedHashMap"), 1);
    requireCount("original market-share export-share call", countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name,
        "getExportMarketSharePercent", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)I"), 0);

    ClassNode template = loadOwnedTemplate(TEMPLATE_OWNER, TEMPLATE_RESOURCE, "market-share aggregation");
    MethodNode templateWrapper = requireMethod(template, "getMarketSharePercentPerFaction", METHOD_DESC);
    MethodNode installed =
        cloneAndRemapOwnedTemplateMethod(templateWrapper, TEMPLATE_OWNER, node.name, "market-share aggregation");
    return Match
        .plan(new Plan(node.methods, wrapper, installed, wrapper.access, wrapper.signature, exceptions(wrapper)));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode wrapper = plan.wrapper();
    wrapper.name = RAW_METHOD;
    wrapper.access =
        (wrapper.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

    MethodNode installed = plan.installed();
    installed.access = plan.originalAccess();
    installed.signature = plan.originalSignature();
    installed.exceptions =
        plan.originalExceptions() == null ? null : new ArrayList<>(List.of(plan.originalExceptions()));
    plan.classMethods().add(installed);

    PatchReport report = new PatchReport();
    report.add("linear market-share aggregation", 1);
    report.add("preserved vanilla market-share fallback", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePostcondition(node, plan.installed(), plan.wrapper());
  }

  private static void requirePostcondition(ClassNode node, MethodNode wrapper, MethodNode raw) {
    if ((raw.access & Opcodes.ACC_PRIVATE) == 0 || (raw.access & Opcodes.ACC_SYNTHETIC) == 0
        || (raw.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
      throw mismatch("market-share raw method metadata changed");
    }
    requireCount("linear market-share raw fallback calls", countCalls(wrapper, -1, node.name, RAW_METHOD, METHOD_DESC),
        2);
    requireCount("linear market-share getMarkets call",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name, "getMarkets", "()Ljava/util/List;"), 1);
    requireCount("linear market-share export-share call", countCalls(wrapper, Opcodes.INVOKEVIRTUAL, node.name,
        "getExportMarketSharePercent", "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)I"), 1);
    requireCount("linear market-share single-faction calls",
        countCalls(wrapper, -1, node.name, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 0);
    requireCount("linear market-share runtime eligibility guard",
        countCalls(wrapper, Opcodes.INVOKESTATIC, RUNTIME, ELIGIBILITY_METHOD, ELIGIBILITY_DESC), 1);
    requireCount("linear market-share exact-class literals", countClassLiterals(wrapper, node.name), 0);
    requireCount("linear market-share direct getClass guards",
        countCalls(wrapper, Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;"), 0);
    requireCount("linear market-share LinkedHashMap allocation",
        countTypeInstructions(wrapper, Opcodes.NEW, "java/util/LinkedHashMap"), 1);
    requireCount("linear market-share local identity maps",
        countTypeInstructions(wrapper, Opcodes.NEW, "java/util/IdentityHashMap"), 2);
    requireNoOwnedTemplateReferences(node, TEMPLATE_OWNER, "market-share aggregation");
  }
}
