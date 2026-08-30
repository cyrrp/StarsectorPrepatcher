> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

# Устройство market scheduler

`patch.marketScheduler` ограничивает число дорогих `Market.advance(float)` при ускорении кампании,
не теряя исходную последовательность simulation-step `amount`. Внешний вызов рынка может быть
объединён, но подтверждённые vanilla-компоненты с пошаговой семантикой воспроизводят исходные шаги
локально.

## 1. Область ответственности

Система обслуживает четыре вида входов:

1. Периодический Economy source.
2. Периодический planet-condition source из `BaseCampaignEntity`.
3. Синхронные event/direct/fail-open barriers.
4. Save flush.

Периодические источники могут создавать pending history. Синхронные barriers сначала доставляют
старую историю, затем выполняют текущий исходный шаг отдельно. Поэтому новый event не обгоняет
старое время и не сливается с собственным `amount`.

## 2. Structural readiness

Deferral включается только после регистрации всех одиннадцати компонентов:

| Компонент | Structural owner | Назначение |
|---|---|---|
| Batch protocol | presentation/structural contract `CampaignState` | подтверждает `false → loop/true → false` |
| Tick, batch и lifecycle | `CampaignEngine` | открывает simulation tick и закрывает render batch |
| Economy source | `economyAdvancePlan` | направляет центральный вызов рынка в scheduler |
| Entity source | `BaseCampaignEntity` | направляет planet-condition вызов |
| Save barrier | `saveMethodPlan` | атомарно выполняет maintenance и market flush до сериализации; не зависит от optional buffer dedup |
| Market semantic boundary | `marketAdvancePlan` | публикует batch context внутри конкретного `Market.advance()` и ставит Market mutator barriers |
| Military replay | `MilitaryBase` | воспроизводит исходные шаги локально |
| Lions Guard replay | `LionsGuardHQ` | воспроизводит исходные шаги локально |
| Recent Unrest replay | `RecentUnrest` | воспроизводит исходные шаги с остановкой после удаления condition |
| Industry barriers | `BaseIndustry` | не пропускает debt через build/upgrade transitions |
| Queue barriers | `ConstructionQueue` | не пропускает debt через внешние изменения очереди |

Дополнительно scheduler требует активную campaign generation. До полной готовности вызовы остаются
синхронными и не создают pending history. Каждый structural owner регистрирует свой bit только после
успешной combined postcondition. Marker или wrapper без registration bit считается partial state и
получает `SKIPPED_STRUCTURAL`; поэтому core scheduler не может стать `READY`, если хотя бы один
обязательный semantic wrapper/barrier не установлен.

## 3. Временная модель

Один внешний `CampaignEngine.advance(...)` — один simulation tick. Один render batch — все simulation
ticks, выполненные между начальным и финальным `setFastForwardIteration(false)`.

Starsector ускоряет кампанию количеством simulation ticks, а не увеличением `amount`. Поэтому cadence
измеряется render batches. При ускорении ×N scheduler получает N исходных шагов с теми же float,
но batch-managed рынок обычно получает один coalesced callback.

Вложенный `CampaignEngine.advance()` не заменяет context внешнего tick и идёт через синхронный
fail-open путь.

## 4. Состояние рынка

Состояния хранятся в weak-identity map:

```text
MarketAPI identity → MarketScheduleState
```

Weak keys не удерживают удалённые рынки. State process-local и сбрасывается при смене campaign
generation.

Основные поля:

- `pendingAmount` — последовательная float-сумма недоставленных шагов;
- `pendingSteps` — точное число шагов;
- `pendingRuns` — run-length encoded история;
- `nextDueBatch` — следующий допустимый periodic срок;
- `lastTouchedBatch` — защита batch worklist;
- `sourceMask` и source diagnostics;
- `debtSequence` — порядок появления debt для save;
- `perSimulationTick` — compatibility opt-out;
- `constructionFullRate` — текущий construction mode;
- `constructionMutationEpoch` — dirty generation от mutation barriers;
- `constructionAuditedEpoch` и `lastConstructionAuditBatch` — состояние последнего полного scan;
- `inAdvance` — reentrancy guard;
- `disabled` — постоянный synchronous fallback после неоднозначной ошибки callback.

Игровые методы никогда не вызываются под монитором state или map.

## 5. Точная pending history

Каждый входной шаг добавляется как raw float:

```java
pendingAmount += amount;
pendingSteps++;
```

Соседние значения с одинаковыми `Float.floatToRawIntBits()` объединяются в run:

```text
0.016 × 2
0.020 × 3
```

Разные значения и их порядок сохраняются. Никакое восстановление через
`pendingAmount / pendingSteps` не используется.

Число runs ограничено:

```properties
market.remote.maxPendingRuns=32
```

Если следующий отличный шаг превысит лимит, текущая история выполняется coalesced с доступным batch
context, после чего новый шаг начинает следующую историю. История не усредняется и не отбрасывается.

При detach, flush и generation reset deque очищается; reusable batch frames также очищают market и
run arrays после `pop`.

## 6. Thread-local batch context

Перед coalesced `Market.advance(totalAmount)` scheduler помещает историю в reusable thread-local
stack. Unified `Market.advancePlan` оборачивает конкретный вызов:

```java
beginMarketAdvanceInvocation(this);
try {
    smo$marketAdvancePlanBody(amount);
} finally {
    endMarketAdvanceInvocation();
}
```

Allocation-free API для component wrappers:

```java
hasCurrentMarketAdvanceBatch(market)
currentMarketAdvanceBatchRunCount(market)
currentMarketAdvanceBatchRunAmount(market, run)
currentMarketAdvanceBatchRunRepeats(market, run)
```

Context доступен только внутри конкретного `Market.advance()` и только для совпадающего market
identity. Вложенный вызов другого рынка получает отдельный stack frame или видит отсутствие context;
после него внешний context восстанавливается. Direct current step и exact full-rate replay не
создают context.

## 7. Периодическое накопление

`Economy` и entity source вызывают:

```java
advanceMarketScheduled(market, amount, source);
```

После readiness/lifecycle/context проверок scheduler:

1. получает identity-state;
2. проверяет callback reentrancy и compatibility policy;
3. проверяет construction mode;
4. для обычного рынка добавляет точный шаг в RLE history;
5. помещает рынок в `batch.touched` не более одного раза за render batch.

Policy и cadence вычисляются при закрытии batch, а не на каждом simulation tick.

## 8. Cadence

Интервалы измеряются render batches:

```text
current-location / player-owned / interaction  1
обычный удалённый                               market.scheduler.batches
hidden удалённый                               market.scheduler.hiddenBatches
```

Stable phase распределяет рынки по slots. После callback хранится абсолютный `nextDueBatch`, поэтому
редкий source выполняется при первом вызове после срока и не обязан точно попасть в phase.

Memory opt-out:

```properties
market.scheduler.perSimulationTickMemoryKey=$starsectorPrepatcher_perSimulationTickMarket
```

Рынок с `true` получает один callback на каждый simulation tick. Это явный compatibility режим;
наличие `MilitaryBase` или `LionsGuardHQ` само по себе его не включает.

## 9. Локальный replay чувствительных vanilla-компонентов

Три класса получают отдельные idempotent wrappers:

- `MilitaryBase.advance(F)V`;
- `LionsGuardHQ.advance(F)V`;
- `RecentUnrest.advance(F)V`.

Их исходная реализация переименована в private synthetic `smo$advanceSingleStep(F)V`.

Без batch context wrapper вызывает single-step body один раз с исходным `amount`. При coalesced
market callback wrapper проходит runs и вызывает single-step body для каждого исходного шага в
точном порядке.

Таким образом внутри каждого целевого компонента сохраняются:

- `MilitaryBase` сохраняет последовательность собственных вызовов, пошаговый `IntervalUtil`, patrol
  limits и внутриклассовые transitions;
- `LionsGuardHQ` сохраняет такую же последовательность для собственного тела;
- `RecentUnrest` сохраняет не более одного penalty transition за исходный шаг.

`RecentUnrest` после каждого шага проверяет наличие `Conditions.RECENT_UNREST` и прекращает replay,
если condition удалена: последующие vanilla `Market.advance()` уже не вызвали бы этот plugin.

Остальные vanilla и mod-компоненты продолжают видеть один coalesced `amount`.

Это не восстанавливает глобальное межкомпонентное чередование вида `A1 → B1 → A2 → B2`:
локальный replay выполняет `A1 → A2` внутри одного вызова A, затем `B1 → B2` внутри B. Поэтому
полная эквивалентность общего RNG-порядка и промежуточных shared-market состояний не заявляется без
campaign-level differential tests. Текущие тесты доказывают точную историю, wrapper control flow,
идемпотентность и реальную JVM-загрузку, но не заменяют такой игровой differential harness.

## 10. Construction full-rate

Строительство чувствительно к границам всего `Market.advance()`, поэтому локального replay одной
industry недостаточно. Полный scan проверяет:

- непустую `ConstructionQueue`;
- `Industry.isBuilding()`;
- `Industry.isUpgrading()` как независимый диагностический признак.

Но scan не выполняется на каждом simulation input. Mutation barriers увеличивают
`constructionMutationEpoch`; следующий scheduler input выполняет один scan. При отсутствии мутаций
состояние перепроверяется только через safety interval:

```properties
market.remote.constructionAuditBatches=180
observer.marketConstructionDiagnostics=false
observer.marketConstructionDiagnosticsMaxSamplesPerReason=32
```

Save flush всегда принудительно выполняет свежий audit. При активном construction mode:

1. старая pending history воспроизводится отдельными `Market.advance(step)`;
2. текущий шаг выполняется отдельным full-rate callback;
3. новые шаги не добавляются в pending history.

Когда очередь пуста и effective building отсутствует, рынок автоматически возвращается в coalesced
mode. Для наследников `BaseIndustry` effective building определяется raw-полем `building`; virtual
`isBuilding()` остаётся telemetry и fallback только для других реализаций `Industry`. `isUpgrading()`
и virtual-building при raw=false сохраняются в reason mask/gauges, но не включают full-rate:
ванильный `PopulationAndInfrastructure` использует эти состояния для роста размера рынка.
Наличие отрасли, которая когда-либо строилась, не делает режим постоянным.

Ошибка compatibility probe трактуется консервативно как активное строительство.

Detector публикует причины отдельно. Текущие gauges различают queue, effective building, upgrading,
reported-building-without-raw, uncertain и multiple-reason markets. Накопительные counters различают dirty, safety-audit и forced
scans, cached decisions, queue/items/industries null cases, число просмотренных элементов,
false→true/true→false transitions, изменения reason mask и mutation races. Это позволяет отличить
реальное массовое строительство от постоянного `isBuilding()` у модовой industry или от
консервативного probe failure без включения подробного CSV.

Опциональная выборка:

```properties
observer.marketConstructionDiagnostics=true
observer.marketConstructionDiagnosticsMaxSamplesPerReason=32
```

создаёт `logs/market-construction-diagnostics/session-*/samples.csv`. Выборка bounded отдельно по
reason/transition bucket и хранит только строки/числа: identity hash, market id/name, queue
size/class, legacy first-positive industry, reported/effective building, раздельные effective-
building/upgrading/reported-without-raw industries, raw building field, build progress/time, upgrade
id, functional state, epochs и transition state. Переходы `4->6` и `4->132` получают отдельные
buckets `TRANSITION_4_TO_6` и `TRANSITION_4_TO_132`. Сильные ссылки на game objects не сохраняются,
diagnostic writer не участвует в scheduler policy.

## 11. Construction mutation barriers

Чтобы старое время не переносилось через внешнюю мутацию строительной структуры, exact replay hook
установлен перед подтверждёнными mutators:

- `Market.addIndustry(...)` и `Market.removeIndustry(...)`;
- `BaseIndustry.startBuilding`, `startUpgrading`, `cancelUpgrade`, `downgrade`;
- `ConstructionQueue.setItems`, `addToEnd`, `removeItem`, `moveUp`, `moveDown`, `moveToFront`,
  `moveToBack`.

`Market.getConstructionQueue()` регистрирует weak queue→market owner relation. Повторная регистрация
того же queue→market identity не создаёт новый `WeakReference` и не переписывает weak map. Queue
barrier находит market через эту relation, exact-replay’ит pending history до мутации и помечает
construction state dirty.

Если mutator вызывается из активного `Market.advance()`, `state.inAdvance` предотвращает рекурсивный
flush.

## 12. Синхронные event/direct/fail-open barriers

`advanceMarketSynchronized()` и direct-call wrapper используют одну политику:

1. существующая pending history доставляется первой;
2. если construction активен — exact replay всего рынка;
3. иначе старая history может быть coalesced с batch context;
4. текущий `amount` вызывается отдельно как собственный vanilla step.

Это сохраняет event boundary. Шесть известных core create/remove call sites проходят через этот
контракт. Direct mod calls остаются синхронными и не участвуют в cadence.

Reentrant вызов того же рынка при `inAdvance=true` выполняется непосредственно и не забирает history
повторно.

## 13. Save flush

Перед XStream serialization correctness-critical `saveMethodPlan` выполняет:

```java
runCacheMaintenance(true);
flushMarketSchedulerBeforeSave();
```

Debt обрабатывается в порядке `debtSequence`. Save прерывается, если рынок уже находится внутри
callback или доставка завершается исключением.

Режим по умолчанию:

```properties
market.remote.exactReplayBeforeSave=false
```

Обычный рынок получает один coalesced callback с batch context; три wrapper-компонента локально
воспроизводят шаги. Construction market всегда exact-replay’ится.

`patch.saveOutputBufferDedup` выполняется отдельным structural patch после barrier. Его matcher и
ownership не участвуют в scheduler capability gate. Если внешний `BufferedOutputStream` больше не
совпадает с подтверждённым 1 MiB allocation pattern, dedup локально пропускается, но
`runCacheMaintenance(true)`, `flushMarketSchedulerBeforeSave()` и save capability registration
сохраняются. После успешного dedup общий composition pipeline повторно проверяет barrier
postcondition.

При `true` все pending markets выполняются целиком по исходным шагам. Это диагностический
консервативный режим.

Если callback уже начался и выбросил исключение, его side effects считаются неоднозначными. Detached
history не восстанавливается автоматически: рынок переводится в `disabled` synchronous fail-open,
debt отбрасывается, save прерывается и исключение пробрасывается. Это исключает повторное применение
частично выполненного callback при следующей попытке сохранить игру.

## 14. Исключения и float overflow

Callback exception пробрасывается. Неоднозначно частично применённая история не повторяется;
конкретный рынок переводится в `disabled` synchronous mode.

Если последовательная сумма конечных float переполняется, scheduler не передаёт `Infinity`.
Существующая конечная часть доставляется, а текущий шаг выполняется отдельно. Exact history не
заменяется усреднённым значением.

## 15. Observer рисков модовых компонентов

Опциональный статический observer:

```properties
observer.marketAdvanceSemanticRisks=false
```

анализирует `advance(F)V` mod implementations `Industry`, `MarketConditionPlugin` и
`SubmarketPlugin`. Observer-only конфигурация возвращает исходные bytes после CSV-анализа даже при
наличии прямых `Market.advance()` call sites. Runtime wrapper устанавливается только если отдельно
включён `patch.directMarketObservation` или `patch.marketScheduler`.

Тип компонента определяется по транзитивной цепочке superclass/interfaces, а не только по
непосредственному родителю. Несвязанные классы с методом `advance(F)V` пропускаются. Dedup включает
source location и `modId`, поэтому одинаковые class names из разных модов не конфликтуют.

Отчёт:

```text
logs/market-advance-semantic-risks.csv
```

Категории:

- `INTERVAL_SINGLE_ELAPSE`;
- `RANDOM_ROLL`;
- `RANDOM_SELECTION`;
- `SINGLE_THRESHOLD_TRANSITION`;
- `MARKET_STRUCTURE_MUTATION`.

Vanilla JAR и StarsectorPrepatcher исключены. Строка соответствует уникальному call site или
эвристическому transition site.

## 16. Конфигурация

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
```

## 17. Метрики

### Pending history

- `pendingMarketStepsCurrent`;
- `pendingMarketRunsCurrent`;
- `pendingMarketStepsHighWater`;
- `pendingMarketRunsHighWater`;
- `pendingRunOverflowFlushes`.

### Coalesced execution и local replay

- `coalescedMarketFlushes`;
- `coalescedMarketAmount`;
- `marketBatchContextsCreated`;
- `militaryBaseReplayedSteps`;
- `lionsGuardReplayedSteps`;
- `recentUnrestReplayedSteps`.

### Construction

- `constructionFullRateCalls`;
- `constructionFullRateMarkets`;
- `constructionMarketsQueueNonEmpty`;
- `constructionMarketsIndustryBuilding`;
- `constructionMarketsIndustryUpgrading`;
- `constructionMarketsReportedBuildingWithoutRaw`;
- `constructionMarketsUncertain`;
- `constructionMarketsMultipleReasons`;
- `constructionModeEntries`;
- `constructionModeExits`;
- `constructionBoundaryExactReplays`;
- `constructionScans`, `constructionDirtyScans`, `constructionSafetyAuditScans`,
  `constructionForcedScans`, `constructionCachedDecisions`;
- `constructionDetectedQueueNonEmpty`, `constructionDetectedIndustryBuilding`,
  `constructionDetectedIndustryUpgrading`, `constructionDetectedReportedBuildingWithoutRaw`,
  `constructionDetectedMultipleReasons`, `constructionDetectedProbeFailure`;
- `constructionQueueNullScans`, `constructionQueueItemsNullScans`,
  `constructionIndustriesNullScans`, `constructionQueueItemsObserved`,
  `constructionIndustriesScanned`, `constructionMaxQueueItems`,
  `constructionMaxIndustriesScanned`;
- `constructionAuditStateChanges`, `constructionAuditFalseToTrue`,
  `constructionAuditTrueToFalse`, `constructionReasonChanges`, `constructionMutationRaces`,
  `constructionDiagnosticSamplesDropped`.

### Save

- `saveCoalescedMarkets`;
- `saveExactReplayMarkets`;
- `saveExactReplaySteps`;
- `saveFlushDurationNanos`.

Сохраняются также существующие tick/batch, source, cadence, opt-out, readiness, synchronization и
failure counters. Gauges вычисляются только в периодическом stats report.

## 18. Проверяемые инварианты

Validation требует:

- все одиннадцать readiness-компонентов и активную generation;
- fail-open при отсутствующем semantic wrapper/barrier registration bit;
- callback count batch-managed рынка, не зависящий от ×1/×4/×10/×100 при равном числе render batches;
- точную RLE history с raw-float run comparison;
- flush при превышении max runs без усреднения;
- market-specific, reentrant thread-local context;
- idempotent wrappers трёх vanilla-компонентов;
- остановку `RecentUnrest` после удаления condition;
- временный construction full-rate и автоматический выход;
- exact mutation barriers;
- coalesced и exact save modes;
- два periodic и шесть synchronized core call sites без неизвестных vanilla sites;
- JAR-wide inventory подтверждённых construction mutators и явных exemptions;
- статический observer-only режим, не изменяющий bytes даже при direct `Market.advance()` call site;
- ASM verification, repeated transformation и presentation/structural composition.

## 19. Граница производительности

Центральный Economy loop и дешёвые scheduler lookups всё ещё выполняются на каждом simulation tick.
Construction detector на steady state использует epoch/audit metadata и не обходит industries на
каждом input. Оптимизация сокращает дорогие market callbacks. Три подтверждённых vanilla-компонента
могут получить несколько локальных single-step вызовов внутри одного coalesced market callback;
это дешевле full-rate всего рынка. Активное строительство временно возвращает весь рынок к
full-rate, потому что его последовательность распределена по всему `Market.advance()`.
