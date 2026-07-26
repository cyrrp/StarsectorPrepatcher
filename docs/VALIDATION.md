# Проверка optimization-патчей

Это единый pre-merge playbook для патчей, которые кэшируют данные игры, переиспользуют
mutable-буферы или меняют bytecode. Его цель — ловить повторяющиеся ошибки до графического прогона
и не принимать «патч загрузился» за доказательство корректности.

## Уровни доказательств

- **S — structural:** semantic site однозначно найден; postconditions, API surface и весь класс
  проходят `BasicVerifier`.
- **N — negative:** отсутствие, дублирование или конфликт site приводит к локальному
  `SKIPPED_STRUCTURAL`; несвязанные изменения не мешают патчу.
- **R — runtime:** на реальной modded-сборке есть `APPLIED`/`ALREADY_APPLIED`, нет
  `SKIPPED_ERROR`, optimizer exception или падения игры.
- **A — activity:** счётчик или отдельный лог подтверждает выполнение оптимизированного пути.
- **B — behavior:** целевой сценарий пройден без визуальной, игровой или callback-регрессии;
  fallback и граничные случаи проверены отдельно.
- **P — performance:** записана A/B-телеметрия на одном сохранении, сцене, масштабе и настройках;
  сравниваются frame time, allocations и call counts, а не только субъективный FPS.

Заявление «патч совместим» требует как минимум S+R. Заявление «патч корректен» требует S+N+R+B.
Заявление о приросте производительности требует также A+P. Общий smoke test не заменяет
изолированный B/P-прогон патча.

## Сначала зафиксировать время жизни и ownership

Для каждой новой структуры нужно письменно указать:

- кто владеет ключом и значением;
- допустимое время жизни: вызов, frame, location, campaign или весь JVM process;
- какие campaign/entity/plugin/location объекты достижимы из ключа **и из значения**;
- событие invalidation и поведение до/во время/после reset;
- может ли ссылка уйти наружу через return, callback, public getter или сохранённый аргумент.

Javaagent-классы и их `static`/`ThreadLocal` живут до завершения JVM. Поэтому любая достижимая из
них campaign-ссылка считается process root, пока обратное не доказано GC-тестом.

## Каталог повторяющихся регрессий

| Регрессия | Типичная причина | Обязательное доказательство |
|---|---|---|
| Lifecycle root | `static`, singleton, executor task или `ThreadLocal` хранит entity/plugin/location/engine | lifecycle reset очищает все значения; старое поколение собирается через `WeakReference` + `ReferenceQueue` |
| Weak-key/value cycle | `WeakHashMap<K,V>`, но `V → ... → K` или значение держит тот же campaign graph | тест хранит только map, отпускает key и требует его GC без дополнительного cache access |
| Reentrant reset | reset удаляет `ThreadLocal`, пока старый patched frame ещё исполняется и может создать новый frame | reset вызывается внутри вложенного scope; normal и exceptional unwind оставляют scratch пустым и detached |
| UI owner idle/LRU maintenance | synthetic monotonic clock, `< TTL`, `= TTL`, `> TTL`, owner touch перед limit insertion | удаляются только idle/LRU owners; identity iterator order не влияет; текущая локальная value остаётся usable; generation reset изолирует старые maps |
| Hover physical pruning | fresh/expired `CellHit`, sweep до и после prune interval | до interval scan отсутствует; после interval удаляются только expired cells; `lastResult` не очищается |
| Scratch/starfield high-water trimming | retained list/set растёт выше threshold и очищается caller-ом до scope exit; 64-level reentrancy; nested/exceptional scopes; synthetic grace/clock counter | mutation-site hooks сохраняют peak при последующем `clear()`; concrete `ArrayList`/`HashSet` types не меняются; до grace capacity сохраняется; active frame не заменяется; после grace oversized storage заменяется, trailing inactive frames сокращаются до 4; starfield делает 0 clock reads на `add` и 1 на release; pool limit сохраняется |
| Scratch retained-capacity gauges | одновременно увеличить `entityList`, `hitList`, `labelCandidates`, pooled lists и retained sets; затем reset generation | list/set gauges равны сумме per-collection high-water, а не одному максимуму; `clearCampaignCaches()` немедленно публикует `ScratchGaugeSnapshot.EMPTY` |
| Hover TTL boundary | synthetic cell ages `TTL-1`, `TTL`, `TTL+1` до и после maintenance | hot path и sweep используют один контракт: `age < TTL` valid, `age >= TTL` expired |
| Forced pre-save maintenance | молодой и idle owner, незакрытый maintenance throttle | forced sweep обходит throttle, но не удаляет owner моложе TTL и не нарушает scratch grace; выполняется до market debt flush |
| Public alias semantics | pool/double buffer повторно мутирует массив, коллекцию или vector, ранее возвращённые vanilla-кодом | consumer удерживает старый return/argument; последующие frames не меняют его, если vanilla не меняла |
| Неполная idempotency | первый найденный hook даёт `ALREADY_APPLIED`, поздние sites уже не проверяются | mutation test повреждает **последний** site при сохранённом marker и требует `SKIPPED_STRUCTURAL` |
| Неверный inline fast path | guard найден по похожим `isEmpty()`/field calls, но стоит до стороннего пролога, читает другой field или обходит часть vanilla checks | data-flow связывает guard и исходную работу с теми же `this` fields; branch входит в исходную первую инструкцию или в точно заданную post-check boundary; negative tests меняют receiver, placement и порядок |
| Ложная несовместимость локализованного JAR | whole-class hash реагирует на нерелевантные строки/constant pool | все patches, включая presentation, используют локальные structural contracts; нерелевантные field/method/debug/attribute изменения не блокируют совместимую semantic surface |
| False-green verifier | успех определяется как `patched > 0`, а `UNCHANGED`/missing target допустим | exit `0` только при точном числе targets и sites; missing/unchanged/duplicate/failed дают non-zero |
| TTL correctness | до истечения TTL не проверяется дешёвый mutable input: position, order, identity, tag | мутация сразу после build сравнивается с vanilla; явно доказано допустимое окно stale-данных либо есть live validation |
| Failed-cache storm | failed build не memoize-ится и повторяет allocation/full scan каждый вызов | malformed/custom объект многократно вызывает path; build выполняется ограниченно, fallback остаётся полным |
| Encoding/platform drift | `javac` использует системную code page; PowerShell содержит bash syntax; проверка шла на другой JDK | explicit UTF-8, parser/syntax check для каждого script, полный прогон на целевом JDK 17 и ОС |
| Loader constraint violation | typed hook и target определены sibling/child classloader'ами и имеют разные копии `com.fs.*` | payload определяется через game-loader lookup; hooks, API и targets имеют identity-equal loader; wrong-loader target получает `SKIPPED_LOADER` без изменения bytes |
| FR resolver drift | payload больше не может разрешить `PrepatcherConfig`/`PrepatcherLog` через fallback FR `AppClassLoader` → `JavaAgentLoader` | FR smoke выполняет bridge configuration и runtime logging; install failure происходит до регистрации transformer; остаточная связь явно учитывается до перехода на полностью JDK-only boundary |

## Обязательные review gates

Review не считается завершённым без следующих артефактов:

1. **Root graph:** короткая схема всех долгоживущих ссылок от agent `static`/`ThreadLocal` до игровых
   объектов. Weak key не засчитывается без анализа value graph.
2. **Generation contract:** указаны begin/reset/complete boundaries, поведение старого frame после
   смены поколения и fail-closed путь до готовности новой campaign.
3. **Alias contract:** для каждого pooled mutable объекта доказано отсутствие escape. Если объект
   возвращается public API или передаётся неизвестному callback, reuse запрещён без копии/ownership API.
4. **Whole-patch postcondition:** проверяются все sites, descriptors, counts, scope hooks и marker.
   `ALREADY_APPLIED` допустим только после полной проверки сериализованного класса.
5. **Guard contract:** перечислены все runtime inputs и причина каждого structural pattern check. Disabled
   status должен быть заметен пользователю, а не только присутствовать в отдельном debug log.
6. **Fallback contract:** malformed/custom/throwing inputs сохраняют vanilla result, order, identity,
   exception timing и не создают rebuild storm.
7. **Freshness contract:** для TTL указаны mutable inputs, максимальная допустимая задержка и дешёвые
   same-frame invalidators. Производительность не оправдывает незаявленное изменение семантики.
8. **Toolchain contract:** команды воспроизводимы из clean checkout, используют Java 17 и explicit
   UTF-8; Windows и POSIX entrypoints передают одинаковые arguments и exit codes.
9. **Loader contract:** перечислены loader control plane, payload, API и каждого target. До
   регистрации transformer весь payload определён loader'ом target; несовпадение identity даёт
   локальный `SKIPPED_LOADER`, а не потенциальный поздний `LinkageError`.
10. **Parent-loader exceptions:** target, который не видит game-loader runtime, не получает typed
    helper call. Для `sound.Sound` разрешена только structurally-verified inline-трансформация без
    runtime descriptor; новые исключения должны быть перечислены отдельно.

## Обязательные test gates

До merge должны пройти:

- positive structural transform и `BasicVerifier` для каждого target class;
- для presentation targets: positive transform по локальной semantic surface, owner/mask и точному
  hook inventory; unrelated class mutations продолжают применяться, relevant call/receiver/control-
  flow damage даёт `SKIPPED_STRUCTURAL`;
- для structural patches: повторный transform с точным `ALREADY_APPLIED` без изменений bytes и без
  structural skip; для presentation plans повторный transform проверяет owner/mask/combined
  postcondition, не добавляет hooks и возвращает `null` как `ALREADY_APPLIED`;
- mutation tests: missing, duplicate, foreign marker, broken first site и broken last site; для
  inline guards отдельно меняются receiver, entry prologue, branch target и порядок исходных
  checks при сохранённом marker;
- exact-count verifier: один missing/unchanged/duplicate target обязан завершать процесс ненулевым кодом;
- lifecycle GC: несколько load/reset generations, reentrant reset, normal/exceptional unwind и GC без
  «лечащего» обращения к cache;
- behavior parity для identity/order/duplicates/custom equality/callback exception/public aliases;
- TTL tests с mutation непосредственно после build и на boundary истечения;
- repeated malformed/failure test, подтверждающий bounded rebuild/allocation rate;
- documentation consistency: строгий `X.Y.Z` совпадает в `mod_info.json`, agent sources и
  manifests; changelog упорядочен, все актуальные документы достижимы от README, ссылки целы,
  а `SHA256SUMS.txt` полностью покрывает поставляемое дерево и совпадает с его содержимым;
- agent JAR содержит все top-level/nested entries `com/fs/starfarer/api/StarsectorPrepatcher*`, а
  control plane не имеет typed-ссылок, которые заставят agent loader определить payload;
- loader harness подтверждает vanilla и FR-like topology: payload/API/targets принадлежат одному
  system/game loader, premain control code — отдельному agent loader; wrong-loader negative case
  оставляет bytes без изменений и публикует `SKIPPED_LOADER`;
- packaged startup использует один `StarsectorPrepatcherAgent.jar`: presentation transformer
  зарегистрирован перед structural transformer, presentation hooks входят в тот же game-loader
  payload, отдельной FFP `-javaagent` записи или второго runtime нет;
- structural test `sound.Sound` подтверждает exact inline suppression, отсутствие helper
  `INVOKESTATIC` и полную postcondition/idempotency marker;
- полный `verify-structural` на целевой Java 17; PowerShell parser check и POSIX shell syntax check;
- реальный startup/activity smoke на той же Starsector-установке, для которой публикуется guard.

Численное performance-заявление делается только после этих gates и одинакового A/B-прогона. Если
какой-либо gate временно неприменим, патч остаётся experimental/disabled by default. Исключение
допустимо только по явному решению владельца релиза, при наличии отдельного kill switch и записи
причины/остаточного риска в отчёте соответствующего выпуска из [`releases/`](releases/0.11.0.md).

## Матрица запуска vanilla и Faster Rendering

Loader harness необходим, но не заменяет запуск настоящего `fr.jar`. Перед merge/release нужно
проверить одну и ту же mod-сборку и порядок agents как минимум в двух режимах:

1. Vanilla: root `vmparams`, запуск обычного launcher.
2. Faster Rendering: `starsector-core/fr.vmparams`, запуск `starsector-core/fr.bat`.

В обоих режимах выполняются cold startup, создание новой кампании до первого campaign frame,
загрузка существующего save, открытие sector/system/Intel map, переход через hyperspace и один бой.
При включённой telemetry порядок должен быть `Telemetry` → `Prepatcher`; его можно восстановить
идемпотентным вызовом `StarsectorPrepatcher.bat install Vanilla` или
`StarsectorPrepatcher.bat install FasterRendering`.

Обязательные loader assertions для FR:

```text
StarsectorPrepatcherHooks.class.getClassLoader()
    == StarsectorPrepatcherPresentationHooks.class.getClassLoader()
    == CampaignEngine.class.getClassLoader()
    == ClassLoader.getSystemClassLoader()
```

В `prepatcher.log` должны быть успешная установка target-loader runtime и ожидаемые patch statuses.
Smoke фиксирует `APPLIED` для structural targets из обоих containers (`CampaignEngine`, `BaseTerrain`) и
`APPLIED` для нерелевантно изменённого FR target либо `SKIPPED_STRUCTURAL` при изменении owned site; его structural
statuses при этом остаются `APPLIED`. Любой `LinkageError`, `NoClassDefFoundError` payload/agent
types, `SKIPPED_LOADER`, неожиданный structural status, определение payload через agent loader
или иное незаявленное расхождение результатов vanilla/FR блокирует merge. Отдельно
сохраняются startup/mission logs: успешный главный экран не покрывает deferred hook linkage,
которое впервые происходит при генерации кампании или первом frame.

## Временно отключённые startup-патчи

`patch.loadingTextReader` и `patch.startupLogAggregation` имеют подтверждённую регрессию запуска
миссий в текущей modded-сборке. В `0.7.1` оба переключателя установлены в `false` во всех
поставляемых профилях: default, safe, aggressive и debug. Их реализация и тестовые сценарии сохранены для
отдельной диагностики, но повторное включение в поставляемом профиле запрещено до изоляции причины,
исправления и нового startup/mission B-прогона каждого патча по отдельности.

## Structural plans для fast-forward presentation

Все 24 presentation target-класса проверяются по локальной структуре owned methods и call sites.
SHA-256 класса и содержащего JAR не участвуют в compatibility decision. Для каждого класса
фиксируются method descriptors, original/wrapper semantic sites, receiver/argument dataflow,
ожидаемые counts после validator, общий owner, global feature mask и combined postcondition.

Class-level pipeline обязан принимать только `VANILLA` или
`COMPLETE_PATCHED_WITH_EXPECTED_MASK`. Нерелевантные private field/method/debug/attribute изменения
не блокируют plan. Missing, duplicated или moved site, неверный receiver/argument, mixed
original+wrapper state, foreign hooks без owner либо mask mismatch дают `SKIPPED_STRUCTURAL` без
частичного commit.

`CampaignState.advance(...)` доказывает протокол `false → advance/true loop → false` через CFG и
`SourceValue`; номера JVM locals не являются контрактом. `CampaignFleet` выбирает pulse fader по
semantic region относительно sensor-range presentation, а не по порядковому номеру Fader-вызова.
Safe baseline остаётся `20` классов/`59` wrapper sites, aggressive — `24`/`71`.

### CoreScript core-worlds extent cache

Для `patch.coreWorldsExtentCache` обязательны:

1. structural transform фактического `CoreScript.class` без hash allowlist;
2. idempotent second pass с owner marker и полной hook postcondition;
3. unique `Global.getSector()` → `ASTORE` data-local proof без фиксированного slot number;
4. единственный terminal `Misc.computeCoreWorldsExtent()` непосредственно после
   `RouteManager.advance(F)V`; duplicate, moved или foreign hook state должен дать fail-open;
5. runtime regression origin-inclusive min/max, unchanged no-write path, mutation/identity repair,
   radical fast-forward deferral, timed-expiry repair и GC proof, что активное static state не
   удерживает sector, memory, system list или опубликованные vectors;
6. target-loader payload/FR loader inventory с `StarsectorPrepatcherCoreWorldsRuntime`.

## Матрица целевых сценариев

| Патч | Сценарий для A/B | Activity signal | Обязательная проверка поведения |
|---|---|---|---|
| `patch.mapRenderStuff` | global/system map с тысячами entities/icons и длительный render | `retainCalls`, `avoidedContainsUpperBound`, `retainKeysScanned/Removed`, `retainEqualityFallbacks`; allocation profile/GC | тот же набор и порядок иконок; custom keys сохраняют `equals/hashCode`; один scope очищает обе reusable collections на normal/exceptional exit; missing/ambiguous reconciliation или allocation site отменяет всю группу |
| `patch.labelSpatialCandidates` | global map на нескольких zoom и плотных кластерах | `labelCandidates=candidates/total` | те же решения о размещении/скрытии подписей на границах bucket и после TTL rebuild |
| `patch.mapHitTest` | длительное движение/остановка мыши над объектами и пустым hyperspace | allocation profile/GC; `hoverHits`, `hoverMisses` | hit-list/vector scratch не утекают между reentrant/exceptional exits; cache miss выполняет preserved original; hover меняется не позднее TTL/cell boundary; любой missing/mixed allocation, scope или wrapper отменяет всю группу |
| `patch.intelCallbackCache` | Intel map locations и plugins разных модов | `mapLocationHits/Misses` | callback return/`null`/exception fallback; допустима только заявленная TTL-видимость изменений |
| `patch.intelEntityIndex` | многократное обновление Intel icons | `intelIndexHits`, `intelIndexBuilds` | identity, а не `equals`; удаление/замена plugin становится видимой после rebuild |
| `patch.intelReconciliation` | добавление, обновление и удаление Intel rows/icons при большом missing-list | dedicated counters или CPU profile EventsPanel | direct candidate lookup совпадает с vanilla scan; custom equality/duplicates сохраняют тот же missing set; повреждение любого site/scope отменяет всю группу |
| `patch.intelArrowRendering` | Intel map с большим числом стрелок и plugins разных модов | `arrowHits/Misses`; allocation profile `Vector2f` | callback return/`null`/exception fallback; координаты/углы совпадают; vectors не утекают; повреждение callback/allocation/scope отменяет всю группу |
| `patch.systemNebulaCache` | повторно открыть system/global map, затем сменить систему | `nebulaHits`, `nebulaMisses` | synthetic entities каждый раз новые; metadata актуальны после invalidation |
| `patch.sampleCacheClearThrottle` | быстро закрывать/открывать hyperspace map | `sampleClearSkips` | после фактического clear и истечения interval terrain samples корректны |
| `patch.gridLineCap` | огромный сектор на min/max zoom | `Map grid LOD: ... spacing=...` | координаты и hit tests неизменны; меняется только плотность grid, без gaps/мерцания |
| internal `campaignCacheLifecycle` | load → map/Intel/route → reload/reset, несколько поколений | `campaignCacheResets`; GC harness/heap roots | после reset caches пусты, старые engine собираются; до lifecycle-ready используется vanilla fallback |
| `patch.campaignListenerThrottle` | закрытая карта, создание/удаление системы и custom list mutation | `campaignListenerRuns`, `campaignListenerSkips` | repositories получают listener сразу при обычном изменении и не позже audit при прямой мутации |
| `patch.campaignSnapshotReuse` | campaign advance в hyperspace/системах, paused/reentrant/throwing callbacks | JFR allocation stacks; counts пяти snapshot hooks | тот же point-in-time набор и порядок; no escape; все exits обнуляют campaign refs |
| `patch.entityScriptSnapshotReuse` | scripts добавляют/удаляют scripts и бросают exception | JFR allocation stacks; отсутствие scratch hooks на empty path | guard читает ровно `this.scripts` и входит перед исходным snapshot без обхода стороннего пролога; non-empty snapshot/order/call count остаются vanilla |
| `patch.coreWorldsExtentCache` | CoreScript при 1× и fast-forward N×, неизменная/изменённая геометрия, замена/мутация memory-векторов, timed expiry и GC активного cache state | `coreWorldsCalls/SystemScans/SystemsVisited/FastForwardSkips/UnchangedSkips/Publishes/IntegrityRepairs/Fallbacks`; weak-reachability harness | origin-inclusive bounds и порядок min/max/center совпадают; unchanged path не пишет memory; repair немедленный; safe сканирует каждый substep, radical — первый substep внешнего frame; static state не удерживает sector/memory/systems/vectors; unrelated class changes не блокируют structural match |
| `patch.emptyMemoryAdvanceFastPath` | тысячи пустых `Memory` плюс expire/require entries | CPU/allocation stacks; отсутствие двух retired iterator hooks | guard после restoration/pause читает `this.expire` + `this.require`; conversion, expiration/require cleanup/order на непустом пути совпадают с vanilla |
| `patch.routeJumpPointIndex` | маршруты внутри/между системами, wormholes и одинаковые anchors | route index hits/builds/fallbacks | destination, distance и tie-break совпадают; malformed/custom getter использует полный список |
| `patch.strategicJumpDestinationFirst` | system→hyperspace, hyperspace→system, system A→B, null target; 0/1/multiple accepted destinations | hostile-market call location/count and campaign frame tails | выбранный candidate и early-return совпадают; 0 scans для rejected jump points и ровно 1 для accepted jump point |
| `patch.strategicJumpDestinationIndex` | cold lookup в arbitrary small/large location; exact target+fallback union; negative miss; point add/clear/remove, retarget, source add/remove, direct mutable edit, malformed getter, generation reset, capacity pressure и missing capability surface | `strategicJumpIndex*` admissions/deferred/deferred-plan/build/work/audit/retry-heap/queue/phase/oldest-age/full-maintenance/overrun/failure/eviction counters; ASM source+expiry+four topology surfaces; JFR post-load frame tails | lookup не читает destinations и не строит index; один maintenance ≤ `indexMaxWorkUnits`, полный lock+selection+queue+LRU+cleanup time вычитается из wall-clock bucket, overrun ограничен одной неделимой unit; retry/audit selection не сканирует LRU; `BUILDING`/`REFRESHING` не вытесняются capacity pressure; нет location type/size admission branch; non-READY не возвращает full list и сохраняет expired plan; READY subset сохраняет outer identity order/dedup; miss пустой; point delta не запускает full build; failures имеют один heap entry и cooldown; destination-first postcondition остаётся валидной |
| `patch.economyLocationCache` | ускорение времени в секторе с большим числом markets | `economyLocationChecks/Dirty/Skips` + JFR | explicit dirty rebuild same-frame; add/remove/reorder/move/id сразу меняет fingerprint; reset очищает state |
| `patch.marketScheduler` | ×1/×4/×10/×100 constant-step batches; mixed raw-float RLE runs; run-limit overflow; nested market context; all 11 capability bits and missing-registration negatives; MilitaryBase/LionsGuardHQ/RecentUnrest wrappers; JAR-wide construction-mutator inventory; dirty/safety/forced construction scans; queue/building/upgrading/multiple/probe-failure reasons; bounded per-reason diagnostic CSV; coalesced/exact save and callback-failure policy; two periodic + six core event sources; observer-only class containing direct Market.advance | tick/batch/source counters; pending steps/runs gauges and high-water; capability mask; coalesced/context/replay counters; construction reason gauges, scan/transition/cost counters, `logs/market-construction-diagnostics/session-*/samples.csv`; CSV risk report; ASM/actual-agent | scheduler remains synchronous if any semantic wrapper/barrier is absent; exact step order is available inside local wrappers; no averaging at run limit; steady state does not scan all industries per input; reasons distinguish queue/building/upgrading/uncertain states; diagnostic cap applies independently per category and retains no game objects; time does not cross mutation boundary; begun failing save callbacks are not replayed; observer-only mode returns original bytes; global intercomponent/RNG equivalence remains explicitly unproven |
| `patch.directMarketObservation` | mod classes в system/child loaders: real temporary `mod_info.json`, observation enabled and scheduler-sync-only, executed and never-executed call sites, ARG/CONST/derived amount, exception, reflection/MethodHandle, known planet engine path, two report intervals и validation smoke | `call-sites.csv`, `observations.csv`, `stacks.csv`, `unknown-stacks.csv`, `summary.csv`, `session.json`, `prepatcher.log`; explicit `mod_id`, `mod_name`, `mod_directory`, `jar_name`, `source`; JFR overhead A/B | wrapper installs whenever observation or scheduler requires it; call multiplicity/thread/exception сохраняются; without debt amount is exact, with scheduler debt callback gets `pending + direct`; manifest exists before first execution when observer is active; mod identity comes from owning `mod_info.json` rather than manual source-path parsing; metadata v1 remains readable; unknown budget renews per interval; observer failure does not suppress original call |
| internal `economyAdvancePlan` | все 7 непустых комбинаций persistent/location/scheduler; повреждённый location region; unowned split state; damaged scheduler hook; повторная обработка и mask mismatch | patch status `economyAdvancePlan`, ownership marker, private feature-mask field, exact persistent/location/scheduler hook counts, scheduler registration, ASM verifier | один commit и один marker; location hook согласован с наличием persistent state; disabled components остаются vanilla; отказ одного компонента не оставляет другие; legacy split markers не принимаются; полный candidate idempotent только при той же mask |
| internal `marketAdvancePlan` | все 15 непустых комбинаций snapshots/commodity/entry/scheduler-semantics; повреждённый commodity region; unowned split state; missing semantic registration; damaged hook; повторная обработка и mask mismatch | patch status `marketAdvancePlan`, ownership marker, private feature-mask field, exact component hook counts, ASM verifier | один commit и один marker; disabled components остаются vanilla; отказ одного компонента не оставляет другие; legacy split markers не принимаются; полный candidate idempotent только при той же mask |
| internal `saveMethodPlan` + ordered `saveOutputBufferDedup` | пять config-сценариев: buffer-only, maintenance-only, maintenance+buffer, scheduler+maintenance, all; barrier masks `1`/`3`; повреждённая 1 MiB allocation; malformed maintenance prefix; unowned barrier/dedup states; damaged flush hook; повторная обработка и barrier-mask mismatch | отдельные patch statuses/ownership markers; `smo$saveMethodPlanMask`; exact maintenance/flush/registration/buffer counts; ASM verifier и earlier-postcondition revalidation | maintenance+flush коммитятся одним correctness-critical barrier; scheduler barrier остаётся первой save-инструкцией; dedup применяется после barrier; несовместимый buffer pattern пропускает только `saveOutputBufferDedup` и не удаляет scheduler capability; malformed barrier не регистрирует capability, но не блокирует независимый dedup; legacy scheduler markers не принимаются |
| `patch.economyPersistentSnapshots` | 1000+ markets, неизменные структуры, nested callbacks, backing-list replacement и прямые live-list edits | aggregate `economyPersistent*`; split `economyMarketPersistent*`, `marketConditionPersistent*`, `marketIndustryPersistent*`; `economyLocationEpochHits/Audits/SequenceMismatches` + JFR | API mutator и source replacement инвалидируют немедленно; unchanged owner-local audit не перепубликует fingerprint и не использует global map; direct edit виден не позже audit; RandomAccess/iterator identity order совпадает; старый snapshot не мутируется; load/clone/reset не сохраняют state; removed scratch hooks отсутствуют, paused-condition copy остаётся vanilla |
| `patch.commodityEventModDirtyCache` | 1000+ markets в обычной кампании, затем торговое событие и истечение trade mod | JFR `reapplyEventMod`/`HashMap.remove`; structural field/control-flow checks | первый zero-вызов после load удаляет `eMod`, следующие zero-вызовы пропускают remove; nonzero сохраняет порядок remove -> calculate -> add, transient flag очищается до него; моды не должны напрямую использовать private key `eMod` |
| `patch.commodityTemporalFastPath` | 1000+ markets: stable commodities, API mutations, temporary expiry, retained `getMods()` map, reorder/add/remove commodity и shared/subclass stats | sampled `commodityTemporalMarkets/Entries/Active/InactiveSkips`; exact dirty/exposure/audit/rebuild/fallback counters; JFR `Market.advance`; actual-javaagent smoke | stable entry покидает active list; API mutation будит к следующему market tick; порядок live list сохраняется; expiry/reapply выполняются; shared/subclass/foreign state идёт vanilla; direct mutation обнаруживается не позже audit; owner/role/state не входят в save |
| `patch.tempModExpiryScheduler` | 1000+ markets, tied/nearby/ULP durations, add/refresh/remove/has/getMods/save, live-map mutation и subclass owner | `tempModInitialSweeps/ExpirySweeps/SyncSweeps/ScheduleRebuilds/ModsScanned/ExternalExposures/SubclassFallbacks/FailureFallbacks` + JFR + child-JVM actual-agent/XStream smoke | current minimum использует repeated float countdown; нет aggregate deadline comparison; one-pass materialize/removal/rebuild; add/remove/getMods/save синхронизируют; `hasMod` не sweep; exposure/subclass/anomaly идут retained vanilla; transient fields не входят в save |
| `patch.marketNoOpCallbacks` | тысячи inherited vanilla/BaseIndustry subclasses: dormant, building, disrupted, direct disruption-memory edit и custom overrides | JFR `BaseIndustry.advance`; structural retained-body/wake checks; isolated actual-agent audit=2 smoke; callback counters в fixture | dormant inherited call skips between audits; building/setDisrupted wake same call; custom `advance/isDisrupted/getDisruptedKey` full cadence; condition callbacks untouched; state transient |
| `patch.commRelaySystemIndex` | hyperspace с functional/nonfunctional relays и 1000+ systems | index hits/builds/fallbacks, candidates/total, validation scans/systems | relay и Random/tie behavior совпадают; size/endpoints проверяются каждый query; exact identity/location audit выполняется по TTL; владелец релиза принимает bounded staleness до TTL для direct middle-system mutation |
| `patch.shipAdvanceScratch` | бой 100–300 ships/fighters с modded listeners/commands | scratch counts + allocation profile | listener snapshot fresh; order, multiplicity, callback count и nested reentrancy совпадают |
| `patch.particleCleanup` | particle-heavy эффект с массовым expiry | group/expiry/linear-removal counts | все particles advance до cleanup; survivor order/duplicates/equality/exception behavior совпадают |
| `patch.loadingTextReader` | **known-disabled:** изолированный startup/mission прогон больших JSON/CSV с CRLF, UTF-8 boundary и ошибками чтения | allocation profile + loading/save runtime suite + mission smoke | текст, normalization, close и exception timing совпадают с vanilla; до доказательства переключатель остаётся `false` во всех профилях |
| `patch.startupLogAggregation` | **known-disabled:** изолированный cold startup/mission прогон большой mod-сборки | число INFO/WARN/ERROR до и после + mission smoke | уменьшаются только целевые repetitive INFO; работа loaders и WARN/ERROR сохраняются; до доказательства переключатель остаётся `false` во всех профилях |
| `patch.rulesLiteralParser` | загрузка большого rules.csv и randomized differential corpus | parser call/CPU profile + differential suite | split/replace semantics совпадают для delimiters, пустых fields и Unicode |
| `patch.saveLoadProgressThrottle` | сохранение и загрузка большого save | progress redraw count/interval | первый, финальный и forced update сохраняются; progress монотонен |
| `patch.saveOutputBufferDedup` | повторное save/load одного состояния; altered/absent 1 MiB outer allocation при включённом scheduler barrier | allocation profile output buffers; отдельные `saveMethodPlan`/`saveOutputBufferDedup` statuses | байты save, close/flush chain и exception behavior не меняются; structural skip dedup не откатывает maintenance/flush/registration |
| `patch.fastForwardPresentation` | structural target inventory, fast-forward off/on, master switch off | per-target `APPLIED`/`ALREADY_APPLIED` и structural/loader statuses; owner/mask gauges | full simulation выполняется на каждом substep; master off не меняет ни один target; unrelated class changes применяются, relevant surface damage fail-open |
| `patch.fastForwardFrameMarker` | outer frames с 1/N steps, ранний/поздний flag/step mismatch и exceptional exit | `frames`, `substeps`, `flagMismatches` + hook harness | final-only cadence только при согласованном marker; mismatch прекращает дальнейшее coalescing без state leak в следующий frame; ранние пропуски не replay'ятся |
| `patch.fastForwardActionIndicators` | action indicators при 1× и fast-forward N× | `skippedAction` + target call counter | safe/default: один финальный visual call вместо N; disabled даёт N; ранний/поздний mismatch проверяет documented latch; realtime/simulation amount проверяется отдельно |
| `patch.fastForwardLocationVisuals` | system/hyperspace background, light fader и particle group | `skippedLocation` + visual capture/call counters | safe/default: final state без пропажи объектов; 1× parity, marker fallback и оба visual-time режима |
| `patch.fastForwardFloatingText` | floating text в normal и paused entity paths | `skippedText` + lifetime/position capture | safe/default: один финальный update; текст, появившийся/удалённый на boundary, корректен; disabled/mismatch сохраняет vanilla cadence |
| `patch.fastForwardFleetView` | несколько fleets, zoom/selection и fast-forward transition | `skippedFleet` + `CampaignFleetView.advance` counter | safe/default: финальный view совпадает, промежуточный state не протекает; 1× и fallback полностью vanilla |
| `patch.fastForwardFleetPresentation` | ability layers, view clear, sensor range и pulse fader | `skippedFleet` + отдельные call-site counters | safe/default: каждый exact site вызывается один раз на финальном step; selection/ability state не stale после окончания fast-forward |
| `patch.fastForwardSensorIndicators` | selection и contact indicators, включая lazy obfuscated bridge | `skippedEntityUi`, `skippedSensor` + bridge resolution smoke | safe/default: final indicator state совпадает; bridge/linkage работает в vanilla/FR game loader; после mismatch original calls больше не подавляются |
| `patch.fastForwardCelestialVisuals` | planets и jump-point ring/corona animations | `skippedCelestial` + capture/call counters | safe/default: geometry/final state корректны; nonlinear difference режима `simulation` зафиксирована и не влияет на campaign state |
| `patch.fastForwardAuroraAnimation` | corona/magnetic/anomaly terrain aurora при N substeps | `skippedAurora` + renderer counter/capture | safe/default: один финальный renderer update; terrain mechanics продолжают каждый substep; повреждённая local semantic surface остаётся vanilla |
| `patch.fastForwardContinuousSound` | terrain/ability/slipstream/gate loops, filters и music suppression | `skippedSound` + audio API call counters | safe/default: финальный loop/filter state слышим без dropout; 1×/disabled сохраняют multiplicity; mismatch прекращает дальнейшее подавление и сохраняет exception behavior |
| `patch.fastForwardGateJitter` | active/dormant gates, fader/warp/jitter transitions | `skippedGate`, `skippedJitter` + visual capture | safe/default: gate final state корректен; jitter seed меняется один раз только в подтверждённом N-step frame |
| `patch.fastForwardGlobalAnimations` | broad global animations в длительном fast-forward | `skippedAnimations` + animation callback/lifetime counters | **aggressive:** записать изменённую callback cadence и исключить зависимость mechanics/mod callbacks; проверить jumps в обоих visual-time режимах |
| `patch.fastForwardSensorFaders` | sensor visibility/fade-in/fade-out/despawn boundaries | `skippedSensor` + frame-by-frame capture | **aggressive:** visibility и despawn timing приемлемы; нет исчезновения/зависания; disabled возвращает exact vanilla cadence |
| `patch.fastForwardSlipstreamParticles` | вход/выход из slipstream и длительный fast-forward | `skippedSlipstream` + particle counts/lifetime/RNG capture | **aggressive:** документируются density/lifetime/RNG differences; mechanics/sound независимы; no orphan/stale particles после transition |
| `patch.fastForwardParticleEmitters` | gate, mote, coronal tap и Zig emitters на разных N | `skippedEmitters` + spawn/interval/RNG counters | **aggressive:** emission count/timing и RNG difference визуально приняты; interval не теряет state, disabled полностью vanilla, mismatch прекращает дальнейшее подавление |
| `patch.hyperspaceViewportBounds` | hyperspace на широком/высоком viewport, нескольких zoom и non-square terrain grids | atomic offline site count, partial-state rejection + visual/boundary capture | `xEnd` использует width, `yEnd` height и inner dimension; при несовпадении любого coupled site не меняется ни один |
| `patch.skipNoOpTerrainLayer` | полёт через обычный hyperspace и storms | target status + render/GPU profile | пропускается только `TERRAIN_9`; отсутствие его `preRender`/GL sequence визуально принято |
| `patch.terrainRandomReuse` | длительный terrain render с фиксированным состоянием | JFR allocations `Random`; отсутствие `LongAdder`/clock в tile stack; накопительный `pooledRandomApprox`; exact site counts | seed/draw sequence и итоговая геометрия совпадают; counter монотонен, включает live pending tails и остаётся approximate только из-за concurrent snapshot/weak pool lifecycle |
| `patch.automatonBufferReuse` | несколько rollover, два engine-internal reads, удержание public `getCells()`, unpatched owner и subclass owner | накопительные `automatonAlloc`, `automatonReuse`, `automatonInternalReads` + automaton regression | direct internal read разрешён только exact vanilla owner с подтверждённым reuse patch; public/subclass/unconfirmed fallback вызывает virtual getter; retained alias не меняется; reused buffer zeroed |
| `patch.starfieldCleanupBuffers` | длительный hyperspace flight с массовым parallax expiry | cleanup-list allocations | двухфазный cleanup, survivor set/order и exceptional behavior совпадают |
| `patch.starfieldLinearRemoval` | starfield выше/ниже removal threshold, duplicates/custom equality | removal CPU/counts | stable order и equality-aware fallback совпадают с исходным `removeAll` |


| `patch.marketShareLinearAggregation` | large economy, many owner factions, player-owned markets under foreign factions, equal-but-not-identical faction keys; real AoTD fork class; synthetic future critical override | target method CPU/allocation profile; `getMarkets()`/export-share operation counts; AoTD eligibility declaration audit; disposable child-loader GC; punitive/economy-tick frame tail | result values, identity/equality behavior, insertion order, zero entries, mutability and fresh-map behavior match raw vanilla; exact vanilla and current owned AoTD fork are linear; unknown/overriding subclass uses preserved raw body; ClassValue does not retain mod loader or campaign roots |
| `patch.marketShareDataPutElision` | repeated `getMarketShareData()` for new and existing markets | `LinkedHashMap.put` count and target status | first miss creates/inserts once; later hits return the same value without put; map contents/order unchanged; composition with linear wrapper remains valid |
| `patch.punitivePlayerShareLocalCache` | vanilla/AoTD competitive/free-port checks, repeated loop iterations, absent player owner key, custom API implementation, future AoTD critical override | helper/direct-call counts, one local `IdentityHashMap` allocation, runtime eligibility result, target status, allocated bytes | reasons/weights/order unchanged; identity lookup avoids equal-key aliasing; current owned AoTD fork and vanilla coalesce; custom/overriding implementation keeps original call multiplicity; no fields/static maps/ThreadLocal retention; disabled vanilla flag does not affect Nex target |
| `patch.nexPunitivePlayerShareLocalCache` | supported Nex competitive/free-port checks, changed/partial Nex bytecode, independent switch combinations | helper/direct-call counts, optional-target status, switch-isolation assertions, child-loader retention | same semantic conditions as vanilla; changed Nex shape fails atomically; disabled Nex flag leaves vanilla P1 active; default is enabled; no classloader retention |

Комбинированный scheduler-аудит измеряется в доставленных callback attempts. При совместном прогоне
нужно отдельно подтвердить, что `commodity.temporalAuditFrames`/`market.structureAuditFrames`
увеличиваются только на фактическом `Market.advance()`, а `market.noOpIndustryAuditFrames` — на
попытке inherited industry callback. Для scheduled/hidden cadence `4`/`8` допустимое wall-frame окно
приблизительно умножается на этот cadence; hot/safety/save paths должны сокращать его.

## Presentation/structural composition

Для пяти пересекающихся target-классов проверяются:

- exact application order `presentation → structural`;
- owner/mask и точный hook inventory presentation-stage;
- сохранение presentation postcondition после каждого structural patch и в финальном class bytes;
- idempotent structural reprocessing уже составленного класса;
- отказ structural-stage при ownerless hooks, partial owner/mask и повреждённом wrapper;
- reverse-order offline test: локально доказанная surface применяется, несовместимая получает `SKIPPED_STRUCTURAL`;
- actual-javaagent status `presentationStructuralComposition=PASSED`.

## Stage 8 AoTD production profile

Required gates:

- clean-wrapper structural matcher and postcondition;
- raw fallback and resolver-path runtime test;
- repeat-transform idempotence;
- bridge schema 5 / capability mask `0xff`;
- AoTD deficit semantic fixture;
- Stage 6 pure-price and Stage 7 global-phase regressions;
- ASM verification and runtime payload inventory.


## AoTD runtime epoch и live capability profile

Required gates:

- bridge capability mask `0x1ff`;
- direct `publishRuntimeEpoch(JJ)` transformation;
- loader-neutral market-state reset on epoch publication;
- stale global-boundary callback rejection;
- domain-specific revision masks без ложного structural generation;
- live capability downgrade и однократная generation resynchronization;
- AoTD worker restart and executor recreation;
- stale price/trade ticket rejection with identical market identity;
- pre-epoch serialized task safe drop;
- save-failure barrier release.
