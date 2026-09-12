package com.starsector.prepatcher.agent.economy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;
import static com.starsector.prepatcher.agent.bytecode.Mutations.*;
import static com.starsector.prepatcher.agent.bytecode.Names.*;
import static com.starsector.prepatcher.agent.PrepatcherTransformer.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import com.starsector.prepatcher.agent.patch.StructuredPatch;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.patch.PatchReport;

public final class LocalResourcesColdMarketDataElision
    implements StructuredPatch<LocalResourcesColdMarketDataElision.Plan> {
  private static final String COMMODITY_API = "com/fs/starfarer/api/campaign/econ/CommodityOnMarketAPI";
  private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
  private static final String MARKET_DATA_API = "com/fs/starfarer/api/campaign/econ/CommodityMarketDataAPI";
  private static final String SHARE_DATA_API = "com/fs/starfarer/api/campaign/econ/MarketShareDataAPI";
  private static final String CONCRETE_DATA = "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
  private static final String COMMODITY_DESC = "L" + COMMODITY_API + ";";
  private static final String MARKET_DESC = "L" + MARKET_API + ";";
  private static final String GETTER_DESC = "()L" + MARKET_DATA_API + ";";
  private static final String SHIPPING_DESC = "(" + MARKET_DESC + "Z)I";
  private static final String SHOULD_HOOK_DESC = "(" + MARKET_DESC + COMMODITY_DESC + ")Z";

  record Plan(ClassNode node, MethodNode stockpile, MethodNode shouldHave, AbstractInsnNode getterReceiver,
      MethodInsnNode getter, MethodInsnNode shipping, FieldInsnNode marketField) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    MethodNode stockpile = requireMethod(node, "getStockpileLimit", "(" + COMMODITY_DESC + ")I");
    MethodNode shouldHave = requireMethod(node, "shouldHaveCommodity", "(" + COMMODITY_DESC + ")Z");

    int stockGetter =
        countCalls(stockpile, Opcodes.INVOKEINTERFACE, COMMODITY_API, "getCommodityMarketData", GETTER_DESC);
    int stockInterfaceShipping =
        countCalls(stockpile, Opcodes.INVOKEINTERFACE, MARKET_DATA_API, "getMaxShipping", SHIPPING_DESC);
    int stockStaticShipping =
        countCalls(stockpile, Opcodes.INVOKESTATIC, CONCRETE_DATA, "getShippingCapacity", SHIPPING_DESC);
    int shouldGetter =
        countCalls(shouldHave, Opcodes.INVOKEINTERFACE, COMMODITY_API, "getCommodityMarketData", GETTER_DESC);
    int shouldHook =
        countCalls(shouldHave, Opcodes.INVOKESTATIC, HOOKS, "localResourcesShouldHaveCommodity", SHOULD_HOOK_DESC);

    boolean patched = stockGetter == 0 && stockInterfaceShipping == 0 && stockStaticShipping == 1 && shouldGetter == 0
        && shouldHook == 1;
    if (patched) {
      requireLocalResourcesShouldWrapper(node.name, shouldHave, SHOULD_HOOK_DESC);
      return Match.alreadyApplied("Local Resources cold CommodityMarketData paths are removed");
    }
    if (stockStaticShipping != 0 || shouldHook != 0) {
      return Match.incompatible("Local Resources has a partial/foreign cold-data rewrite");
    }

    requireCount("Local Resources stockpile CommodityMarketData getter", stockGetter, 1);
    requireCount("Local Resources stockpile getMaxShipping", stockInterfaceShipping, 1);
    requireCount("Local Resources should-have CommodityMarketData getter", shouldGetter, 1);
    requireCount("Local Resources should-have Market.isIllegal",
        countCalls(shouldHave, Opcodes.INVOKEINTERFACE, MARKET_API, "isIllegal", "(" + COMMODITY_DESC + ")Z"), 1);
    requireCount("Local Resources should-have getMarketShareData", countCalls(shouldHave, Opcodes.INVOKEINTERFACE,
        MARKET_DATA_API, "getMarketShareData", "(" + MARKET_DESC + ")L" + SHARE_DATA_API + ";"), 1);
    requireCount("Local Resources should-have isSourceIsIllegal",
        countCalls(shouldHave, Opcodes.INVOKEINTERFACE, SHARE_DATA_API, "isSourceIsIllegal", "()Z"), 1);

    MethodInsnNode getter =
        only(calls(stockpile, Opcodes.INVOKEINTERFACE, COMMODITY_API, "getCommodityMarketData", GETTER_DESC),
            "Local Resources stockpile CommodityMarketData getter");
    MethodInsnNode shipping =
        only(calls(stockpile, Opcodes.INVOKEINTERFACE, MARKET_DATA_API, "getMaxShipping", SHIPPING_DESC),
            "Local Resources stockpile getMaxShipping");
    AbstractInsnNode getterReceiver = previousMeaningful(getter);
    AbstractInsnNode marketLoad = nextMeaningful(getter);
    AbstractInsnNode marketGetterInsn = nextMeaningful(marketLoad);
    AbstractInsnNode sameFaction = nextMeaningful(marketGetterInsn);
    if (!(getterReceiver instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 1
        || !(marketLoad instanceof VarInsnNode marketOwner) || marketOwner.getOpcode() != Opcodes.ALOAD
        || marketOwner.var != 1 || !(marketGetterInsn instanceof MethodInsnNode marketGetter)
        || !callMatches(marketGetter, Opcodes.INVOKEINTERFACE, COMMODITY_API, "getMarket", "()" + MARKET_DESC)
        || sameFaction == null || sameFaction.getOpcode() != Opcodes.ICONST_0
        || nextMeaningful(sameFaction) != shipping) {
      return Match.incompatible("Local Resources stockpile shipping operand sequence changed");
    }

    List<FieldInsnNode> marketFields = new ArrayList<>();
    for (AbstractInsnNode insn : shouldHave.instructions.toArray()) {
      if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
          && field.desc.equals(MARKET_DESC)) {
        marketFields.add(field);
      }
    }
    requireCount("Local Resources should-have market field reads", marketFields.size(), 2);
    FieldInsnNode marketField = marketFields.get(0);
    for (FieldInsnNode candidate : marketFields) {
      if (!candidate.owner.equals(marketField.owner) || !candidate.name.equals(marketField.name)) {
        return Match.incompatible("Local Resources should-have market field identity changed");
      }
    }
    return Match.plan(new Plan(node, stockpile, shouldHave, getterReceiver, getter, shipping, marketField));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    plan.stockpile().instructions.remove(plan.getterReceiver());
    plan.stockpile().instructions.remove(plan.getter());
    plan.stockpile().instructions.set(plan.shipping(),
        new MethodInsnNode(Opcodes.INVOKESTATIC, CONCRETE_DATA, "getShippingCapacity", SHIPPING_DESC, false));

    FieldInsnNode marketField = plan.marketField();
    InsnList shouldCode = new InsnList();
    shouldCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
    shouldCode.add(new FieldInsnNode(Opcodes.GETFIELD, marketField.owner, marketField.name, marketField.desc));
    shouldCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
    shouldCode.add(
        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "localResourcesShouldHaveCommodity", SHOULD_HOOK_DESC, false));
    shouldCode.add(new InsnNode(Opcodes.IRETURN));
    replaceMethodBody(plan.shouldHave(), shouldCode, 2);

    PatchReport report = new PatchReport();
    report.add("static Local Resources shipping capacity", 1);
    report.add("cold-safe Local Resources legality predicate", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requireCount("Local Resources patched stockpile getter",
        countCalls(plan.stockpile(), Opcodes.INVOKEINTERFACE, COMMODITY_API, "getCommodityMarketData", GETTER_DESC), 0);
    requireCount("Local Resources patched stockpile static shipping",
        countCalls(plan.stockpile(), Opcodes.INVOKESTATIC, CONCRETE_DATA, "getShippingCapacity", SHIPPING_DESC), 1);
    requireCount("Local Resources patched should-have getter",
        countCalls(plan.shouldHave(), Opcodes.INVOKEINTERFACE, COMMODITY_API, "getCommodityMarketData", GETTER_DESC),
        0);
    requireLocalResourcesShouldWrapper(node.name, plan.shouldHave(), SHOULD_HOOK_DESC);
  }

  private static void requireLocalResourcesShouldWrapper(String owner, MethodNode method, String hookDesc) {
    List<AbstractInsnNode> code = meaningfulInstructions(method);
    requireCount("Local Resources should-have wrapper instruction count", code.size(), 5);
    requireVar(code.get(0), Opcodes.ALOAD, 0, "Local Resources wrapper owner");
    if (!(code.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
        || !field.owner.equals(owner) || !field.desc.equals("Lcom/fs/starfarer/api/campaign/econ/MarketAPI;")) {
      throw mismatch("Local Resources should-have wrapper market field changed");
    }
    requireVar(code.get(2), Opcodes.ALOAD, 1, "Local Resources wrapper commodity");
    requireCall(asMethod(code.get(3), "Local Resources wrapper hook"), Opcodes.INVOKESTATIC, HOOKS,
        "localResourcesShouldHaveCommodity", hookDesc, "Local Resources wrapper hook");
    if (code.get(4).getOpcode() != Opcodes.IRETURN) {
      throw mismatch("Local Resources should-have wrapper return changed");
    }
  }
}
