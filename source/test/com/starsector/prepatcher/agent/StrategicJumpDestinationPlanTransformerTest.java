package com.starsector.prepatcher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/** Unified-plan structural regression for the StrategicModule destination surface. */
public final class StrategicJumpDestinationPlanTransformerTest {
  private static final String PATCH_ID = "strategicJumpDestinationPlan";
  private static final String MASK_FIELD = "smo$strategicJumpDestinationPlanMask";
  private static final String METHOD_DESC = "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;"
      + "Lcom/fs/starfarer/api/campaign/LocationAPI;)" + "Lcom/fs/starfarer/campaign/ai/StrategicModule$Oo;";
  private static final String UPDATE_DESC = "(Lcom/fs/starfarer/api/campaign/LocationAPI;F)V";
  private static final String HOSTILE_DESC =
      "(Lcom/fs/starfarer/api/campaign/FactionAPI;" + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)I";
  private static final String INDEX_HOOK_DESC = "(Lcom/fs/starfarer/api/campaign/LocationAPI;Ljava/lang/Class;"
      + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Ljava/util/List;";
  private static final String DEFER_DESC =
      "(Lcom/fs/starfarer/api/campaign/LocationAPI;" + "Lcom/fs/starfarer/api/campaign/LocationAPI;)Z";
  private static final String HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherHooks";
  private static final String STATUS_KEY =
      "starsector.prepatcher.patchStatus." + "com.fs.starfarer.campaign.ai.StrategicModule." + PATCH_ID;

  private StrategicJumpDestinationPlanTransformerTest() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "Usage: StrategicJumpDestinationPlanTransformerTest " + "<config> <starfarer_obf.jar>");
    }
    PrepatcherConfig config = PrepatcherConfig.load(Path.of(args[0]));
    require(config.strategicJumpDestinationFirst, "test configuration must enable patch.strategicJumpDestinationFirst");
    require(config.strategicJumpDestinationIndex, "test configuration must enable patch.strategicJumpDestinationIndex");
    byte[] original = readClass(Path.of(args[1]), PrepatcherTransformer.STRATEGIC_MODULE);

    assertVanillaState(original);
    byte[] patched = transform(config, original);
    require(patched != null, "vanilla StrategicModule was not patched");
    require("APPLIED".equals(System.getProperty(STATUS_KEY)), "vanilla plan status was not APPLIED");
    assertPatchedState(patched, 3);
    verifyBytecode(patched);

    byte[] repeated = transform(config, patched);
    require(repeated == null, "second pass was not idempotent");
    require("ALREADY_APPLIED".equals(System.getProperty(STATUS_KEY)), "second pass status was not ALREADY_APPLIED");

    byte[] unrelated = addUnrelatedNop(original);
    byte[] unrelatedPatched = transform(config, unrelated);
    require(unrelatedPatched != null, "unrelated instruction changed destination plan compatibility");
    assertPatchedState(unrelatedPatched, 3);

    assertFirstOnlyMask(original);
    assertMemberFailureRollback(config, original);
    assertForeignAndTamperedStates(config, patched);
    assertLegacyMarkersRejected(config, original);

    System.out.println(
        "OK strategic destination plan vanilla/idempotent/mask=1/member-rollback/foreign/tamper/legacy-marker");
  }

  private static void assertFirstOnlyMask(byte[] original) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("patch.strategicJumpDestinationFirst", "true");
    properties.setProperty("patch.strategicJumpDestinationIndex", "false");
    PrepatcherConfig firstOnly = config(properties);
    byte[] patched = transform(firstOnly, original);
    require(patched != null, "destination-first-only plan did not apply");
    require("APPLIED".equals(System.getProperty(STATUS_KEY)), "mask=1 plan status was not APPLIED");
    assertPatchedState(patched, 1);
    verifyBytecode(patched);
    require(transform(firstOnly, patched) == null, "mask=1 plan was not idempotent");
    require("ALREADY_APPLIED".equals(System.getProperty(STATUS_KEY)),
        "mask=1 second pass status was not ALREADY_APPLIED");

    // The index member must not be silently skipped under a first-only mask:
    // its surfaces stay vanilla while the first member's post-state is complete.
    ClassNode node = read(patched);
    MethodNode method = method(node, "findNearestSafeJumpPoint", METHOD_DESC);
    require(count(method, Opcodes.INVOKESTATIC, HOOKS, "strategicJumpPointsForDestination", INDEX_HOOK_DESC) == 0,
        "mask=1 emitted the index source hook");
    MethodNode update = method(node, "updateJumpPlanTo", UPDATE_DESC);
    require(count(update, Opcodes.INVOKESTATIC, HOOKS, "strategicJumpDeferExpiredPlan", DEFER_DESC) == 0,
        "mask=1 emitted the expiry deferral hook");

    // Index-only is impossible: the config contract requires destination-first.
    Properties indexOnly = new Properties();
    indexOnly.setProperty("patch.strategicJumpDestinationFirst", "false");
    indexOnly.setProperty("patch.strategicJumpDestinationIndex", "true");
    PrepatcherConfig indexOnlyConfig = config(indexOnly);
    require(!indexOnlyConfig.strategicJumpDestinationIndex, "index-only configuration was not normalized to disabled");
    require(transform(indexOnlyConfig, original) == null, "index-only configuration still transformed the class");
  }

  private static void assertMemberFailureRollback(PrepatcherConfig config, byte[] original) {
    // A broken first-member surface rejects the whole plan: the index member
    // must not apply alone even though its own surface is intact.
    byte[] wrongRadius = mutateRadius(original, 3999f);
    require(transform(config, wrongRadius) == null, "changed hostile-market radius was accepted");
    require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
        "changed radius did not report SKIPPED_STRUCTURAL");

    byte[] wrongMultiplier = mutateMultiplier(original);
    require(transform(config, wrongMultiplier) == null, "changed hostile-market multiplier was accepted");
    require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
        "changed multiplier did not report SKIPPED_STRUCTURAL");

    // A broken index-member surface rejects the whole plan symmetrically: the
    // first member must not survive without the index member.
    ClassNode malformed = read(original);
    MethodNode update = method(malformed, "updateJumpPlanTo", UPDATE_DESC);
    AbstractInsnNode zero = null;
    for (AbstractInsnNode insn : update.instructions.toArray()) {
      if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
          || !field.owner.equals("com/fs/starfarer/campaign/ai/CampaignFleetAI$JumpPlan")
          || !field.name.equals("timeLeft") || !field.desc.equals("F")) {
        continue;
      }
      AbstractInsnNode candidate = nextMeaningful(field);
      if (candidate != null && candidate.getOpcode() == Opcodes.FCONST_0) {
        require(zero == null, "ambiguous JumpPlan expiry fixture");
        zero = candidate;
      }
    }
    require(zero != null, "JumpPlan expiry fixture missing");
    update.instructions.set(zero, new InsnNode(Opcodes.FCONST_1));

    require(transform(config, write(malformed)) == null,
        "broken index-member surface left the destination-first member applied");
    require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
        "broken index-member surface did not report SKIPPED_STRUCTURAL");
  }

  private static void assertForeignAndTamperedStates(PrepatcherConfig config, byte[] patched) {
    byte[] foreignLazy = removeOwnershipMarker(patched);
    assertPatchedBytecode(foreignLazy);
    require(transform(config, foreignLazy) == null,
        "foreign destination-plan state without ownership marker was accepted");
    require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
        "foreign lazy state did not report SKIPPED_STRUCTURAL");

    byte[] tamperedOwned = mutateGuardSentinel(patched);
    require(transform(config, tamperedOwned) == null, "tampered owned destination-plan state was accepted");
    require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
        "tampered owned state did not report SKIPPED_STRUCTURAL");

    // A plan committed under a different mask is not adopted under another.
    Properties firstOnly = new Properties();
    firstOnly.setProperty("patch.strategicJumpDestinationFirst", "true");
    firstOnly.setProperty("patch.strategicJumpDestinationIndex", "false");
    try {
      require(transform(config(firstOnly), patched) == null, "mask=3 plan state was accepted under a mask=1 request");
    } catch (Exception ex) {
      throw new AssertionError("failed to build first-only config", ex);
    }
    require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
        "mask-mismatched owned state did not report SKIPPED_STRUCTURAL");
  }

  private static void assertLegacyMarkersRejected(PrepatcherConfig config, byte[] original) {
    for (String legacy : List.of("strategicJumpDestinationFirst", "strategicJumpDestinationIndex")) {
      ClassNode legacyNode = read(original);
      legacyNode.fields.add(new FieldNode(Opcodes.ASM8,
          Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "smo$patched$" + legacy,
          "Ljava/lang/String;", null, "StarsectorPrepatcher:patch-v1:" + legacy));
      require(transform(config, write(legacyNode)) == null,
          "legacy split marker was adopted by the unified plan: " + legacy);
      require("SKIPPED_STRUCTURAL".equals(System.getProperty(STATUS_KEY)),
          "legacy split marker did not report SKIPPED_STRUCTURAL: " + legacy);
    }
  }

  private static PrepatcherConfig config(Properties properties) throws Exception {
    Constructor<PrepatcherConfig> constructor = PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
    constructor.setAccessible(true);
    return constructor.newInstance(properties);
  }

  private static byte[] transform(PrepatcherConfig config, byte[] bytes) {
    System.clearProperty(STATUS_KEY);
    return new PrepatcherTransformer(config).transform(null, PrepatcherTransformer.STRATEGIC_MODULE, null, null, bytes);
  }

  private static void assertVanillaState(byte[] bytes) {
    ClassNode node = read(bytes);
    MethodNode method = method(node, "findNearestSafeJumpPoint", METHOD_DESC);
    MethodInsnNode destinations =
        onlyCall(method, "com/fs/starfarer/campaign/JumpPoint", "getDestinations", "()Ljava/util/List;");
    MethodInsnNode hostile = onlyCall(method, "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
    require(method.instructions.indexOf(hostile) < method.instructions.indexOf(destinations),
        "vanilla hostile-market call is not eager");
    require(!hasMarker(node), "vanilla class already has ownership marker");
    require(findField(node) == null, "vanilla class already has the plan mask field");
  }

  private static void assertPatchedState(byte[] bytes, int expectedMask) {
    ClassNode node = read(bytes);
    require(hasMarker(node), "unified plan ownership marker missing");
    FieldNode mask = findField(node);
    require(mask != null && "I".equals(mask.desc), "unified plan mask field missing");
    require(mask.value instanceof Integer value && value == expectedMask,
        "unified plan mask changed: expected=" + expectedMask);
    require(!hasLegacyMarker(node, "strategicJumpDestinationFirst")
        && !hasLegacyMarker(node, "strategicJumpDestinationIndex"), "unified plan emitted legacy member markers");
    assertPatchedBytecode(bytes);
  }

  private static void assertPatchedBytecode(byte[] bytes) {
    ClassNode node = read(bytes);
    MethodNode method = method(node, "findNearestSafeJumpPoint", METHOD_DESC);
    MethodInsnNode destinations =
        onlyCall(method, "com/fs/starfarer/campaign/JumpPoint", "getDestinations", "()Ljava/util/List;");
    MethodInsnNode hostile = onlyCall(method, "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
    require(method.instructions.indexOf(hostile) > method.instructions.indexOf(destinations),
        "hostile-market call was not moved after destination enumeration");

    AbstractInsnNode destinationReceiver = previousMeaningful(destinations);
    require(destinationReceiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD,
        "getDestinations receiver changed");
    AbstractInsnNode sentinelStore = previousMeaningful(destinationReceiver);
    AbstractInsnNode sentinel = previousMeaningful(sentinelStore);
    require(
        sentinelStore instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE
            && sentinel instanceof LdcInsnNode ldc && Integer.valueOf(Integer.MIN_VALUE).equals(ldc.cst),
        "sentinel initialization is missing");

    JumpInsnNode guard = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IF_ICMPNE
          && previousMeaningful(jump) instanceof LdcInsnNode ldc
          && Integer.valueOf(Integer.MIN_VALUE).equals(ldc.cst)) {
        require(guard == null, "duplicate destination-first guard");
        guard = jump;
      }
    }
    require(guard != null, "destination-first guard is missing");
    AbstractInsnNode scoreBody = nextMeaningful(guard.label);
    require(scoreBody instanceof VarInsnNode jumpLoad && jumpLoad.getOpcode() == Opcodes.ALOAD
        && nextMeaningful(scoreBody) instanceof MethodInsnNode getLocation
        && getLocation.owner.equals("com/fs/starfarer/campaign/JumpPoint") && getLocation.name.equals("getLocation"),
        "guard does not join at the original candidate body");

    AbstractInsnNode radius = previousMeaningful(hostile);
    require(radius instanceof LdcInsnNode ldc && ldc.cst instanceof Float value
        && Float.floatToIntBits(value) == Float.floatToIntBits(4000f), "lazy hostile-market radius changed");
    AbstractInsnNode two = nextMeaningful(hostile);
    require(two != null && two.getOpcode() == Opcodes.ICONST_2 && nextMeaningful(two).getOpcode() == Opcodes.IMUL,
        "lazy hostile-market multiplier changed");
  }

  private static byte[] addUnrelatedNop(byte[] source) {
    ClassNode node = read(source);
    method(node, "findNearestSafeJumpPoint", METHOD_DESC).instructions.insert(new InsnNode(Opcodes.NOP));
    return write(node);
  }

  private static byte[] mutateRadius(byte[] source, float value) {
    ClassNode node = read(source);
    MethodInsnNode hostile = onlyCall(method(node, "findNearestSafeJumpPoint", METHOD_DESC),
        "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
    AbstractInsnNode radius = previousMeaningful(hostile);
    require(radius instanceof LdcInsnNode, "hostile-market radius constant missing");
    ((LdcInsnNode) radius).cst = Float.valueOf(value);
    return write(node);
  }

  private static byte[] mutateMultiplier(byte[] source) {
    ClassNode node = read(source);
    MethodInsnNode hostile = onlyCall(method(node, "findNearestSafeJumpPoint", METHOD_DESC),
        "com/fs/starfarer/api/util/Misc", "getNumHostileMarkets", HOSTILE_DESC);
    AbstractInsnNode two = nextMeaningful(nextMeaningful(nextMeaningful(hostile)));
    require(two != null && two.getOpcode() == Opcodes.ICONST_2, "vanilla multiplier constant missing");
    method(node, "findNearestSafeJumpPoint", METHOD_DESC).instructions.set(two, new InsnNode(Opcodes.ICONST_3));
    return write(node);
  }

  private static byte[] removeOwnershipMarker(byte[] source) {
    ClassNode node = read(source);
    List<FieldNode> retained = new ArrayList<>();
    for (FieldNode field : node.fields) {
      if (!field.name.equals("smo$patched$" + PATCH_ID) && !field.name.equals(MASK_FIELD)) retained.add(field);
    }
    require(retained.size() + 2 == node.fields.size(), "ownership marker or mask missing");
    node.fields = retained;
    return write(node);
  }

  private static byte[] mutateGuardSentinel(byte[] source) {
    ClassNode node = read(source);
    MethodNode method = method(node, "findNearestSafeJumpPoint", METHOD_DESC);
    int changed = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IF_ICMPNE) continue;
      AbstractInsnNode sentinel = previousMeaningful(jump);
      if (sentinel instanceof LdcInsnNode ldc && Integer.valueOf(Integer.MIN_VALUE).equals(ldc.cst)) {
        ldc.cst = Integer.valueOf(-1);
        changed++;
      }
    }
    require(changed == 1, "destination-first guard sentinel not found");
    return write(node);
  }

  private static boolean hasMarker(ClassNode node) {
    int count = 0;
    for (FieldNode field : node.fields) {
      if (field.name.equals("smo$patched$" + PATCH_ID)) count++;
    }
    require(count <= 1, "duplicate ownership marker");
    return count == 1;
  }

  private static boolean hasLegacyMarker(ClassNode node, String patchId) {
    return node.fields.stream().anyMatch(field -> field.name.equals("smo$patched$" + patchId));
  }

  private static FieldNode findField(ClassNode node) {
    for (FieldNode field : node.fields) {
      if (field.name.equals(MASK_FIELD)) return field;
    }
    return null;
  }

  private static MethodNode method(ClassNode node, String name, String desc) {
    MethodNode found = null;
    for (MethodNode method : node.methods) {
      if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
      require(found == null, "duplicate method " + name + desc);
      found = method;
    }
    require(found != null, "method missing: " + name + desc);
    return found;
  }

  private static MethodInsnNode onlyCall(MethodNode method, String owner, String name, String desc) {
    MethodInsnNode found = null;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(owner) || !call.name.equals(name)
          || !call.desc.equals(desc))
        continue;
      require(found == null, "duplicate call " + owner + "." + name + desc);
      found = call;
    }
    require(found != null, "missing call " + owner + "." + name + desc);
    return found;
  }

  private static int count(MethodNode method, int opcode, String owner, String name, String desc) {
    int result = 0;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode && call.owner.equals(owner)
          && call.name.equals(name) && call.desc.equals(desc))
        result++;
    }
    return result;
  }

  private static byte[] readClass(Path jar, String internalName) throws Exception {
    try (JarFile file = new JarFile(jar.toFile())) {
      var entry = file.getJarEntry(internalName + ".class");
      require(entry != null, "class missing from JAR: " + internalName);
      try (InputStream input = file.getInputStream(entry)) {
        return input.readAllBytes();
      }
    }
  }

  private static ClassNode read(byte[] bytes) {
    ClassNode node = new ClassNode(Opcodes.ASM8);
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  private static byte[] write(ClassNode node) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  private static void verifyBytecode(byte[] bytes) {
    ClassNode node = read(bytes);
    for (MethodNode method : node.methods) {
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
      try {
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
      } catch (AnalyzerException ex) {
        throw new AssertionError("BasicVerifier rejected " + method.name + method.desc, ex);
      }
    }
  }

  private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
    AbstractInsnNode current = node == null ? null : node.getNext();
    while (current != null && current.getOpcode() < 0) current = current.getNext();
    return current;
  }

  private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
    AbstractInsnNode current = node == null ? null : node.getPrevious();
    while (current != null && current.getOpcode() < 0) current = current.getPrevious();
    return current;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
