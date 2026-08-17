# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Текущая версия: **0.18.3**. Поддерживаемая версия игры: **Starsector 0.98a-RC8**.

[![Без препатчера и с ним](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Нажмите на превью, чтобы открыть полное сравнение в WebM при 60 FPS.

StarsectorPrepatcher — compatibility-first слой ранних патчей Starsector. Startup-javaagent
запускается до обычной загрузки игровых и модовых classloader'ов, поэтому защищённые структурные
патчи применяются в момент первого появления целевых классов в JVM.

Задача проекта шире одной только оптимизации карты:

- поддерживать тщательно проверенные исправления производительности и корректности внутренних
  классов игры;
- предоставить стабильный документированный API к полезным возможностям, которых нет в публичном
  API Starsector;
- хранить зависимое от версии игры знание о bytecode внутри prepatcher, а не размножать его по
  игровым модам.

Публичный API в `0.18.3` ещё не выпущен и остаётся пунктом roadmap. Планируемый namespace —
`com.starsector.prepatcher.api`; типы станут поддерживаемым контрактом только после появления
документации и compatibility-тестов.

## Как это работает

Поставка содержит sandbox-safe bootstrap и один startup-javaagent:

```text
agent/StarsectorPrepatcherAgent.jar
```

Единый agent независимо сопоставляет и проверяет каждый патч, включая hyperspace и fast-forward
presentation. Все блоки используют локальные structural-контракты и принимают совместимые
оригинальные, переводные или перепакованные game files, пока принадлежащая patch semantic surface
не изменилась. Неизвестный, неоднозначный, частичный или foreign hook-shaped target остаётся
vanilla, а причина записывается в лог.

Control-код agent и typed runtime намеренно разделены. При запуске agent читает runtime-classfile'ы
`com.fs.starfarer.api.StarsectorPrepatcher*` из собственного JAR и определяет их в classloader'е,
которому принадлежит API Starsector. Поэтому типы аргументов hooks остаются loader-identical как в
vanilla launcher, так и с custom system classloader Faster Rendering. Transformer регистрируется
только после успешной установки runtime и пропускает target, загруженный другим loader'ом.

Fast-forward presentation coalescing регистрируется внутри того же startup-agent, а его hooks входят
в общий game-loader runtime payload. Второй javaagent не устанавливается и не нужен; исходный
standalone-agent FastForward Presentation Patch не следует устанавливать одновременно с Prepatcher.

Bootstrap plugin не меняет bytecode. Он выводит состояние агентов в обычный лог игры и предупреждает,
если мод включён без startup-agent.

## Установка

1. Полностью закройте Starsector.
2. Распакуйте каталог как `<Starsector>\mods\StarsectorPrepatcher`.
3. Установите agent для используемого способа запуска (команды ниже).
4. Включите **StarsectorPrepatcher** в launcher и запустите игру.

Если установлен **AoTD — Theory of Toolbox**, используйте поддерживаемый
[Scheduler Fork](https://github.com/cyrrp/AoTD-Theory-Of-Toolbox-Scheduler-Fork) выпуска
`1.0.14-spp13`. Форк необходим для оптимальной производительности AoTD и поддерживаемого native
scheduler/capability path. Будущие ревизии остаются fail-closed до проверки их контрактов. Без AoTD
форк не требуется.

В Windows запустите двойным щелчком `StarsectorPrepatcher.bat`, выберите
**Install javaagent**, затем Vanilla, Faster Rendering или оба варианта. Интерфейс BAT-файла
на английском; те же действия доступны из консоли:

```bat
StarsectorPrepatcher.bat install Vanilla
StarsectorPrepatcher.bat install FasterRendering
StarsectorPrepatcher.bat install Both
```

Launcher поясняет назначение каждого действия до выбора. Installer понимает как vanilla command
line в `vmparams`, так и Java argfile Faster Rendering `starsector-core\fr.vmparams`. Для каждого
изменяемого файла он создаёт timestamped backup, заменяет существующую запись этой установки и
размещает Prepatcher после остальных `-javaagent`:

```text
-javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
```

Имя каталога не должно содержать пробелы; после установки его следует оставить
`StarsectorPrepatcher`. Дополнительные `--add-exports` не нужны: agent экспортирует необходимые JDK
ASM packages через `Instrumentation.redefineModule()`.

Сначала установите telemetry и остальные agents либо повторно запустите этот installer после них.
Prepatcher должен оставаться последним `-javaagent`, чтобы его transformer видел bytes, возвращённые
ранее зарегистрированными agents.

Prepatcher не изменяет формат сохранений, а его runtime-кэши не сериализуются.

## Текущие области патчей

- sector, system и Intel map: reconciliation, spatial candidates, callbacks, hover, entity indexes,
  nebula metadata, scratch collections и grid LOD;
- campaign и economy: lifecycle-bound кэши, listener refresh, reusable snapshots, единый scheduler
  всех преобразованных engine-owned обновлений рынков, исправленное observation прямых mod-вызовов
  `Market.advance()`, атомарный targeted/local UI market-mutation refresh для доказанных
  policy/trade/free-port/industry изменений; admin, unsupported и unknown callers сохраняют исходный
  global step,
  owner-local persistent copy-on-write snapshots
  markets/conditions/industries со structure epochs и bounded audit, owner-local ReachEconomy
  fingerprint, ordered fast path неактивных commodities вместе с direct expiry-aware scheduler
  `MutableStatWithTempMods`, guarded fast path для dormant-наследников `BaseIndustry`, подавление
  повторного удаления уже отсутствующего commodity event mod, fast paths для пустых scripts/Memory,
  fail-closed инкрементальный индекс границ core worlds с hooks в `CampaignEngine`/`BaseLocation`,
  bounded audit прямых изменений тегов и comm-relay candidates;
- routing: упорядоченные jump-point/system indexes с vanilla selection/fallback;
- combat и particles: внутренние scratch collections и стабильная deferred cleanup;
- fast-forward presentation: final-substep coalescing защищённых campaign visuals и continuous
  audio; широкие animation/fader/particle группы включены в default/aggressive profile;
- loading/save: literal parsing, progress redraw и исправления output path;
- hyperspace: terrain culling, layer selection, seeded random reuse, owner-local automaton buffers и
  moving-starfield cleanup.

Полный перечень переключателей и инвариантов находится в
[`docs/PATCHES.md`](docs/PATCHES.md).

## Конфигурация и откат

Все настройки находятся в `prepatcher.properties`. Пользовательские группы имеют отдельные
`patch.*` switches и требуют полного перезапуска игры. Весь prepatcher отключается так:

```properties
enabled=false
```

`patch.loadingTextReader` и `patch.startupLogAggregation` остаются отключёнными во всех поставляемых
профилях после подтверждённых ошибок запуска миссий. Они не будут включены снова до отдельного
исправления и изолированного startup/mission-прогона.

Default, safe и aggressive profiles держат дорогие observers, CSV/stack sampling, verbose-вывод
transformer-а, presentation metrics и периодический stats worker выключенными. Копируйте
`profiles/debug.properties` поверх `prepatcher.properties` только на ограниченное время диагностики:
этот профиль наследует все настройки aggressive, включает перечисленные средства и пишет
дополнительные данные в `logs/`. Соответствие контракту «aggressive плюс диагностика» закреплено
repository consistency test-ом.

Safe profile включает structurally matched master/frame marker fast-forward presentation и более
узкие visual/audio группы. Default profile полностью совпадает с aggressive: global animations,
sensor faders, slipstream particles и particle emitters включены, несмотря на более широкую область
callback, lifetime, RNG и emission cadence. `fastForward.visualTime=realtime` оставляет presentation на одном
обычном update за outer frame, а `simulation` накапливает substep time и может давать заметные
скачки. Сама simulation продолжает выполняться на каждом substep.

`patch.marketScheduler` включён в default/aggressive profile и направляет все известные core-вызовы
`MarketAPI.advance(float)` через единый контракт. Периодические источники Economy loop и
planet-condition накапливают `amount` на каждом simulation tick, но cadence проверяется один раз на
render batch. При ускорении Starsector выполняет несколько `CampaignEngine.advance()` за один
отрисованный кадр; финальная итерация определяется через
`CampaignEngine.setFastForwardIteration(false)`. Поэтому обычные и hot-рынки получают не более одного
callback на render batch, а их callback count не растёт кратно ускорению. Удалённые видимые рынки
используют `market.scheduler.batches`, скрытые удалённые — `market.scheduler.hiddenBatches`, а рынки
текущей location, interaction market и player-owned — один callback на batch.

Явный compatibility opt-out задаётся memory key
`$starsectorPrepatcher_perSimulationTickMarket=true`. Только такие рынки сохраняют один callback на
каждый simulation tick. В stats выводятся и текущее число таких рынков, и стоимость их вызовов. Шесть
редких vanilla create/remove call sites, прямые mod-вызовы, fail-open ветки и pre-save flush используют
более дешёвый synchronous hook: он сначала поглощает существующий pending debt, затем выполняет
исходный event callback. Scheduler активируется только после инициализации lifecycle/batch компонента
CampaignEngine, подтверждённого batch-протокола `CampaignState`, Economy source, entity source и save
flush; до этого вызовы синхронны и debt не создают. Подробное последовательное описание:
[docs/architecture/MARKET_SCHEDULER.md](docs/architecture/MARKET_SCHEDULER.md).

Runtime stats используют одно семейство `marketScheduler*`. Метрики
`marketSchedulerSimulationTicks` и `marketSchedulerRenderBatches` показывают фактический коэффициент
ускорения, а `marketSchedulerMaxTicksPerBatch` — крупнейший batch. Отдельно считаются накопленные
input calls, выполненные callbacks, per-simulation-tick opt-outs и синхронное поглощение debt. Ошибки
разделены по конкретным причинам. Ошибка обычного callback отключает batching только для данного
рынка; ошибка уже начатого pre-save callback отбрасывает отделённый неоднозначный debt, переводит
рынок в immediate execution и прерывает save, чтобы частично применённый callback не запускался
автоматически повторно. Периодические counters используют `sumThenReset()`.

`patch.directMarketObservation` включён только в debug profile. Он не
throttling-ует прямые вызовы модов: каждый вызов остаётся синхронным и немедленным. Известный
planet-condition engine path учитывается отдельно от unknown, manifest преобразованных call sites
пишется до первого выполнения, а лимит unknown stacks обновляется каждый отчётный интервал.
Каталоги validation-smoke имеют заметную метку, а `session.json` содержит `sessionOrigin`.
Результаты находятся в `logs/direct-market-observe/session-*/`; после сбора данных observer стоит
выключить, чтобы убрать sampling overhead. `call-sites.csv` и `observations.csv` содержат отдельные
поля `mod_id`, `mod_name`, каталог мода и имя JAR, полученные из `mod_info.json`; поле `source`
остаётся точным code-source path и больше не является единственным способом определить мод.

Причины construction full-rate всегда накапливаются в агрегированных counters/gauges; debug profile
выводит их в периодической строке stats. `Industry.isUpgrading()` остаётся диагностическим признаком. Для наследников
`BaseIndustry` policy использует authoritative raw-поле `building`, а не переопределённый virtual
`isBuilding()`; для произвольных реализаций `Industry` сохраняется fallback к интерфейсному методу.
Случаи virtual=true при raw=false учитываются отдельным reason/counter/gauge, но не включают full-rate.
Ограниченную диагностическую выборку можно включить через
`observer.marketConstructionDiagnostics=true`; CSV записывается в
`logs/market-construction-diagnostics/session-*/`, раздельно фиксирует reported/effective building,
источники building/upgrading, transition buckets и скалярное состояние `BaseIndustry`, не удерживает
игровые объекты и не меняет поведение scheduler.

Для удаления запустите `StarsectorPrepatcher.bat`, выберите **Remove javaagent** и нужный
launcher. Консольные варианты: `StarsectorPrepatcher.bat uninstall Vanilla`,
`... uninstall FasterRendering` и `... uninstall Both`. Каждый изменяемый файл предварительно
сохраняется в backup.

## Диагностика и проверка

Runtime-логи:

```text
mods\StarsectorPrepatcher\logs\prepatcher.log
mods\StarsectorPrepatcher\logs\direct-market-observe\session-*\
mods\StarsectorPrepatcher\logs\market-construction-diagnostics\session-*\
```

Agent пишет `APPLIED`, `ALREADY_APPLIED`, `SKIPPED_STRUCTURAL`, `SKIPPED_COMPOSITION`,
`SKIPPED_LOADER`, `SKIPPED_ALREADY_LOADED` или `SKIPPED_ERROR`. Presentation и hyperspace targets
используют ту же локальную structural-модель статусов, что и остальные patches. Каждый skip работает fail-open;
`SKIPPED_LOADER` нужно разобрать до заявления совместимости соответствующего способа запуска.

В Windows выберите **Run full verification** в `StarsectorPrepatcher.bat` либо запустите
`StarsectorPrepatcher.bat verify`; в Linux/macOS используйте `./verify-structural.sh`. Suite
включает документацию, structural/negative/idempotency, lifecycle/GC, runtime, hyperspace и startup
единого agent. При наличии `fr.jar` дополнительно запускается smoke с настоящим classloader Faster
Rendering. Сборка описана в [`BUILDING.md`](BUILDING.md).

## Документация

- [`README.md`](README.md) — основная английская версия;
- [`CHANGELOG.md`](CHANGELOG.md) — история публичных версий `X.Y.Z`;
- [`BUILDING.md`](BUILDING.md) — сборка и полная проверка;
- [`docs/PATCHES.md`](docs/PATCHES.md) — переключатели патчей и поведенческие инварианты;
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — structural matching и fail-open правила;
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — playbook регрессионных и performance-проверок;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — план structural discovery, архитектуры, tooling и платформ;
- [`docs/architecture/MARKET_SCHEDULER.md`](docs/architecture/MARKET_SCHEDULER.md) — долговременное
  устройство и инварианты scheduler;
- [`docs/releases/0.18.3.md`](docs/releases/0.18.3.md) — подробный отчёт текущего выпуска.

Условия распространения находятся в [`LICENSE`](LICENSE).

## Интеграция с AoTD Scheduler Fork

Prepatcher 0.18.3 сохраняет clean wrapper на оригинальный `BaseIndustry.getMaxDeficit()` и
поддерживает Scheduler Fork `1.0.14-spp13`. Обязательный production-профиль `0xbff` включает
economy-restore coordination; optional UI market-mutation capability расширяет полный V10-профиль
до `0xfff`. Bridge публикует campaign/economy epoch и читает актуальную runtime
capability mask. Поздние callbacks старой эпохи отклоняются, а fail-stop listener запускает
однократную синхронизацию поколений перед включением fallback dirtying. Старый изменённый
`starfarer.api.jar` устанавливать нельзя.

Обязательные capabilities не зависят от optional optimization switches, поэтому safe profile тоже
согласует `0xbff`. Регистрация поддерживает только текущий контракт: форма bridge должна быть V10,
версия — точно `1.0.14-spp13`, declared mask — точно `0xfff`. Любое расхождение
логируется и целиком отклоняется без режима частичной совместимости.

Read-only UI patch также удаляет точный UI-triggered глобальный `tripleStep()` при открытии
Command/Colonies, текущего и legacy commodity-detail dialog, а также из независимо проверяемых
vanilla `MarketCMD.showDefenses()` и Nexerelin `Nex_MarketCMD.showDefenses()` после захвата входных
данных обороны. Это чистые inline fail-closed удаления без runtime cache и ссылок на рынки; safe
profile оставляет их выключенными. В реальном AoTD fork descendants vanilla surfaces нет, а
optional Nex-класс преобразуется только в памяти без изменения его JAR.

Exact ветка detached Cargo с `fake_market` теперь оптимизируется и без AoTD. Если runtime-классы
являются точными vanilla `Economy` и `ReachEconomy`, а финальный bytecode по-прежнему доказывает, что
`tripleStep()` состоит ровно из трёх `nextStep()`, Prepatcher пропускает этот не относящийся к
инвентарю пересчёт сектора. Для поддерживаемого форка тот же exact call-site guard вызывает его
explicit dispatcher с классифицированным synthetic-Cargo intent. Отклонённый/ошибочный dispatch и
replacement/subclass экономики сохраняют исходный virtual call, который всегда остаётся глобальным.
Market-open, Cargo и trade guards больше не публикуют fork context. Tooltip Local Resources
использует call-local snapshot лимитов и не удерживает рынки или commodities после завершения
вызова.

Schema V8 передаёт packed reason/scope для доказанных local policy mutations. Schema V9 расширяет
ту же атомарную feature-группу immutable sorted commodity-ID payload. Для non-trade mutations
one-shot context остаётся внутренним для Prepatcher между exact vanilla setter или industry wrapper
и shared helper; форк получает только уже классифицированный intent. Exact free-port change и пять
exact vanilla industry branches — start-upgrade, downgrade, два remove/shut-down и cancel-upgrade —
обновляют один рынок и перестраивают global/econ-group records только затронутых товаров; industry
mutation без commodity diff выполняет только local industry/state refresh. Trade не использует
setter/helper context: exact guard получает affected IDs непосредственно из immutable bought/sold
cargo и применяет тот же affected-commodity commit. Admin assignment, stabilization, construction
queue и неизвестные/custom helper callers сохраняют исходный global step.

Schema V10 добавляет один exact completion signal в успешном хвосте
`CoreLifecyclePluginImpl.econPostSaveRestore()V`, после восстановления всех industries и обоих
market reapply-вызовов. Во всех поставляемых профилях им управляет единый переключатель
`patch.aotdEconomyRestoreCoordination=true`. Hook игрового classloader выполняет O(1) работу:
вызывает один loader-local `Runnable` и не обходит рынки. Форк восстанавливает commodity-структуру
без немедленного расчёта, объединяет изменения каждого рынка в один dirty scheduler refresh и
сохраняет последнюю committed revision, пока industry snapshot временно недоступен. Только snapshot stage может вернуть
`NOT_READY`; ошибки calculation scripts остаются видимыми. Новые сохранения исключают содержимое и
ссылки derived per-industry supply/demand caches и перестраивают их после полного restore barrier.

При установленном AoTD Theory of Toolbox поддерживаемый Scheduler Fork `1.0.14-spp13` необходим для
оптимальной производительности. В остальных конфигурациях Prepatcher не требует ни AoTD, ни форк.
Исходная сборка AoTD может использовать сохранённые fail-closed/raw пути, но не предоставляет полный
поддерживаемый native scheduler contract.

Группа market-share optimizations также явно поддерживает принадлежащий проекту
`AoTDCommodityMarketData`. Loader-local `ClassValue` допускает текущий fork только пока пять
критических market-share методов наследуются из vanilla. Будущий override локально переводит
данный runtime class на сохранённое raw-поведение, не отключает vanilla-патч и не удерживает
classloader форка.

Патч Local Resources против холодной материализации распознаёт точный AoTD-контракт commodity,
supply/demand data и calculation script. Он читает только уже опубликованное состояние и применяет
форковое преобразование raw units, не вызывая lazy builder рыночных данных. Owner-local индекс
`econGroup` также допускает точный наследуемый read surface `AoTDReachEconomy`. Real-fork gate
проверяет, что текущий `addMarket` ровно один раз делегирует vanilla; будущий critical read override
локально возвращается к raw path, а обход `super` в mutator обнаруживается через source validation
и bounded audit, а не считается немедленным epoch event.

Exact fork-owned `AoTDConstructionSite.setAssignedWonder(String)` получает проверенную scheduler
mutation boundary, поэтому переход в `building=true` не остаётся скрытым до периодического
construction audit. Эти пути не добавляют статический cache campaign/mod objects: optional
accessors используют `ClassValue`, а массивы group index остаются transient-состоянием владельца
`ReachEconomy`.

Интеграция также исправляет синхронную последовательность открытия рынка/Cargo, не меняя смысл
стандартного economy API. `AoTDEconomy.nextStep(...)`, `doubleStep()`, `tripleStep()` и
`AoTDReachEconomy.nextStep(...)` всегда выполняют полный global step и не выводят UI intent из
`currentlyOpenMarket` или отсутствующего payload; double/triple сохраняют vanilla-кратность в два
и три шага. Exact Prepatcher call-site guards вместо этого
вызывают единый public final dispatcher форка для market-open, Cargo или market-mutation action.
Поддержанный intent выполняет committed single-market cut; `GLOBAL_TOPOLOGY`, отсутствующий
barrier/capability, неподдержанный action, `false` или exception запускают сохранённый исходный
virtual call и тем самым глобальный путь.

Патч управляется ключом `patch.aotdCleanDeficitPath=true` и включён во всех поставляемых профилях.
