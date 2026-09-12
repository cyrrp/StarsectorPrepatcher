package com.starsector.prepatcher.agent;

import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.SourceValue;
import com.starsector.prepatcher.agent.bytecode.ClassIO;
import com.starsector.prepatcher.agent.bytecode.Contract;
import com.starsector.prepatcher.agent.bytecode.Flow;
import com.starsector.prepatcher.agent.patch.Match;
import com.starsector.prepatcher.agent.bytecode.Queries;
import com.starsector.prepatcher.agent.combat.ShipAdvanceScratch;
import com.starsector.prepatcher.agent.patch.StructuralMismatchException;
import com.starsector.prepatcher.agent.patch.StructuredPatch;

/** Verifier-valid receivers must be refused by match before any candidate mutation. */
final class ScratchReceiverApplicabilityTest {
  public static void main(String[] args) throws Exception {
    if (args.length == 0 || args[0].equals("particle")) {
      var constructor =
          Class.forName("com.starsector.prepatcher.agent.combat.DynamicParticleCleanup").getDeclaredConstructor();
      constructor.setAccessible(true);
      check((StructuredPatch<?>) constructor.newInstance(), "com/fs/graphics/particle/DynamicParticleGroup",
          "java/util/List", "particle scratch List.add receiver changed");
    }
    if (args.length == 0 || args[0].equals("ship")) {
      check(new ShipAdvanceScratch(), "com/fs/starfarer/combat/entities/Ship", "java/util/Set",
          "Ship.advance scratch mutation receivers changed");
    }
  }

  private static <P> void check(StructuredPatch<P> patch, String owner, String collection, String reason)
      throws Exception {
    ClassNode node = new ClassNode();
    try (var input = ClassLoader.getSystemResourceAsStream(owner + ".class")) {
      require(input != null, "missing original " + owner);
      new ClassReader(input).accept(node, 0);
    }
    byte[] original = ClassIO.writeClass(node);
    Match<P> valid = patch.match(node);
    require(valid.plan != null, "vanilla receiver refused: " + owner);
    require(Arrays.equals(original, ClassIO.writeClass(node)), "vanilla match changed candidate");

    MethodNode advance = Contract.requireMethod(node, "advance", "(F)V");
    MethodInsnNode add = Contract.only(
        Queries.calls(advance, Opcodes.INVOKEINTERFACE, collection, "add", "(Ljava/lang/Object;)Z"), "scratch add");
    SourceValue receiver = Flow.receiverSource(advance, add, Flow.sourceFrames(owner, advance));
    require(receiver != null && receiver.insns.size() == 1, "receiver not unique");
    AbstractInsnNode producer = receiver.insns.iterator().next();
    require(producer instanceof VarInsnNode && producer.getOpcode() == Opcodes.ALOAD, "receiver not a local load");
    advance.instructions.set(producer, new InsnNode(Opcodes.ACONST_NULL));
    new Analyzer<>(new BasicVerifier()).analyze(owner, advance);
    byte[] before = ClassIO.writeClass(node);
    try {
      Match<P> refused = patch.match(node);
      require(refused.plan == null && !refused.alreadyApplied && reason.equals(refused.reason),
          "match must refuse changed receiver: " + owner);
    } catch (StructuralMismatchException ex) {
      require(reason.equals(ex.getMessage()), "unexpected refusal: " + ex.getMessage());
    } finally {
      require(Arrays.equals(before, ClassIO.writeClass(node)), "refusing match changed candidate: " + owner);
    }

    // Restore the original receiver and consume a fresh plan as a positive control.
    ClassNode restored = ClassIO.readClass(original);
    Match<P> restoredMatch = patch.match(restored);
    require(restoredMatch.plan != null, "restored receiver refused");
    patch.mutate(restoredMatch.plan);
    patch.verify(restoredMatch.plan, restored);
    new Analyzer<>(new BasicVerifier()).analyze(owner, Contract.requireMethod(restored, "advance", "(F)V"));
    require(patch.match(restored).alreadyApplied, "valid receiver transformation is not complete");
    System.out.println("RECEIVER_REGRESSION " + owner + " null-refused candidate-unchanged vanilla-applied");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
