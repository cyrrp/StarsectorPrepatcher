# Журнал изменений

Здесь фиксируются заметные пользовательские изменения StarsectorPrepatcher. Формат основан на
[Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии следуют
[Semantic Versioning](https://semver.org/lang/ru/).

Публичная версия всегда имеет вид `X.Y.Z` без названий веток и суффиксов `exp*`/`integrated`:

- `X` меняется при несовместимом изменении;
- `Y` — при добавлении совместимой функциональности;
- `Z` — при совместимом исправлении ошибки или документации.

До `1.0.0` проект остаётся экспериментальным, но придерживается этой схемы настолько строго,
насколько позволяет модификация внутренних классов Starsector. История экспериментальных веток
остаётся в истории Git и не дублируется в поставке.

## [Unreleased]

### Добавлено

- Location-agnostic incremental index `StrategicModule` по destination `LocationAPI`. Cold build,
  replacement, point refresh и continuous audit разделены на элементарные операции под единым
  wall-clock/work-unit budget; lookup не выполняет синхронный полный scan. Пока snapshot не готов,
  существующий истёкший `JumpPlan` сохраняется, а новый rebuild откладывается. Добавлены точные hooks
  `JumpPoint`, `JumpDestination` и `BaseLocation`, negative-result cache, failure cooldown, bounded LRU,
  fixed weak owner accelerator и campaign-generation reset без weak-key/value retention cycle.
- Full-call enforcement стратегического budget: lock/selection/queues/LRU/cleanup теперь входят в
  token bucket; retry LRU scan заменён indexed min-heap, audit scan — O(1) round-robin queue. Capacity
  pressure больше не вытесняет незавершённые builds. Добавлены phase/queue/maintenance-overrun и
  oldest deferred-route diagnostics для проверки gameplay latency debt.

## [0.11.0] - 2026-07-24

### Добавлено

- Чистая обёртка `BaseIndustry.getMaxDeficit()` сохраняет vanilla-реализацию и
  включает AoTD priority-deficit semantics только после полного native handshake.
- Capability `CLEAN_DEFICIT_SEMANTICS`, координация campaign/economy epoch и полный
  production-профиль AoTD с mask `0x1ff`.
- `SchedulerBridge` schema V6 с прямой loader-safe публикацией runtime epoch и запросом
  актуальной capability mask.
- Explicit `AOTD_NATIVE` mode для commodity temporal fast path. Availability, excess,
  deficit и trade stats переходят в спящий режим при пустых temporal maps и просыпаются
  от существующих mutation hooks.
- Sampled-телеметрия exact-vanilla/AoTD-native/vanilla-only inventories, active sets и
  scheduler due/policy decisions.
- Единый интерактивный `StarsectorPrepatcher.bat` для сборки, установки, удаления и
  полного release gate с выбором Vanilla/Faster Rendering/Both и CLI-режимом.
- Structural/runtime validation для raw fallback, resolver path и повторной трансформации.

### Изменено

- AoTD Scheduler Fork больше не требует и не допускает старую замену `starfarer.api.jar`.
- Non-structural AoTD dirty masks сохраняются без ложного повышения structural generation;
  вложенные mutation boundaries объединяют маски и публикуют не более одной structural revision.
- Lookup `getExcDefData/excess/deficit` кэшируется при построении market state и не
  выполняется в `advancePrepared()`.
- Condition-only рынки вне economy получают кэшируемую и staggered due-проверку; economy markets,
  пришедшие через тот же call site, сохраняют точную покадровую policy.
- Delivered-time callbacks для рынков вне economy не публикуются в AoTD registry и учитываются
  отдельной диагностикой.

### Исправлено

- AoTD native mutation boundaries больше не выполняют повторный exact replay при
  вложенном вызове: вложенная граница объединяет reason mask и использует token
  внешней границы. После внешнего replay runtime повторно читает market state,
  поскольку replay может вызвать campaign callbacks.
- При смене runtime epoch очищается loader-neutral market state, а поздние callbacks
  старой global boundary отклоняются без изменения generation новой кампании.
- После fail-stop отключения delivery listener актуальная capability mask немедленно
  видна AoTD; fork один раз синхронизирует пропущенные поколения и включает fallback dirtying.
- Кэш reflection-accessors для AoTD temporal stats переведён с process-lifetime map на
  unload-safe `ClassValue`, поэтому он не удерживает classloader завершённой кампании/сборки.

Подробности и состав проверок: [отчёт о выпуске 0.11.0](docs/releases/0.11.0.md).


## [0.10.0] - 2026-07-21

### Market.advance step semantics

- Added raw-float RLE pending history and reusable market-specific batch-context stack.
- Added local exact-step replay wrappers for `MilitaryBase`, `LionsGuardHQ`, and `RecentUnrest`.
- Added temporary construction full-rate mode plus exact mutation barriers for `Market`, `BaseIndustry`, and `ConstructionQueue`.
- Added coalesced/exact save modes, scheduler semantic metrics, and a behavior-neutral mod risk observer.
- Fixed the scheduler capability gate so deferral requires all eleven core/semantic components,
  including `Market.advancePlan`, the three replay wrappers and both construction-barrier groups.
  Missing registration or a structurally skipped wrapper now keeps the scheduler synchronous.
- Made semantic-risk observer-only mode strictly static even when the inspected class contains a
  direct `Market.advance()` call; hierarchy classification is transitive and report dedup includes
  source/mod identity.
- Replaced per-input construction scans with mutation epochs plus a bounded safety audit, removed
  duplicate queue-owner registration, and added a JAR-wide construction-mutator inventory test.
- Defined save callback failure as non-retriable once invocation begins: ambiguous detached debt is
  discarded, the market is disabled into synchronous fail-open, and the save exception propagates.
- Documented that local component replay does not prove global intercomponent/RNG ordering without
  campaign-level differential tests.

### Изменено

- Во всех поставляемых профилях увеличены bounded safety-audit интервалы: economy structure до
  `5000` ms, market structure/no-op industry до `600` frames, commodity temporal до `300` frames и
  construction до `180` batches. Scheduler-enabled профили используют policy audit `300` batches;
  safe не задаёт этот неиспользуемый при выключенном scheduler параметр.
- Дорогие direct-market/construction/semantic-risk observers, CSV/stack sampling, verbose
  presentation logging, presentation metrics и периодический stats worker вынесены из default,
  safe и aggressive в отдельный `profiles/debug.properties`. Debug-профиль наследует все значения
  aggressive; repository consistency test запрещает их расхождение и посторонние debug-only ключи.
- Интегрирован CoreScript core-worlds optimizer: terminal `Misc.computeCoreWorldsExtent()` в
  `CoreScript.advance(F)V` заменяется helper-ом общего runtime payload. Exact class/JAR SHA-256
  gating удалён; совместимость теперь доказывается тем же локальным structural contract,
  ownership marker, postcondition и ASM verification, что и у остальных патчей. Sector local
  выводится из `Global.getSector()`/`ASTORE`, поэтому matcher не зависит от номера local slot.

- Direct-market call-site metadata переведены на schema 2: `call-sites.csv` и
  `observations.csv` теперь содержат отдельные `mod_id`, `mod_name`, `mod_directory` и `jar_name`,
  разрешённые из `mod_info.json` владельца code source. Точный `source` path сохранён отдельным
  полем; runtime parser продолжает принимать прежнюю schema 1.
- Construction detector получил полную reason telemetry: queue/building/upgrading/uncertain gauges,
  dirty/safety/forced scan counters, стоимость обходов, state/reason transitions и mutation races.
  Отдельный выключенный по умолчанию `observer.marketConstructionDiagnostics` пишет bounded
  per-reason CSV без сильных ссылок на market/industry objects и не влияет на scheduler policy.
- Upgrade-only reason (`isUpgrading() && !isBuilding()`) переведён в diagnostic-only: он остаётся в
  counters/gauges и CSV, но больше не удерживает рынок в full-rate. Для наследников `BaseIndustry`
  active construction теперь определяется по raw-полю `building`, а virtual `isBuilding()` остаётся
  telemetry/fallback для произвольных реализаций `Industry`. Расхождение virtual=true/raw=false
  получает отдельный reason, counter, gauge и CSV bucket; CSV также содержит раздельные источники,
  scalar `BaseIndustry` state и transition buckets `TRANSITION_4_TO_6`/`TRANSITION_4_TO_132`.
- Direct-market identity resolver читает только root-level `id`/`name` из `mod_info.json`; вложенные
  dependency descriptors больше не могут подменить владельца JAR.
- `FastForwardPresentationTransformer` переведён с whole-class/JAR SHA-256 gating на 24 локальных
  structural class plans. Все включённые компоненты класса применяются одной транзакцией; каждый
  transformed target получает общий owner и global feature mask. `CampaignState` выводит substep и
  total locals через data-flow/loop protocol, а `CampaignFleet` выбирает pulse fader относительно
  sensor-range presentation block, не по порядковому номеру. Удалены прежняя JAR-hash guard setting и
  hash-specific statuses; unrelated classfile changes больше не блокируют compatible call sites.


### Объединено

- Пересечения presentation transformer и structural transformer закрыты единым
  ownership/postcondition контрактом для `CampaignState`, `CampaignEngine`, `BaseLocation`,
  `BaseCampaignEntity` и `HyperspaceTerrainPlugin`. Presentation-stage публикует private synthetic
  owner и feature mask; structural-stage принимает только полностью vanilla или полностью
  подтверждённое presentation-состояние, перепроверяет его после каждого structural commit и в
  финальной композиции. Partial/foreign hooks отклоняют structural invocation до любых изменений;
  обратный порядок не является runtime-контрактом и проверяется локально structural matcher-ом.
- Save method `CampaignGameManager.o00000(CampaignEngine$o,J,Z)` разделён на correctness-critical
  `saveMethodPlan` и ordered optional `saveOutputBufferDedup`. `saveMethodPlan` атомарно владеет только
  forced cache maintenance и scheduler pre-save flush/registration. Output-chain rewrite применяется
  после него с отдельным marker и обязан сохранить barrier postcondition. Несовместимость 1 MiB
  allocation pattern теперь пропускает только dedup и больше не отключает scheduler capability;
  malformed barrier, напротив, не может зарегистрировать save-компонент, но не блокирует независимый
  buffer patch.
- `Economy.advance(F)V` и связанное owner-local состояние теперь имеют единый structural patch plan
  `economyAdvancePlan`. Независимые config keys persistent snapshots, location cache и market
  scheduler source формируют точную feature mask, но анализируются и коммитятся одной транзакцией
  в порядке snapshots → location cache → scheduler source. Legacy split markers, unowned partial
  state, повреждённая combined postcondition или несовместимость любого компонента оставляют весь
  класс `Economy` неизменённым.
- `Market.advance(F)V` теперь имеет единый structural patch plan и ownership marker
  `marketAdvancePlan`. Независимые config keys persistent snapshots, commodity temporal fast path и
  direct entry observation формируют точную feature mask, но анализируются и коммитятся одной
  транзакцией в явном порядке. Legacy split markers, unowned partial state, повреждённая combined
  postcondition или несовместимость любого запрошенного компонента оставляют весь класс `Market`
  неизменённым.
- `Z.o00000(FF)V` теперь принадлежит одному атомарному `patch.intelArrowRendering`: `getArrowData` cache, две reusable `Vector2f` и общий reentrant scratch scope анализируются и устанавливаются вместе. Старый `patch.arrowVectorPool` удалён без alias; `patch.intelCallbackCache` продолжает владеть только независимыми `getMapLocation` sites в `A` и `EventsPanel`. Mixed/partial state оставляет весь arrow-rendering path vanilla.
- `EventsPanel.addMissingIconsAndRows()` теперь принадлежит одному атомарному `patch.intelReconciliation`: missing-plugin membership, direct existing-icon candidates и общий reentrant scratch scope анализируются и устанавливаются вместе. Старые `patch.intelFastContains` и `patch.intelExistingIconLookup` удалены без aliases; mixed/partial state оставляет всю reconciliation-группу vanilla, не отменяя независимый `patch.intelCallbackCache`.
- Весь `A.OO0000(FFF)` hit-test теперь принадлежит одному атомарному `patch.mapHitTest`: reusable
  hit list, reusable hit point, reentrant scratch scope, preserved original method и bounded exact-result
  cache wrapper анализируются и устанавливаются вместе. Старые `patch.scratchCollections` и
  `patch.hoverHitTestCache` удалены без aliases; mixed/partial wrapper, scope или allocation state
  оставляет весь hit-test vanilla, не отменяя независимый Intel callback patch класса `A`.
- `H.renderStuff(FZ)V` теперь изменяется одним атомарным `patch.mapRenderStuff`: semantic
  `retainAllFast`, reusable entity list, reusable class set и общий reentrant scratch scope
  анализируются и устанавливаются как одна группа. Старый `patch.retainAll` удалён.
  Mixed/partial state отклоняет всю группу без потери других патчей класса `H`.
- Старые `patch.remoteMarketScheduler` и `patch.planetConditionMarketScheduler` удалены без aliases.
  Их заменяет один `patch.marketScheduler` с единым hook, одной identity-keyed state map, одной
  policy, одним pending debt и одним pre-save flush для Economy-loop и planet-condition источников.
  Режим `FRAME_CONTEXT_ONLY`, отдельные runtime hooks/state maps и агрегированные fallback counters
  удалены. Source id используется только для диагностики.
- FastForward Presentation Patch `1.1.0` интегрирован как structural transformer внутри единого
  `StarsectorPrepatcherAgent.jar`. Его hooks входят в общий target/game-loader runtime payload;
  отдельный FFP-agent, вторая `-javaagent` запись и дублирующая startup-инфраструктура не нужны.
- Presentation transformer регистрируется перед structural transformer Prepatcher, чтобы проверять
  исходные bytes каждого target, сохраняя общий classloader guard для vanilla и Faster Rendering.

### Удалено

- Удалён отдельный `patch.economySnapshotReuse`: config field, transformer paths, ownership markers,
  scratch runtime hooks и метрика `economySnapshotElements` больше не входят в агент. Старый key
  только выдаёт предупреждение и не выбирает трансформацию.
- `patch.economyPersistentSnapshots` больше не скрывает scratch-оптимизацию paused-condition loop;
  этот редкий участок сохраняет vanilla fresh-copy поведение.

### Исправлено

- Scratch high-water теперь фиксируется в ASM-доказанных mutation sites, а не только при
  `ScratchFrame.clear()`: peak retained list/set сохраняется даже после caller `clear()` до выхода
  из scope. Возвращаемые коллекции остаются exact `ArrayList`/`HashSet`; tracked subclasses не
  используются.
- После `scratch.trimGraceMs` удаляются trailing inactive frames, созданные патологической глубокой
  реентерабельностью; сохраняются четыре обычных frame для штатной вложенности. Active/nested frame
  не удаляется.
- Starfield `PooledList.add/addAll` больше не вызывает `nanoTime()` на каждой операции: hot path
  обновляет только integer high-water, а oversized timestamp ставится одним clock read при release.
- `scratchRetainedListCapacity` и `scratchRetainedSetCapacity` теперь суммируют high-water каждой
  retained коллекции. Campaign generation reset немедленно сбрасывает scratch gauges в EMPTY
  snapshot.
- Hover freshness и physical pruning используют одинаковую границу: `age < TTL` валиден,
  `age >= TTL` просрочен.

- Market scheduler cadence переведён с simulation ticks на render batches. Граница batch определяется
  через `CampaignEngine.setFastForwardIteration(false)`, поэтому при постоянном `amount` и ускорении
  ×4/×10/×100 число callback-ов batch-managed markets не растёт пропорционально числу simulation
  iterations; весь accumulated amount доставляется на batch boundary.
- Шесть оставшихся core create/remove `MarketAPI.advance(0f)` call sites направлены через дешёвый
  synchronous debt hook. Coverage test требует два periodic и шесть synchronized core sites и падает
  при появлении нового неизвестного call site.
- Full-rate memory opt-out заменён явным per-simulation-tick key
  `$starsectorPrepatcher_perSimulationTickMarket`; stats показывают текущее число таких рынков, их
  callbacks и изменения флага. Routine game-day/deferred-call caps удалены.
- `patch.hyperspaceCulling` и `patch.hyperspaceYClamp` объединены в атомарный
  `patch.hyperspaceViewportBounds`: обе связанные части вычисления вертикальной границы во всех
  ожидаемых viewport-методах сначала структурно проверяются и только затем изменяются вместе.
  Частично исправленное или неоднозначное состояние больше не допускает применения второй половины.
- Старые два config key принимаются как deprecated aliases только при одинаковом значении; при
  расхождении атомарный патч выключается вместо частичного исправления.

### Добавлено

- Campaign-thread cache maintenance для `LABEL_INDEXES`, `INTEL_ENTITY_INDEXES` и `HIT_CACHES`:
  отдельные owner idle TTL, линейный LRU при лимитах 32/32/64, интервальное удаление expired hover
  cells и generation-safe throttle. Forced sweep встроен первой save-boundary операцией перед
  market-debt flush и XStream serialization; отдельного maintenance thread и `System.gc()` нет.
- Delayed high-water trimming reusable `ScratchFrame` и hyperspace/starfield `ListPool`. Oversized
  storage сохраняется в течение configurable grace period, никогда не заменяется внутри active или
  nested scope и затем освобождается заменой свободного frame/drop списка. Добавлены gauges retained
  capacity/pool sizes и cumulative idle/LRU/prune/trim counters.
- Детерминированный `CacheMaintenanceRuntimeTest` с synthetic clock покрывает TTL boundaries, LRU,
  hover prune interval, generation isolation, forced maintenance, nested/exceptional scratch close и
  starfield grace trimming.

- Единая telemetry market scheduler: source counters используются только для attribution, а причины
  fail-open разделены на not-ready tick/call, nested tick, outside tick, lifecycle, frame capture,
  invalid input, state, reentrant, duplicate tick, policy, callback, direct synchronization и
  save-flush counters. Агрегированного fallback counter нет; все периодические counters используют
  `sumThenReset()`.
- Четыре обязательных market-scheduler component регистрируются после нормальной инициализации
  классов. Deferral включается только после готовности CampaignEngine tick/lifecycle, Economy source,
  entity source и save flush; частичная установка остаётся synchronous-immediate и не создаёт debt.
- Scheduler tick перенесён с `Economy.advance()` на весь `CampaignEngine.advance()`, поэтому
  planet-condition source не зависит от чужого scratch scope. State map использует weak identity
  keys, duplicate same-tick call потребляет ранний debt, direct mod calls синхронизируют pending,
  callback failure отключает scheduling конкретного market, а failed pre-save flush прерывает save.
- Runtime regression защищает capability/lifecycle gate, реальную tick boundary, deferred duplicate
  ordering, callback exception policy, save-abort/restore, direct synchronization и weak-key GC.
- Master/frame marker и отдельные переключатели fast-forward coalescing для action/location/floating
  text, fleet, sensor, celestial/aurora, continuous sound и gate presentation. Simulation продолжает
  выполняться на каждом substep; подтверждённые presentation calls выполняются один раз на финальном
  substep outer frame.
- Опции `fastForward.visualTime`, `fastForward.verbose` и
  `fastForward.metrics`. Режим `realtime` оставляет один обычный visual-time шаг, а opt-in
  `simulation` накапливает время substeps и может давать заметные нелинейные скачки.
- Global animations, sensor faders, slipstream particles и particle emitters выделены в aggressive
  opt-in groups из-за возможных изменений callback/lifetime, RNG и emission cadence. Default/safe
  profiles оставляют эти четыре группы выключенными.

### Совместимость

- Presentation targets используют локальные structural contracts и не зависят от SHA-256
  classfile/JAR. Partial/ambiguous/foreign hook-shaped states получают `SKIPPED_STRUCTURAL`.
- Уже загруженный presentation-only target получает локальный `SKIPPED_ALREADY_LOADED` и больше не
  отменяет установку прежних structural patches. Если до premain уже загружен обязательный
  `CampaignState` frame marker, выключается только presentation transformer.
- Faster Rendering с пустым `CodeSource` проверяется через exact defining-loader JAR resource.
  Классы, которые FR изменил до Instrumentation, применяются при неизменной local presentation
  surface либо получают локальный `SKIPPED_STRUCTURAL`; независимые structural patches продолжают
  применяться.

Подробности и performance-контекст: [отчёт о выпуске 0.10.0](docs/releases/0.10.0.md).

## [0.9.5] - 2026-07-18

### Объединено

- Параллельная ветка no-op market callbacks объединена с `0.9.4` в один unified transformer и один
  startup-javaagent. Полная замена `Market.advance()` и отдельный helper-agent не используются.
- Полезная industry-идея переработана в прямой dormant fast path внутри exact
  `BaseIndustry.advance(float)`: исходное тело сохранено как private synthetic raw method, а
  per-instance state хранится в двух private transient synthetic полях.

### Изменено

- После полного vanilla-вызова inherited `BaseIndustry.advance()` может пропускаться, пока
  `building=false`, `wasDisrupted=false` и не наступил `market.noOpIndustryAuditFrames`.
- `BaseIndustry.setDisrupted(float, boolean)` немедленно сбрасывает dormant state до выполнения
  исходного метода. Custom `advance`, `isDisrupted` и `getDisruptedKey` остаются на полном vanilla
  cadence.
- Пустые inherited `BaseMarketConditionPlugin.advance()` намеренно не перехватываются: отдельный
  classifying helper оказался дороже самого пустого virtual callback.

### Совместимость

- Публичный API и save graph не изменены; synthetic state transient. На fast path отсутствуют
  reflection, global `IdentityHashMap`, helper-call, allocation и per-call telemetry counter.
- Мод, который меняет private disruption memory в обход `setDisrupted()`, может быть замечен только
  на bounded audit. Это осознанная aggressive-семантика; safe profile выключает патч.
- Structural mismatch, partial prior patch или неизвестный override contract оставляют
  `BaseIndustry` vanilla без отключения других блоков.

### Проверено

- Structural retained-body/postcondition и `setDisrupted` wake-prologue.
- Actual-javaagent smoke с dormant skip, periodic audit, immediate disruption wake и custom override
  fallbacks; building guard проверяется structural postcondition.
- Runtime config/classification suite, API snapshot, ASM `BasicVerifier`, lifecycle/save/hyperspace и
  startup regressions.

Подробности: [отчёт о выпуске 0.9.5](docs/releases/0.9.5.md).

## [0.9.4] - 2026-07-18

### Объединено

- В unified transformer интегрирована параллельная ветка commodity temporal active set. Она
  работает поверх одновременно интегрированного direct `MutableStatWithTempMods` hybrid и не устанавливает второй
  javaagent и не заменяет игровой класс целиком.
- `Market.advance()` теперь хранит private transient ordered state и не выполняет четыре
  `MutableStatWithTempMods.advance()` плюс `CommodityOnMarket.reapplyEventMod()` для commodities,
  у которых нет временных modifiers и не было изменений relevant stats.

### Добавлено

- `patch.commodityTemporalFastPath` с bounded audit `commodity.temporalAuditFrames`.
- Private transient owner/role binding в точном vanilla `MutableStat`: обычные `modify*`,
  `unmodify*`, `setBaseValue` и temporary-mod mutators немедленно будят owning commodity.
- Private audit bridge к direct expiry scheduler: periodic market audit материализует или
  перестраивает nearest-deadline state и восстанавливает работу после прямых map-изменений,
  обнаруживаемых без штатного mutator.
- Первый audit deadline разнесён между markets; high-volume telemetry обновляется выборочно раз в
  64 market calls, чтобы статистика не стала новым economy hot path.
- Active set автоматически устанавливает direct temp-mod scheduler как зависимость даже при
  случайно выключенном отдельном `patch.tempModExpiryScheduler`.
- Actual-javaagent smoke для совместной загрузки `Market`, `CommodityOnMarket`, `MutableStat` и
  `MutableStatWithTempMods`, включая stable skip, dirty wake-up, exact expiry и retained-map audit.

### Исправлено

- Переписанный `CommodityOnMarket.reapplyEventMod()` теперь содержит корректные stack-map frames.
  Ранее offline `BasicVerifier` принимал method, но фактическая JVM могла выдать `VerifyError` при
  первом определении класса; новый actual-agent smoke закрывает этот пробел.
- XStream test дополнительно проверяет, что commodity owner/role bindings не попадают в save и не
  восстанавливаются после load.

### Совместимость

- Порядок live commodities, точный `ArrayList`/`LinkedHashMap` behavior и исходные методы
  `reapplyEventMod()`/`MutableStatWithTempMods.advance()` сохранены; subclasses, shared stats,
  неизвестные backing maps и helper failures используют vanilla fallback.
- Обычные API-mutations видны следующему `Market.advance()` немедленно. Мод, сохраняющий live map
  из `getMods()` и меняющий её позже в обход API, может быть обнаружен только на bounded audit;
  это намеренный aggressive-profile компромисс.
- Safe profile оставляет active set выключенным. Формат сохранений и публичный API не изменены.

### Проверено

- Structural/negative/idempotency suite: 30 transformed classes, 1886 verified concrete methods.
- Actual-javaagent commodity smoke, direct temp-mod differential/float fixtures, XStream save/load,
  persistent economy, lifecycle/GC, loading/save, hyperspace и startup suites.

Подробности: [отчёт о выпуске 0.9.4](docs/releases/0.9.4.md).

## [0.9.3] - 2026-07-18

### Добавлено

- `patch.planetConditionMarketScheduler` покрывает отдельный vanilla-путь
  `BaseCampaignEntity.advance() -> MarketAPI.advance()` для `planetConditionMarketOnly` markets.
- Добавлены stable phases, exact accumulated `amount`, first-tick/current-location full-rate,
  memory opt-out, frame/day caps, lifecycle reset, reentrant fallback и pre-save flush для нового
  scheduler-а.
- `summary.csv` observer-а получил отдельные scheduled/fallback/save-flush/immediate counters
  planet-condition пути.
- `session.json` schema 2 содержит `sessionOrigin`; startup/FR smoke создают явно помеченные каталоги.

### Исправлено

- Массовый известный `planetConditionMarketOnly` engine path больше не учитывается как
  `unknown Market.advance()`.
- `call-sites.csv` теперь получает manifest трансформированных mod call sites до первого
  выполнения, поэтому пустой runtime не выглядит как отсутствие найденных sites.
- Лимит unknown stack samples обновляется каждый report interval; ранний частый caller больше не
  исчерпывает его на весь процесс.
- Если central scheduler выключен, planet scheduler всё равно получает frame-clock boundary, не
  изменяя central market-loop.

### Проверено

- Добавлены structural negative/idempotency tests точного BaseCampaignEntity bridge.
- Runtime suite покрывает stable cadence, current-location policy, opt-out, separate identity state,
  save flush, reentrancy, disabled fallback, eager observer manifest и interval stack budget.
- Lifecycle/GC suite проверяет точный inventory из 12 campaign-bound maps.

Подробности: [отчёт о выпуске 0.9.3](docs/releases/0.9.3.md).

## [0.9.2] - 2026-07-18

### Добавлено

- `patch.remoteMarketScheduler` перенаправляет только центральный market-loop
  `Economy.advance()` в staggered scheduler. Удалённые рынки получают накопленный исходный
  `amount` на стабильных фазах; скрытые рынки имеют отдельный более редкий cadence.
- Перед сохранением pending market time принудительно применяется, а transient scheduler state
  остаётся вне save graph.
- Добавлены full-rate политики для текущей location, interaction market, player-owned markets и
  per-market memory opt-out `$starsectorPrepatcher_fullRateMarket`.
- Добавлены runtime counters и differential regression suite для cadence, amount conservation,
  hidden markets, hot policies, save flush, identity-keyed state и reentrancy fallback.
- `patch.directMarketObservation` инструментирует прямые вызовы
  `MarketAPI.advance(float)` в загружаемом bytecode модов, не откладывая, не объединяя и не
  подавляя исходный синхронный вызов.
- Для каждого call site записываются JAR/source, class, method, descriptor, line, ordinal и
  best-effort классификация источника `amount`.
- Добавлены выборочное измерение inclusive времени, sampled stack signatures, приблизительное
  число затронутых рынков, recursive/error/amount counters и отдельный учёт неизвестных
  reflective/неинструментированных входов в vanilla `Market.advance()`.
- Каждая игровая сессия получает отдельный каталог
  `logs/direct-market-observe/session-*` с `call-sites.csv`, `observations.csv`, `stacks.csv`,
  `unknown-stacks.csv`, `summary.csv` и `session.json`.
- Добавлены structural и runtime regression tests, подтверждающие исходную multiplicity,
  delivered amount, exception propagation, mod-loader transformation и CSV output.

### Изменено

- Default/aggressive profile теперь сознательно выбирает performance-first cadence для удалённых
  рынков: обычные рынки обновляются примерно раз в 4 кадра, скрытые — раз в 8 кадров, с
  ограничением накопленного времени и числа отложенных вызовов.
- Scheduler state переведён на campaign-lifetime identity map: vanilla `Market` переопределяет
  `equals/hashCode`, поэтому equality-keyed map не подходит для независимого состояния рынков.
- `Economy.advance()` всегда получает reentrant scratch scope; nested вызов экономики полностью
  уходит в immediate vanilla path и не перезаписывает контекст внешнего прохода.

### Поведение

- Частота `MarketAPI.advance()` для удалённых рынков намеренно уменьшается. Суммарное игровое время
  сохраняется, но RNG sequence, точный межрыночный порядок наблюдений и frame-count callbacks
  модовых conditions/industries/submarkets могут измениться.
- Observation mode не является scheduler: инструментированные прямые вызовы модов выполняются
  немедленно в текущем потоке, а код после `advance()` видит уже обновлённый рынок как прежде.
- Измерение времени выполняется выборочно (`1/128` по умолчанию), stack capture — на первом и затем
  редких вызовах (`1/2048`), чтобы наблюдатель не стал новым market bottleneck.
- `profiles/safe.properties` оставляет scheduler и observation выключенными; default/aggressive
  включает их для performance-first cadence и временного сбора данных.

### Исправлено

- Pending time не теряется на обычной границе save: save manager вызывает flush до сериализации.
- Custom/vanilla market equality больше не объединяет scheduler state разных экземпляров.

### Документация

- Описаны настройки агрессивного scheduler, opt-out memory key, ожидаемые изменения поведения
  модов и обязательные validation-сценарии.

Подробности: [отчёт о выпуске 0.9.2](docs/releases/0.9.2.md).

## [0.9.1] - 2026-07-18

### Добавлено

- `patch.commodityEventModDirtyCache` устраняет повторный `unmodifyFlat("eMod")`, когда
  `CommodityOnMarket` уже подтвердил нулевое recent-trade quantity и отсутствие модификатора.
  Первый zero-вызов после создания/загрузки и весь nonzero-путь остаются vanilla; transient-флаг
  не меняет cadence market/condition/industry callbacks.

Подробности: [отчёт о выпуске 0.9.1](docs/releases/0.9.1.md).

## [0.9.0] - 2026-07-18

### Добавлено

- Installer получил явный `-Target Vanilla|FasterRendering|Both`: он поддерживает как root
  `vmparams`, так и `starsector-core/fr.vmparams`, сохраняет отдельные timestamped backups и
  идемпотентно размещает Prepatcher последним `-javaagent`.
- Добавлены loader regression gates для vanilla и FR-like topology, wrong-loader fail-open и
  parent-loaded `sound.Sound`.

### Изменено

- Typed hooks перенесены в payload exact-пакета `com.fs.starfarer.api`. Startup control plane читает
  их classfile'ы из agent JAR и определяет через lookup loader'а Starsector до регистрации
  transformer.
- `sound.Sound`, который Faster Rendering оставляет в parent loader, подавляет сопоставленный
  pure-INFO блок inline без cross-loader helper call.

### Исправлено

- Устранён `loader constraint violation` при вызове typed hooks с Faster Rendering: transformer
  теперь требует identity-equal target/runtime loader и fail-open публикует `SKIPPED_LOADER` при
  несовпадении.

### Документация

- Добавлен технический roadmap: обязательный name-independent structural discovery до загрузки
  классов, декомпозиция patch/runtime-кода, formatter, Linux Tier 1 и macOS best effort.
- Описаны classloader topology Faster Rendering, остаточная зависимость payload от FR fallback в
  `JavaAgentLoader`, installer targets и обязательная real-launch матрица до выпуска.

Подробности: [отчёт о выпуске 0.9.0](docs/releases/0.9.0.md).

## [0.8.0] - 2026-07-17

### Добавлено

- Основной английский `README.md` и дополнительный `README_RU.md` с переключателем языка в шапке.
- Зафиксировано направление будущего API для возможностей, отсутствующих в публичном API игры;
  планируемый namespace — `com.starsector.prepatcher.api`.

### Изменено

- Проект переименован из **Starsector Map Optimizer** в **StarsectorPrepatcher**, чтобы название
  отражало раннее применение патчей до обычной загрузки игровых и модовых классов.
- Mod id изменён с `starsector_map_optimizer` на `starsector_prepatcher`; каталог поставки теперь
  называется `StarsectorPrepatcher`.
- Java namespace переведён с `com.starsector.mapoptimizer` на `com.starsector.prepatcher` до
  публикации первого поддерживаемого API.
- Переименованы agent/bootstrap JAR, конфигурация, runtime-лог, system properties и
  ownership marker структурных патчей.
- Все патчи объединены в одном `StarsectorPrepatcherAgent.jar`, одном transformer и одном
  `prepatcher.properties`; второй javaagent и дублирующая startup-инфраструктура удалены.
- Hyperspace-патчи переведены с allowlist полных hash классов на независимые локальные structural
  contracts. Совместимые переводные classfile теперь поддерживаются без отдельных hash-снимков.

### Поведение

- Формат сохранений не меняется, runtime-кэши не сериализуются.
- Состав и поведение оптимизационных патчей `0.7.1` не урезаны.

Подробности: [отчёт о выпуске 0.8.0](docs/releases/0.8.0.md).

## [0.7.1] - 2026-07-17

### Исправлено

- `BaseCampaignEntity.runScripts()` больше не открывает и не очищает общий scratch scope для
  сущностей с пустым списком scripts; empty path возвращает до любой optimizer-аллокации, а
  непустой путь сохраняет свежий vanilla snapshot.
- Helper-based `Memory.advance()` iterator patch заменён inline fast return для полностью пустых
  expire/require queues; устранён измеренный CPU-overhead `memoryExpireIterator()`.
- Hyperspace automaton теперь отличает два engine-internal чтения `cells` от публичной выдачи
  массива через `getCells()`; direct read разрешён только exact vanilla owner с подтверждённым
  reuse patch, а subclass/unconfirmed path сохраняет virtual getter semantics.
- Comm-relay index больше не перепроверяет identity и координаты всех систем на каждом frame;
  полный audit выполняется по TTL, а live relay/tag/memory checks остаются vanilla.
- Terrain `Random` telemetry переведена с `LongAdder` и clock-check на каждом tile на локальное
  пакетирование; накопительная приблизительная статистика больше не находится в многомиллионном
  hot path и сохраняет редкие rollover/reuse events между интервалами логирования.
- Hyperspace telemetry schema теперь cumulative: `pooledRandom` переименован в
  `pooledRandomApprox`, добавлен `automatonInternalReads`, а неинформативные runtime counters
  `cullHeight`/`yClamp` удалены.
- `ScratchFrame.clear()` обходит только реально заимствованные snapshots/lists/sets и не очищает
  исторически созданные, но не использованные в текущем scope контейнеры.
- `patch.loadingTextReader` и `patch.startupLogAggregation` отключены во всех поставляемых профилях
  как known-disabled после подтверждённых ошибок запуска миссий; реализация сохранена для
  отдельного исправления и изолированной повторной проверки.

### Проверено

- Structural/negative/idempotency suite обновлён для inline guards без runtime hooks.
- Runtime comm-relay test теперь проверяет bounded coordinate audit вместо ошибочного требования
  полного O(N)-сканирования внутри TTL.
- Hyperspace verifier проверяет internal-read reuse, public-alias isolation и zeroing reusable buffer.

Подробности и performance-контекст: [отчёт о выпуске 0.7.1](docs/releases/0.7.1.md).

## [0.7.0] - 2026-07-17

### Добавлено

- Оптимизации economy snapshots/location state, hyperspace comm-relay index, combat scratch и
  particle cleanup.
- Streaming loading/save paths, агрегация startup-логов и ограничение частоты progress redraw.
- Отдельный hyperspace javaagent для terrain culling, layer selection, automaton buffers и
  движущегося starfield.
- Единые structural, lifecycle/GC, runtime и offline hyperspace regression suites.
- Обязательный playbook повторяющихся lifecycle, weak-reference, reentrancy, aliasing, TTL,
  verifier и toolchain-регрессий.

### Изменено

- В одну поставку объединены map/campaign/route, economy/combat, loading/save и hyperspace ветки.
- Main agent применяет независимый structural/data-flow matching; hyperspace agent использует
  точные per-class guards вместо привязки к несвязанному содержимому всего JAR.
- Verifier требует полного покрытия target-классов и повторно проверяет сериализованный bytecode.
- Ownership marker теперь использует стабильную версию patch schema, не связанную с SemVer релиза.
- Документация сведена к актуальным справочникам; сырые verifier-логи находятся только в
  `.build/reports`.

### Исправлено

- Campaign caches, Economy state и reentrant `ThreadLocal` scratch больше не удерживают старые
  экземпляры `CampaignEngine`.
- Comm-relay candidates немедленно проверяют актуальные позиции систем; повтор неудачной сборки
  индекса ограничен TTL и не удерживает campaign objects.
- Сохранена vanilla alias/allocation-семантика hyperspace automaton: owner-local transient state,
  zeroed buffers и vanilla fallback для subclass owners.
- Частично применённый или повреждённый transformer patch больше не принимается за полный.
- Исправлены PowerShell native-exit handling и явная UTF-8 сборка/генерация отчётов.

Подробности и исходный baseline: [отчёт о выпуске 0.7.0](docs/releases/0.7.0.md).

## [0.4.0] - 2026-07-17

### Добавлено

- Symbolic/data-flow matching, ownership markers, postconditions, `BasicVerifier` и
  negative/idempotency tests для независимых патчей main agent.
- Campaign-generation lifecycle reset, reusable location/entity snapshots, empty `Memory` iterator
  fast path и lifecycle/GC runtime harness.

### Изменено

- Main-agent SHA gates заменены fail-open структурной проверкой каждого патча.
- Усилены ordered route indexes и conservative fallbacks.

### Исправлено

- Долгоживущие campaign caches отсоединяются при замене или reset `CampaignEngine`.
- Убрана очистка уже пустой `IdentityHashMap` на каждом entity-script scope exit, вызвавшая
  CPU-регрессию экспериментальной итерации.

## [0.3.0] - 2026-07-16

### Добавлено

- Первая поставка startup javaagent и bootstrap для Starsector `0.98a-RC8`.
- Оптимизации sector/Intel map reconciliation, scratch allocations, spatial lookup, hover,
  callbacks, nebula sampling и grid rendering.
- Campaign listener throttling, ordered route indexes, installer/uninstaller, профили, build scripts
  и первичные отчёты проверки.

## Stage 2 AoTD native contract

- Added diagnostic early discovery of the AoTD scheduler-fork marker.
- Added a verified transformer for the fork-owned no-op SchedulerBridge.
- Added a direct target-loader capability handshake with no reflection in mod code.
- Added loader-ancestry, ABI, marker, idempotency and conflict checks.
- Negotiates only capability `0x1`; later scheduler capabilities remain disabled.

## Stage 3 AoTD delivered-time contract

- Extended the verified no-reflection bridge to capability mask `0x0f`.
- Added post-success `Market.advance()` delivery events and weak per-market generations.
- Added exact local mutation barriers that replay old pending market time before covered AoTD structural changes.
- Added nested mutation tokens, structural generations and a loader-neutral JDK callback.
- Kept ordinary AoTD reads/calculations off the exact replay path.

## Stage 6 AoTD pure price offload

- Added negotiation for AoTD capability `0x40` (`PURE_PRICE_OFFLOAD`).
- Full Stage 6 fork capability mask is now `0x6f`.
- Kept delivered-time, exact structural mutation replay and loader policy
  unchanged.
