package com.starsector.prepatcher.agent.patch;

import org.objectweb.asm.tree.ClassNode;

/**
 * A patch that proves its structural contract before it changes anything.
 *
 * <p>{@link #match(ClassNode)} leaves its input untouched and returns a plan or a refusal.
 * {@link #mutate} receives the plan, which may carry mutable nodes or a prepared class candidate;
 * this is a separation of responsibilities, not an enforced capability boundary.
 * Postcondition and applicability callers invoke match alone at the outer boundary. Composite
 * matchers may still run full member actions on isolated copies, including during state probes.
 */
public interface StructuredPatch<P> {
  Match<P> match(ClassNode node);

  PatchReport mutate(P plan);

  /** What must hold once the plan has been applied. Runs before the envelope serializes. */
  default void verify(P plan, ClassNode node) {}
}
