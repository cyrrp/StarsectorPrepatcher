package com.starsector.prepatcher.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.starsector.prepatcher.agent.Contract.*;

/**
 * Owns the exact temporary-market branch shared by detached campaign Cargo
 * and generated-loot transfer panels (campaignCargoNoGlobalEconomyStep and
 * lootTransferNoGlobalEconomyStep share this one guarded site and marker).
 * The runtime guard handles only proven skip/local cases; unknown economy
 * implementations preserve tripleStep() as their global fallback.
 */
final class DetachedCargoEconomyStep implements StructuredPatch<DetachedCargoEconomyStep.Plan> {
  static final String TARGET = "com/fs/starfarer/campaign/ui/class";
  private static final String CTOR_DESC =
      "(Lcom/fs/starfarer/campaign/ui/class$Oo;" + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;"
          + "Lcom/fs/starfarer/campaign/command/OutpostListPanel$Oo;" + "Lcom/fs/starfarer/campaign/fleet/CargoData;"
          + "Ljava/lang/String;Lcom/fs/starfarer/coreui/_$o;" + "Lcom/fs/starfarer/ui/U;)V";
  private static final String ENTITY_DESC = "()Lcom/fs/starfarer/api/campaign/SectorEntityToken;";
  private static final String RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge";
  private static final String SHOULD_SKIP = "shouldSkipVanillaCargoEconomyStep";
  private static final String SHOULD_SKIP_DESC =
      "(Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/Object;" + "Ljava/lang/Object;)Z";
  private static final String MARKER = "spp$patched$aotdDetachedCargoContext";
  private static final String MARKER_VALUE = "StarsectorPrepatcher:cargo-ui-economy-explicit-guard-v5";

  record Plan(MethodNode constructor, int detachedLocal, FieldInsnNode marketField, List<FieldNode> classFields) {
  }

  @Override
  public Match<Plan> match(ClassNode node) {
    FieldNode marker = field(node, MARKER);
    if (marker != null) {
      requireMarker(marker);
      requirePatchedShape(node);
      return Match.alreadyApplied("detached cargo context postcondition matches");
    }
    MethodNode factory = requireFakeMarketFactory(node);
    MethodNode constructor = requireMethod(node, "<init>", CTOR_DESC);
    requireOriginalShape(node, constructor, factory);
    return Match.plan(new Plan(constructor, deriveDetachedLocal(constructor, node.name, factory),
        deriveUiMarketField(constructor, node.name), node.fields));
  }

  @Override
  public PatchReport mutate(Plan plan) {
    MethodNode constructor = plan.constructor();
    MethodInsnNode originalCall = only(calls(constructor, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/EconomyAPI", "tripleStep", "()V"), "EconomyAPI.tripleStep");
    int economyLocal = constructor.maxLocals;
    constructor.maxLocals = economyLocal + 1;

    LabelNode done = new LabelNode();
    InsnList guard = new InsnList();
    // The EconomyAPI receiver is already on the operand stack.
    guard.add(new VarInsnNode(Opcodes.ASTORE, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
    guard.add(new FieldInsnNode(Opcodes.GETFIELD, plan.marketField().owner, plan.marketField().name,
        plan.marketField().desc));
    guard.add(new VarInsnNode(Opcodes.ILOAD, plan.detachedLocal()));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 3));
    guard.add(new VarInsnNode(Opcodes.ALOAD, 4));
    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, SHOULD_SKIP, SHOULD_SKIP_DESC, false));
    guard.add(new JumpInsnNode(Opcodes.IFNE, done));
    guard.add(new VarInsnNode(Opcodes.ALOAD, economyLocal));

    constructor.instructions.insertBefore(originalCall, guard);
    constructor.instructions.insert(originalCall, done);

    plan.classFields()
        .add(new FieldNode(Opcodes.ASM8,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, MARKER,
            "Ljava/lang/String;", null, MARKER_VALUE));
    PatchReport report = new PatchReport();
    report.add("detached cargo economy-step guard", 1);
    return report;
  }

  @Override
  public void verify(Plan plan, ClassNode node) {
    requirePatchedShape(node);
  }

  private static void requireOriginalShape(ClassNode node, MethodNode constructor, MethodNode factory) {
    if ((constructor.access & Opcodes.ACC_PUBLIC) == 0
        || (constructor.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      throw mismatch("Cargo panel constructor access changed");
    }
    if (constructor.tryCatchBlocks != null && !constructor.tryCatchBlocks.isEmpty()) {
      throw mismatch("Cargo panel constructor gained exception regions");
    }
    requireCount("fake-market factory calls",
        countCalls(constructor, Opcodes.INVOKEVIRTUAL, node.name, factory.name, factory.desc), 2);
    requireCount("EconomyAPI.tripleStep calls", countCalls(constructor, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/EconomyAPI", "tripleStep", "()V"), 1);
    requireCount("vanilla detached-Cargo skip guards",
        countCalls(constructor, Opcodes.INVOKESTATIC, RUNTIME, SHOULD_SKIP, SHOULD_SKIP_DESC), 0);
    deriveDetachedLocal(constructor, node.name, factory);
    deriveUiMarketField(constructor, node.name);
  }

  private static void requirePatchedShape(ClassNode node) {
    requireMarker(field(node, MARKER));
    MethodNode factory = requireFakeMarketFactory(node);
    MethodNode constructor = requireMethod(node, "<init>", CTOR_DESC);
    int detachedLocal = deriveDetachedLocal(constructor, node.name, factory);
    FieldInsnNode marketField = deriveUiMarketField(constructor, node.name);
    requireCount("patched EconomyAPI.tripleStep calls", countCalls(constructor, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/EconomyAPI", "tripleStep", "()V"), 1);
    requireCount("patched vanilla detached-Cargo skip guard",
        countCalls(constructor, Opcodes.INVOKESTATIC, RUNTIME, SHOULD_SKIP, SHOULD_SKIP_DESC), 1);
    requireCount("patched exception regions", constructor.tryCatchBlocks.size(), 0);
    MethodInsnNode guard = only(calls(constructor, Opcodes.INVOKESTATIC, RUNTIME, SHOULD_SKIP, SHOULD_SKIP_DESC),
        "vanilla detached-Cargo skip guard");
    AbstractInsnNode guardBranch = nextMeaningful(guard);
    if (!(guardBranch instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNE) {
      throw mismatch("vanilla detached-Cargo skip guard no longer branches around tripleStep");
    }
    AbstractInsnNode guardOtherCargo = previousMeaningful(guard);
    AbstractInsnNode guardOutpost = previousMeaningful(guardOtherCargo);
    AbstractInsnNode guardMode = previousMeaningful(guardOutpost);
    AbstractInsnNode guardDetached = previousMeaningful(guardMode);
    AbstractInsnNode guardMarket = previousMeaningful(guardDetached);
    AbstractInsnNode guardMarketOwner = previousMeaningful(guardMarket);
    AbstractInsnNode guardEconomy = previousMeaningful(guardMarketOwner);
    requireLoad(guardOtherCargo, Opcodes.ALOAD, 4, "vanilla skip guard other cargo");
    requireLoad(guardOutpost, Opcodes.ALOAD, 3, "vanilla skip guard outpost");
    requireLoad(guardMode, Opcodes.ALOAD, 1, "vanilla skip guard mode");
    requireLoad(guardDetached, Opcodes.ILOAD, detachedLocal, "vanilla skip guard proven branch");
    if (!(guardMarket instanceof FieldInsnNode fieldRead) || fieldRead.getOpcode() != Opcodes.GETFIELD
        || !marketField.owner.equals(fieldRead.owner) || !marketField.name.equals(fieldRead.name)
        || !marketField.desc.equals(fieldRead.desc)) {
      throw mismatch("vanilla skip guard market field changed");
    }
    requireLoad(guardMarketOwner, Opcodes.ALOAD, 0, "vanilla skip guard market owner");
    if (!(guardEconomy instanceof VarInsnNode economyLoad) || economyLoad.getOpcode() != Opcodes.ALOAD) {
      throw mismatch("vanilla skip guard economy receiver changed");
    }

    MethodInsnNode fallback = only(calls(constructor, Opcodes.INVOKEINTERFACE,
        "com/fs/starfarer/api/campaign/econ/EconomyAPI", "tripleStep", "()V"), "guarded EconomyAPI.tripleStep");
    AbstractInsnNode fallbackReceiver = previousMeaningful(fallback);
    if (!(fallbackReceiver instanceof VarInsnNode receiverLoad) || receiverLoad.getOpcode() != Opcodes.ALOAD
        || !(guardEconomy instanceof VarInsnNode guardedEconomy) || receiverLoad.var != guardedEconomy.var) {
      throw mismatch("global fallback economy receiver changed");
    }
    if (instructionIndex(constructor, guardBranch) >= instructionIndex(constructor, fallback)
        || instructionIndex(constructor, fallback) >= instructionIndex(constructor,
            ((JumpInsnNode) guardBranch).label)) {
      throw mismatch("skip guard does not enclose only tripleStep fallback");
    }
  }

  private static int instructionIndex(MethodNode method, AbstractInsnNode target) {
    int index = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction == target) return index;
      index++;
    }
    return -1;
  }

  private static FieldInsnNode deriveUiMarketField(MethodNode constructor, String owner) {
    MethodInsnNode conditionOnly =
        only(calls(constructor, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/econ/Market",
            "isPlanetConditionMarketOnly", "()Z"), "Market.isPlanetConditionMarketOnly");
    AbstractInsnNode field = previousMeaningful(conditionOnly);
    AbstractInsnNode receiver = previousMeaningful(field);
    if (!(field instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETFIELD || !owner.equals(read.owner)
        || !"Lcom/fs/starfarer/campaign/econ/Market;".equals(read.desc)) {
      throw mismatch("Cargo UI market field anchor changed");
    }
    requireLoad(receiver, Opcodes.ALOAD, 0, "Cargo UI market field receiver");
    return read;
  }

  private static MethodNode requireFakeMarketFactory(ClassNode node) {
    List<MethodNode> matches = new ArrayList<>();
    for (MethodNode method : node.methods) {
      if (!ENTITY_DESC.equals(method.desc) || (method.access & Opcodes.ACC_PRIVATE) == 0
          || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
        continue;
      }
      if (countNew(method, "com/fs/starfarer/campaign/CampaignOrbitalStation") == 1
          && countLdc(method, "fake_market") == 1 && countLdc(method, "storage") == 2
          && countCalls(method, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/FactoryAPI", "createMarket",
              "(Ljava/lang/String;Ljava/lang/String;I)" + "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;") == 1
          && countCalls(method, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/api/impl/campaign/submarkets/StoragePlugin",
              "setPlayerPaidToUnlock", "(Z)V") == 1
          && countCalls(method, Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/campaign/CampaignOrbitalStation", "setMarket",
              "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V") == 1) {
        matches.add(method);
      }
    }
    if (matches.size() != 1) { throw mismatch("fake_market factory expected=1 actual=" + matches.size()); }
    return matches.get(0);
  }

  private static int deriveDetachedLocal(MethodNode constructor, String owner, MethodNode factory) {
    List<MethodInsnNode> factoryCalls = calls(constructor, Opcodes.INVOKEVIRTUAL, owner, factory.name, factory.desc);
    requireCount("fake-market factory calls", factoryCalls.size(), 2);
    Integer local = null;
    for (MethodInsnNode call : factoryCalls) {
      int candidate = findFollowingTrueStore(call, 32);
      if (candidate < 0) { throw mismatch("fake-market call is not followed by a true branch store"); }
      if (local == null) local = candidate;
      else if (local.intValue() != candidate) { throw mismatch("fake-market calls use different branch locals"); }
    }
    if (local == null) throw mismatch("detached branch local missing");
    int zeroStores = 0;
    for (AbstractInsnNode instruction : constructor.instructions) {
      if (instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ISTORE
          && variable.var == local.intValue()) {
        AbstractInsnNode value = previousMeaningful(instruction);
        if (value != null && value.getOpcode() == Opcodes.ICONST_0) zeroStores++;
      }
    }
    requireCount("detached branch false initialization", zeroStores, 1);
    return local.intValue();
  }

  private static int findFollowingTrueStore(AbstractInsnNode start, int limit) {
    AbstractInsnNode current = start;
    for (int i = 0; i < limit && current != null; i++) {
      current = nextMeaningful(current);
      if (current instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ISTORE) {
        AbstractInsnNode value = previousMeaningful(current);
        if (value != null && value.getOpcode() == Opcodes.ICONST_1) { return variable.var; }
      }
    }
    return -1;
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

  private static void requireMarker(FieldNode marker) {
    if (marker == null
        || marker.access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)
        || !"Ljava/lang/String;".equals(marker.desc) || !MARKER_VALUE.equals(marker.value)) {
      throw mismatch("owned marker is missing or malformed");
    }
  }

  private static List<MethodInsnNode> calls(MethodNode method, int opcode, String owner, String name, String desc) {
    List<MethodInsnNode> result = new ArrayList<>();
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
          && name.equals(call.name) && desc.equals(call.desc)) {
        result.add(call);
      }
    }
    return result;
  }

  private static int countCalls(MethodNode method, int opcode, String owner, String name, String desc) {
    return calls(method, opcode, owner, name, desc).size();
  }

  private static int countNew(MethodNode method, String type) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof TypeInsnNode typed && typed.getOpcode() == Opcodes.NEW && type.equals(typed.desc))
        count++;
    }
    return count;
  }

  private static int countLdc(MethodNode method, Object value) {
    int count = 0;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof LdcInsnNode ldc && value.equals(ldc.cst)) count++;
    }
    return count;
  }

  private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
    AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
    while (current != null && (current.getOpcode() < 0)) current = current.getPrevious();
    return current;
  }

  private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
    AbstractInsnNode current = instruction == null ? null : instruction.getNext();
    while (current != null && (current.getOpcode() < 0)) current = current.getNext();
    return current;
  }

  private static <T> T only(List<T> values, String label) {
    if (values.size() != 1) { throw mismatch(label + " expected=1 actual=" + values.size()); }
    return values.get(0);
  }

  private static void requireLoad(AbstractInsnNode instruction, int opcode, int local, String label) {
    if (!(instruction instanceof VarInsnNode variable) || variable.getOpcode() != opcode || variable.var != local) {
      throw mismatch(label + " changed");
    }
  }

  private static void requireCount(String label, int actual, int expected) {
    if (actual != expected) { throw mismatch(label + " expected=" + expected + " actual=" + actual); }
  }
}
