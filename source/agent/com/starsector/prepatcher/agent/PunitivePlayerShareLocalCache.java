package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;

import static com.starsector.prepatcher.agent.ClassIO.*;
import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Flow.*;
import static com.starsector.prepatcher.agent.Mutations.*;
import static com.starsector.prepatcher.agent.OwnedTemplates.*;
import static com.starsector.prepatcher.agent.Queries.*;

/**
 * Caches the player faction's market share in a method-local IdentityHashMap
 * inside getExpeditionReasons, so the competitive and free-port checks reuse
 * the per-faction lookup instead of calling back into CommodityMarketData.
 */
final class PunitivePlayerShareLocalCache implements StructuredPatch<PunitivePlayerShareLocalCache.Plan> {
  private static final String REASONS_DESC =
      "(Lcom/fs/starfarer/api/impl/campaign/intel/punitive/" + "PunitiveExpeditionManager$PunExData;)Ljava/util/List;";
  private static final String TEMPLATE_OWNER = "com/starsector/prepatcher/agent/templates/PunitivePlayerShareTemplate";
  private static final String TEMPLATE_RESOURCE = "/" + TEMPLATE_OWNER + ".class";
  private static final String HELPER_METHOD = "spp$punitiveCachedPlayerShare";
  private static final String HELPER_DESC = "(Lcom/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI;"
      + "Lcom/fs/starfarer/api/campaign/FactionAPI;Ljava/util/Map;" + "Ljava/util/IdentityHashMap;)I";
  private static final String COMMODITY_MARKET_DATA_API = "com/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI";
  private static final String COMMODITY_MARKET_DATA = "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
  private static final String FACTION_API = "com/fs/starfarer/api/campaign/FactionAPI";
  private static final String SECTOR_API = "com/fs/starfarer/api/campaign/SectorAPI";
  private static final String MARKET_SHARE_METHOD_DESC = "()Ljava/util/Map;";
  private static final String MARKET_SHARE_RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherMarketShareRuntime";
  private static final String MARKET_SHARE_ELIGIBILITY_METHOD = "isEligible";
  private static final String MARKET_SHARE_ELIGIBILITY_DESC = "(Ljava/lang/Object;)Z";

  record PunitiveShareSites(MethodInsnNode perFactionCall, List<MethodInsnNode> directCalls, int sharesLocal,
      int playerLocal) {
  }

  record Plan(String owner, List<MethodNode> classMethods, int methodIndex, MethodNode expanded,
      PunitiveShareSites sites, MethodNode installedHelper) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode original = requireMethod(node, "getExpeditionReasons", REASONS_DESC);
    List<MethodNode> helpers = methods(node, HELPER_METHOD, HELPER_DESC);
    if (!helpers.isEmpty()) {
      requireCount("punitive player-share helper", helpers.size(), 1);
      requirePostcondition(node, original, helpers.get(0));
      return Match.alreadyApplied("punitive local player-share cache postcondition matches");
    }

    PunitiveShareSites originalSites = requireOriginalShareSites(node.name, original);
    ClassNode expandedClass = readClassExpanded(writeClass(node));
    MethodNode expanded = requireMethod(expandedClass, "getExpeditionReasons", REASONS_DESC);
    PunitiveShareSites sites = requireOriginalShareSites(node.name, expanded);
    if (sites.sharesLocal() != originalSites.sharesLocal() || sites.playerLocal() != originalSites.playerLocal()) {
      return Match.incompatible("punitive player-share locals changed while expanding frames");
    }

    int methodIndex = node.methods.indexOf(original);
    if (methodIndex < 0) return Match.incompatible("punitive method replacement index missing");

    ClassNode template = loadOwnedTemplate(TEMPLATE_OWNER, TEMPLATE_RESOURCE, "punitive player-share cache");
    MethodNode templateHelper = requireMethod(template, HELPER_METHOD, HELPER_DESC);
    MethodNode installedHelper =
        cloneAndRemapOwnedTemplateMethod(templateHelper, TEMPLATE_OWNER, node.name, "punitive player-share cache");
    return Match.plan(new Plan(node.name, node.methods, methodIndex, expanded, sites, installedHelper));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode expanded = plan.expanded();
    PunitiveShareSites sites = plan.sites();
    int cacheLocal = expanded.maxLocals;
    appendInitializedLocalToExpandedFrames(expanded, cacheLocal, "java/util/IdentityHashMap");
    InsnList initialization = new InsnList();
    initialization.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
    initialization.add(new InsnNode(Opcodes.DUP));
    initialization.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false));
    initialization.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
    AbstractInsnNode entry = expanded.instructions.getFirst();
    if (entry == null) throw mismatch("punitive getExpeditionReasons has no code");
    expanded.instructions.insertBefore(entry, initialization);
    expanded.maxLocals = cacheLocal + 1;

    MethodInsnNode competitive = sites.directCalls().get(0);
    InsnList competitiveArgs = new InsnList();
    competitiveArgs.add(new VarInsnNode(Opcodes.ALOAD, sites.sharesLocal()));
    competitiveArgs.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
    expanded.instructions.insertBefore(competitive, competitiveArgs);
    makeStatic(competitive, plan.owner(), HELPER_METHOD, HELPER_DESC);

    MethodInsnNode freePort = sites.directCalls().get(1);
    InsnList freePortArgs = new InsnList();
    freePortArgs.add(new InsnNode(Opcodes.ACONST_NULL));
    freePortArgs.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
    expanded.instructions.insertBefore(freePort, freePortArgs);
    makeStatic(freePort, plan.owner(), HELPER_METHOD, HELPER_DESC);

    plan.classMethods().set(plan.methodIndex(), expanded);
    MethodNode installedHelper = plan.installedHelper();
    installedHelper.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    plan.classMethods().add(installedHelper);

    PatchReport report = new PatchReport();
    report.add("punitive competitive player-share reuse", 1);
    report.add("punitive free-port local identity cache", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePostcondition(node, plan.expanded(), plan.installedHelper());
  }

  private static PunitiveShareSites requireOriginalShareSites(String owner, MethodNode method) {
    if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions == null
        || method.instructions.size() == 0) {
      throw mismatch("punitive getExpeditionReasons has no concrete code");
    }
    MethodInsnNode perFaction = only(calls(method, Opcodes.INVOKEINTERFACE, COMMODITY_MARKET_DATA_API,
        "getMarketSharePercentPerFaction", MARKET_SHARE_METHOD_DESC), "punitive per-faction share call");
    List<MethodInsnNode> direct = calls(method, Opcodes.INVOKEINTERFACE, COMMODITY_MARKET_DATA_API,
        "getMarketSharePercent", "(L" + FACTION_API + ";)I");
    requireCount("punitive direct player-share calls", direct.size(), 2);
    direct
        .sort((left, right) -> Integer.compare(method.instructions.indexOf(left), method.instructions.indexOf(right)));
    int perFactionIndex = method.instructions.indexOf(perFaction);
    if (perFactionIndex >= method.instructions.indexOf(direct.get(0))
        || method.instructions.indexOf(direct.get(0)) >= method.instructions.indexOf(direct.get(1))) {
      throw mismatch("punitive market-share call order changed");
    }

    AbstractInsnNode sharesStoreInsn = nextMeaningful(perFaction);
    if (!(sharesStoreInsn instanceof VarInsnNode sharesStore) || sharesStore.getOpcode() != Opcodes.ASTORE) {
      throw mismatch("punitive per-faction shares are not stored in one local");
    }
    int sharesLocal = sharesStore.var;

    Frame<SourceValue>[] frames = sourceFrames(owner, method);
    int perFactionDataLocal = requireLoadedLocal(receiverSource(method, perFaction, frames), Opcodes.ALOAD,
        "punitive per-faction data receiver");
    int firstDataLocal = requireLoadedLocal(receiverSource(method, direct.get(0), frames), Opcodes.ALOAD,
        "punitive competitive data receiver");
    if (firstDataLocal != perFactionDataLocal) {
      throw mismatch("punitive competitive share does not reuse per-faction data object");
    }
    int playerLocal = requireLoadedLocal(argumentSource(method, direct.get(0), frames, 0), Opcodes.ALOAD,
        "punitive competitive player faction");
    int freePortPlayerLocal = requireLoadedLocal(argumentSource(method, direct.get(1), frames, 0), Opcodes.ALOAD,
        "punitive free-port player faction");
    if (playerLocal != freePortPlayerLocal) {
      throw mismatch("punitive player-share calls use different player locals");
    }

    List<MethodInsnNode> playerGetters =
        calls(method, Opcodes.INVOKEINTERFACE, SECTOR_API, "getPlayerFaction", "()L" + FACTION_API + ";");
    int matchingPlayerStores = 0;
    for (MethodInsnNode getter : playerGetters) {
      AbstractInsnNode next = nextMeaningful(getter);
      if (next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == playerLocal) {
        matchingPlayerStores++;
      }
    }
    requireCount("punitive player-faction local initialization", matchingPlayerStores, 1);
    return new PunitiveShareSites(perFaction, direct, sharesLocal, playerLocal);
  }

  private static void requirePostcondition(ClassNode node, MethodNode method, MethodNode helper) {
    if ((helper.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != (Opcodes.ACC_PRIVATE
        | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)
        || (helper.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
      throw mismatch("punitive player-share helper metadata changed");
    }
    requireCount("punitive original player-share calls", countCalls(method, Opcodes.INVOKEINTERFACE,
        COMMODITY_MARKET_DATA_API, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 0);
    requireCount("punitive cached player-share calls",
        countCalls(method, Opcodes.INVOKESTATIC, node.name, HELPER_METHOD, HELPER_DESC), 2);
    requireCount("punitive per-faction share call", countCalls(method, Opcodes.INVOKEINTERFACE,
        COMMODITY_MARKET_DATA_API, "getMarketSharePercentPerFaction", MARKET_SHARE_METHOD_DESC), 1);
    requireCount("punitive local IdentityHashMap allocation",
        countTypeInstructions(method, Opcodes.NEW, "java/util/IdentityHashMap"), 1);
    requireCount("punitive local IdentityHashMap construction",
        countCalls(method, Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V"), 1);
    requireCount("punitive helper runtime eligibility guard", countCalls(helper, Opcodes.INVOKESTATIC,
        MARKET_SHARE_RUNTIME, MARKET_SHARE_ELIGIBILITY_METHOD, MARKET_SHARE_ELIGIBILITY_DESC), 1);
    requireCount("punitive helper concrete-class literals", countClassLiterals(helper, COMMODITY_MARKET_DATA), 0);
    requireCount("punitive helper direct fallback calls", countCalls(helper, Opcodes.INVOKEINTERFACE,
        COMMODITY_MARKET_DATA_API, "getMarketSharePercent", "(L" + FACTION_API + ";)I"), 2);
    requireCount("punitive helper static fields", countRetainedFields(node), 0);
    requireNoOwnedTemplateReferences(node, TEMPLATE_OWNER, "punitive player-share cache");
  }

  private static int countRetainedFields(ClassNode node) {
    int count = 0;
    for (FieldNode field : node.fields) {
      if ((field.access & Opcodes.ACC_STATIC) != 0 && field.name.startsWith("spp$punitive")) count++;
    }
    return count;
  }
}
