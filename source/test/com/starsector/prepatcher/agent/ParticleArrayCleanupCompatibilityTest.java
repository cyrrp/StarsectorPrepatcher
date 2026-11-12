package com.starsector.prepatcher.agent;

import com.starsector.prepatcher.agent.combat.DynamicParticleCleanup;
import com.starsector.prepatcher.agent.presentation.PresentationStructuralContract;
import com.starsector.prepatcher.agent.bytecode.Markers;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import static com.starsector.prepatcher.agent.bytecode.ClassIO.*;
import static com.starsector.prepatcher.agent.bytecode.Queries.*;
import static com.starsector.prepatcher.agent.bytecode.OwnedTemplates.*;

/** An already optimized input must be proven, byte-preserved, and revalidated after later changes. */
public final class ParticleArrayCleanupCompatibilityTest {
  private static final String TARGET = "com/fs/graphics/particle/DynamicParticleGroup";
  public static void main(String[] args) throws Exception {
    String template = "com/starsector/prepatcher/agent/templates/ParticleArrayCleanupTemplate";
    byte[] input;
    String source;
    if (args.length == 1) {
      source = "com/genir/renderer/overrides/render/particle/DynamicParticleGroup";
      try (JarFile jar = new JarFile(args[0])) {
        input = jar.getInputStream(jar.getJarEntry(source + ".class")).readAllBytes();
      }
    } else {
      source = template;
      ClassNode node = loadOwnedTemplate(template, "/" + template + ".class", "particle test");
      input = writeClass(node);
    }
    ClassWriter writer = new ClassWriter(0);
    Map<String, String> names = Map.of(source, TARGET,
        "com/genir/renderer/overrides/render/particle/BaseParticle", "com/fs/graphics/particle/BaseParticle");
    new ClassReader(input).accept(new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, names)), 0);
    byte[] original = writer.toByteArray();
    PrepatcherConfig config = PrepatcherConfig.load(null);
    String status = "starsector.prepatcher.patchStatus." + TARGET.replace('/', '.') + ".particleCleanup";
    String applied = System.getProperty("starsector.prepatcher.appliedPatches");
    String skipped = System.getProperty("starsector.prepatcher.skippedPatches");
    PrepatcherTransformer transformer = new PrepatcherTransformer(config);
    require(transformer.transform(null, TARGET, null, null, original) == null, "array cleanup bytes changed");
    require("NOT_NEEDED".equals(System.getProperty(status)), "array cleanup was not recognized");
    require(Objects.equals(applied, System.getProperty("starsector.prepatcher.appliedPatches"))
        && Objects.equals(skipped, System.getProperty("starsector.prepatcher.skippedPatches")), "unnecessary patch changed counters");
    require(transformer.transform(null, TARGET, null, null, original) == null, "array cleanup repeat changed bytes");
    for (int variant = 0; variant < 4; variant++) {
      ClassNode damaged = readClass(original);
      MethodNode advance = damaged.methods.stream().filter(m -> m.name.equals("advance") && m.desc.equals("(F)V")).findFirst().orElseThrow();
      if (variant == 0) {
        for (AbstractInsnNode n : advance.instructions) if (n instanceof IincInsnNode increment) { increment.incr = 2; break; }
      } else if (variant == 1) {
        Markers.addPatchMarker(damaged, "particleCleanup");
      } else if (variant == 2) {
        damaged.fields.stream().filter(f -> f.name.equals("particlesNum")).findFirst().orElseThrow().access |= Opcodes.ACC_STATIC;
      } else {
        for (AbstractInsnNode n : advance.instructions) if (n instanceof MethodInsnNode call && call.name.equals("isExpired")) { call.name += "$changed"; break; }
      }
      require(new PrepatcherTransformer(config).transform(null, TARGET, null, null, writeClass(damaged)) == null, "damaged array cleanup was rewritten");
      require("SKIPPED_STRUCTURAL".equals(System.getProperty(status)), "damaged array cleanup was called unnecessary");
    }
    StructuralPatchEnvelope envelope = new StructuralPatchEnvelope(config);
    StructuralPatchEnvelope.TransformState state = new StructuralPatchEnvelope.TransformState(TARGET, original,
        PresentationStructuralContract.inspect(TARGET, original, config));
    envelope.apply(state, "particleCleanup", true, new DynamicParticleCleanup());
    ClassNode changed = readClass(original);
    changed.methods.stream().filter(m -> m.name.equals("advance")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
    state.bytes = writeClass(changed);
    require(!envelope.validateFinalComposition(state), "later changes bypassed the absence postcondition");
    require(Arrays.equals(original, state.incomingBytes), "absence contract lost its rollback input");
    System.out.println("OK particle array cleanup NOT_NEEDED bytes/counters/idempotence/branch-field-marker-negative/final-composition source=" + (args.length == 1 ? "real-FR" : "synthetic"));
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
