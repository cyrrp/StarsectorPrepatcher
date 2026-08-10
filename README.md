# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Current version: **0.18.1**. Supported game build: **Starsector 0.98a-RC8**.

[![Unplayable without Prepatcher versus smooth with Prepatcher](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Click the preview for the full 60 FPS WebM comparison.

StarsectorPrepatcher is a compatibility-first pre-load patching layer for Starsector. Its startup
javaagent runs before the game and mod classloaders begin normal loading, so guarded structural
patches can be applied at the point where the affected classes first enter the JVM.

The project has a broader direction than map optimization alone:

- maintain carefully verified performance and correctness patches for game internals;
- provide a stable, documented API for useful capabilities that Starsector's public API does not
  expose;
- keep version-specific bytecode knowledge inside the prepatcher instead of duplicating it across
  gameplay mods.

The public API is a roadmap item, not a published compatibility surface in `0.18.1`. Its intended
namespace is `com.starsector.prepatcher.api`; API types will only become supported once they are
documented and covered by compatibility tests.

## How it works

The distribution contains a sandbox-safe mod bootstrap and one startup agent:

```text
agent/StarsectorPrepatcherAgent.jar
```

The agent matches and verifies every patch independently, including hyperspace and fast-forward
presentation patches. Compatibility is decided from local method structure, data flow, control flow,
ownership markers, feature masks, and combined postconditions. Compatible localized or otherwise
repacked class files remain supported when the owned semantic surface is unchanged. Unknown,
ambiguous, partial, or foreign hook-shaped targets remain vanilla and the reason is written to the
diagnostic log.

The agent control code and the typed runtime are deliberately separated. At startup the agent reads
the `com.fs.starfarer.api.StarsectorPrepatcher*` runtime classfiles from its own JAR and defines them
in the classloader that owns Starsector's API. This keeps hook arguments loader-identical under both
the vanilla launcher and Faster Rendering's custom system classloader. The transformer is registered
only after that runtime is installed successfully, and it skips a target whose loader differs from
the runtime loader.

Fast-forward presentation coalescing is registered inside this same startup agent and its hooks are
part of the same game-loader runtime payload. It does not install or require a second javaagent; do
not install the original standalone FastForward Presentation Patch agent alongside Prepatcher.

The bootstrap plugin does not perform bytecode work. It exposes agent status through the normal game
log and warns when the mod is enabled without the startup agent.

## Installation

1. Fully close Starsector.
2. Extract the directory as `<Starsector>\mods\StarsectorPrepatcher`.
3. Install the agent for the launcher you use (commands below).
4. Enable **StarsectorPrepatcher** in the launcher and start the game.

If you use **AoTD — Theory of Toolbox**, install the maintained
[Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork), release
`1.0.14-spp11`. The fork is required for optimal AoTD performance and for the supported native
scheduler/capability path. Future fork revisions remain fail-closed until their contracts are
reviewed. The fork is not required when AoTD is absent.

On Windows, double-click `StarsectorPrepatcher.bat`, choose **Install javaagent**, and select
Vanilla, Faster Rendering, or both. The same actions are available from a terminal:

```bat
StarsectorPrepatcher.bat install Vanilla
StarsectorPrepatcher.bat install FasterRendering
StarsectorPrepatcher.bat install Both
```

The launcher explains every action before selection. Installation understands both vanilla's
`vmparams` command line and Faster Rendering's `starsector-core\fr.vmparams` Java argument file.
For every changed file it creates a timestamped backup, replaces any existing entry for this
installation, and places Prepatcher after every other `-javaagent` option:

```text
-javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
```

The folder name must not contain whitespace and should remain `StarsectorPrepatcher` after
installation. No additional `--add-exports` options are required; the agent exports the required JDK
ASM packages through `Instrumentation.redefineModule()`.

Install telemetry and other agents first, or rerun this installer afterward. Prepatcher must remain
the final `-javaagent` so its transformer sees the bytes returned by earlier agents.

The prepatcher does not modify save data, and its runtime caches are never serialized.

## Current patch areas

- sector, system, and Intel maps: reconciliation, spatial candidates, callbacks, hover checks,
  entity indexes, nebula metadata, scratch collections, and grid LOD;
- campaign and economy: lifecycle-bound caches, listener refresh, reusable snapshots, one unified
  scheduler for all transformed engine-owned market updates, corrected
  observation of direct mod `Market.advance()` calls, an atomic targeted/local UI market-mutation
  refresh for proven policy/trade/free-port/industry changes; administrator, unsupported, and
  unknown callers retain the original global step,
  owner-local persistent copy-on-write market/condition/industry snapshots with structure
  epochs and bounded audits, an owner-local
  ReachEconomy fingerprint, an ordered inactive-commodity fast path combined with the direct
  expiry-aware `MutableStatWithTempMods` scheduler, a guarded dormant inherited-`BaseIndustry`
  fast path, repeated absent commodity event-mod removal suppression, empty-script/empty-memory
  fast paths, a fail-closed incremental core-worlds index with `CampaignEngine`/`BaseLocation`
  mutation hooks and a bounded direct-tag audit, and comm-relay candidates;
- routing: ordered jump-point and system indexes with vanilla selection and fallback semantics;
- combat and particles: internal scratch collections and stable deferred cleanup;
- fast-forward presentation: final-substep coalescing for guarded campaign visuals and continuous
  audio, with broader animation/fader/particle groups enabled by the default/aggressive profile;
- loading and save paths: literal parsing, progress redraw, and output-path fixes;
- hyperspace: terrain culling, layer selection, seeded random reuse, owner-local automaton buffers,
  and moving-starfield cleanup.

The complete switch and invariant reference is in [`docs/PATCHES.md`](docs/PATCHES.md).

## Configuration and rollback

All settings are in `prepatcher.properties`. Every user-facing patch group has a dedicated `patch.*`
switch and requires a full game restart. The following disables the entire prepatcher:

```properties
enabled=false
```

`patch.loadingTextReader` and `patch.startupLogAggregation` remain disabled in all supplied profiles
because they participated in confirmed mission-startup failures. They will not be re-enabled until
their fixes pass an isolated startup and mission suite.

The default, safe, and aggressive profiles keep expensive observers, CSV/stack sampling, verbose
transformation output, presentation metrics, and the periodic stats worker disabled. Copy
`profiles/debug.properties` over `prepatcher.properties` only for a bounded diagnostic session; it
inherits every aggressive-profile setting, enables all of those facilities, and writes additional
data below `logs/`. A repository consistency test enforces the aggressive-plus-diagnostics contract.

The safe profile enables the structurally matched fast-forward presentation master, frame marker,
and narrower visual/audio groups. The default profile exactly mirrors aggressive: global animations,
sensor faders, slipstream particles, and particle emitters are enabled despite their broader callback,
lifetime, RNG, and emission-cadence surface. `fastForward.visualTime=realtime` keeps presentation at one
ordinary update per outer frame, while `simulation` accumulates substep time and may produce visible
jumps. Simulation itself still runs on every substep.

`patch.marketScheduler` is enabled in the default/aggressive profile and routes every known core
`MarketAPI.advance(float)` call through one scheduler contract. The periodic Economy-loop and
planet-condition sources accumulate `amount` during each simulation tick, but cadence is evaluated
once per render batch. Starsector performs multiple `CampaignEngine.advance()` calls per rendered
frame when campaign speed is increased; the scheduler detects the final iteration through
`CampaignEngine.setFastForwardIteration(false)`, so ordinary and hot markets receive at most one
callback per render batch instead of scaling callback count with acceleration. Remote visible markets
use `market.scheduler.batches`; hidden remote markets use `market.scheduler.hiddenBatches`; current-
location, interaction, and player-owned markets use one callback per batch.

A market may explicitly opt out with the memory key
`$starsectorPrepatcher_perSimulationTickMarket=true`. Only these compatibility markets retain one
callback per simulation tick. Statistics report both the current number of opted-out markets and their
callback cost. Six rare vanilla create/remove call sites, direct mod calls, scheduler fail-open paths,
and pre-save flushes use a cheaper synchronous hook that consumes existing pending debt before the
original event callback. The scheduler activates only after its CampaignEngine lifecycle/batch,
validated `CampaignState` batch protocol, Economy source, entity source, and save-flush components
initialize; before that calls remain synchronous and accumulate no debt. The complete runtime
contract is documented in [docs/architecture/MARKET_SCHEDULER.md](docs/architecture/MARKET_SCHEDULER.md).

Runtime statistics use one `marketScheduler*` family. `marketSchedulerSimulationTicks` and
`marketSchedulerRenderBatches` expose the actual acceleration ratio; `marketSchedulerMaxTicksPerBatch`
shows the largest observed batch. Work counters distinguish accumulated input calls, delivered
callbacks, per-simulation-tick opt-outs, and synchronous debt consumption. Failure counters remain
split by concrete cause. A failed normal callback disables batching only for that market; a failed
pre-save callback discards the already-detached ambiguous debt, switches that market to immediate
execution, and aborts the save so a partially applied callback is never retried automatically.
Periodic counters use `sumThenReset()`.

`patch.directMarketObservation` is enabled only by the debug profile. It does
not throttle direct mod calls: each call remains synchronous and immediate. Known planet-condition
engine calls are reported separately from unknown entries, transformed call sites are written to the
manifest before first execution, and the unknown-stack budget renews every report interval. Per-run
CSV/stacks are written under `logs/direct-market-observe/session-*/`; validation-smoke directories are
visibly labelled and `session.json` records `sessionOrigin`. Disable observation after collecting data
to remove sampling overhead. `call-sites.csv` and `observations.csv` contain explicit `mod_id`,
`mod_name`, mod-directory, and JAR columns resolved from the owning mod's `mod_info.json`; `source`
remains available as the exact code-source path rather than being the only way to identify a mod.

Construction classification maintains aggregate reason/scan counters; the debug profile publishes
them in its periodic stats line. `Industry.isUpgrading()` is diagnostic-only. For `BaseIndustry` subclasses, full-rate policy uses
the authoritative raw `building` field rather than an overridden virtual `isBuilding()` result;
non-`BaseIndustry` implementations retain the interface-method fallback. Virtual-building reports with
raw `building=false` are counted and sampled separately but do not enable full-rate. Optional bounded
samples can be enabled with `observer.marketConstructionDiagnostics=true`; they are written under
`logs/market-construction-diagnostics/session-*/`, include effective/reported building state, separate
source industries, transition buckets, and scalar `BaseIndustry` state, retain no game objects, and do
not change scheduler behavior.

Run `StarsectorPrepatcher.bat`, choose **Remove javaagent**, and select the affected launcher.
The console equivalents are `StarsectorPrepatcher.bat uninstall Vanilla`,
`... uninstall FasterRendering`, and `... uninstall Both`. Each changed file is backed up.

## Diagnostics and verification

Runtime logs:

```text
mods\StarsectorPrepatcher\logs\prepatcher.log
mods\StarsectorPrepatcher\logs\direct-market-observe\session-*\
mods\StarsectorPrepatcher\logs\market-construction-diagnostics\session-*\
```

The agent records `APPLIED`, `ALREADY_APPLIED`, `SKIPPED_STRUCTURAL`, `SKIPPED_COMPOSITION`,
`SKIPPED_LOADER`, `SKIPPED_ALREADY_LOADED`, or `SKIPPED_ERROR`. Presentation and hyperspace targets
use the same local structural status model as the other patches. Every skip is fail-open; `SKIPPED_LOADER`
must be investigated before calling that launch path compatible.

Choose **Run full verification** in `StarsectorPrepatcher.bat` (or run
`StarsectorPrepatcher.bat verify`) on Windows. Use `./verify-structural.sh` on Linux/macOS.
The suite covers documentation, structural, negative/idempotency, lifecycle/GC, runtime,
hyperspace, and agent startup; when `fr.jar` is present it also runs the real Faster Rendering
classloader smoke. Build details are in [`BUILDING.md`](BUILDING.md).

## Documentation

- [`AGENTS.md`](AGENTS.md) — repository rules for patch composition and transformation surfaces;
- [`README_RU.md`](README_RU.md) — Russian version of this overview;
- [`CHANGELOG.md`](CHANGELOG.md) — public `X.Y.Z` release history;
- [`BUILDING.md`](BUILDING.md) — build and verification workflow;
- [`docs/PATCHES.md`](docs/PATCHES.md) — patch switches and behavioral invariants;
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — structural matching and fail-open rules;
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — regression and performance validation playbook;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — structural discovery, architecture, tooling, and platform plan;
- [`docs/architecture/MARKET_SCHEDULER.md`](docs/architecture/MARKET_SCHEDULER.md) — durable
  scheduler design and invariants;
- [`docs/releases/0.18.1.md`](docs/releases/0.18.1.md) — current detailed release report.

StarsectorPrepatcher is distributed under the terms in [`LICENSE`](LICENSE).

## AoTD Scheduler Fork integration

Prepatcher 0.18.1 supports Scheduler Fork `1.0.14-spp11` and installs a clean wrapper around the
original `BaseIndustry.getMaxDeficit()`. The preserved vanilla implementation remains active until
a compatible AoTD Scheduler Fork registers the complete native capability profile `0xbff`.
Bridge schema V10 retains the V9 UI payload and adds required economy-restore coordination bit 11;
the optional UI market-mutation bit 10 extends the full profile to `0xfff`.
The required capabilities do not depend on optional optimization switches, so the safe profile
still negotiates `0xbff`. Registration is current-only: a non-V10 shape, any fork identifier other
than `1.0.14-spp11`, or a declared mask other than `0xfff` is logged and rejected with no partial
compatibility mode.
Late callbacks from an older epoch are rejected; a listener fail-stop triggers one generation
resynchronization before fallback dirtying is enabled. Do not install the obsolete modified
`starfarer.api.jar`.

The read-only UI patch also removes the exact UI-triggered global `tripleStep()` from Command/Colonies,
current and legacy commodity-detail dialogs, and the independently matched vanilla
`MarketCMD.showDefenses()` and Nexerelin `Nex_MarketCMD.showDefenses()` after their defense inputs have
already been captured. These are pure inline, fail-closed removals with no runtime cache or market
reference; the safe profile leaves them disabled. The real AoTD fork contains no descendant on the
vanilla surfaces, while the optional Nex class is transformed in memory without changing its JAR.

The exact detached-Cargo `fake_market` constructor branch is also optimized without requiring
AoTD. When both runtime classes are the exact vanilla `Economy` and `ReachEconomy`, and the final
bytecode contract still proves `tripleStep()` is exactly three `nextStep()` calls, Prepatcher skips
that unrelated sector-wide refresh. For the owned fork, the same exact call-site guard invokes its
explicit dispatcher with a classified synthetic-Cargo intent. A rejected or failed dispatch and any
replacement/subclass economy retain the original virtual call, which is always global. No
market-open, Cargo, or trade guard publishes a fork context. Local Resources tooltips use a
call-local stockpile snapshot; no market or commodity is retained after the tooltip call.

Schema V8 carries reason/scope data for the proven local immigration, incentives, and
stockpile-policy mutations. Schema V9 extends the same atomic optional capability with an immutable
sorted commodity-ID payload. For non-trade mutations, the one-shot context remains internal to
Prepatcher between an exact vanilla setter or industry wrapper and its shared helper; the fork
receives only the already classified intent. Exact free-port changes and five exact industry dialog
branches refresh one market and rebuild global/econ-group data only for affected commodity IDs; an
industry mutation with no commodity diff performs only the local industry/state refresh. Trade uses
no setter/helper context: its exact guard derives affected IDs directly from immutable bought/sold
cargo and uses the same affected-commodity commit. Administrator, stabilization, construction queue,
custom providers, and unknown helper callers retain the original global step.

Schema V10 adds one exact completion signal at the successful tail of
`CoreLifecyclePluginImpl.econPostSaveRestore()V`, after every industry restore and both market
reapply calls. It is controlled by the single `patch.aotdEconomyRestoreCoordination=true` switch in
every shipped profile. The game-loader hook performs O(1) work: it invokes one loader-local
`Runnable` and never scans markets. The fork performs structural-only commodity restoration,
coalesces each affected market into one materialized scheduler refresh, and preserves the last
committed revision when the industry snapshot is temporarily unavailable. Authoritative aggregates
use a dedicated materialized-input generation that trade/accessibility/faction-only work does not
advance; a post-immigration proof mismatch queues one coalesced repair even when trade values are
unchanged. Only the snapshot stage may return `NOT_READY`;
calculation-script failures remain visible errors. Newly written saves omit derived per-industry
supply/demand cache contents and references, and rebuild them after the complete restore barrier.

The maintained Scheduler Fork `1.0.14-spp11` is required for optimal performance when AoTD Theory of
Toolbox is installed. Prepatcher does not require AoTD or the fork in any other setup. The original
AoTD build may use preserved fail-closed/raw paths, but it does not provide the complete supported
native scheduler contract.

The market-share optimization group also treats the owned
`AoTDCommodityMarketData` as a supported implementation. A loader-local `ClassValue` admits the
current fork only while all five critical market-share methods remain inherited from vanilla. A
future fork override fails closed to the preserved raw implementation for that runtime class; it
does not disable the vanilla patch or retain the fork classloader.

The Local Resources cold-materialization patch recognizes the exact AoTD commodity/data/calculation
surface. It reads only already-published supply/demand data and applies the fork's own raw-unit
conversion without invoking its lazy market-data builder. The owner-local `econGroup` index also
admits the exact inherited `AoTDReachEconomy` read surface. The real-fork gate verifies that the
currently supplied `addMarket` delegates once to vanilla; a future critical read override falls
back locally, while a future mutator bypass is recovered by source validation and the bounded audit
rather than being treated as an immediate epoch event.

The exact fork-owned `AoTDConstructionSite.setAssignedWonder(String)` method receives a verified
scheduler mutation wrapper so the transition to `building=true` cannot remain hidden until the
periodic construction audit. These compatibility paths add no static campaign/mod-object cache:
optional accessors use `ClassValue`, and group-index arrays remain transient state of the owning
`ReachEconomy`.

The integration also fixes the synchronous market/Cargo refresh sequence without redefining the
standard economy API. `AoTDEconomy.nextStep(...)`, `doubleStep()`, `tripleStep()` and
`AoTDReachEconomy.nextStep(...)` are always full global steps and never infer UI intent from
`currentlyOpenMarket` or an absent payload; double/triple retain vanilla two/three-step
multiplicity. Exact Prepatcher call-site guards instead invoke the
fork's single public final dispatcher for market-open, Cargo or market-mutation actions. A supported
intent performs the committed single-market cut; `GLOBAL_TOPOLOGY`, a missing barrier/capability,
an unsupported action, `false`, or an exception executes the preserved original virtual call and
therefore the global path.
