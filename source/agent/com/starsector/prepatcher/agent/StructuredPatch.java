package com.starsector.prepatcher.agent;

import org.objectweb.asm.tree.ClassNode;

/**
 * A patch that proves its structural contract before it changes anything.
 *
 * <p>{@link #match(ClassNode)} may read the whole class but must leave it untouched; it either
 * returns a proven plan or refuses with a reason. {@link #mutate} receives only that plan and
 * never sees the {@code ClassNode}, so it cannot search and cannot reach a site the match did
 * not prove. Callers that only need the question "would this patch apply?" — postcondition
 * validation and the composition probe — run the match alone.
 */
interface StructuredPatch<P> {
  Match<P> match(ClassNode node);

  PatchReport mutate(P plan);

  /** What must hold once the plan has been applied. Runs before the envelope serializes. */
  default void verify(P plan, ClassNode node) {}
}
