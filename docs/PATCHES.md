# Карта патчей

## Модель применения

Основной agent выполняет независимое structural matching фактически загружаемого bytecode. Каждый
блок имеет ownership marker, postcondition, ASM verification и fail-open fallback.

Hyperspace-патчи проходят тот же независимый structural pipeline единого agent: каждый патч
проверяет target-класс, метод и descriptor, локальный bytecode-контракт, точное число точек
внедрения, ownership marker, postcondition и результат ASM verification. Allowlist полных SHA-256
не используется, поэтому оригинальный и переводной JAR принимаются автоматически, пока структура
конкретного изменяемого участка совместима; изменения строк и constant pool этому не мешают. При
неизвестном или неоднозначном участке только соответствующий патч остаётся vanilla со статусом
`SKIPPED_STRUCTURAL`, а остальные патчи рассматриваются независимо.

FastForward Presentation Patch также использует class-level structural plans. Все включённые
presentation-компоненты одного target применяются одной транзакцией, а совместимость определяется
локальными method/call-site contracts, owner, feature mask и combined postcondition. SHA-256
classfile или JAR не участвует в решении.

## Main agent

| Config | Target/область | Изменение | Основной compatibility-инвариант |
|---|---|---|---|
| `patch.mapRenderStuff` | map `A.H.renderStuff` | atomic linear reconciliation plus reusable entity/class collections under one scratch scope | all three semantic sites, ownership marker, non-escape contracts and scope must match together; `LinkedHashMap` order and equality-aware fallback remain |
| `patch.labelSpatialCandidates` | map labels | spatial candidate buckets | original exact overlap checks остаются |
| `patch.intelCallbackCache` | Intel map locations | short TTL для `getMapLocation` | miss/error вызывает callback |
| `patch.intelEntityIndex` | Intel synthetic entities | identity index | generation/TTL/fallback |
| `patch.intelReconciliation` | `EventsPanel.addMissingIconsAndRows` | atomic missing-plugin hash membership plus direct existing-icon candidates under one scratch scope | both semantic sites, scope and ownership marker must match together; vanilla equality and full-scan fallback remain |
| `patch.mapHitTest` | map `A.OO0000(FFF)` hit test | atomic reusable hit list/point plus bounded exact-result cache wrapper | original method, two non-escaping allocations, one scratch scope, wrapper and vanilla fallback must match together |
| `patch.systemNebulaCache` | map construction | immutable system metadata cache | synthetic entities создаются заново |
| `patch.sampleCacheClearThrottle` | map construction | suppress rapid repeated clear | configurable interval |
| `patch.gridLineCap` | huge sector map | dynamic grid spacing | визуальный LOD only |
| `patch.intelArrowRendering` | `Z.o00000(FF)` Intel arrows | atomic `getArrowData` TTL cache plus two reusable internal vectors under one scratch scope | callback, both non-escaping allocations, scope and ownership marker must match together; miss/error calls plugin |
| internal lifecycle | `CampaignEngine.set/resetInstance`, `CampaignEngine.advance`, save boundary | generation reset plus throttled UI-cache/scratch maintenance | two-phase generation boundary; maintenance runs only on campaign thread and forced save sweep still honors idle/grace thresholds |
| `patch.campaignListenerThrottle` | campaign advance | skip unchanged internal repository listener sweep | public method untouched, periodic audit |
| `patch.campaignSnapshotReuse` | `BaseLocation` | reusable eager snapshots | point-in-time order/mutation isolation |
| `patch.entityScriptSnapshotReuse` | entity scripts | inline empty fast return; non-empty fresh vanilla snapshot | no scratch scope for empty lists; mutation isolation unchanged |
| `patch.emptyMemoryAdvanceFastPath` | `Memory.advance` | inline return when expire+require are empty | restoration/pause and non-empty loops remain vanilla |
| `patch.coreWorldsExtentCache` | `CoreScript.advance(F)V`, `CampaignEngine.create/removeStarSystem`, `BaseLocation.add/remove/clearTags` | one campaign snapshot, weak system/core index, O(C+B) steady validation, unchanged-write suppression and optional fast-forward skip | all three structural surfaces must apply; missing mutation hook fails closed to vanilla; bounded audit covers direct live-tag additions |
| `patch.routeJumpPointIndex` | route widget | ordered jump/system candidates | original filter/distance/tie loop |
| `patch.strategicJumpDestinationFirst` | `StrategicModule.findNearestSafeJumpPoint` | lazy hostile-market score only after the original destination predicate accepts a jump point | exact target/hyperspace fallback, one score per jump point, candidate order/sort/early return unchanged |
| `patch.strategicJumpDestinationIndex` | `StrategicModule.findNearestSafeJumpPoint`, expired `JumpPlan`, jump/location topology mutators | budgeted ordered index keyed by destination `LocationAPI` identity | requires destination-first; no location type/size admission rule; cold/dirty work is deferred under one global frame budget |
| `patch.economyLocationCache` | `Economy.advance` | omit only redundant automatic dirty write | explicit mod dirty state authoritative |
| `patch.marketScheduler` | `Economy.advance`, `BaseCampaignEntity.advance`, pre-save boundary | one stable-phase scheduler for all transformed engine-owned market updates with exact accumulated amount | one identity state and policy; direct mod calls immediate; hot markets full-rate; callback cadence intentionally changes |
| `patch.campaignCargoNoGlobalEconomyStep` | exact detached campaign-Cargo `fake_market` constructor branch | exact vanilla `Economy + ReachEconomy` skips the unrelated three-step refresh; owned AoTD receives an explicit synthetic-Cargo dispatch | structural anchors, `CARGO` mode, null outpost/other-cargo and final `Economy.tripleStep/getEconomy` contract; rejected/failed dispatch, subclasses and replacement economies execute the original global call |
| `patch.lootTransferNoGlobalEconomyStep` | exact generated-loot `fake_market` constructor branch | exact vanilla skips the unrelated three-step refresh; owned AoTD receives the same explicit synthetic-Cargo dispatch after loot generation | structural anchors, `LOOT` mode, null outpost and real `CargoData`; unknown modes/economies preserve the original global call |
| `patch.planetConditionMarketOpenNoGlobalEconomyStep` | `CampaignEngine.reportPlayerOpenedMarket` for non-economy condition-only markets | skip only the initial all-market `Economy.nextStep`; keep ability/listener callbacks and `currentlyOpenMarket` publication | exact vanilla `Economy + ReachEconomy` identity scan or accepted owned-AoTD market-open dispatch; live/unknown markets fall back |
| `patch.vanillaMarketOpenLocalization` | `CampaignEngine.reportPlayerOpenedMarket` plus the immediate live-market Cargo constructor | exact vanilla uses a synchronous one-market refresh and consumes the following duplicate `tripleStep`; owned AoTD receives explicit market-open/Cargo intents | final vanilla `Economy`/`ReachEconomy` contracts, exact class/loader/economy membership, scheduler debt barrier and one-shot vanilla coalescing token; any failed guard or dispatcher executes the original global call |
| `patch.uiMarketMutationRefresh` | exact vanilla market-overview policy mutations and shared helper; trade `confirmTransaction()V`; five industry-dialog branches plus `IndustryListPanel`; final vanilla `CommodityMarketData`; AoTD Scheduler Bridge V9 | one atomic mutation feature: setter/helper paths use an internal one-shot context, while trade uses a boolean guard around the preserved original `doubleStep()`; vanilla refreshes locally and owned AoTD receives one explicit dispatch; affected IDs rebuild only their global/econ-group records | each independent bytecode surface fails closed locally; every preparation, snapshot, proof and publication read is best-effort; the industry dialog/panel pair activates only after both postconditions hold; changed inventory, non-stock economy, poisoned/stale context, `GLOBAL_TOPOLOGY`, missing barrier/capability, admin, construction queue, custom providers and unknown helpers execute the original global step |
| `patch.commandTabNoGlobalEconomyStep` | `com.fs.starfarer.campaign.command.F.<init>` | opening Command/Colonies consumes the last committed economy state without a terminal sector-wide `tripleStep()` | exact public constructor descriptor, one class-local `tripleStep`, exact `Global.getSector → getEconomy` chain and terminal `RETURN`; any changed shape remains vanilla |
| `patch.commodityDetailNoGlobalEconomyStep` | current `CommodityDetailDialogV2` and dormant legacy `CommodityDetailDialog` constructors | commodity producer/consumer/import/export details use already-published `CommodityMarketData` without three synchronous global passes | each class is matched independently by exact constructor descriptor, unique call chain and post-step `CommodityOnMarket.getMarket()` anchor; one mismatch does not disable the other |
| `patch.marketDefensesNoGlobalEconomyStep` | vanilla `MarketCMD.showDefenses(boolean)` and optional Nexerelin `Nex_MarketCMD.showDefenses(boolean)` | removes each owner-local guarded global `tripleStep()` after interaction fleet, station fleet and station state have already been captured | exact protected method, owner/superclass, market-null guard/join and ordering proof; changed military/UI flow preserves that class's original call |
| `patch.directMarketObservation` | mod call sites + known engine origins + concrete `Market.advance` entry | synchronous wrappers, eager call-site manifest, sampled timing and interval-bounded stack attribution | direct mod calls are never delayed/merged/suppressed; known planet path is classified separately |
| `patch.economyPersistentSnapshots` | Economy/Market | owner-local copy-on-write snapshots + structure epochs | API mutators invalidate immediately; direct live-list edits bounded by audit |
| `patch.localResourcesNoColdMarketData` | `LocalResourcesSubmarketPlugin.getStockpileLimit/shouldHaveCommodity` | removes accidental cold global `CommodityMarketData` materialization; exact AoTD cold state uses its committed supply/demand model | both method surfaces apply atomically; unknown/future commodity classes retain the raw getter path |
| `patch.localResourcesTooltipSnapshot` | `LocalResourcesSubmarketPlugin.createTooltipAfterDescription` plus owned AoTD overrides | one stockpile-limit snapshot per commodity; sort compares saved integers only | exact vanilla/Nex classes or fork-owned helper only; unknown subclasses execute preserved raw tooltip; no persistent cache |
| `patch.economyGroupIndex` | `ReachEconomy.getMarketsInGroup`, market/group mutators | owner-local ordered `econGroup` index; each caller still receives a fresh mutable list | exact vanilla and verified `AoTDReachEconomy`; mutation epoch, source identity/size and bounded ordered audit prevent stale publication |
| `patch.commodityEventModDirtyCache` | `CommodityOnMarket.reapplyEventMod` | skip repeated removal after zero quantity proved private `eMod` absent | first zero call/load and the complete nonzero remove/calculate/add path stay vanilla; direct external mutation of the private key is unsupported |
| `patch.commodityTemporalFastPath` | `Market.advance` + `MutableStat` | ordered active set: stable commodity skips 4 temp-stat advances and event-mod reapply | API mutations wake immediately; subclasses/shared stats fall back; direct live-map edits bounded by audit |

### Read-only UI economy-step removals

The read-only UI transformer owns four vanilla classes plus the reviewed Nexerelin override and
removes only the exact stack-neutral
`Global.getSector() → SectorAPI.getEconomy() → EconomyAPI.tripleStep()` sequence. It adds no runtime
hook, cache, listener, market reference or serialized state. Each class carries a private synthetic
ownership marker, is re-read after emission, and every concrete method passes ASM `BasicVerifier`.

- Command/Colonies: the call is the terminal action of the exact public constructor, so removal does
  not reorder any UI initialization.
- Commodity detail V2 and legacy: the next semantic instructions still initialize the dialog from
  the supplied `CommodityOnMarket`; the two classes have independent compatibility results.
- Market defenses: vanilla `MarketCMD` and the real Nexerelin 0.12.1d `Nex_MarketCMD` independently
  execute `getInteractionTargetForFIDPI()`, `getStationFleet()` and `getStationState()` before their
  guarded global step. Consequently that step cannot change the already captured fleet/station
  inputs used by the current dialog. The patch removes no Nex invasion/responder option or local
  defense calculation, and never modifies `ExerelinCore.jar` on disk.

The maintained AoTD fork has no descendants or overrides of the four vanilla surfaces. Its real-JAR
gate continues to reject a future fork override. The optional Nex target is instead transformed
directly in its child mod loader, adds no cross-loader reference, and is gated against the real
`ExerelinCore.jar`; a changed superclass or method shape remains raw. Safe profile disables all
three switches; default/aggressive/debug enable them. A structural mismatch is local and
fail-closed: original bytes, including `tripleStep()`, are returned unchanged.

### Explicit AoTD UI economy dispatch

Scheduler Fork `1.0.14-spp9` restores the standard virtual-step contract:
`AoTDEconomy.nextStep(EconWorkParams)`, `doubleStep()`, `tripleStep()` and
`AoTDReachEconomy.nextStep(EconWorkParams)` always run the full all-market pipeline. They do not
inspect `currentlyOpenMarket`, infer UI intent from a null payload or consume a Prepatcher context;
`doubleStep()` and `tripleStep()` retain vanilla two/three-step multiplicity.

Prepatcher classifies only structurally proven UI call sites and invokes the fork's single public
final `dispatchPrepatcherUiEconomyStep(int, MarketAPI, long, String[])` entry point. Market-open,
Cargo and trade transformations are boolean guards around the existing virtual
`nextStep`/`tripleStep`/`doubleStep` invocation; they no longer wrap the enclosing method or publish
a fork context. Before semantic commit, every proof and diagnostic read is inside the fail-open
boundary: `false` or an escaped failure leaves the original call in bytecode to execute once outside
the helper, including when the exact fork/capability is unavailable or a debt barrier cannot prove
the local cut safe. A successful dispatcher/local refresh is the boolean commit point. Fork and
Prepatcher diagnostics after that point are contained and cannot turn the committed `true` back into
`false` or select a duplicate global fallback. The exact fork loader is derived from the two
callbacks registered by verified Scheduler Bridge V9, so the
real parent-runtime/child-mod topology is accepted while an equal-name economy from another loader
is not eligible.

Non-trade market mutation keeps one internal weak, same-thread, epoch-bound context only between an
exact vanilla setter/industry wrapper and its shared helper. Original setters and industry mutators
execute exactly once outside instrumentation catches. Preparation, before/after snapshots, context
publication, exact-class/membership proofs and pre-commit diagnostics are fail-open. A failed read
poisons the current setter batch monotonically, so a later successful record cannot erase
uncertainty before the helper consumes it. The helper then executes the preserved global step.
Post-commit counters and logging are best-effort and cannot revoke successful local work. Trade
instead collects its immutable transaction IDs directly inside its guard and uses no record/consume
round trip. No
static `Class`, `Method`, `ClassLoader`, campaign object or mod instance is cached for dispatch.

Only exact Scheduler Fork `1.0.14-spp9` registers. The current declared mask must be exactly
`0x7ff`; `spp4`–`spp8`, future and partially declared contracts are logged and rejected wholesale
with mask `0`. For the exact current fork, the required dispatcher bit is independent of profile switches:
safe negotiates `0x3ff`, while `patch.uiMarketMutationRefresh=true` adds the sole optional bit and
negotiates `0x7ff`.

`Economy.advance(F)V` и связанное owner-local состояние имеют одного structural-владельца
`economyAdvancePlan`. Публичные переключатели `patch.economyPersistentSnapshots`,
`patch.economyLocationCache` и `patch.marketScheduler` образуют feature mask. Все компоненты сначала
проверяются на одинаковых входных bytes, затем изолированный candidate строится в явном порядке
persistent snapshots → location cache → scheduler source. Порядок обязателен: location-cache hook
выбирает persistent-вариант только после установки snapshot state. Поля, accessors, lifecycle/mutator
hooks, `Economy.advance`, paused path и scheduler registration коммитятся одной транзакцией. Один
ownership marker и private static final mask подтверждаются общей postcondition; legacy split markers
и unowned partial state не принимаются.

`Market.advance(F)V` имеет одного structural-владельца `marketAdvancePlan`. Три публичных
переключателя — `patch.economyPersistentSnapshots`, `patch.commodityTemporalFastPath` и
`patch.directMarketObservation` — образуют feature mask. План проверяет vanilla-состояние всех трёх
компонентов на одинаковых входных bytes, строит изолированный candidate в порядке snapshots →
commodity loop → entry observation, затем одним commit заменяет fields/methods класса. Candidate
получает один ownership marker и private static final mask. Несовместимость любого запрошенного
компонента оставляет весь `Market` неизменённым; legacy split markers и unowned hook-shaped state не
принимаются.
| `patch.tempModExpiryScheduler` | `MutableStatWithTempMods.advance` | direct float countdown ближайшего expiry; one-pass map scan только у deadline/synchronization | sync before mutation/read/save; live-map exposure, subclasses и anomalies используют retained vanilla path |
| `patch.marketNoOpCallbacks` | inherited `BaseIndustry.advance` | after a full dormant proof, skip disruption/build checks until wake or bounded audit | custom `advance/isDisrupted/getDisruptedKey` stay vanilla; direct disruption-memory edits may wait for audit |
| `patch.commRelaySystemIndex` | `IntelManager` | conservative spatial system candidates + TTL position audit | original order/live relay checks; bounded coordinate staleness |
| `patch.shipAdvanceScratch` | `Ship.advance` | reuse 3 lists + command snapshot + 2 sets | fresh listener snapshot, no API objects pooled |
| `patch.particleCleanup` | `DynamicParticleGroup` | reuse expiry list + stable linear removal | all particles advance before removal |
| `patch.loadingTextReader` | `LoadingUtils` | streaming UTF-8 normalization | **known-disabled in 0.7.1 profiles:** mission startup regression requires separate fix |
| `patch.startupLogAggregation` | loaders/specs/rules/sound | remove/aggregate high-volume INFO | **known-disabled in 0.7.1 profiles:** mission startup regression requires separate fix |
| `patch.rulesLiteralParser` | rules loader | literal fixed-delimiter operations | randomized differential semantics |
| `patch.saveLoadProgressThrottle` | progress streams | redraw ceiling | final forced updates retained |
| `patch.saveOutputBufferDedup` | save output chain | remove one duplicate outer buffer | save bytes/format/close chain unchanged |

`CampaignGameManager.o00000(CampaignEngine$o,J,Z)` имеет correctness-critical structural-владельца
`saveMethodPlan` только для pre-save barrier. Его feature mask включает forced cache maintenance и
scheduler debt flush; maintenance-компонент включается при наличии campaign-cache lifecycle или
scratch/starfield trimming, а scheduler-компонент — при `patch.marketScheduler=true`. Оба компонента
проверяются на одинаковых входных bytes, затем изолированный candidate коммитится одной транзакцией
в порядке forced maintenance → scheduler pre-save barrier. Один ownership marker и private static
final mask подтверждаются общей postcondition; legacy scheduler markers, unowned partial state и
повреждённый first-instruction barrier не принимаются.

`patch.saveOutputBufferDedup` теперь является отдельным ordered structural patch с собственным
ownership marker. Он применяется после `saveMethodPlan`, а общий composition pipeline повторно
проверяет postcondition уже установленного barrier после успешного output-chain rewrite. Если
1 MiB allocation pattern изменён или неоднозначен, только dedup получает `SKIPPED_STRUCTURAL`:
maintenance hook, scheduler flush, component registration и scheduler capability остаются активны.
Таким образом, необязательная allocation-оптимизация больше не влияет на correctness/readiness
планировщика.

### Destination-first strategic jump filtering

`patch.strategicJumpDestinationFirst` изменяет только локальный порядок вычислений в
`StrategicModule.findNearestSafeJumpPoint(CampaignFleetAPI, LocationAPI)`. Vanilla сначала для
каждого допустимого `JumpPoint` вызывает `Misc.getNumHostileMarkets(..., 4000f)`, а уже затем
проверяет его `JumpDestination`. Патч записывает sentinel в исходный `int` local и переносит
единственный hostile-market вызов в ветвь, где исходный identity-предикат уже принял exact target
или штатный fallback в hyperspace. При нескольких подходящих destinations одного jump point score
вычисляется один раз. Фильтры star anchor/unstable/wormhole, `getNumUnsafeFleetsAround`, candidate
allocation, insertion order, comparator, `Collections.sort` и ранний return `< 2000` не меняются.

Transformation surface ограничена одним методом и прямым value-flow hostile-market score → candidate
unsafe field. Других патчей этого класса нет. Matcher требует точные descriptors, общий continue для
трёх reject-ветвей, схождение exact/fallback ветвей, radius `4000f`, multiplier `2`, unsafe-fleet
arguments `fleet/jumpPoint/750f` и последующее `IADD`. Owned post-state требует sentinel, один
`IF_ICMPNE` guard и один hostile-market call; похожий foreign lazy-state без marker получает
`SKIPPED_STRUCTURAL`.

### Strategic destination-location index

`patch.strategicJumpDestinationIndex` применяется после destination-first и атомарно меняет две
части `StrategicModule`: источник внешнего цикла `findNearestSafeJumpPoint` и ветвь истечения
существующего `JumpPlan` в `updateJumpPlanTo`. Runtime не классифицирует current location как
hyperspace/system и не использует размер списка как admission-эвристику. Hyperspace остаётся только
частью исходного vanilla-предиката exact-target/fallback, который патч не изменяет.

Первый lookup не строит индекс и не читает destinations: он лишь принимает bounded state для
`LocationAPI`. `campaignCacheMaintenanceTick()` продвигает все `BUILDING`, `REFRESHING`, retry и
audit-задачи под одним process-wide token bucket. За каждые 16,67 мс допускается не более
`strategicJump.indexBudgetMicros` полной wall-clock стоимости и не более
`strategicJump.indexMaxWorkUnits` элементарных операций. В wall-clock accounting входят ожидание
общего lock, выбор задачи, retry heap, work/audit queues, LRU, idle cleanup и сама неделимая unit.
Deadline проверяется до выбора следующей unit, поэтому один вызов может выйти за него не более чем
на стоимость одной уже начатой unit и постоянного финального accounting. Дополнительные simulation
substeps при fast-forward не умножают допустимую работу. `indexMaxWorkUnits` остаётся вторичным
ограничителем для очень дешёвых units; штатное значение 512 не увеличивает frame budget в 250 мкс.
Полное построение по-прежнему требует просмотра всех `J` jump points и `D` relations, но эта стоимость
распределяется между кадрами; READY lookup читает только ordered candidates для exact target и
vanilla fallback. Проверенный miss является пустым cache hit и не возвращает полный список.

Due retries выбираются indexed min-heap без полного прохода LRU. READY states находятся в отдельной
intrusive round-robin audit queue; audit получает не более одной шестнадцатой work-unit ceiling за
maintenance-вызов и не сжигает весь budget в спокойном steady state. Эти структуры ограничены числом
активных location states и очищаются при campaign-generation reset.

Пока точный snapshot не READY, hook возвращает immutable empty source, поэтому исходный полный
`Θ(J + D)` цикл не возникает в том же кадре. Во время `BUILDING`/`REFRESHING` уже существующий
`JumpPlan` временно сохраняется; флот без плана повторяет запрос в последующих advances. В
`FAILED_COOLDOWN` старый план очищается, чтобы постоянная ошибка не удерживала stale route. Это намеренное изменение
только момента route rebuild: после READY vanilla destination/safety/distance/candidate/sort/early-return
логика получает тот же identity-ordered subset.

Обычные topology-события обслуживаются дельтами: `JumpPoint.addDestination`, `clearDestinations` и
`removeDestination`, `JumpDestination.setDestination`, а также добавление/удаление `JumpPoint` через
`BaseLocation` получают отдельные hooks. Изменение destinations одного point пересчитывает только его
relations; изменение source membership запускает replacement build, также ограниченный общим бюджетом.
Прямые изменения mutable lists обнаруживаются непрерывным budgeted audit. Ошибка build/refresh переводит
только эту location в `FAILED_COOLDOWN` с ограниченным exponential retry и не запускает новый полный
проход на каждом lookup.

State хранится в identity LRU с жёстким `strategicJump.indexMaxLocations`, idle TTL и полным reset при
смене campaign generation. Capacity eviction допускает только `READY` и `FAILED_COOLDOWN` states;
`BUILDING`/`REFRESHING` не теряют уже выполненную работу. Если все слоты заняты незавершёнными
индексами, новый admission быстро откладывается до появления безопасной жертвы, а не запускает LRU
thrashing. Длительно неиспользуемый state всё ещё может быть удалён idle TTL под тем же maintenance
budget. Accelerator `JumpDestination -> PointRecord` является фиксированным set-associative
weak-identity cache; он не растёт вместе с числом когда-либо увиденных relations, а его miss
исправляется audit-ом. Candidate union имеет фиксированный малый cache и не удерживает
неограниченное число requested locations.

Периодическая статистика публикует полную/максимальную длительность maintenance, максимальный
budget overrun, размеры work/audit/retry queues, phase gauges, deferred-plan count, oldest pending/
deferred ages и максимальную latency до READY. Это позволяет отделить устранённый CPU hotpath от
возможного gameplay latency debt после загрузки.

Structural capability считается READY только при наличии owned post-state destination-first,
maintenance hook и всех topology hooks. Matcher повторно проверяет lazy hostile-market guard после
source rewrite и требует точную expired-plan deferral; mixed/foreign state получает fail-open
`SKIPPED_STRUCTURAL` без частичного изменения класса.

### Campaign cache maintenance and scratch retention

`campaignCacheMaintenanceTick()` выполняется на campaign thread из `CampaignEngine.advance()` и
внутренне ограничивается `cache.maintenanceIntervalMs`. Перед сериализацией pre-save barrier вызывает
`runCacheMaintenance(true)` до market-debt flush; forced sweep игнорирует только throttle, но сохраняет
owner idle TTL и scratch grace period.

Только три долгоживущие UI owner-карты используют idle eviction: `LABEL_INDEXES`,
`INTEL_ENTITY_INDEXES` и `HIT_CACHES`. Freshness TTL содержимого остаётся независимым от
`*.ownerIdleTtlMs`. Каждое value хранит `lastAccessNanos`; insertion сначала удаляет idle entries, затем
при жёстком лимите 32/32/64 выполняет линейный identity-preserving LRU по минимальному timestamp.
Hover cells физически удаляются только общим sweep не чаще `hover.cellPruneIntervalMs`.
Freshness использует единый half-open контракт: cell валиден только при `age < hover.cellTtlMs` и
считается expired при `age >= hover.cellTtlMs`. Поэтому результат на точной TTL-границе не зависит
от того, успел ли maintenance sweep. `lastResult` pruning не затрагивает.

Reusable `ScratchFrame` и starfield `ListPool` не получают owner TTL. Scratch сохраняет exact
concrete types `ArrayList`/`HashSet`; per-collection integer high-water обновляется allocation-free
hooks только в ASM-доказанных `add`/`addAll` sites. Поэтому peak не теряется, даже если caller вызвал
`clear()` до выхода из scope. Clock читается один раз при полном закрытии root scope: тогда
фиксируется oversized timestamp, но активные и вложенные frames никогда не заменяются. После
`scratch.trimGraceMs` oversized свободный frame заменяется, а хвост после патологической
реентерабельности сокращается до четырёх обычных retained frames. Gauges суммируют high-water
каждой retained list/set отдельно и сбрасываются в EMPTY snapshot при смене campaign generation.

Starfield `PooledList.add/addAll` также обновляет только integer high-water без `nanoTime()`;
единственный clock read выполняется при release lease. Oversized free lists удаляются после
`starfield.pool.oversizedGraceMs`. Каждый borrow фиксирует campaign generation. При lifecycle reset
свободные lists текущего campaign thread очищаются и `ThreadLocal` отсоединяется немедленно; list,
borrowed до reset, при позднем release очищается и отбрасывается по stale generation, поэтому старый
большой backing array не может повторно попасть в pool новой кампании. Другие thread-local pools
очищаются лениво при следующем обращении по тому же generation contract, без process-lifetime
реестра `Thread`/`ListPool`. Отдельного executor, daemon maintenance thread или `System.gc()` нет.

### Commodity temporal active set

`patch.commodityTemporalFastPath` заменяет только фиксированный commodity-maintenance loop внутри
точного vanilla `Market.advance()`. Каждый market хранит private transient entries в исходном порядке
live commodity list и отдельный ordered active list. Commodity без temporary modifiers, pending dirty
signal и audit work не вызывает:

```text
4 × MutableStatWithTempMods.advance(days)
1 × CommodityOnMarket.reapplyEventMod()
```

В точном vanilla `MutableStat` synthetic owner/role fields связывают stat с единственным commodity.
Четырнадцать штатных mutation methods имеют null-guard notification: `setBaseValue`, `modify*`,
`unmodify*` и temporary-mod operations будят entry до следующего `Market.advance()`. Internal source
`eMod` игнорируется как self-write `reapplyEventMod()`. Shared stats, subclasses и foreign shape
получают полный vanilla loop.

`commodity.temporalAuditFrames` ограничивает задержку для прямых изменений backing maps/lists в
обход mutators. На audit active set проверяет identity/order commodity list, будит все entries и
вызывает private bridge direct temp-mod scheduler, чтобы materialize/rebuild nearest deadline.
Первый audit deadline разнесён между markets по identity/generation. High-volume counters
`Markets/Entries/Active/InactiveSkips` sampled раз в 64 market calls; transition/fallback counters
остаются точными.
Public `getMods()` по-прежнему переводит конкретный stat на retained vanilla scheduler; если мод
позднее изменит сохранённую live map, market audit снова обнаружит непустой stat и вернёт commodity
в active list. Same-size mutation посреди deferred interval имеет неизвестный точный момент и потому
может получить aggregate elapsed attribution — это намеренный aggressive-profile компромисс.

State и binding fields private/transient/synthetic, в save не попадают. Ошибка helper-а, lifecycle
not-ready или structural ambiguity выполняет исходный commodity loop. Порядок обработанных
commodities не меняется, а никакие condition/industry/submarket callbacks этим патчем не
throttling'уются. Dirty mutation другого inactive commodity из callback может перенести его работу
на следующий market tick; это документированная aggressive-семантика. Active set автоматически
устанавливает direct temp-mod scheduler, от которого зависит его membership logic.

### Expiry-aware temporary stats

`patch.tempModExpiryScheduler` сохраняет исходные тела `advance`, `getMods`, private `getMod`,
`removeTemporaryMod`, `hasMod` и `writeReplace` как private synthetic fallback. В exact vanilla owner
шесть private transient scalar fields находятся прямо в `MutableStatWithTempMods`: отдельный helper
object и reflection отсутствуют на hot path. Ближайший deadline обновляется тем же покадровым
`float countdown -= days`, что и vanilla `timeRemaining -= days`; aggregate `deferred >= minimum` не
используется, поскольку сложение и последовательное вычитание округляются по-разному.

До deadline `advance()` выполняет O(1). У ближайшего expiry либо перед mutation/read/save один проход
по `LinkedHashMap` одновременно материализует deferred time, удаляет истёкшие entries через original
`Iterator.remove()`, вызывает `unmodify(source)` в исходном порядке и строит следующий schedule.
Счётчик tied minima позволяет обновлять schedule после refresh/remove без лишнего полного прохода.
`hasMod()` использует уже актуальное membership и не запускает materialization sweep.

Публичный `getMods()` сначала синхронизирует значения и затем навсегда переводит конкретный stat на
retained vanilla per-frame path, поскольку мод может сохранить mutable live map. Подклассы,
необычный backing-map, неожиданная прямая мутация размера и runtime anomaly также fail-open остаются
vanilla. State transient; `writeReplace()` материализует значения, поэтому scheduler не попадает в
save. Reflection-чтение package-private `timeRemaining` без `getMods()` между sync points может
увидеть последнее материализованное значение. Для далёких survivors aggregate materialization может
отличаться на несколько ULP от покадрового vanilla subtraction; exact nearest countdown и порядок
удаления сохраняются без хранения всей истории `days`.

### Dormant inherited BaseIndustry callbacks

`patch.marketNoOpCallbacks` в `0.9.5` не переносит параллельный callback-helper буквально.
Transformer сохраняет полное исходное `BaseIndustry.advance(float)` как private synthetic raw method
и устанавливает прямой wrapper с двумя `private transient synthetic int` fields. После одного полного
vanilla-вызова exact inherited implementation считается dormant, если `building=false` и
`wasDisrupted=false`. Следующие вызовы выполняют только два field checks и countdown decrement.

Full raw call выполняется немедленно, когда:

- `building` или `wasDisrupted` становятся true;
- вызван штатный `BaseIndustry.setDisrupted(float, boolean)` — transformer добавляет wake-prologue;
- истёк `market.noOpIndustryAuditFrames`;
- runtime class переопределяет `advance(float)`, `isDisrupted()` или `getDisruptedKey()`;
- structural matcher или helper classification не подтвердили точный контракт.

Параллельная версия также классифицировала пустой inherited
`BaseMarketConditionPlugin.advance()`, но такой helper-call оказался дороже самого пустого virtual
callback. Поэтому condition callbacks вообще не перехватываются. Для industries нет глобальной
`IdentityHashMap`, reflection или helper на steady-state hot path: class eligibility вычисляется
`ClassValue` только после первого полного вызова конкретного экземпляра.

Патч намеренно меняет callback count только у exact inherited dormant `BaseIndustry`. Модовые
`advance()` overrides продолжают работать каждый market frame. Мод, который меняет disruption
через private market memory в обход `setDisrupted()`, может быть замечен только следующим bounded
audit. Safe profile выключает блок; default/aggressive включают его как performance-first
компромисс. Synthetic state не сериализуется.

Оба startup-патча остаются в коде и structural/runtime suites, но все поставляемые профили держат
их выключенными. Они не возвращаются ни в один поставляемый профиль, включая debug, до изолированного исправления и
успешного startup/mission прогона каждого переключателя по отдельности.

### Единый market scheduler

`patch.marketScheduler` агрегирует periodic `Market.advance(float)` по render batch, но сохраняет
точную RLE-историю исходных simulation-step `amount`. Полный runtime-контракт описан в
[MARKET_SCHEDULER.md](architecture/MARKET_SCHEDULER.md).

Основные поверхности:

- `CampaignState` подтверждает fast-forward batch protocol;
- `CampaignEngine` задаёт simulation tick/render batch и lifecycle;
- `economyAdvancePlan` устанавливает Economy source;
- `BaseCampaignEntity` устанавливает planet-condition source;
- `saveMethodPlan` выполняет forced cache maintenance и market flush;
- `marketAdvancePlan` оборачивает конкретный `Market.advance` invocation context и construction
  mutation barriers;
- `MilitaryBase`, `LionsGuardHQ`, `RecentUnrest` получают local single-step replay wrappers;
- `BaseIndustry` и `ConstructionQueue` получают exact construction mutation barriers.

```properties
patch.marketScheduler=true
market.scheduler.batches=4
market.scheduler.hiddenBatches=8
market.scheduler.hot.currentLocation=true
market.scheduler.hot.playerOwned=true
market.scheduler.hot.interaction=true
market.scheduler.policyAuditBatches=300
market.remote.constructionAuditBatches=180
market.scheduler.perSimulationTickMemoryKey=$starsectorPrepatcher_perSimulationTickMarket
market.remote.maxPendingRuns=32
market.remote.exactReplayBeforeSave=false
observer.marketAdvanceSemanticRisks=false
observer.marketConstructionDiagnostics=false
observer.marketConstructionDiagnosticsMaxSamplesPerReason=32
```

`pendingAmount`, `pendingSteps` и `pendingRuns` сохраняют сумму, число и порядок исходных float.
Соседние raw-bit-identical значения объединяются в run. При превышении `maxPendingRuns` текущая
история выполняется с batch context; усреднение не используется.

Обычный рынок получает один coalesced callback, а `MilitaryBase`, `LionsGuardHQ` и `RecentUnrest`
внутри него воспроизводят исходные шаги только для собственного `advance()`. `RecentUnrest`
останавливается после удаления condition. Наличие военной базы не переводит весь рынок в full-rate.

Непустая construction queue, effective building или uncertain probe state временно переводят весь
рынок в full-rate. Для наследников `BaseIndustry` effective building читается из raw-поля `building`;
virtual `Industry.isBuilding()` используется как fallback только для других реализаций.
`Industry.isUpgrading()` и virtual-building при raw=false остаются reason/gauge для диагностики, но не
включают full-rate. Старая history exact-replay’ится до текущего шага.
Подтверждённые mutators `Market`, `BaseIndustry`
и `ConstructionQueue` flush’ят pending history до изменения структуры. Owned
`AoTDConstructionSite.setAssignedWonder(String)` является отдельной fork-owned surface: exact
structural transformer сохраняет исходное тело как private synthetic raw method и ставит
`SchedulerBridge.beforeMarketMutation/afterMarketMutation` в `try/finally`. Тем самым переход
`building=false -> true` сначала доставляет накопленное время старому состоянию, затем немедленно
публикует structure/industry/derived-economy dirty mask. Transformer включён тем же
`patch.marketScheduler`, не создаёт cache/listener/state и отклоняет future implementation, если
изменились write/call/return invariants.

После завершения строительства рынок автоматически возвращается к coalescing. Полный detector scan
выполняется после mutation epoch и через редкий safety audit, а не на каждом simulation input.

Периодическая строка stats всегда содержит причины и стоимость detector scan: queue/effective-
building/upgrading/reported-without-raw gauges, dirty/safety/forced scans, cached decisions,
state/reason transitions, неизвестные queue/industry containers и probe failures. При включённом
`observer.marketConstructionDiagnostics` bounded CSV записывается в
`logs/market-construction-diagnostics/session-*/samples.csv`. Лимит применяется отдельно к каждой
reason/transition bucket; строки содержат только identity hash, id/name, reported/effective building,
раздельные effective-building/upgrading/reported-without-raw industries, transition mask и скалярный
снимок queue/BaseIndustry полей, не удерживая
`MarketAPI`, `Industry` или queue objects. Этот observer не влияет на policy result.

Direct/event/fail-open barrier сначала доставляет старую history, затем выполняет текущий amount
отдельно. Save по умолчанию coalesced с batch context; `market.remote.exactReplayBeforeSave=true`
включает exact replay всех рынков. Construction markets всегда exact-replay’ятся.

`observer.marketAdvanceSemanticRisks` создаёт статический CSV-отчёт по mod component `advance(F)V`
(`INTERVAL_SINGLE_ELAPSE`, random, single-threshold и market-structure mutation). Observer-only
режим не меняет bytes даже у класса с прямым `Market.advance()` call site; runtime instrumentation
требует отдельного `patch.directMarketObservation` или `patch.marketScheduler`.

Scheduler readiness требует одиннадцать registration bits: пять core surfaces, semantic boundary
`Market.advancePlan`, три local replay wrapper и две construction-barrier группы. Отсутствующий или
повреждённый wrapper/barrier оставляет scheduler в synchronous fail-open.

Локальный replay доказывает последовательность шагов внутри каждого целевого компонента, но не
восстанавливает глобальное межкомпонентное чередование и общий RNG order. Полная vanilla-
эквивалентность этих аспектов требует campaign-level differential tests и пока не заявляется.

Дополнительные metrics: pending steps/runs и high-water, overflow flushes, coalesced amount/context,
три local replay counters, construction mode/boundary counters и save coalesced/exact duration.
Coverage test продолжает требовать два periodic и шесть synchronized vanilla core call sites.

### Level 1: observation и синхронизация прямых mod-вызовов Market.advance

Отдельный transformer рассматривает mod bytecode и заменяет только прямые вызовы:

```text
invokeinterface MarketAPI.advance(F)V
invokevirtual   Market.advance(F)V
```

на typed synchronous wrapper. Он устанавливается, когда включён либо
`patch.directMarketObservation`, либо `patch.marketScheduler`:

- при одном observer wrapper сохраняет исходный `amount`, cadence, multiplicity, thread и exception;
- при scheduler без observer wrapper не создаёт telemetry session, но если у рынка есть pending debt,
  один callback получает `pending + direct amount`, после чего debt обнуляется;
- если debt отсутствует, direct call получает исходный float без изменения.

При активном observer transformer заранее регистрирует manifest call site после успешной bytecode
verification, поэтому `call-sites.csv` содержит найденные sites даже до первого исполнения. Wrapper
записывает site ID/counters, опционально измеряет inclusive время и stack, затем синхронно вызывает
`market.advance(effectiveAmount)` в том же потоке и сохраняет exception propagation.

Concrete vanilla `Market.advance(float)` дополнительно имеет дешёвый entry probe. Calls от двух
источников единого scheduler-а, его fail-open/save-flush путей и instrumented mod sites помечаются
через `ThreadLocal` origin. Поэтому массовый известный vanilla
`planetConditionMarketOnly` путь больше не загрязняет `UNKNOWN_DIRECT`. Непомеченный вход получает
ограниченное число stack samples **на каждый отчётный интервал**, что сохраняет шанс обнаружить
поздние reflection/MethodHandle и нестандартные loader paths.

Настройки `profiles/debug.properties`:

```properties
patch.directMarketObservation=true
directMarket.timingSampleEvery=128
directMarket.stackSampleEvery=2048
directMarket.maxStacksPerSite=8
directMarket.reportIntervalSeconds=15
directMarket.maxSites=4096
directMarket.unknownStackSamples=32
```

`session.json` использует schema 3 и содержит `sessionOrigin`. Обычный запуск имеет значение
`game`; startup/FR validation smoke получает отдельное значение и заметный префикс каталога, чтобы
короткую тестовую JVM нельзя было принять за игровую телеметрию.

Каждый запуск создаёт отдельную session-директорию:

```text
logs/direct-market-observe/session-[<origin>-]<UTC>-pid<PID>/
```

`call-sites.csv` и `observations.csv` записывают `mod_id`, `mod_name`, `mod_directory` и `jar_name`
отдельными колонками. Значения извлекаются из `mod_info.json` владельца code source; directory name
используется только как fallback. Точный `source` path сохраняется отдельной колонкой и не требуется
для ручного определения мода.

Для анализа нужны все файлы session вместе с `logs/prepatcher.log`. Observer не удерживает
`MarketAPI` references, не пишет данные в save, работает только на daemon reporter thread и
fail-open при любой telemetry/file ошибке. Ненулевой диагностический overhead принят только в
`profiles/debug.properties`; этот профиль наследует все значения aggressive и добавляет только
утверждённые диагностические опции. Default, safe и aggressive profiles держат observer выключенным.

### Persistent economy snapshots

`patch.economyPersistentSnapshots` — единственный economy snapshot optimizer. Он заменяет горячие
defensive copies для списка markets в `Economy` и списков conditions/industries в `Market` на
owner-local copy-on-write snapshots. Отдельной scratch-стратегии и runtime hooks для неё больше нет.
Внутренний condition snapshot в `Economy.advanceMarketConditionsWhenPaused()` намеренно остаётся
vanilla fresh `ArrayList(Collection)`, поскольку его owner-local состояние принадлежит отдельным
`Market`, а не `Economy`.

Каждый transformed owner хранит private transient copy-on-write state:

- Economy: market snapshot, structure epoch и ReachEconomy location fingerprint;
- Market: отдельные condition и industry snapshots.

Owner-local fields убирают глобальный `IdentityHashMap` lookup из каждого `Market.advance()`.
Конструкторы, `readResolve()` и `Market.clone()` создают независимые states. Published snapshot
никогда не очищается после публикации: nested/reentrant callback может создать и опубликовать новый
`ArrayList`, не изменяя список, который уже итерирует внешний callback.

Обычные `Economy.add/removeMarket`, `Market.add/removeCondition` и
`Market.add/removeIndustry` paths повышают structure epoch и перестраивают snapshot на следующем
borrow. Замена самого backing list обнаруживается немедленно по reference identity и также повышает
epoch. Прямые изменения identity/order элементов существующего live list обнаруживаются bounded
audit:

- Economy market list и market/location/id fingerprint: `economy.structureAuditMs`;
- Market conditions и industries: `market.structureAuditFrames`.

`RandomAccess` lists проверяются индексированным identity scan без iterator allocations; остальные
collections используют iterator fallback. Missing/foreign state и helper error возвращают свежий
vanilla-style `ArrayList`.

В persistent режиме ReachEconomy fingerprint хранится в том же Economy state, поэтому steady-state
location path не выполняет synchronized lookup глобальной weak map. Оригинальный
`ReachEconomy.updateLocationMap()` всё равно вызывается каждый frame: explicit dirty flag мода
остаётся authoritative. Unchanged periodic audit лишь переносит deadline и не создаёт новый
fingerprint.

### Incremental core-worlds extent index

`patch.coreWorldsExtentCache` is one fail-closed contract across three vanilla classes:

1. `CoreScript.advance(F)V` replaces the single terminal `Misc.computeCoreWorldsExtent()` after
   `RouteManager.advance(F)V` with `StarsectorPrepatcherCoreWorldsRuntime.update(SectorAPI)`;
2. `CampaignEngine.createStarSystem(String)` and `removeStarSystem(StarSystemAPI)` publish the two
   authoritative system-list mutations after the original mutation succeeds;
3. `BaseLocation.addTag(String)`, `removeTag(String)` and `clearTags()` publish all normal tag API
   exits so changes of `theme_core` update membership immediately.

Every matcher proves the local mutation/data-flow shape, exact hook multiplicity, owner marker,
idempotent postcondition and ASM verification without a class/JAR digest or fixed local slot. The
runtime enables the incremental path only after both mutation-hook class statuses are `APPLIED` or
`ALREADY_APPLIED`. A missing, partial or structurally changed surface therefore does not produce a
partially coherent cache: `CoreScript` calls the preserved vanilla computation until the complete
capability is available, and a terminal structural failure leaves it on that fallback.

The first update for a campaign identity obtains one defensive `SectorAPI.getStarSystems()` snapshot
and builds two process-local indexes: all systems and the subset currently tagged `theme_core`.
Entries hold only `WeakReference<StarSystemAPI>`. Normal create/remove/tag events update the indexes
incrementally. Since `StarSystemAPI.getLocation()` exposes a mutable `Vector2f`, each validated frame
still reads every known core-system coordinate; this is required to preserve direct coordinate
mutation semantics. Direct removal of `theme_core` from the live `getTags()` set is detected in that
same O(C) pass. Direct addition through the live set bypasses every setter, so a rotating audit checks
at most `coreWorlds.auditSystemsPerFrame` entries per validated outer frame (default `64`).

With `S` total systems, `C` core systems and audit budget `B`, the old implementation performed
Θ(S) traversal plus a fresh Θ(S)-reference `ArrayList` allocation every invocation. The incremental
runtime performs Θ(S) work/allocation once at campaign initialization or explicit anomaly recovery,
then Θ(C+B) work and no full-list allocation in steady state. At `S=2559`, `B=64`, a direct unhooked
core-tag addition is discovered within at most `ceil(S/B)` validated outer frames (about 40 with the
shipped values); API mutations are immediate. Rare create/remove/tag event lookup is O(S), but it is
outside the per-frame hot path and never materializes `SectorAPI.getStarSystems()`.

Vanilla origin-inclusive `+0.0f` min/max arithmetic and publication order for `$coreWorldsMin`,
`$coreWorldsMax`, and `$coreWorldsCenter` are preserved. Raw float-bit comparison suppresses the three
`Vector2f` allocations and `MemoryAPI.set` calls when bounds are unchanged. Deletion, replacement or
mutation of published vectors and optional timed expiry are repaired. `coreWorlds.validationFrames`
defaults to `1`; larger values intentionally allow bounded coordinate/membership staleness.
`coreWorlds.skipFastForwardIterations=true` skips intact extra fast-forward substeps, while safe mode
keeps O(C) coordinate validation on every substep without returning to an O(S) sector scan.

Lifecycle reset replaces both index containers at campaign boundaries, releasing their
backing-array high-water marks. Static state contains weak sector, system and published-vector
references only; there is no static game-object map, `ThreadLocal`,
game-instance field or serialized state. Runtime failures clear the index, execute the vanilla
calculation for the current call and rebuild from one authoritative snapshot on the next call.

Design rationale, complexity and release evidence:
[release report 0.12.0](releases/0.12.0.md#incremental-core-worlds-extent-index).

## Fast-forward presentation coalescing

| Config | Target/область | Изменение | Профиль и основной риск |
|---|---|---|---|
| `patch.fastForwardPresentation` | весь presentation-блок | master switch structural class-plan transformer/runtime | safe/default/aggressive/debug; выключение оставляет весь блок vanilla |
| `patch.fastForwardFrameMarker` | `CampaignState.advance` | отмечает outer frame, номер и число simulation substeps | обязателен для всех групп; mismatch fast-forward flag немедленно прекращает дальнейшее coalescing в этом frame |
| `patch.fastForwardActionIndicators` | `CampaignEngine` action indicators | один visual `advance` на последнем substep | safe/default/aggressive/debug; меняется только presentation cadence |
| `patch.fastForwardLocationVisuals` | `BaseLocation` light fader, background/stars, particle group | visual refresh один раз за outer frame | safe/default/aggressive/debug; визуальные lifetime/скорость следуют выбранному visual time |
| `patch.fastForwardFloatingText` | entity floating text, включая paused path | один visual `advance` на последнем substep | safe/default/aggressive/debug; текст не ускоряется вместе с simulation при `realtime` |
| `patch.fastForwardFleetView` | `CampaignFleetView.advance` | один view refresh за outer frame | safe/default/aggressive/debug; промежуточные substep states не рисуются |
| `patch.fastForwardFleetPresentation` | fleet layers/view clear/sensor range/pulse fader | объединяет fleet-only presentation work | safe/default/aggressive/debug; видимым остаётся финальное состояние outer frame |
| `patch.fastForwardSensorIndicators` | selection/contact indicators | один indicator refresh на последнем substep | safe/default/aggressive/debug; selection bridge fail-open при marker mismatch |
| `patch.fastForwardCelestialVisuals` | planets, jump-point rings/corona | объединяет графические animation calls | safe/default/aggressive/debug; nonlinear animation может отличаться при `simulation` visual time |
| `patch.fastForwardAuroraAnimation` | terrain `AuroraRenderer` | один aurora refresh за outer frame | safe/default/aggressive/debug; промежуточная визуальная анимация пропускается |
| `patch.fastForwardContinuousSound` | terrain, abilities, slipstream и gate loops/filters/music suppression | повторные audio refresh calls выполняются на финальном substep | safe/default/aggressive/debug; transient промежуточные audio parameters не подаются mixer'у |
| `patch.fastForwardGateJitter` | gate faders/warp/jitter seed | объединяет gate-only visual updates | safe/default/aggressive/debug; jitter RNG обновляется один раз только в подтверждённом N-step frame |
| `patch.fastForwardGlobalAnimations` | global `AnimationManager.advanceAll` | объединяет все global animation callbacks | **default/aggressive/debug; false в safe:** широкая callback/lifetime-семантика, возможны скачки и изменённая cadence |
| `patch.fastForwardSensorFaders` | entity sensor faders | один fader update за outer frame | **default/aggressive/debug; false в safe:** может менять visibility/despawn timing |
| `patch.fastForwardSlipstreamParticles` | slipstream particle add/advance | emission и particle advance только на финальном substep | **default/aggressive/debug; false в safe:** меняются density, lifetime, RNG и emission cadence |
| `patch.fastForwardParticleEmitters` | gate/mote/coronal/Zig emitter intervals | interval advances только на финальном substep | **default/aggressive/debug; false в safe:** меняются spawn count/timing и RNG sequence |

Simulation logic по-прежнему выполняется на каждом substep. Runtime coalescing действует только
внутри подтверждённого multi-step outer frame и выполняет целевой presentation-call на последнем
substep. Если frame marker, число шагов или `CampaignEngine.isFastForwardIteration()` расходятся с
ожидаемой формой, runtime фиксирует mismatch до конца frame и с момента обнаружения выполняет
последующие wrappers с vanilla cadence. Уже пропущенные calls ранних substeps намеренно не
проигрываются задним числом; следующий `beginOuterFrame` полностью сбрасывает это состояние.

Каждый из 24 target-классов имеет локальный class-level structural plan. Inspection проверяет
исходные и уже преобразованные call sites, data-flow receiver/arguments, control-flow anchors,
ownership field, feature mask и combined postcondition. Решение не зависит от SHA-256 classfile или
содержащего JAR. Изменения других методов, constant pool, debug metadata и необязательных class
attributes не блокируют патч, пока принадлежащая плану semantic surface остаётся совместимой.

Успешно преобразованный класс получает private synthetic constants
`smo$patched$fastForwardPresentation` и `smo$fastForwardPresentationMask`. Все включённые компоненты
одного класса устанавливаются одной транзакцией. Допустимы только полностью vanilla state либо
полностью patched state с ожидаемой mask. Partial, ambiguous, foreign hook-shaped и mask-mismatch
состояния получают `SKIPPED_STRUCTURAL` без частичного изменения class bytes.

`fastForward.visualTime=realtime` передаёт финальному call обычный `amount`: визуальная/audio
presentation идёт в реальном frame cadence, пока simulation ускорена. Значение `simulation`
умножает `amount` на число substeps; оно сохраняет суммарное visual time, но может давать заметные
скачки и нелинейные отличия. `fastForward.verbose` управляет подробными сообщениями об успешном
применении; structural/loader/error skips всегда остаются видимыми. `fastForward.metrics` включает
накопительные frame/substep/skip counters. Оба диагностических переключателя включены только в
`profiles/debug.properties`.

Safe profile включает master/frame marker и консервативные группы до gate jitter включительно;
четыре bold-marked группы остаются `false`. Default profile машинно проверяемо совпадает с aggressive
и включает все группы, но не меняет `visualTime=realtime`. Название safe означает более узкую область риска, а не
byte-for-byte visual parity: сама цель блока — намеренно убрать повторные presentation callbacks.

Интеграция не устанавливает второй agent. Structural presentation transformer регистрируется внутри того же
`StarsectorPrepatcherAgent.jar` перед structural transformer, а
`StarsectorPrepatcherPresentationHooks` входит в общий target/game-loader runtime payload. Поэтому
для vanilla и Faster Rendering сохраняется одно loader-identity правило и одна `-javaagent` запись;
исходный отдельный FastForward Presentation Patch agent одновременно устанавливать не нужно.

Порядок `presentation → structural` является частью runtime-контракта, а не только порядком строк
регистрации. На пяти общих target-классах presentation pass добавляет private synthetic
`smo$patched$fastForwardPresentation` и `smo$fastForwardPresentationMask`. Structural transformer до
анализа принимает только полностью vanilla presentation-state либо owner/mask с точным набором
`StarsectorPrepatcherPresentationHooks`; после каждого structural commit и в финале этот набор
перепроверяется. Marker без hooks, hooks без marker, неверная mask или повреждение одного wrapper
дают `SKIPPED_COMPOSITION` и оставляют входные bytes активными. Поддерживаемый runtime-порядок
остаётся `presentation → structural`; offline reverse-order проверка либо структурно доказывает
локальную surface, либо локально возвращает `SKIPPED_STRUCTURAL`.

Ранняя загрузка presentation-only target не является причиной отменять прежний structural-блок:
этот target получает `SKIPPED_ALREADY_LOADED` и остаётся vanilla, а остальные targets продолжают
загружаться через оба transformer'а. Если уже загружен обязательный `CampaignState` frame marker,
отключается только presentation transformer; structural patches всё равно устанавливаются.

Faster Rendering может изменить target до вызова Instrumentation. Нерелевантная для presentation
surface модификация больше не блокирует patch plan. Если FR изменил сам owned call site, receiver,
arguments или control-flow anchor, соответствующий класс получает `SKIPPED_STRUCTURAL`, тогда как
его независимые structural patches продолжают применяться.


## Hyperspace

| Config | Target | Изменение | Принятое поведение/риск |
|---|---|---|---|
| `patch.hyperspaceViewportBounds` | `BaseTiledTerrain.render/isTileVisible` | atomically corrects the vertical range to use viewport height and clamps it to the inner tile-array dimension | both sites in both methods must match before either is changed; affects every subclass by design |
| `patch.skipNoOpTerrainLayer` | `HyperspaceTerrainPlugin.getActiveLayers` | removes `TERRAIN_9` from backing set | also skips that layer's `preRender` sequence by design |
| `patch.terrainRandomReuse` | tiled/hyperspace terrain | seeded `Random` ring + batched diagnostics | same seed/draw sequence; cumulative approximate counter; no per-tile `LongAdder`/clock call |
| `patch.automatonBufferReuse` | automaton + terrain internal reads | owner-local spare `int[][]`; confirmed exact-owner internal reads bypass public escape mark | public/mod/subclass/unconfirmed paths use virtual getter; escaped aliases are never reused; transient state, zero-init |
| `patch.starfieldCleanupBuffers` | parallax starfield implementation | reusable stale list | two-phase cleanup retained |
| `patch.starfieldLinearRemoval` | same | thresholded stable iterator removal | order retained; equality-aware fallback |



## Local Resources и economy-group hot paths

### `patch.localResourcesNoColdMarketData`

Target surfaces are the exact vanilla methods
`LocalResourcesSubmarketPlugin.getStockpileLimit(CommodityOnMarketAPI)` and
`shouldHaveCommodity(CommodityOnMarketAPI)`. They form one atomic capability: if either bytecode
surface changes, both remain raw.

`getStockpileLimit()` no longer reaches an instance through
`CommodityOnMarket.getCommodityMarketData()` merely to call `getMaxShipping()`. The latter is a
stateless forwarding method, so the transformed site calls the same static
`CommodityMarketData.getShippingCapacity(market, false)` calculation directly. Warm results are
unchanged, while a Local Resources frame can no longer construct a global commodity model through
that call site.

For illegal commodities, `shouldHaveCommodity()` first peeks the already-published transient
market-data reference. An exact warm object uses the original `MarketShareData.sourceIsIllegal`
semantics. An exact cold vanilla commodity uses its last committed legal supply/demand snapshot.
The maintained AoTD fork is handled separately: a loader-local `ClassValue` verifies the exact
`AoTDCommodityOnMarket`/`AoTDAvailableStat`/`AoTDSupplyDemandData`/calculation-script surface, peeks
only previously published supply/demand data, and runs the same
`convertRawUnitsToSupply/Demand()` methods used by the fork. It does not invoke AoTD's lazy getter,
refresh industries, or materialize `AoTDCommodityMarketData`. Missing committed data, a repairable
wrong market-data type, linkage failure, another subclass, or a future changed fork surface calls
the preserved raw getter instead of guessing.

The runtime stores only core `VarHandle` metadata, primitive counters and unload-safe `ClassValue`
accessors. It does not keep a static `MarketAPI`, commodity, optional `Class`, `Method`,
`ClassLoader`, or mod instance.

### `patch.economyGroupIndex`

`ReachEconomy.getMarketsInGroup(String)` is wrapped around a private retained raw method. The index
state is a private transient synthetic field of the owning `ReachEconomy`; it contains one ordered
market array plus primitive bucket indices. Every successful lookup creates a new mutable
`ArrayList`, preserving caller mutation freedom and source order. Unknown group names return a new
empty list and are not inserted into the index.

`ReachEconomy.addMarket/removeMarket` and `Market.setEconGroup` advance a primitive structure epoch.
Each borrow additionally checks active campaign generation, owner identity, source-list identity
and size; a timed ordered identity/group audit covers direct list or reflective mutation. Rebuild
replaces exact-sized arrays and removal clears the previous owner-local arrays immediately. Thus the
cycle `ReachEconomy -> index -> markets -> ReachEconomy` has no static root and is collected with the
campaign.

The exact maintained `AoTDReachEconomy` uses the indexed inherited path only while
`getMarketsInGroup`, `getMarkets`, `isInGroup` and `removeMarket` remain vanilla-owned. The
real-fork build gate additionally verifies that the currently supplied fork-owned `addMarket`
delegates exactly once to `ReachEconomy.addMarket`. A future critical read override is rejected for
that concrete runtime class. If a future `addMarket` body stops delegating, immediate epoch
invalidation is no longer guaranteed, but source-list identity/size checks and the bounded ordered
audit still detect the mutation and rebuild the owner-local index; the exact-JAR compatibility gate
must then be updated before claiming the same immediate-invalidation performance contract. The
shipped profiles explicitly enable the feature; an old/custom config missing the key remains
fail-closed.

## Market share и punitive expeditions

### `patch.marketShareLinearAggregation`

Target: vanilla `com.fs.starfarer.campaign.econ.reach.CommodityMarketData`; его wrapper также
обслуживает owned AoTD Scheduler Fork
`data.kaysaar.aotd.tot.scripts.commoditydata.AoTDCommodityMarketData`.

Исходный `getMarketSharePercentPerFaction()` для каждого впервые встреченного faction key
повторно вызывает `getMarketSharePercent(faction)`, а тот снова материализует список рынков и
обходит всю economy group. Wrapper сохраняет исходный метод как private synthetic raw fallback и
выполняет один snapshot/проход для exact vanilla и подтверждённого AoTD-класса.

AoTD eligibility вычисляется один раз на runtime class через loader-local `ClassValue<Boolean>`.
Fork допускается только пока `getMarketSharePercentPerFaction`, `getMarketSharePercent`,
`getMarkets`, `getExportMarketSharePercent` и `getMarketShareData` разрешаются непосредственно в
vanilla `CommodityMarketData`. Любой будущий override, ошибка reflection или другой subclass
автоматически выбирает raw fallback. ClassValue не хранит strong map по `Class`/`ClassLoader` и не
мешает сборке optional mod loader.

Сохраняются новый mutable `LinkedHashMap` на каждый вызов, порядок первого ключа, zero-share
entries, `FactionAPI.equals()` для состава ключей, identity для ownership contribution,
player-owned union, `int` overflow и существующее округление внутри
`getExportMarketSharePercent()`.

Все дополнительные `FactionAPI[]`, `IdentityHashMap` и accumulator arrays локальны одному вызову.
Static/instance campaign cache, `ThreadLocal`, ссылки на sector/economy и изменения save schema
отсутствуют.

### `patch.marketShareDataPutElision`

Target: concrete `CommodityMarketData.getMarketShareData(MarketAPI)`.

Патч перемещает уже существующий `LinkedHashMap.put()` внутрь ветви `data == null`. Он не меняет
key set, insertion order или identity возвращаемого `MarketShareData`; для существующей записи
устраняется только лишний hash-table update. Патч имеет отдельный owner marker и после применения
повторно проверяет postcondition линейного wrapper того же класса.

### `patch.punitivePlayerShareLocalCache` и `patch.nexPunitivePlayerShareLocalCache`

Первый флаг управляет только vanilla `PunitiveExpeditionManager`, второй — только
optional `exerelin.campaign.intel.Nex_PunitiveExpeditionManager`. Оба включены по
умолчанию. Трансформация и её fail-closed postcondition у targets одинаковы, но
решение о попытке патча принимается независимо для каждого класса.

В `getExpeditionReasons()` создаётся один method-local `IdentityHashMap`. Competitive branch
извлекает player value из уже построенной per-faction map только при identity-совпадении ключа;
при отсутствии ключа выполняется исходный single-faction getter. Free-port call site использует
тот же local cache, поэтому повторные loop iterations для одного `CommodityMarketData` выполняют
исходный getter один раз. Exact vanilla и структурно совместимый owned AoTD
`AoTDCommodityMarketData` используют cache; неизвестная API-реализация, другой subclass или
будущая AoTD-версия с critical override остаются на прямом пути и сохраняют исходную кратность
вызовов.

Optional Nex class не добавляется в обязательный core target inventory и не хранится как
`Class<?>`, `Method`, `ClassLoader` или runtime object. Helper внедряется private static synthetic
непосредственно в target class; cache живёт только до возврата метода. Если точная data-flow
структура двух call sites не доказана, весь Nex player-share target получает `SKIPPED_STRUCTURAL` без частичной
модификации.

## Намеренно не реализовано

- storm/automaton update throttling;
- dropped simulation debt;
- неявные callback-frequency changes вне явно документированных `patch.marketScheduler` и exact dormant-наследников `BaseIndustry`;
- public combat-grid reuse;
- generic particle object pooling;
- fleet-pair broadphase без runtime parity harness;
- GL batching/FBO/VBO;
- inter-frame terrain geometry cache;
- save-format или serialized-object changes.

## `aotdCleanDeficitPath`

Target: clean game `BaseIndustry.getMaxDeficit(String...)`.

The patch preserves the original method under a private synthetic name and installs a thin
resolver wrapper. Without the complete active AoTD native contract, the original vanilla code is
called.
With the complete `0xff` contract, the source-level AoTD priority-deficit resolver is used.
