package com.starsector.prepatcher.agent.patch;

import org.objectweb.asm.tree.ClassNode;

import static com.starsector.prepatcher.agent.bytecode.Contract.*;

@FunctionalInterface
public interface PatchAction {
  PatchReport apply(ClassNode node);

  /**
   * Adapts a two-phase patch to the single-call contract the envelope expects, keeping refusal
   * signalling on the boundary where every other patch already raises it.
   */
  public static <P> PatchAction structured(StructuredPatch<P> patch) {
    return node -> {
      Match<P> match = patch.match(node);
      if (match.alreadyApplied) throw already(match.reason);
      if (match.plan == null) throw mismatch(match.reason);
      PatchReport report = patch.mutate(match.plan);
      patch.verify(match.plan, node);
      return report;
    };
  }
}
