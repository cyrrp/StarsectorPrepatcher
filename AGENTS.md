# Repository instructions

## Patch composition and transformation surfaces

A patch's **transformation surface** is the exact part of bytecode whose structure or semantics the patch reads, matches, or changes. Identify it at least by target class, target method and descriptor, and the relevant control-flow/data-flow region or semantic call site. Sharing a class alone does not necessarily mean that two patches overlap; reading or changing the same method region, value flow, prologue, wrapper boundary, allocation, invocation, or invariant does.

Transformation surfaces of independent patches must not overlap.

When two or more patches have overlapping transformation surfaces, follow these rules:

1. **Merge overlapping patches whenever possible.** Implement them as one patch or one atomic patch group with a shared matcher, a shared mutation plan, and a combined postcondition. The group must either apply consistently or roll back consistently.
2. **Define an explicit application order.** Dependencies and ordering must be represented in code or patch metadata, not only implied by switch-case position or comments.
3. **Make later patches consume the current transformed state.** A later patch must analyze the bytecode produced by all earlier patches. It must never rebuild a class or method from vanilla bytes after another patch has modified it.
4. **Account for earlier changes when merging is impossible.** The later patch's matcher must explicitly recognize the valid post-state of every earlier patch that can affect its transformation surface. Its mutation and postcondition must preserve those earlier changes.
5. **Do not silently lose a patch because an earlier patch changed its matcher input.** If an enabled later patch cannot apply after an earlier patch, treat this as a composition defect. Fix the shared matcher/group/order rather than accepting `SKIPPED_STRUCTURAL` as normal behavior.
6. **Revalidate earlier postconditions.** After applying a later patch, verify that the postconditions of all earlier patches on the class still hold. A marker alone is not proof that the earlier transformation remains intact.
7. **Declare irreconcilable conflicts.** If two enabled patches cannot be merged and no ordered composition can preserve both, declare an explicit conflict and fail or disable the affected feature group predictably. Do not choose an accidental winner based on transformer registration order.

## Required validation for overlapping work

Every change to an overlapping transformation surface must include tests that cover:

- the full enabled patch order for the affected class;
- application of each later patch to the post-state of earlier patches;
- combined final postconditions for all patches in the group;
- idempotent reprocessing of the fully transformed class;
- rollback or a clear failure when one member of an atomic group cannot match;
- a regression case proving that an earlier transformation cannot make a later enabled patch silently skip.

Before adding a new patch, document its transformation surface and compare it with the surfaces of existing patches targeting the same class or method.

## Owned AoTD fork compatibility

The maintained AoTD Scheduler Fork is a first-class implementation, not an unknown third-party
subclass. Before adding an exact-class guard or changing virtual-call multiplicity in a vanilla
class, inventory the fork subclasses and overrides in the supplied fork source/JAR.

For every affected owned-fork class:

- use the optimized inherited path when the relevant semantic surface is identical;
- transform the fork method explicitly when it owns a different but supported implementation;
- otherwise fail closed only for that concrete runtime class, preserving the raw/original path;
- test the real fork JAR, not a name-only stub;
- add a future-override negative fixture so a fork update cannot silently receive unsafe semantics;
- keep compatibility state loader-safe: no static strong `Class`, `Method`, `ClassLoader`, campaign
  object, or mod-instance cache.

A patch is not considered complete merely because vanilla tests pass while an owned fork subclass
routes around it. The validation report must state whether fork support is inherited, directly
transformed, intentionally unnecessary, or safely rejected.
