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

## Patch naming and scope

- Name patches by behavior or transformation surface. Development labels (`P0`, `P2A`, phases, milestones) are forbidden.
- One behavior gets one config switch and capability. Use separate transformers only for independent surfaces; dependent targets form one atomic group.

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

## Documentation ownership and anti-duplication

Documentation has one canonical owner per kind of information. Before creating a Markdown file,
compare the content with the map below and extend the existing owner. Do not create a parallel
report because a task, investigation, test run, or implementation iteration needs notes.

- `README.md` and `README_RU.md` own the user overview, installation, supported versions, short
  operational guidance, and navigation. Keep them equivalent; link to detailed documents instead
  of copying their tables or proofs.
- `CHANGELOG.md` owns the concise user-visible history. Each released section links to its single
  `docs/releases/<version>.md`; it does not duplicate validation matrices, implementation diaries,
  or raw evidence.
- `docs/releases/<version>.md` is the single canonical report for one release. It records the final
  integrated behavior, release-specific transformation surfaces and composition decisions,
  owned-fork support status, executed validation, packaging/update notes, and accepted residual
  risks. Update this file throughout release preparation. Do not create iteration reports,
  worklogs, handoff reports, or a second regression report for the same release.
- `docs/PATCHES.md` owns the current patch/configuration catalog, transformation surfaces, runtime
  behavior, invariants, and kill switches. It describes the current product rather than the
  chronology of a release.
- `docs/COMPATIBILITY.md` owns structural matching, application/composition order, loader rules,
  conflicts, fail-open/fail-closed policy, and maintained-fork compatibility.
- `docs/VALIDATION.md` owns reusable review gates, scenario matrices, performance methodology, and
  acceptance criteria. Release-specific results belong in the release report; raw output belongs
  in `.build/reports/`.
- `docs/ROADMAP.md` owns only unfinished future work and technical debt. Move completed outcomes to
  the changelog/release report and remove completed roadmap instructions.
- `docs/architecture/` is reserved for durable, cross-release subsystem design that cannot be
  expressed clearly in `PATCHES.md` or `COMPATIBILITY.md`. A bug investigation, regression matrix,
  release hardening pass, or temporary implementation plan is not an architecture document.

Raw verifier output and generated diagnostics remain under `.build/reports/`, are ignored by Git,
and must not be copied into tracked Markdown. Filenames such as `*_REPORT.md`,
`*_REGRESSION.md`, `*_NOTES.md`, iteration summaries, and work diaries are forbidden unless the
repository owner explicitly designates a new canonical document.

When information touches more than one owner, put the full explanation in the most specific
canonical document and use short links elsewhere. Every documentation change must preserve valid
relative links, README reachability, English/Russian overview parity, current version references,
and the required `docs/releases/<current-version>.md` consistency gate.
