package com.starsector.prepatcher.agent.patch;

import org.objectweb.asm.tree.ClassNode;

/**
 * A patch that proves its structural contract before it changes anything.
 *
 * <p>{@link #match(ClassNode)} may read the whole class but must leave it untouched; it either
 * returns a proven plan or refuses with a reason. {@link #mutate} must change only sites
 * proven by the match. The plan can carry mutable nodes and class inventories, so this is an
 * obligation of the implementation, not an access restriction enforced by the interface.
 * Callers that only need the question "would this patch apply?" — postcondition validation
 * and the composition probe — run the match alone.
 */
public interface StructuredPatch<P> {
  Match<P> match(ClassNode node);

  PatchReport mutate(P plan);

  /** What must hold once the plan has been applied. Runs before the envelope serializes. */
  default void verify(P plan, ClassNode node) {}
}
