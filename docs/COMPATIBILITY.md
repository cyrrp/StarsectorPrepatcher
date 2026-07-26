# Совместимость и применение патчей

Единый agent не сравнивает SHA-256 игры, JAR или target-классов. Каждый `patch.*`, включая
hyperspace-патчи из `starfarer.api.jar` и `starfarer_obf.jar`, независимо проверяет локальный
контракт фактически загружаемого JVM bytecode и точное число изменяемых sites.

Поэтому перевод, меняющий строки или constant pool класса, не требует отдельного снимка hash. Если
локальная структура нужного метода сохранилась, патч применяется; если изменилась — fail-open
пропускает только этот патч и оставляет соответствующий участок vanilla.

## Classloader-архитектура и Faster Rendering

В vanilla запуске game classes и startup-agent доступны через system loader. Faster Rendering
меняет topology: `com.genir.renderer.loaders.AppClassLoader` становится system/game loader и
child-load'ит `com.fs.*`, а premain-классы javaagent находятся в отдельном
`AppClassLoader$JavaAgentLoader`. Если typed hook остаётся в JAR agent как обычный вызываемый класс,
оба loader'а могут определить собственный `CampaignEngine`, `Economy` и API types. Первый вызов
hook с таким descriptor завершается `loader constraint violation`/`LinkageError`.

Prepatcher разделяет control plane и target runtime:

```text
agent loader
└─ com.starsector.prepatcher.agent.*       config, ASM, transformer, RuntimeInstaller

system/game loader
├─ com.fs.starfarer.api.Global             lookup anchor
├─ com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge
├─ com.fs.starfarer.api.StarsectorPrepatcherHooks
├─ com.fs.starfarer.api.StarsectorPrepatcherHyperspaceHooks
├─ com.fs.starfarer.api.StarsectorPrepatcherTempModHooks
└─ com.fs.*                                transformed targets and argument types
```

Payload-classfile'ы остаются обычными entries внутри agent JAR, но control plane никогда не
ссылается на них статически. `RuntimeInstaller` читает entries с префиксом
`com/fs/starfarer/api/StarsectorPrepatcher` как bytes, получает `Global` через system loader и
определяет payload через `MethodHandles.privateLookupIn(...).defineClass(...)`. Exact-пакет
`com.fs.starfarer.api` нужен для package lookup. Внешний descriptor configuration bridge содержит
только JDK types (`Object`, `Path`), поэтому agent control plane не разрешает payload-классы.

Текущая реализация runtime внутри bridge/hooks всё ещё использует `PrepatcherConfig` и
`PrepatcherLog` из control plane. В проверенной topology Faster Rendering system `AppClassLoader`
для non-`com.fs.*` сначала делегирует parent, затем явно использует fallback `JavaAgentLoader`,
поэтому обе ссылки разрешаются в исходную agent-копию. Это осознанная остаточная связь, а не общий
classloader-контракт: изменение resolver в другой версии FR должно привести к ошибке установки до
регистрации transformer. Будущее усиление — передавать immutable JDK-only config snapshot и logging
callback, полностью убрав обратные symbolic references из payload.

Transformer регистрируется лишь после успешной установки и настройки всего payload. Для каждого
target, который вызывает typed runtime, loader должен быть identity-equal loader'у runtime. При
несовпадении bytes не меняются, а статус становится `SKIPPED_LOADER`, что предотвращает поздний
`LinkageError` и делает несовместимость видимой.

Исключение — `sound.Sound`: в Faster Rendering этот класс остаётся у parent/JDK loader и не может
вызывать runtime из game child loader. Его часть `patch.startupLogAggregation` удаляет точно
сопоставленный pure-INFO блок inline, без helper call и runtime counter. Structural marker и exact
site count сохраняют ownership/idempotency. Сам `patch.startupLogAggregation` пока остаётся
known-disabled независимо от этого loader-исключения.

## Жизненный цикл патча

1. Найти ровно один целевой метод по имени и descriptor.
2. Найти ожидаемые symbolic call/field/allocation sites.
3. Через ASM `Analyzer<SourceValue>` проверить происхождение receiver, аргументов и local values.
4. Отличить три состояния: исходный код, уже установленный этим агентом патч и
   конфликт/неоднозначность. Владение подтверждается отдельным private synthetic marker вместе с
   полной postcondition, а не только похожим вызовом hook или inline guard. Для inline fast paths
   дополнительно сверяются вся входная последовательность, data-flow полей и исходный remainder
   метода. Marker использует стабильную версию patch schema и не меняется при обычном SemVer bump
   релиза.
5. Изменить отдельную копию текущего класса.
6. Проверить postconditions: точные имена/descriptors и число hooks, отсутствие заменённых sites,
   точную wiring-схему wrapper и неизменность public/protected API.
7. Сериализовать, повторно прочитать класс и прогнать все concrete methods через
   `Analyzer<BasicValue>` + `BasicVerifier`.
8. Только после успеха передать новые bytes следующему transformer. При ошибке пропускается
   один патч; ранее подтверждённые изменения того же класса сохраняются.

Строковые литералы, constant-pool ordering, line numbers и debug metadata не являются частью
решения о совместимости. Поэтому переводы и изменения несвязанных методов не блокируют патчи.

## Owned fork как first-class compatibility surface

AoTD Scheduler Fork принадлежит тому же проекту и рассматривается как обязательная compatibility
surface для затрагиваемых vanilla-классов. Любой новый exact-class guard должен сопровождаться
inventory fork subclasses/overrides. Возможны только четыре документированных результата: inherited
optimized path, отдельная трансформация fork-owned метода, отсутствие соответствующего subclass,
либо локальный fail-closed raw fallback. Проверки должны использовать реальный fork JAR и negative
fixture будущего override; name-only allowlist недостаточен.

## Owned AoTD market-share subclass

`AoTDCommodityMarketData` является намеренно поддерживаемым subclass vanilla
`CommodityMarketData`. P0/P1 не полагаются только на имя или версию форка: loader-local
`StarsectorPrepatcherMarketShareRuntime` допускает его после одноразовой проверки, что весь
critical market-share surface по-прежнему объявлен vanilla-классом. Изменение форка, добавляющее
любой из этих overrides, локально возвращает raw behavior; P0.5 в унаследованном
`getMarketShareData()` остаётся независимым. Решение хранится в `ClassValue`, поэтому child
classloader не становится strong root.

## Статусы

В `logs/prepatcher.log` для каждого загруженного target выводится один из статусов:

- `APPLIED` — контракт совпал, результат верифицирован;
- `ALREADY_APPLIED` — точный postcondition уже присутствует;
- `SKIPPED_STRUCTURAL` — site отсутствует, неоднозначен или конфликтует;
- `SKIPPED_LOADER` — target и typed runtime принадлежат разным classloader'ам; target оставлен vanilla;
- `SKIPPED_ERROR` — анализ/сериализация завершились непредвиденной ошибкой.

Количество применённых и пропущенных патчей также экспортируется в system properties и
показывается bootstrap-плагином в `starsector.log`.

## MutableStatWithTempMods: принятая aggressive-семантика

`patch.tempModExpiryScheduler` не меняет публичные methods, descriptors, тип `Map` или save graph.
Transformer сохраняет exact vanilla bodies как private synthetic methods и добавляет в target только
private transient scalar state. O(1) hot path не создаёт отдельный State object, не вызывает
reflection и не обновляет atomic counters.

Ближайший expiry ведётся покадровым `float countdown -= days`. Это намеренно повторяет vanilla
округление для текущего minimum и исправляет расхождение прежнего double-clock scheduler. Один map
pass на deadline одновременно materialize'ит survivors, удаляет due entries в `LinkedHashMap` order,
вызывает `unmodify(source)` и строит следующий minimum/tie count. Условие
`deferredDays >= scheduledMin` запрещено: оно способно удалить modifier на один frame раньше.

Перед `addTemporaryMod*`, `removeTemporaryMod`, public `getMods` и `writeReplace` deferred state
синхронизируется. `hasMod()` не materialize'ит survivor fields: deadline sweep уже удалил expired
entries до возврата из `advance()`. После первого public `getMods()` конкретный stat permanently
переходит на retained vanilla path, потому что external code может хранить и позже менять live map.
То же происходит для subclass owner, необычного backing-map, прямой внешней смены размера и runtime
anomaly. Fail-open локален одному экземпляру.

Мод, который reflection'ом читает `TemporaryStatMod.timeRemaining` без публичного `getMods()`, между
sync points увидит последнее материализованное значение. Для non-min survivors один aggregate float
subtraction на sweep может отличаться на несколько ULP от всей последовательности vanilla
subtractions; полностью устранить это без хранения/replay всей истории frame deltas нельзя. Exact
float countdown гарантирован для текущего nearest deadline, tied minima и removal order; directed
ULP fixtures входят в actual-agent regression suite.

Все synthetic fields имеют `private transient synthetic`. `writeReplace()` сначала materialize'ит
время; XStream smoke подтверждает, что поля scheduler не входят в XML/save, а загруженный object
заново строит schedule лениво.

## Основные data-flow контракты

- `H.renderStuff`: один semantic group содержит `LinkedHashMap.keySet() -> Set.retainAll(entityList)` с отброшенным результатом, локальный `ArrayList(Collection)` entity snapshot и локальный `HashSet()` class set. Все три сайта обязаны иметь доказанный non-escape/use contract и один полный `beginScratchScope/endScratchScope`; mixed/partial state не изменяется.
- `H.getTextAlignmentFor`: `getIcons` backing field -> `LinkedHashMap.values -> iterator`.
- `H.getIntelIconEntity`: одна iterated Intel list, `customData[INTEL_ICON_DATA_KEY]`, identity
  comparison с argument plugin и `null` на miss.
- `H.updateSystemNebulas`: три output lists сопоставляются по `createNebula`,
  `createConstellationLabel` и `StarSystemAPI.getStar`, а не по порядку полей.
- `H.null(F)V`: четыре `FLOAD / 2000f / FDIV / F2I` bounds и один
  `2000f * mapScale` step после starscape guard. Radar constant не меняется.
- `EventsPanel.addMissingIconsAndRows`: entity local берётся из результата
  `H.getIntelIconEntity`; missing-list должен быть свежим локальным `ArrayList`.
- `CampaignEngine.advance`: receiver — `this`; backing systems/hyperspace fields выводятся из
  реализации `readdChangeListeners` и передаются hook напрямую.
- `BaseLocation.advance` и `advanceEvenIfPaused`: matcher требует соответственно ровно три и два
  `new ArrayList(Collection)`, каждый результат должен сразу записываться в отдельный local.
  Data-flow разрешает только ожидаемые вызовы `List.iterator()` от этого local (один на snapshot,
  кроме одного paused snapshot с двумя итерациями), без merge, mutation, escape или иных uses.
- `BaseCampaignEntity.runScripts`: исходный метод должен начинаться с единственного
  `new ArrayList(this.scripts)`, сразу записанного в local и использованного ровно одним
  `List.iterator()`. Inline guard читает то же поле `this.scripts`, возвращает только на
  `isEmpty()` и на non-empty branch попадает в исходную первую инструкцию. Сторонний исполняемый
  пролог, другой receiver, partial guard или иные uses дают `SKIPPED_STRUCTURAL`.
- `Memory.advance`: expire iterator должен происходить именно от `this.expire`, а require
  iterator — от `this.require.values()`. Единственный `CampaignClock.convertToDays(F)F` остаётся
  после restore/pause path и до обоих scans; expire iterator предшествует require iterator.
  Inline guard читает те же два поля, стоит после исходного pause-return и до clock conversion.
  Partial/raw-hook mixture, другой map receiver, изменённый порядок и лишние sites отвергаются.
- Persistent Economy/Market snapshots: synthetic owner fields must be private, transient, and
  initialized in constructors plus `readResolve`; `Market.clone()` receives independent state.
  Transformed public mutators mark the exact owner epoch after a confirmed structural mutation.
  Accessors return concrete `ArrayList` point-in-time snapshots. Rebuild is copy-on-write: an old
  snapshot is never cleared or reused while an outer/nested callback may still iterate it. Direct
  mutation of vanilla live lists bypasses epochs and is intentionally visible only at the configured
  identity/order audit (`economy.structureAuditMs` or `market.structureAuditFrames`). Missing,
  foreign, disabled, or malformed state fails open to a fresh `new ArrayList(source)`.
- `CommodityOnMarket.reapplyEventMod`: принимается только точная vanilla-цепочка
  `getCombinedTradeModQuantity -> unmodifyFlat("eMod") -> getModValueForQuantity -> modifyFlat`
  с отдельной zero-quantity веткой и общим `RETURN`. Inline-патч добавляет один private transient
  synthetic known-absent flag. Nonzero-ветка сохраняет исходный порядок remove -> calculate ->
  conditional add; zero-ветка пропускает только повторное удаление после успешного remove.
  Partial/foreign shape отвергается.
- `MutableStatWithTempMods`: принимаются exact `advance/getMods/getMod/removeTemporaryMod/hasMod/
  writeReplace`, семь внутренних вызовов `getMods`, один iterator removal и один `unmodify(source)`.
  Исходные тела переименовываются в private synthetic methods; wrappers обязаны синхронизировать
  state перед mutation/read/save. Private transient state field, полная wrapper wiring и отсутствие
  public `getMods` calls внутри raw methods входят в postcondition.
- `BaseIndustry.advance`: exact base method должен содержать один `isDisrupted`, один
  `disruptionFinished`, чтение `building/wasDisrupted` и запись `wasDisrupted`. Исходное тело
  переносится в private synthetic raw method; wrapper допускает skip только после полного вызова,
  exact class-eligibility и состояния `!building && !wasDisrupted`. `setDisrupted(FZ)` получает
  branchless wake-prologue. Partial fields/raw method, неизвестный override contract или leaked
  template owner дают `SKIPPED_STRUCTURAL`/vanilla fallback.
- `CampaignEngine.setInstance/resetInstance`: существует единственное static поле типа
  `CampaignEngine`; `setInstance` записывает в него argument 0, а `resetInstance` — `null`.
  Begin-hooks с сохранением transition token должны непосредственно предшествовать подтверждённым
  singleton `PUTSTATIC`; completion-hooks с тем же token должны непосредственно предшествовать
  каждому normal `RETURN` обоих методов.
- `O0Oo`: system/anchor locals выводятся из identity comparisons с destination location и
  hyperspace anchor; номера local slots не фиксируются.
- Scratch locals принимаются только при точном наборе receiver/argument uses. Для pooled
  `H/A/Z`, label-candidate, Intel-contains и `BaseLocation` путей ставится depth-indexed reentrant
  scope с
  очисткой на каждом normal/exceptional exit; catch-all handler получает явный `F_FULL` frame
  с исходными locals.
- Два internal-read sites `HyperspaceTerrainPlugin` могут читать `HyperspaceAutomaton.cells`
  напрямую только для точного vanilla runtime owner и только когда buffer-reuse transformation
  подтверждена. Для subclass, неподтверждённого patch state или недоступного поля используется
  исходный virtual `getCells()`; такое чтение считается public escape и безопасно запрещает reuse.
- Wrapper сохраняет исходные declaration/type/parameter annotations и parameter metadata,
  переносит реализацию в private synthetic method и при повторной трансформации заново
  проверяет semantic contract оригинала и точную последовательность wrapper-инструкций.

## Observation transformer для mod bytecode

`patch.directMarketObservation` регистрирует отдельный observation-only transformer после
основного engine transformer. Он не меняет публичные interfaces и не определяет runtime types в
mod loader: typed hook уже находится в game/system loader, а изменённый mod bytecode только
вызывает этот hook.

Класс рассматривается только если:

- его loader совпадает с game runtime loader или является его потомком;
- system-loader class имеет `CodeSource` внутри `mods/`;
- источник не является core/API/common/sound JAR или самим Prepatcher;
- bytecode содержит точный direct call `MarketAPI.advance(F)V` либо concrete
  `Market.advance(F)V`.

Каждый call site заменяется синхронным wrapper-вызовом с тем же receiver и `float amount` на
operand stack. Wrapper всегда вызывает original market до возврата. Поэтому не меняются:

- число и порядок direct calls;
- thread и синхронный post-call contract;
- exception propagation;
- callback cadence;
- save state.

После успешной bytecode verification transformer немедленно регистрирует metadata site через
loader-neutral bridge. Поэтому `call-sites.csv` является manifest найденных sites и не зависит от
того, исполнилась ли соответствующая ветка мода. Если eager registration недоступна, runtime wrapper
сохраняет прежний lazy fail-open путь.

Entry probe concrete `Market.advance()` отличает central scheduler, planet-condition
scheduler/immediate, save/direct origins через `ThreadLocal`. Непомеченный вход лишь считает и
bounded-sample'ит stack. Unknown stack budget обновляется каждый report interval, а signature map
атомарно отсоединяется при отчёте: ранний частый caller не может исчерпать sampling на весь процесс.

Изменения, которые мод теоретически может наблюдать: дополнительный wrapper frame в stack,
изменённые class bytes до JVM definition и небольшой sampling overhead. Self-integrity моды могут
отказаться принимать transformed bytes; structural/loader ошибка в таком классе даёт fail-open и
оставляет его bytecode vanilla. Reflection/MethodHandle paths остаются синхронными и попадают в
bounded `UNKNOWN_DIRECT`, если не имеют известного engine origin.

`session.json` schema 3 содержит `sessionOrigin`. Обычная игра использует `game`; startup/FR
validation smoke получают отдельную метку и префикс каталога. Observer не удерживает ссылки на
markets, а file/telemetry error не подавляет original call.

## Контракт единого market scheduler

`patch.marketScheduler` сохраняет точную последовательность входных `amount` в RLE-history и
агрегирует cadence по render batch. Callback всего рынка может быть coalesced, но подтверждённые
vanilla-компоненты `MilitaryBase`, `LionsGuardHQ` и `RecentUnrest` воспроизводят исходные шаги
локально. Их наличие не делает рынок full-rate.

Активная construction queue, building или upgrading требуют full-rate всего рынка, поскольку
порядок completion/queue-start распределён по `Market.advance()`. Режим действует только пока
состояние активно. Mutation barriers exact-replay’ят старую history до изменений `Market`,
`BaseIndustry` и `ConstructionQueue`. Mutation epoch запускает следующий полный detector scan;
без мутаций используется редкий safety audit, а не обход industries на каждом simulation tick.

Direct, core event и fail-open calls остаются синхронными: старая history выполняется первой,
текущий шаг — отдельно. Save по умолчанию coalesced с batch context; диагностическая настройка
`market.remote.exactReplayBeforeSave=true` выполняет все pending steps отдельно.

Runtime state weak-identity keyed и process-local. Component replay stack thread-local, reentrant и
market-specific. Исключение callback пробрасывается и изолирует конкретный рынок. Mod-компоненты не
переводятся автоматически в full-rate; optional `observer.marketAdvanceSemanticRisks` только
создаёт статический CSV risk report и в observer-only режиме не меняет class bytes.

Deferral активируется только после регистрации всех core и semantic capabilities: `CampaignState`,
`CampaignEngine`, `Economy`, `BaseCampaignEntity`, save barrier, `Market.advancePlan`, wrappers
`MilitaryBase`/`LionsGuardHQ`/`RecentUnrest`, barriers `BaseIndustry`/`ConstructionQueue`. Если
любой компонент структурно пропущен, scheduler остаётся synchronous fail-open.

Callback multiplicity обычных mod-компонентов при coalescing остаётся изменённой и не считается
полностью совместимым контрактом. Для обнаружения риска используются категории interval/random/
single-transition/structure-mutation; ручной opt-out остаётся
`$starsectorPrepatcher_perSimulationTickMarket=true`.

Local replay сохраняет исходную последовательность внутри конкретного компонента, но не глобальное
чередование нескольких компонентов между шагами. Поэтому общий RNG order и наблюдение
промежуточных shared-market состояний не считаются доказанными до campaign-level differential
tests. Save callback, который уже начался и завершился исключением, не повторяется автоматически:
его detached debt отбрасывается, рынок переводится в synchronous fail-open, а save прерывается.

## Ограничения

Статический анализ не может доказать намерение произвольного стороннего патча. Если target
сохраняет похожие инструкции, но меняет их смысл так, что контракт перестаёт быть однозначным,
соответствующий блок должен дать `SKIPPED_STRUCTURAL`. Hook-и дополнительно сохраняют vanilla
fallback на отключённой настройке, cache miss и runtime error.

Route indexes намеренно имеют TTL: size/first/last проверяются на каждом hit, полный identity/
relationship snapshot — раз в `route.indexTtlMs`. Это сохраняет быстрый hit path и ограничивает
видимость прямой мутации сторонним модом. Значение TTL `0` отключает runtime index; в
`profiles/safe.properties` route patch отключён полностью.

`patch.strategicJumpDestinationFirst` не использует route index, TTL или runtime helper. Это
самостоятельная inline-трансформация `StrategicModule.findNearestSafeJumpPoint`: accepted destination
predicate остаётся vanilla, а `getNumHostileMarkets` лениво выполняется не более одного раза на
принятый jump point. Поэтому патч не зависит от `patch.routeJumpPointIndex` и не делит с ним
transformation surface. Несовпадение radius/multiplier/control-flow либо foreign lazy-state оставляет
весь класс без изменений со `SKIPPED_STRUCTURAL`.

`patch.strategicJumpDestinationIndex` имеет явную structural-зависимость от destination-first и
потребляет его уже преобразованный post-state. Он также требует owned lifecycle-maintenance hook,
`JumpPoint` destination mutators, `JumpDestination.setDestination` и membership hooks `BaseLocation`.
До загрузки всех поверхностей capability остаётся `PENDING`, partial index не публикуется.

Runtime не использует route-widget cache и не определяет тип current location. Каждый запрошенный
`LocationAPI` принимается одинаково, с bounded admission rate и bounded LRU. Первый lookup только
регистрирует demand; точный ordered index строится и проверяется в campaign maintenance под общим
wall-clock/work-unit budget. Полная длительность maintenance — включая lock, выбор задачи, очереди,
retry heap, LRU и cleanup — вычитается из token bucket; линейного поиска retry/audit state в общем LRU
нет. Пока state `BUILDING`, `REFRESHING` или `FAILED_COOLDOWN`, внешний цикл получает пустой source
вместо полного vanilla list. В `BUILDING`/`REFRESHING` истёкший существующий `JumpPlan` остаётся
активным, а флот без плана повторяет rebuild позже; `FAILED_COOLDOWN` очищает stale plan и также ждёт
ограниченного retry. Следовательно, патч намеренно допускает задержку маршрута на несколько кадров,
но не меняет candidate result после READY.

Exact target и исходный hyperspace fallback объединяются по identity в vanilla outer order, а один
jump point присутствует в union один раз. Проверенный destination miss кешируется как пустой результат.
Нормальные API-мутации обновляют один point или запускают budgeted replacement source build; прямые
mutable-list edits обнаруживает непрерывный budgeted audit. Ошибки одной location получают cooldown и
редкий ограниченный retry, поэтому несовместимый getter не вызывает полный rebuild на каждом lookup.
Неожиданная ошибка самого runtime hook считается capability failure: оптимизация отключается и только
в этом аварийном случае возвращается vanilla source.

Memory retention ограничена числом LRU states, idle TTL, campaign-generation reset, фиксированным
weak-identity owner accelerator и фиксированным merge cache. Capacity pressure вытесняет только
готовый или failed state; незавершённый build не перезапускается из-за admission churn. Due failures
хранятся по одному разу в indexed bounded heap, READY audit — по одному разу в intrusive queue.
Внешний index state не добавляется в save и не полагается на weak-key map, value которой через
`JumpPoint` снова удерживает location key.

Comm-relay index намеренно использует такой же bounded-staleness контракт. Size/radius и identity
первой/последней системы проверяются на каждом запросе, а полный ordered identity/coordinate audit
выполняется раз в `commRelay.indexTtlMs`. Владелец релиза явно принимает задержку до TTL для прямого
перемещения или замены средней системы сторонним модом; vanilla distance/tag/memory/tie loop над
возвращёнными candidates не меняется. Значение `0` отключает runtime index.

Persistent economy snapshots intentionally accept bounded behavior changes for mods that mutate
`Economy.getMarkets()`, `Market.getConditions()`, or `Market.getIndustries()` live lists directly
instead of using the corresponding mutators. Standard transformed mutators and replacement of the
backing list invalidate immediately; identity/order edits inside the same list may remain invisible
until the audit. State is private/transient and owner-local, so reflection code that enumerates all
declared members must ignore synthetic fields/methods. The optimized callbacks still receive the
same eager ordered objects once a snapshot is rebuilt.

### Dormant inherited BaseIndustry

`patch.marketNoOpCallbacks` является явно aggressive-исключением из общего правила сохранения
callback cadence. Он не затрагивает `MarketConditionPlugin`, `SubmarketPlugin`, custom Industry
`advance()` или любой runtime class, который переопределяет `isDisrupted()`/`getDisruptedKey()`.
Только exact inherited `BaseIndustry.advance()` после полного vanilla-вызова может перейти в dormant
state.

В dormant state wrapper каждый attempted market tick всё равно читает `building` и `wasDisrupted`.
Обычный `setDisrupted()` сбрасывает state до выполнения исходного метода. Раз в
`market.noOpIndustryAuditFrames` выполняется полный raw call, поэтому прямое изменение disruption
memory в обход API имеет bounded visibility. Это намеренное изменение поведения: число вызовов
унаследованного base callback уменьшается, а private-memory mutation может задержаться до audit.

Class eligibility кэшируется через `ClassValue`, а per-instance state находится в двух private
transient synthetic fields; глобальной strong/identity map нет. На fast path нет helper-call,
reflection, counter или allocation. State не входит в save. Safe profile выключает patch.

### Commodity temporal active set

Компоненты, изменяющие `Economy.advance(F)V` и его owner-local support state, применяются только
через единый `economyAdvancePlan`: persistent market snapshots, location-cache sequence и central
market-scheduler source проверяются как одна feature-mask комбинация. Candidate строится в порядке
snapshots → location cache → scheduler source; частичное или чужое split-состояние не принимается.

Компоненты, изменяющие `Market.advance(F)V`, применяются только через единый
`marketAdvancePlan`: persistent snapshots, commodity temporal loop и direct entry probe сначала
проверяются как одна feature-mask комбинация и не могут остаться в split/partial состоянии.

`patch.commodityTemporalFastPath` применяется только к точным vanilla `Market`,
`CommodityOnMarket`, `MutableStat` и `MutableStatWithTempMods`. Market-state сохраняет исходный
identity/order live commodity list; active subset всегда перестраивается в том же порядке.
Публичные API signatures, callback cadence conditions/industries/submarkets и save graph не
меняются.

Standard MutableStat mutators немедленно ставят dirty bit через private owner/role binding. Binding
допускает ровно одного owner: shared stat, повторная роль одного stat внутри commodity, subclass или
foreign backing map переводят соответствующий entry на полный vanilla loop. Synthetic fields private
and transient; reflection-код модов должен игнорировать synthetic members.

Прямая mutation live map/list в обход штатного mutator обнаруживается bounded audit, а не
same-frame. Public `getMods()` materialize'ит direct expiry state и оставляет конкретный stat на
retained vanilla scheduler; market active set всё равно может исключить пустой exposed stat и снова
активировать его после audit. Same-size direct replacement в середине deferred interval не содержит
информации о точном моменте mutation, поэтому audit применяет накопленное elapsed время как
агрегат. Это принято только в default/aggressive profile; safe profile отключает patch.

Internal `eMod` notification подавляется, потому что это собственная запись
`CommodityOnMarket.reapplyEventMod()`. Все прочие relevant mutations вызывают оригинальный
`reapplyEventMod()` перед возвратом entry в inactive state. Если callback одного commodity меняет
другой inactive commodity, второй допускается обработать на следующем market tick, а не позже в том
же исходном list pass. Первый full audit staggered между markets; четыре high-volume counters sampled
раз в 64 calls. Helper exception или lifecycle boundary unbind'ит state и использует vanilla
fallback; ни один cache не сериализуется.

Commodity event-mod cache предполагает, что vanilla-private source id `eMod` принадлежит
`CommodityOnMarket.reapplyEventMod`. Прямая запись сторонним модом в `available` stat с тем же id
при сохранении нулевого combined trade quantity не инвалидирует known-absent flag и поэтому не
поддерживается. Обычные public trade-mod методы поддерживаются: nonzero transition сначала очищает
flag и выполняет полную vanilla-цепочку; первый zero-вызов после load тоже выполняет remove.

Hyperspace diagnostics не участвует в tile-level clock/counter hot path. Значение
`pooledRandomApprox` является накопительным и монотонным с запуска JVM: опубликованные пачки
дополняются live pending tails из weak registry активных `RandomPool`. Приблизительность остаётся
из-за concurrent snapshot и weak lifecycle pool, но незаполненный хвост учитывается, пока pool жив.
Automaton counters также накопительные; поэтому редкие rollover/reuse остаются видимыми после
последующих интервалов логирования.

Telemetry schema `0.7.1`: старый `pooledRandom` называется `pooledRandomApprox`, добавлен
`automatonInternalReads`, а статические `cullHeight`/`yClamp` counters удалены из runtime stats.
Анализатор старых и новых сессий должен различать schema: отсутствие удалённого поля не означает
нулевой activity или structural skip.

Structural proof показывает однозначность site, linkage, no-escape и verifier postconditions, но
не доказывает величину ускорения. Runtime и performance evidence создаётся в `.build/reports/`, а
проверенные выводы сохраняются в отчёте выпуска, например
[`releases/0.11.0.md`](releases/0.11.0.md).

Если несколько javaagent меняют одни и те же классы, располагайте Prepatcher после них:
transformer увидит bytes, возвращённые ранее зарегистрированными агентами. Installer обеспечивает
этот порядок для vanilla `vmparams` и Faster Rendering `starsector-core/fr.vmparams`:

```bat
StarsectorPrepatcher.bat install Vanilla
StarsectorPrepatcher.bat install FasterRendering
StarsectorPrepatcher.bat install Both
```

Без параметров единый Windows-launcher показывает меню и поясняет назначение каждой операции.
Каждый изменяемый файл получает отдельный timestamped backup; повторный вызов идемпотентен.
Если telemetry или другой agent был установлен позднее, installer нужно запустить ещё раз, чтобы
Prepatcher снова стал последним `-javaagent`.

## Presentation и structural patches

Общие классы не полагаются на случайный порядок двух независимых transformer-ов. Поддерживаемая
поддерживаемая runtime-последовательность остаётся `presentation → structural`. Все presentation
target-классы проверяются по локальной структуре методов; SHA-256 класса и JAR не участвуют в
compatibility decision. Presentation stage публикует owner, global feature mask и точный hook
inventory. Structural stage проверяет их до анализа и после каждого commit. При локальном
`SKIPPED_STRUCTURAL` presentation-класс остаётся входным, а независимые structural patches могут
продолжить работу на своих surfaces.

## AoTD Scheduler Fork Stage 8

The Stage 8 fork requires Prepatcher 0.11.0 and the original game `starfarer.api.jar`.
A partial capability profile is intentionally rejected. The legacy AoTD core-JAR replacement is
not compatible with this profile.

## Optional Nexerelin market-share target

`patch.nexPunitivePlayerShareLocalCache` независимо от vanilla-флага распознаёт
`exerelin.campaign.intel.Nex_PunitiveExpeditionManager` по локальной структуре фактического
classfile. Оба caller-флага включены по умолчанию; отключение Nex-флага не влияет на
`patch.punitivePlayerShareLocalCache` и vanilla manager. Nexerelin не является обязательной зависимостью: имя optional target хранится как строка,
а helper внедряется в mod-owned class без system-loader registry или reflection cache.
Поддерживаемая приложенная версия содержит один per-faction call и два player-share call sites.
Другая версия с изменённым data flow получает локальный `SKIPPED_STRUCTURAL`; core P0/P0.5 и
vanilla P1 продолжают применяться независимо.
