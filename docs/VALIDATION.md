> Project home:
> [StarsectorPrepatcher — overview, installation and downloads](https://github.com/kirpoly/StarsectorPrepatcher)

---

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

## UI economy end-to-end matrix

The scenario matrix covers condition-only, Survey, salvage LOOT, detached Cargo, live-market,
policy mutation, affected-commodity and colonization contracts. The release gate uses real game
JARs for ordering checks and a real javaagent process for final transformer-status checks. The
semantic baseline input under `baseline/aotd/` must be present in a clean unpacked tree and covered
by `SHA256SUMS.txt`.

## Startup non-retransformable target coverage

`StartupAuditCoverageTest` derives the transformer inventory from `PrepatcherAgent.premain`
bytecode instead of maintaining a duplicate list of Cargo or market-open cases. Every exact-target
transformer must expose `TARGET`, `TARGET_CLASSES` and, where applicable,
`OPTIONAL_TARGET_CLASSES`; its complete target universe must equal the inventory consumed by the
startup loaded-class audit. Presentation targets and the open-ended mod call-site observer require
explicit special policies. The test defines one already-loaded synthetic class for every declared
target and verifies rejection in the runtime loader, non-rejection when the feature target is absent,
and non-rejection of an unrelated-loader copy. Adding a transformer without an audit entry or an
explicit special policy must fail this gate.

## Java 17/27 и Faster Rendering

Release gate выполняется на Java 17 и на точной Miko Java `27+22`. Для Java 27 обязательны три
реальных subprocess-сценария: обычный Prepatcher без FR, `fr.agent.jar → Prepatcher` с новым
`fr.jar` и legacy `AppClassLoader + Prepatcher` со старым установленным `fr.jar`. Проверяются
выбранный профиль, порядок единого pipeline, все presentation/structural targets, отсутствие
нелегальных method names, загрузка `CampaignState` и полная verification-загрузка Local Resources.

No-FR gate использует эффективные JVM arguments без `fr.agent.jar`, legacy system loader и
`fr.jar` в classpath. Он обязан выбрать `JAVA27_STANDARD`, загрузить все 24 presentation targets и
оба Local Resources patches с `-Xverify:all`. Отдельный classification test проверяет новый FR по
имени и по `Premain-Class`, legacy loader, конфликт обоих маршрутов, agent после Prepatcher,
осиротевший `fr.jar` в classpath и duplicate Prepatcher.

Для `JAVA27_STANDARD` и legacy FR отдельно проверяется общий length-preserving constant-pool
repair: реальный `CampaignState`, согласованная synthetic declaration/reference, неизменность всех
байтов вне точного UTF8-имени, identity-idempotence и отказ на повреждённом class file.

UI market alias gate дополнительно использует настоящие `campaign.ui.class` и `marketinfo.s` в
четырёх формах pipeline: raw Java 17, repair внутри `JAVA27_STANDARD`, уже исправленные bytes
`FR_AGENT_CHAIN` и pre-define вызов `FR_PREDEFINE_BRIDGE`. Для обоих полей проверяются raw/repaired
alias, точный descriptor, неоднозначность, неизвестное имя, idempotence и `BasicVerifier`. Trade
обязательно проходит в порядке `Trade mutation → AoTD detached-cargo context`; повреждение поздней
Cargo surface откатывает оба маркера к совместимому входу pipeline.

Agent probe имеет отрицательные fixtures: FR заявлен, но ничего не меняет; зарегистрирован после
Prepatcher; исправляет только declaration без call; либо повреждает тело класса. Каждый случай
обязан завершить JVM до игровых targets со статусом `fatal-incompatible-java-route`. Legacy route
проверяется как для ещё не загруженного `ClassTransformer`, так и через retransformation; ошибка
handshake обязана удалить property/временный transformer и восстановить исходный класс.

Для `localResourcesTooltipSnapshot` gate требует ровно один `F_SAME` сразу после fallback label,
неизменённые locals/stack, совместные postconditions обоих Local Resources patches, idempotent
reprocessing, atomic rollback при несовпадении и успешную JVM load с `-Xverify:all` на Java 27.
Package gate дважды собирает JAR каждым build script и сравнивает hashes; внутри agent должны быть
private ASM, `META-INF/LICENSES/ASM.txt`, и не должно быть `jdk/internal/org/objectweb/asm`,
`org/objectweb/asm`, `module-info.class`, чужих manifests или signatures.

Miko install/reinstall/uninstall тестируется для обоих layouts FR. Проверяются backups, rollback
при ошибке, завершающие переводы строк, отсутствие duplicate agents и `spp.dump.campaignState`,
порядок остальные agents → FR → Prepatcher → classpath и сохранение реального `%ERRORLEVEL%` в
`Miko_Rouge.bat`. Финальный игровой запуск не завершается автоматизацией: она только читает log,
а процесс завершает пользователь либо он заканчивается сам.

## Каталог повторяющихся регрессий

| Регрессия | Типичная причина | Обязательное доказательство |
|---|---|---|
| Lifecycle root | `static`, singleton, executor task или `ThreadLocal` хранит entity/plugin/location/engine | lifecycle reset очищает все значения; старое поколение собирается через `WeakReference` + `ReferenceQueue` |
| Weak-key/value cycle | `WeakHashMap<K,V>`, но `V → ... → K` или значение держит тот же campaign graph | тест хранит только map, отпускает key и требует его GC без дополнительного cache access |
| Reentrant reset | reset удаляет `ThreadLocal`, пока старый patched frame ещё исполняется и может создать новый frame | reset вызывается внутри вложенного scope; normal и exceptional unwind оставляют scratch пустым и detached |
| UI owner idle/LRU maintenance | synthetic monotonic clock, `< TTL`, `= TTL`, `> TTL`, owner touch перед limit insertion | удаляются только idle/LRU owners; identity iterator order не влияет; текущая локальная value остаётся usable; generation reset изолирует старые maps |
| Hover physical pruning | fresh/expired `CellHit`, sweep до и после prune interval | до interval scan отсутствует; после interval удаляются только expired cells; `lastResult` не очищается |
| Scratch/starfield high-water trimming | retained list/set растёт выше threshold и очищается caller-ом до scope exit; 64-level reentrancy; nested/exceptional scopes; synthetic grace/clock counter; campaign reset с free oversized list и in-flight old-generation lease | mutation-site hooks сохраняют peak при последующем `clear()`; concrete `ArrayList`/`HashSet` types не меняются; до grace capacity сохраняется; active frame не заменяется; после grace oversized storage заменяется, trailing inactive frames сокращаются до 4; starfield делает 0 clock reads на `add` и 1 на release; pool limit сохраняется; reset обнуляет gauges/очищает old pool, stale lease не возвращается в новое поколение; synthetic engine удерживается сильной test fixture до `reachabilityFence` |
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
причины/остаточного риска в отчёте соответствующего выпуска из [`releases/`](releases/0.18.4.md).

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

### Incremental core-worlds extent index

Для `patch.coreWorldsExtentCache` обязательны:

1. structural transform фактических `CoreScript`, `CampaignEngine` и `BaseLocation` без hash
   allowlist; все три класса публикуют один patch ID, но коммитятся независимо и проверяются
   runtime capability gate;
2. idempotent second pass с owner marker и полной postcondition на каждом классе;
3. в `CoreScript`: unique `Global.getSector()` → `ASTORE` data-local proof без фиксированного slot,
   единственный terminal `Misc.computeCoreWorldsExtent()` после `RouteManager.advance(F)V`;
4. в `CampaignEngine`: source-proven `StarSystem` local, добавленный в внутренний `List`, и exact
   argument, удаляемый из внутреннего `List`; hooks стоят на единственных normal return boundaries;
5. в `BaseLocation`: exact `HashSet.add/remove/clear` над tag field и hooks на всех normal returns;
   duplicate, partial, foreign или изменённая mutation shape обязана дать fail-open;
6. runtime regression: ровно один full snapshot в steady state, O(C+B) operation bound,
   create/remove/tag events, direct mutable coordinate, direct live-tag add/remove, memory identity/
   mutation/expiry repair, fast-forward, one-shot anomaly rebuild и совпадение vanilla bounds;
7. fail-closed capability test при отсутствии одного mutation-hook status, actual-agent загрузка всех
   трёх targets и payload/FR loader inventory с `StarsectorPrepatcherCoreWorldsRuntime`;
8. weak-reachability proof для sector, memory, system list, core system и опубликованных vectors;
   lifecycle reset заменяет обе wrapper-list containers, а runtime/game classes не получают
   persistent instance fields или save schema.

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
| `patch.entityLookupIndexRepair` | load where a mod calls `getEntityById()` before XStream attaches star systems; repeated campaign advances; second loaded engine | patch status; exact ASM control-flow and field postconditions; idempotency and partial-state rejection | the first post-load listener restoration is followed by exactly one vanilla map rebuild; later ticks do not rebuild; the transient bit re-arms on the next load; listener-throttle enabled/disabled compositions both verify |
| `patch.campaignSnapshotReuse` | campaign advance в hyperspace/системах, paused/reentrant/throwing callbacks | JFR allocation stacks; counts пяти snapshot hooks | тот же point-in-time набор и порядок; no escape; все exits обнуляют campaign refs |
| `patch.entityScriptSnapshotReuse` | scripts добавляют/удаляют scripts и бросают exception | JFR allocation stacks; отсутствие scratch hooks на empty path | guard читает ровно `this.scripts` и входит перед исходным snapshot без обхода стороннего пролога; non-empty snapshot/order/call count остаются vanilla |
| `patch.coreWorldsExtentCache` | 2500+ systems, few core systems; create/remove, API/direct tag edits, mutable coordinates, 1×/fast-forward, memory mutation/expiry, campaign reload | `coreWorldsSystemScans/SystemsVisited/CoreSystemChecks/MembershipAuditChecks/Sweeps/MembershipChanges/SystemAddEvents/SystemRemoveEvents/TagEvents/RebuildRequests/CapabilityFallbacks/FastForwardSkips/UnchangedSkips/Publishes/IntegrityRepairs/EventFailures/Fallbacks`; JFR path + weak-reachability | после первого/recovery snapshot нет повторного `getStarSystems()` в steady state; работа ≤ O(C+B), bounds/order совпадают с vanilla; API events immediate, direct addition bounded by audit, direct removal/location immediate on validation; incomplete hooks fail closed; static state не удерживает campaign objects |
| `patch.emptyMemoryAdvanceFastPath` | тысячи пустых `Memory` плюс expire/require entries | CPU/allocation stacks; отсутствие двух retired iterator hooks | guard после restoration/pause читает `this.expire` + `this.require`; conversion, expiration/require cleanup/order на непустом пути совпадают с vanilla |
| `patch.routeJumpPointIndex` | маршруты внутри/между системами, wormholes и одинаковые anchors | route index hits/builds/fallbacks | destination, distance и tie-break совпадают; malformed/custom getter использует полный список |
| `patch.strategicJumpDestinationFirst` | system→hyperspace, hyperspace→system, system A→B, null target; 0/1/multiple accepted destinations | hostile-market call location/count and campaign frame tails | выбранный candidate и early-return совпадают; 0 scans для rejected jump points и ровно 1 для accepted jump point |
| `patch.strategicJumpDestinationIndex` | cold lookup в arbitrary small/large location; exact target+fallback union; negative miss; point add/clear/remove, retarget, source add/remove, direct mutable edit, malformed getter, generation reset, capacity pressure и missing capability surface | `strategicJumpIndex*` admissions/deferred/deferred-plan/build/work/audit/retry-heap/queue/phase/oldest-age/full-maintenance/overrun/failure/eviction counters; ASM source+expiry+four topology surfaces; JFR post-load frame tails | lookup не читает destinations и не строит index; один maintenance ≤ `indexMaxWorkUnits`, полный lock+selection+queue+LRU+cleanup time вычитается из wall-clock bucket, overrun ограничен одной неделимой unit; retry/audit selection не сканирует LRU; `BUILDING`/`REFRESHING` не вытесняются capacity pressure; нет location type/size admission branch; non-READY не возвращает full list и сохраняет expired plan; READY subset сохраняет outer identity order/dedup; miss пустой; point delta не запускает full build; failures имеют один heap entry и cooldown; destination-first postcondition остаётся валидной |
| `patch.economyLocationCache` | ускорение времени в секторе с большим числом markets | `economyLocationChecks/Dirty/Skips` + JFR | explicit dirty rebuild same-frame; add/remove/reorder/move/id сразу меняет fingerprint; reset очищает state |
| `patch.marketScheduler` | ×1/×4/×10/×100 constant-step batches; mixed raw-float RLE runs; run-limit overflow; nested market context; all 12 capability bits and missing-registration negatives; MilitaryBase/LionsGuardHQ/RecentUnrest wrappers; JAR-wide construction-mutator inventory; exact real-fork `AoTDConstructionSite.setAssignedWonder` wrapper plus future changed fixture; dirty/safety/forced construction scans; queue/building/upgrading/multiple/probe-failure reasons; bounded per-reason diagnostic CSV; coalesced/exact save and callback-failure policy; two periodic + six core event sources; observer-only class containing direct Market.advance | tick/batch/source counters; pending steps/runs gauges and high-water; capability mask; coalesced/context/replay counters; construction reason gauges, scan/transition/cost counters, `logs/market-construction-diagnostics/session-*/samples.csv`; CSV risk report; ASM/actual-agent | scheduler remains synchronous if any semantic wrapper/barrier is absent; exact step order is available inside local wrappers; no averaging at run limit; steady state does not scan all industries per input; reasons distinguish queue/building/upgrading/uncertain states; diagnostic cap applies independently per category and retains no game objects; time does not cross mutation boundary; begun failing save callbacks are not replayed; observer-only mode returns original bytes; global intercomponent/RNG equivalence remains explicitly unproven |
| `patch.aotdEconomyRestoreCoordination` | real RC8 `CoreLifecyclePluginImpl.econPostSaveRestore()V`; altered call count/order/tail/exception-region fixtures; repeated transform; disabled switch; missing, throwing and linkage-failing V10 listener | patch status; unique hook immediately before sole `RETURN`; restore signal/completion/failure counters; negotiated bit 11; ASM/actual-agent | hook occurs after all restore/reapply calls, performs no market scan, and never lets callback failure escape; structural or linkage failure removes only restore coordination |
| `patch.campaignCargoNoGlobalEconomyStep` | detached Cargo in hyperspace/system under exact vanilla, economy subclasses and exact spp13 AoTD; real station/planet Cargo; unrelated `tripleStep`; changed cargo/Economy bytecode; dispatcher accept/reject/throw and missing capability | cargo transformer status; final `Economy.tripleStep/getEconomy` contract; vanilla skip, AoTD explicit-dispatch and original-call fallback counters; JFR absence of global work only on accepted paths | only the proven `fake_market` CARGO branch is eligible; exact vanilla skips all three steps and exact spp13 dispatches a synthetic intent; no wrapper context exists; rejection/error/subclass/replacement retains the original virtual global call |
| `patch.lootTransferNoGlobalEconomyStep` | vanilla/AoTD ruins and generic salvage `showLoot`; exact `LOOT` mode with generated `CargoData`; altered mode/cargo-type fixtures; dispatcher accept/reject/throw; unknown synthetic modes and replacement economies | `lootTransferVanillaStepsSkipped`, AoTD explicit-dispatch and global-fallback counters; original virtual call inventory; JFR absence of all-market work only after accepted dispatch | only the proven `fake_market + LOOT + CargoData` branch is eligible and loot is generated first; exact vanilla skips, exact spp13 dispatches, and every rejected/unknown path executes the original global call without a ThreadLocal handoff |
| `patch.planetConditionMarketOpenNoGlobalEconomyStep` | open/reopen an uninhabited condition-only planet in exact vanilla and spp13 AoTD; live colony/station; accidental economy member; economy subclasses; dispatcher failure; survey and real colonization | market-open transformer status; vanilla/AoTD accepted/fallback counters; original `nextStep` inventory; JFR absence of global work only on accepted paths | only the initial step for a non-economy `planetConditionMarketOnly` market is skipped; ability/listener callbacks and `currentlyOpenMarket` publication remain; live/unknown/rejected paths fall back; `addMarket + tripleStep` during colonization is untouched |
| `patch.vanillaMarketOpenLocalization` | open/reopen a live colony/station under exact vanilla and spp13 AoTD; immediately enter Cargo; dirty local scheduler debt; intervening mutation; class/loader drift; dispatcher false/error | vanilla Economy/Reach contract properties; call-site guard and original-call inventory; vanilla token plus AoTD dispatcher/localized/fallback counters; ASM/actual-agent smoke; JFR | exact vanilla uses the proven local path and coalesces only an unchanged immediate Cargo call; exact spp13 receives classified market-open/Cargo intents; every barrier/capability/identity/dispatch failure executes the original virtual global step |
| `patch.uiMarketMutationRefresh` | exact policy setter/helper, trade, free-port and five industry branches; partial dialog/panel application; changed/missing/duplicate fixtures; commodity-constructor drift; same/different market/thread/epochs; spp13 dispatch accept/reject/throw; `GLOBAL_TOPOLOGY` and missing barrier/capability; throwing transaction `getMarket/getBought/getSold`, Cargo `getStacksCopy`, stack getters, pre/post snapshots and pre/post-commit diagnostic faults | per-surface inventory; trade guard plus preserved original `doubleStep`; Throwable-region coverage of all proof reads; internal one-shot reason/scope/affected-ID context and sticky batch poison; sorted/deduplicated IDs; original mutation/step call counts and exception identity; industry atomic readiness; vanilla filtered rebuild counters; spp13 dispatcher/global-fallback paths; committed-boolean probes; real-fork/future-override gates | only proven mutations localize and only changed IDs rebuild; the guard never owns fallback; every pre-commit read/diagnostic/dispatcher failure returns `false` and executes the original virtual global call exactly once, while fork or Prepatcher diagnostic failure after semantic commit is contained and leaves the boolean `true` with zero original calls; original setters/mutators execute once outside instrumentation catches; a failed pre/post read cannot be erased by a later successful mutation in the batch; exact spp13 consumes/clears context once and subclass fallback cannot retain it; global topology, failed preparation, partial group, admin/queue/custom callers, old/future fork and structural drift preserve the original path |
| `patch.commandTabNoGlobalEconomyStep` | load exact Command/Colonies constructor; open the tab repeatedly; mutate terminal call/descriptor/return anchor fixtures; process already-patched bytes | per-class status property, raw/patched `tripleStep` count, ownership marker, ASM verifier and actual-javaagent class-load smoke | exact constructor loses only its unique terminal global chain; all other instructions remain ordered; disabled/mismatched/already-owned cases are isolated and deterministic |
| `patch.commodityDetailNoGlobalEconomyStep` | open current commodity detail for legal/illegal commodities; structurally test V2 and legacy independently; mutate first post-step local/load anchor; repeated transform | separate V2/legacy status properties, raw/patched call inventory, marker/idempotency and ASM verifier | both exact constructors retain market/faction initialization and lose only the global chain; a changed/dormant legacy class cannot suppress the current V2 patch, and vice versa |
| `patch.marketDefensesNoGlobalEconomyStep` | vanilla market plus real Nexerelin 0.12.1d `ExerelinCore.jar`; market with/without station, interaction fleet and disrupted defenses; child mod loader plus bootstrap/unrelated-loader rejection; mutate market-null branch/join, captured-state ordering and future Nex superclass; repeated transform | separate vanilla/Nex status properties, call-order indices for interaction/station/state versus `tripleStep`, raw/patched call inventory, marker/idempotency, ASM verifier and actual-javaagent child-loader load | each owner-local global call is removed only when all defense inputs are proven captured before it; local option/fleet/station and Nex invasion/responder logic remains byte-for-byte; absent Nex, changed shape or loader mismatch retains the original call without modifying `ExerelinCore.jar` |
| `patch.directMarketObservation` | mod classes в system/child loaders: real temporary `mod_info.json`, observation enabled and scheduler-sync-only, executed and never-executed call sites, ARG/CONST/derived amount, exception, reflection/MethodHandle, known planet engine path, two report intervals и validation smoke | `call-sites.csv`, `observations.csv`, `stacks.csv`, `unknown-stacks.csv`, `summary.csv`, `session.json`, `prepatcher.log`; explicit `mod_id`, `mod_name`, `mod_directory`, `jar_name`, `source`; JFR overhead A/B | wrapper installs whenever observation or scheduler requires it; call multiplicity/thread/exception сохраняются; without debt amount is exact, with scheduler debt callback gets `pending + direct`; manifest exists before first execution when observer is active; mod identity comes from owning `mod_info.json` rather than manual source-path parsing; metadata v1 remains readable; unknown budget renews per interval; observer failure does not suppress original call |
| internal `economyAdvancePlan` | все 7 непустых комбинаций persistent/location/scheduler; повреждённый location region; unowned split state; damaged scheduler hook; повторная обработка и mask mismatch | patch status `economyAdvancePlan`, ownership marker, private feature-mask field, exact persistent/location/scheduler hook counts, scheduler registration, ASM verifier | один commit и один marker; location hook согласован с наличием persistent state; disabled components остаются vanilla; отказ одного компонента не оставляет другие; legacy split markers не принимаются; полный candidate idempotent только при той же mask |
| internal `marketAdvancePlan` | все 15 непустых комбинаций snapshots/commodity/entry/scheduler-semantics; повреждённый commodity region; unowned split state; missing semantic registration; damaged hook; повторная обработка и mask mismatch | patch status `marketAdvancePlan`, ownership marker, private feature-mask field, exact component hook counts, ASM verifier | один commit и один marker; disabled components остаются vanilla; отказ одного компонента не оставляет другие; legacy split markers не принимаются; полный candidate idempotent только при той же mask |
| internal `saveMethodPlan` + ordered `saveOutputBufferDedup` | пять config-сценариев: buffer-only, maintenance-only, maintenance+buffer, scheduler+maintenance, all; barrier masks `1`/`3`; повреждённая 1 MiB allocation; malformed maintenance prefix; unowned barrier/dedup states; damaged flush hook; повторная обработка и barrier-mask mismatch | отдельные patch statuses/ownership markers; `smo$saveMethodPlanMask`; exact maintenance/flush/registration/buffer counts; ASM verifier и earlier-postcondition revalidation | maintenance+flush коммитятся одним correctness-critical barrier; scheduler barrier остаётся первой save-инструкцией; dedup применяется после barrier; несовместимый buffer pattern пропускает только `saveOutputBufferDedup` и не удаляет scheduler capability; malformed barrier не регистрирует capability, но не блокирует независимый dedup; legacy scheduler markers не принимаются |
| `patch.economyPersistentSnapshots` | 1000+ markets, неизменные структуры, nested callbacks, backing-list replacement и прямые live-list edits | aggregate `economyPersistent*`; split `economyMarketPersistent*`, `marketConditionPersistent*`, `marketIndustryPersistent*`; `economyLocationEpochHits/Audits/SequenceMismatches` + JFR | API mutator и source replacement инвалидируют немедленно; unchanged owner-local audit не перепубликует fingerprint и не использует global map; direct edit виден не позже audit; RandomAccess/iterator identity order совпадает; старый snapshot не мутируется; load/clone/reset не сохраняют state; removed scratch hooks отсутствуют, paused-condition copy остаётся vanilla |
| `patch.localResourcesNoColdMarketData` | warm/cold vanilla; exact AoTD committed raw units around conversion threshold; illegal demand/supply matrix; wrong AoTD market-data type; foreign/future subclasses; repeated tooltip/paused economy | `localResourcesColdAvoided/AoTDColdAvoided/WarmReads/PeekFallbacks`; constructor/materialization probes; exact real-fork and future-contract fixtures; ASM/actual-class-load verifier | warm result equals raw; cold call performs no `CommodityMarketData`/AoTD supply-demand materialization; AoTD uses its exact conversion script, including positive raw -> zero converted edge; unavailable/changed contract calls preserved raw path; the getter pair applies atomically; no optional loader root |
| `patch.localResourcesTooltipSnapshot` | vanilla, exact Nex, AoTD and AoTD×Nex tooltips; equal limits; meta/non-econ/illegal/zero rows; 50/100/300 commodities; unknown subclass | per-commodity virtual-call counter, comparator getter counter, golden row order/text, allocation profile and weak-reference release | one limit calculation per commodity, zero economic getter calls from comparator, stable raw-limit order and identical display multiplier/filtering; unknown subclass raw fallback; no retained market/commodity roots |
| `patch.economyGroupIndex` | exact vanilla and `AoTDReachEconomy`; order/mutable-copy/unknown key; add/remove/set-group; source replacement/size change; direct group/list mutation and timed audit; campaign reset/owner GC; current exact-JAR one-super-call `addMarket` audit | `economyGroupIndexBuilds/Hits/Fallbacks/Audits/AuditMismatches`; real-fork bytecode contract; actual-agent inherited-wrapper/remove-release smoke; weak-reference and bounded-cardinality tests | lookup returns fresh ordered list; steady request O(group size), rebuild O(markets); unknown keys do not grow state; incomplete two-class capability or future critical AoTD read override fails raw; changed/bypassed mutator is detected by source validation or bounded audit rather than assumed to invalidate immediately; remove/rebuild clears arrays; transient owner-local cycle and `ClassValue` do not retain campaign or mod loader |
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
| `patch.fastForwardActionIndicators` | action indicators при 1× и fast-forward N× | `skippedAction` + target call counter | safe/default/aggressive/debug: один финальный visual call вместо N; disabled даёт N; ранний/поздний mismatch проверяет documented latch; realtime/simulation amount проверяется отдельно |
| `patch.fastForwardLocationVisuals` | system/hyperspace background, light fader и particle group | `skippedLocation` + visual capture/call counters | safe/default/aggressive/debug: final state без пропажи объектов; 1× parity, marker fallback и оба visual-time режима |
| `patch.fastForwardFloatingText` | floating text в normal и paused entity paths | `skippedText` + lifetime/position capture | safe/default/aggressive/debug: один финальный update; текст, появившийся/удалённый на boundary, корректен; disabled/mismatch сохраняет vanilla cadence |
| `patch.fastForwardFleetView` | несколько fleets, zoom/selection и fast-forward transition | `skippedFleet` + `CampaignFleetView.advance` counter | safe/default/aggressive/debug: финальный view совпадает, промежуточный state не протекает; 1× и fallback полностью vanilla |
| `patch.fastForwardFleetPresentation` | ability layers, view clear, sensor range и pulse fader | `skippedFleet` + отдельные call-site counters | safe/default/aggressive/debug: каждый exact site вызывается один раз на финальном step; selection/ability state не stale после окончания fast-forward |
| `patch.fastForwardSensorIndicators` | selection и contact indicators, включая lazy obfuscated bridge | `skippedEntityUi`, `skippedSensor` + bridge resolution smoke | safe/default/aggressive/debug: final indicator state совпадает; bridge/linkage работает в vanilla/FR game loader; после mismatch original calls больше не подавляются |
| `patch.fastForwardCelestialVisuals` | planets и jump-point ring/corona animations | `skippedCelestial` + capture/call counters | safe/default/aggressive/debug: geometry/final state корректны; nonlinear difference режима `simulation` зафиксирована и не влияет на campaign state |
| `patch.fastForwardAuroraAnimation` | corona/magnetic/anomaly terrain aurora при N substeps | `skippedAurora` + renderer counter/capture | safe/default/aggressive/debug: один финальный renderer update; terrain mechanics продолжают каждый substep; повреждённая local semantic surface остаётся vanilla |
| `patch.fastForwardContinuousSound` | terrain/ability/slipstream/gate loops, filters и music suppression | `skippedSound` + audio API call counters | safe/default/aggressive/debug: финальный loop/filter state слышим без dropout; 1×/disabled сохраняют multiplicity; mismatch прекращает дальнейшее подавление и сохраняет exception behavior |
| `patch.fastForwardGateJitter` | active/dormant gates, fader/warp/jitter transitions | `skippedGate`, `skippedJitter` + visual capture | safe/default/aggressive/debug: gate final state корректен; jitter seed меняется один раз только в подтверждённом N-step frame |
| `patch.fastForwardGlobalAnimations` | broad global animations в длительном fast-forward | `skippedAnimations` + animation callback/lifetime counters | **default/aggressive/debug; false в safe:** записать изменённую callback cadence и исключить зависимость mechanics/mod callbacks; проверить jumps в обоих visual-time режимах |
| `patch.fastForwardSensorFaders` | sensor visibility/fade-in/fade-out/despawn boundaries | `skippedSensor` + frame-by-frame capture | **default/aggressive/debug; false в safe:** visibility и despawn timing приемлемы; нет исчезновения/зависания; disabled возвращает exact vanilla cadence |
| `patch.fastForwardSlipstreamParticles` | вход/выход из slipstream и длительный fast-forward | `skippedSlipstream` + particle counts/lifetime/RNG capture | **default/aggressive/debug; false в safe:** документируются density/lifetime/RNG differences; mechanics/sound независимы; no orphan/stale particles после transition |
| `patch.fastForwardParticleEmitters` | gate, mote, coronal tap и Zig emitters на разных N | `skippedEmitters` + spawn/interval/RNG counters | **default/aggressive/debug; false в safe:** emission count/timing и RNG difference визуально приняты; interval не теряет state, disabled полностью vanilla, mismatch прекращает дальнейшее подавление |
| `patch.hyperspaceViewportBounds` | hyperspace на широком/высоком viewport, нескольких zoom и non-square terrain grids | atomic offline site count, partial-state rejection + visual/boundary capture | `xEnd` использует width, `yEnd` height и inner dimension; при несовпадении любого coupled site не меняется ни один |
| `patch.skipNoOpTerrainLayer` | полёт через обычный hyperspace и storms | target status + render/GPU profile | пропускается только `TERRAIN_9`; отсутствие его `preRender`/GL sequence визуально принято |
| `patch.terrainRandomReuse` | длительный terrain render с фиксированным состоянием | JFR allocations `Random`; отсутствие `LongAdder`/clock в tile stack; накопительный `pooledRandomApprox`; exact site counts | seed/draw sequence и итоговая геометрия совпадают; counter монотонен, включает live pending tails и остаётся approximate только из-за concurrent snapshot/weak pool lifecycle |
| `patch.automatonBufferReuse` | несколько rollover, два engine-internal reads, удержание public `getCells()`, unpatched owner и subclass owner | накопительные `automatonAlloc`, `automatonReuse`, `automatonInternalReads` + automaton regression | direct internal read разрешён только exact vanilla owner с подтверждённым reuse patch; public/subclass/unconfirmed fallback вызывает virtual getter; retained alias не меняется; reused buffer zeroed |
| `patch.starfieldCleanupBuffers` | длительный hyperspace flight с массовым parallax expiry | cleanup-list allocations | двухфазный cleanup, survivor set/order и exceptional behavior совпадают |
| `patch.starfieldLinearRemoval` | starfield выше/ниже removal threshold, duplicates/custom equality | removal CPU/counts | stable order и equality-aware fallback совпадают с исходным `removeAll` |


| `patch.marketShareLinearAggregation` | large economy, many owner factions, player-owned markets under foreign factions, equal-but-not-identical faction keys; real AoTD fork class; synthetic future critical override | target method CPU/allocation profile; `getMarkets()`/export-share operation counts; AoTD eligibility declaration audit; disposable child-loader GC; punitive/economy-tick frame tail | result values, identity/equality behavior, insertion order, zero entries, mutability and fresh-map behavior match raw vanilla; exact vanilla and current owned AoTD fork are linear; unknown/overriding subclass uses preserved raw body; ClassValue does not retain mod loader or campaign roots |
| `patch.marketShareDataPutElision` | repeated `getMarketShareData()` for new and existing markets | `LinkedHashMap.put` count and target status | first miss creates/inserts once; later hits return the same value without put; map contents/order unchanged; composition with linear wrapper remains valid |
| `patch.punitivePlayerShareLocalCache` | vanilla/AoTD competitive/free-port checks, repeated loop iterations, absent player owner key, custom API implementation, future AoTD critical override | helper/direct-call counts, one local `IdentityHashMap` allocation, runtime eligibility result, target status, allocated bytes | reasons/weights/order unchanged; identity lookup avoids equal-key aliasing; current owned AoTD fork and vanilla coalesce; custom/overriding implementation keeps original call multiplicity; no fields/static maps/ThreadLocal retention; disabled vanilla flag does not affect Nex target |
| `patch.nexPunitivePlayerShareLocalCache` | supported Nex competitive/free-port checks, changed/partial Nex bytecode, independent switch combinations | helper/direct-call counts, optional-target status, switch-isolation assertions, child-loader retention | same semantic conditions as vanilla; changed Nex shape fails atomically; disabled Nex flag leaves the vanilla player-share cache active; default is enabled; no classloader retention |

Комбинированный scheduler-аудит измеряется в доставленных callback attempts. При совместном прогоне
нужно отдельно подтвердить, что `commodity.temporalAuditFrames`/`market.structureAuditFrames`
увеличиваются только на фактическом `Market.advance()`, а `market.noOpIndustryAuditFrames` — на
попытке inherited industry callback. Для scheduled/hidden cadence `4`/`8` допустимое wall-frame окно
приблизительно умножается на этот cadence; hot/safety/save paths должны сокращать его.

## Presentation/structural composition

Для пяти пересекающихся target-классов проверяются:

- exact application order `presentation → structural`;
- owner/mask и точный hook inventory presentation pass;
- сохранение presentation postcondition после каждого structural patch и в финальном class bytes;
- idempotent structural reprocessing уже составленного класса;
- отказ structural pass при ownerless hooks, partial owner/mask и повреждённом wrapper;
- reverse-order offline test: локально доказанная surface применяется, несовместимая получает `SKIPPED_STRUCTURAL`;
- actual-javaagent status `presentationStructuralComposition=PASSED`.

## AoTD production profile

Required gates:

- clean-wrapper structural matcher and postcondition;
- raw fallback and resolver-path runtime test;
- repeat-transform idempotence;
- bridge schema V10 / required capability mask `0xbff` and optional full mask `0xfff`;
- exact-current registration matrix: safe profile still returns exactly `0xbff`, enabled optional
  mutation refresh returns `0xfff`, while spp4-spp9, future identifiers, wrong declared mask and
  missing callbacks return `0` with a reason-specific rejection status;
- bridge-transform negatives for V9, future V11 and malformed current V10, proving that no legacy
  schema or partial current shape is rewritten;
- exact detached-Cargo/LOOT call-site guards, final vanilla `Economy.tripleStep/getEconomy` contract, exact-vanilla skip, spp13 explicit dispatch, subclass/rejection/error fallback, idempotence and changed-body fail-closed fixtures;
- exact RC8 `econPostSaveRestore()V` order/tail proof, idempotency, changed-shape negatives and
  fail-open listener/linkage behavior with no core market scan;
- real-fork structural proof that supply/demand preparation retains one ordered industry snapshot
  and does not re-resolve every entry through `Market.getIndustry(id)`;
- actual-fork XStream 1.4.10 migration of a fixed spp9 XML fixture and spp13 round-trip proving that
  derived per-industry cache contents/references are omitted while aggregates, gameplay modifiers
  and persistent reference aliases survive;
- manual real-campaign save/load smoke with population industry first and a later unavailable
  industry; it must confirm structural-only restore, one coalesced scheduler revision, `NOT_READY`
  generation invariants, visible calculation-script failures, and successful/failed-save recovery;
- real-fork proof that `AoTDEconomy.nextStep/doubleStep/tripleStep` and `AoTDReachEconomy.nextStep` are always global, `doubleStep`/`tripleStep` retain two/three-step multiplicity, and the single dispatcher is public/final and accepts only classified intents;
- future fork/version/override negative fixtures proving that an unreviewed implementation cannot receive local dispatch semantics;
- Local Resources one-call snapshot, read-only AoTD peek and unknown-subclass raw fallback;
- AoTD deficit semantic fixture;
- pure-price и global-phase regressions;
- ASM verification and runtime payload inventory.


## AoTD runtime epoch и live capability profile

Required gates:

- bridge capability masks `0xbff` required (including explicit UI dispatch and restore
  coordination) and `0xfff` with the optional market-mutation refresh;
- no static strong `Class`, `Method`, `ClassLoader`, campaign-object or mod-instance dispatch cache;
- direct `publishRuntimeEpoch(JJ)` transformation;
- loader-neutral market-state reset on epoch publication;
- stale global-boundary callback rejection;
- domain-specific revision masks без ложного structural generation;
- live capability downgrade и однократная generation resynchronization;
- AoTD worker restart and executor recreation;
- stale price/trade ticket rejection with identical market identity;
- pre-epoch serialized task safe drop;
- save-failure barrier release.
