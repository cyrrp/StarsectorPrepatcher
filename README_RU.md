# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Текущая версия: **0.11.0**. Поддерживаемая версия игры: **Starsector 0.98a-RC8**.

[![Без препатчера и с ним](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Нажмите на превью, чтобы открыть полное сравнение в WebM при 60 FPS.

StarsectorPrepatcher — compatibility-first слой ранних патчей Starsector. Startup-javaagent'ы
запускаются до обычной загрузки игровых и модовых classloader'ов, поэтому защищённые структурные
патчи применяются в момент первого появления целевых классов в JVM.

Задача проекта шире одной только оптимизации карты:

- поддерживать тщательно проверенные исправления производительности и корректности внутренних
  классов игры;
- предоставить стабильный документированный API к полезным возможностям, которых нет в публичном
  API Starsector;
- хранить зависимое от версии игры знание о bytecode внутри prepatcher, а не размножать его по
  игровым модам.

Публичный API в `0.11.0` ещё не выпущен и остаётся пунктом roadmap. Планируемый namespace —
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
- campaign и economy: lifecycle-bound кэши, listener refresh, reusable snapshots, агрессивные
  staggered scheduler'ы центральных удалённых рынков и `planetConditionMarketOnly`, исправленное
  observation прямых mod-вызовов `Market.advance()`, owner-local persistent copy-on-write snapshots
  markets/conditions/industries со structure epochs и bounded audit, owner-local ReachEconomy
  fingerprint, ordered fast path неактивных commodities вместе с direct expiry-aware scheduler
  `MutableStatWithTempMods`, guarded fast path для dormant-наследников `BaseIndustry`, подавление
  повторного удаления уже отсутствующего commodity event mod, fast paths для пустых scripts/Memory,
  structural cache границ core worlds в `CoreScript` и comm-relay candidates;
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
- [`docs/releases/0.11.0.md`](docs/releases/0.11.0.md) — подробный отчёт текущего выпуска.

Условия распространения находятся в [`LICENSE`](LICENSE).

## Интеграция с AoTD Scheduler Fork

Prepatcher 0.11.0 устанавливает clean wrapper на оригинальный `BaseIndustry.getMaxDeficit()`.
Vanilla-реализация сохраняется и используется, пока совместимый AoTD Scheduler Fork не зарегистрирует
полный native-профиль `0x1ff`. Bridge schema V6 публикует campaign/economy epoch и читает актуальную
runtime capability mask. Поздние callbacks старой эпохи отклоняются, а fail-stop listener запускает
однократную синхронизацию поколений перед включением fallback dirtying. Старый изменённый
`starfarer.api.jar` устанавливать нельзя.

Патч управляется ключом `patch.aotdCleanDeficitPath=true` и включён во всех поставляемых профилях.
