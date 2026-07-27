# Технический roadmap

Статус: план работ после выпуска `0.13.0`. Persistent economy snapshots, ordered commodity active
set, direct expiry-aware temp-mod scheduler, dormant `BaseIndustry` fast path, production-интеграция
AoTD, incremental core-worlds и StrategicModule indexes и линейный market-share path выпущены в
одном unified agent. Следующий архитектурный milestone сосредоточен на structural discovery; срок
выпуска этим документом не задаётся.

Этот документ описывает известный технический долг, целевую архитектуру и порядок миграции.
Текущее пользовательское поведение патчей зафиксировано в [`PATCHES.md`](PATCHES.md), правила
совместимости — в [`COMPATIBILITY.md`](COMPATIBILITY.md), а методика проверки — в
[`VALIDATION.md`](VALIDATION.md).

## Главные решения

| Приоритет | Решение | Критерий |
| --- | --- | --- |
| **P0** | Structural discovery class-файлов до загрузки игры | Обязательный release gate; точные obfuscated-имена перестают определять совместимость |
| **P0** | Сохранение fail-open и текущей функциональности | Ошибка одной роли или одного патча не отключает остальные и не меняет vanilla-код неподтверждённого участка |
| **P0** | Linux как поддерживаемая платформа | Реальная установка, запуск и полный blocking gate на Linux |
| **P1** | Декомпозиция transformer и runtime hooks | Один каталог патчей, явные `inspect → apply → verify`, тонкие ABI-фасады hooks |
| **P1** | Единый formatter и проверяемый стиль | Форматирование воспроизводимо локально и в CI; механический reformat отделён от логики |
| **P1** | CI и воспроизводимая поставка | Публичные synthetic-тесты без игровых JAR и private/self-hosted проверки реальной игры |
| **P3** | macOS | Best effort, не блокирует релиз до отдельного объявления поддержки |

Structural discovery — не факультативная оптимизация и не обещание «когда-нибудь». Без него
следующая архитектурная версия не считается готовой.

## Что сейчас не так

### Имена и structural matching

1. `PrepatcherTransformer` содержит 31 точное JVM internal name. Класс отбрасывается до
   structural-проверки, если его имя отсутствует в `TARGET_CLASSES`.
2. Один и тот же каталог целей повторяется в `TARGET_CLASSES`, dispatch-switch и switch проверки
   конфигурации. Hyperspace-цели имеют ещё один отдельный список.
3. Текущая проверка хорошо переносит изменения строк и constant pool внутри класса с известным
   именем, но не находит тот же семантический класс после смены obfuscated name.
4. Точные имена скрыты не только в dispatch. Они встречаются в cross-target owners/descriptors,
   hyperspace-вызовах и runtime reflection. Замена одного `switch (className)` дала бы лишь
   частичную name-independent совместимость.
5. Тесты сами открывают `<exact-name>.class` и передают известное имя transformer. Поэтому они не
   доказывают, что цель можно обнаружить без имени.

Строки вида `com/fs/...` являются JVM internal names и не зависят от разделителя каталогов ОС.
Проблема не в `/` на Windows/Linux, а в предположении, что obfuscated name останется тем же в другой
версии или сборке игры.

### Архитектура

- `PrepatcherTransformer.java` вырос примерно до 4,4 тысячи строк и одновременно выполняет
  routing, config dispatch, structural matching, mutation, ownership/idempotency, serialization,
  ASM verification и реализации патчей.
- `PrepatcherHooks.java` вырос примерно до 3 тысяч строк и объединяет loading, campaign lifecycle,
  map/Intel caches, economy, combat, routing и telemetry.
- Match и mutation не выражены отдельными фазами. Часть mutator'ов начинает менять временный
  `ClassNode` до доказательства полного контракта. Транзакция не выпускает ошибочный результат, но
  такой код нельзя безопасно использовать для read-only discovery.
- Hyperspace использует второй вариант patch API и дублирует ASM helpers.
- Production и тесты дублируют чтение class-файлов, verification и анализ bytecode.

Большой файл сам по себе не является дефектом. Дефект — смешение обязанностей и невозможность
переиспользовать доказательство структуры без запуска mutator. Цель декомпозиции — ясные границы,
а не россыпь микроклассов.

### Форматирование и репозиторий

- В репозитории нет закреплённого formatter, `.editorconfig` и `.gitattributes`.
- Особенно плотный стиль hyperspace-кода затрудняет review и даёт автоформатированию большой,
  нестабильный diff.
- Shell scripts в Git имеют mode `100644`, хотя документация предлагает запускать их через `./...`.
- Нет CI workflow; локальная полная проверка использует proprietary game JAR и потому не может
  просто исполняться на публичном GitHub runner.
- Сборочные JAR пока не воспроизводимы побайтно из-за порядка entries/timestamps.

### Платформы и пути

- Есть Windows installer/uninstaller, но нет эквивалента для Linux.
- `-javaagent:` содержит реальный файловый путь, а не JVM internal name. Такой путь должен быть
  корректно разрешён и процитирован независимо от текущего каталога процесса.
- Успешный путь определения каталога agent через `CodeSource` переносим, но fallback
  `../mods/StarsectorPrepatcher` зависит от `user.dir` и layout установки.
- Bash scripts предполагают конкретное положение `starsector-core` относительно каталога мода.
- Поддержка internal ASM требует именно проверенного JDK 17. Условие «17 или новее» слишком широкое:
  внутренний ASM следующего JDK может иметь другой контракт.

## Неприкосновенные инварианты

Архитектурная миграция не должна одновременно перенастраивать производительность или урезать
патчи. Необходимо сохранить:

- независимую транзакцию каждого патча и текущий порядок патчей одного класса;
- применение остальных патчей, если один local structural contract не совпал;
- fail-open: неподтверждённый код остаётся vanilla;
- повторный parse, postcondition, ASM verification и неизменность public/protected API;
- ownership/idempotency matrix, marker schema, существующие patch IDs, config keys и system
  properties;
- hook owner/name/descriptor не меняются как побочный эффект рефакторинга. Если private game type в
  descriptor мешает name-independent discovery, его миграция выполняется отдельной атомарной
  задачей с синхронным изменением injected call, hook entry point и compatibility tests;
- lifecycle-bound caches и отсутствие ссылок, удерживающих старую кампанию;
- отсутствие модификации игровых JAR на диске.

Изменения семантики патча, новые оптимизации и formatter-only diff выполняются отдельными
коммитами.

## Целевая архитектура

```text
premain
  ├─ config + platform paths
  ├─ classpath source locator
  ├─ TargetCatalog → structural discovery → immutable ResolvedTargets
  └─ thin ClassFileTransformer
       └─ PatchEngine
            ├─ PatchCatalog
            ├─ domain PatchSets
            └─ shared read-only ASM/verification services

injected bytecode
  └─ stable PrepatcherHooks / HyperspaceHooks facades
       └─ lifecycle-scoped runtime domain services
```

Предлагаемые границы пакетов:

```text
agent/          premain и тонкий ClassFileTransformer adapter
config/         конфигурация
discovery/      sources, index, logical roles, bindings, diagnostics
transform/      PatchEngine, PatchCatalog, patch SPI и статусы
asm/            codec, verifier, API snapshot, markers, общие queries
patch/          loading, map, campaign, economy, combat, hyperspace
runtime/        реализации за стабильными hook-фасадами
testsupport/    общие fixtures, transformer harness и assertions
```

Каталоги разделяются по направлению зависимостей:

- `TargetCatalog` содержит logical roles и их read-only discovery specs. Он не зависит от patch
  implementations или runtime hooks;
- `PatchCatalog` содержит patch ID, config predicate, порядок, реализацию и ссылку на `TargetRole`.

Одна и та же информация не дублируется между каталогами. Отдельные `TARGET_CLASSES` и parallel
switches удаляются.

Контракт патча:

```text
inspect(read-only ClassNode, TargetBinding)
  → ORIGINAL + immutable PatchPlan
  → ALREADY_APPLIED
  → INCOMPATIBLE + reason

apply(ClassNode, PatchPlan)
  → changed ClassNode

verify(reparsed ClassNode, TargetBinding)
  → VERIFIED | INCOMPATIBLE
```

Matcher сначала собирает и доказывает все sites, receivers, data flow и exact counts. Mutator получает
готовый план и не занимается поиском. `PatchPlan` — package-private one-shot object для того же
`ClassNode`: он не кешируется, не сериализуется и не переносится на reparsed tree. Общий ASM helper
выносится только при наличии минимум двух одинаковых доказанных контрактов; универсальный bytecode
DSL не нужен.

## P0: structural discovery до загрузки классов

### Почему не live instance

В `premain()` transformer должен быть зарегистрирован до появления игровых объектов. Получить
экземпляр и вызвать `instance.getClass()` в этот момент невозможно, а загрузить класс ради поиска —
значит опоздать с prepatch. Корректный name-independent путь — прочитать class-файлы текущего launch
classpath без `Class.forName()`, найти уникальную структуру и только затем зарегистрировать
transformer.

### Модель данных

```text
TargetRole             логическая роль: MAP_RENDERER, CAMPAIGN_ENGINE, ...
TargetSpec             строгий read-only discovery contract роли
TargetBinding          source URI + discovered internal name + member/symbol bindings
ResolvedTargets        immutable registry всех однозначно найденных ролей
PatchDefinition        patch ID + role + config + inspect/apply/verify
```

`TargetBinding` обязан связывать не только имя класса, но и нестабильные символы, например поле
automaton cells или обфусцированный hit-test method. Cross-target owner задаётся через другую
логическую роль. До регистрации transformer из bindings строится `RuntimeBindings`, содержащий
только строки/descriptors; hooks получают его через однократную конфигурацию и используют
vanilla-safe fallback при отсутствии роли. В нём запрещены `Class<?>`, reflection objects,
`ClassNode` и игровые экземпляры.

Game types в hook ABI отдельно инвентаризируются. Каждый такой тип либо признаётся документированным
стабильным anchor, либо переводится на `Object`/публичный API в отдельной ABI-миграции. Это исключение
из правила стабильного descriptor, а не скрытая часть декомпозиции.

### Pipeline

1. **Найти реальные источники.** Прочитать effective launch classpath через `java.class.path` и
   `File.pathSeparator`, разрешить relative/wildcard entries, нормализовать и учитывать фактический
   classpath precedence. Сканируются только уже подключённые JAR/directories, а не весь диск или
   `mods`. Для launcher, не публикующего полный classpath, допускается только явный проверенный
   source override от platform adapter; угадывание каталога игры запрещено.
2. **Построить дешёвый header index.** Одним проходом прочитать class version/access,
   superclass/interfaces и multisets field/method descriptors с `SKIP_CODE | SKIP_DEBUG |
   SKIP_FRAMES`. Не хранить все class bytes или `ClassNode`.
3. **Сформировать shortlist.** Отфильтровать candidates по нескольким независимым структурным
   признакам. Package и историческое имя разрешены лишь как подсказки для производительности и
   диагностики, но не дают кандидату преимущество и не завершают scan досрочно.
4. **Доказать intrinsic identity.** Полностью разобрать только shortlist и проверить независимые
   opcode/CFG relations, descriptors и provenance. Target identity contract отделён от local
   contracts отдельных патчей: исчезновение одного patch site не делает всю роль `UNRESOLVED`.
   Discovery никогда не запускает patch mutator.
5. **Разрешить связи и symbols.** Вторым проходом выполнить relational validation между candidate
   sets, построить owners/fields/methods и достичь фиксированной точки. Это позволяет обрабатывать
   циклы между ролями без принятия слабого кандидата через ещё не доказанную роль.
6. **Применить exact-one к effective definitions.** Для одинакового internal name учитывается только
   entry, реально видимый первым по правилам classloader; shadowed copies лишь логируются. Ноль
   совпадений означает `UNRESOLVED`, два разных эффективных класса с полным contract — `AMBIGUOUS`.
   Никаких tie-break по score, legacy name, имени JAR или порядку обхода.
7. **Опубликовать bindings.** Построить immutable `ResolvedTargets` и строковый `RuntimeBindings`.
   Не публиковать частично разрешённый цикл.
8. **Зарегистрировать transformer.** Routing строится по discovered internal name и фактическому
   source/CodeSource. `null` или несовпавший `ProtectionDomain/CodeSource` не считается молчаливым
   подтверждением и даёт fail-open. Перед регистрацией `Instrumentation.getAllLoadedClasses()`
   проверяет, что цель ещё не загружена; retransform для `TOO_LATE` не выполняется.
9. **Повторно доказать live bytes.** Bytes, переданные JVM, могут быть изменены другим agent. Каждый
   patch снова проверяет свой local contract, ownership, postcondition, API invariance и verifier.

Если одна роль не найдена, отключаются только связанные патчи. Остальные роли и патчи продолжают
работать.

### Устойчивый fingerprint

Fingerprint может использовать:

- inheritance/interface shape;
- field и method descriptors/access;
- стабильные public game API/JDK anchors;
- control-flow и meaningful opcode relations;
- provenance receiver/arguments;
- exact semantic site counts;
- отношения с candidate sets/logical roles во второй фазе validation.

Fingerprint не должен зависеть от:

- переводимых строк и других user-facing literals;
- полного class hash или порядка constant pool;
- debug metadata, line numbers и frames;
- obfuscated member/class names, если роль доказывается структурой;
- единственного слабого признака или «лучшего score».

### Source safety, limits и память

- `JarFile` закрывается в scope чтения; игровые JAR никогда не переписываются и не извлекаются.
- Учитываются duplicate/shadowed и multi-release entries, а также источник, который реально выберет
  classloader.
- Ошибка ZIP/ASM, превышение лимита или несовпадение CodeSource дают локальный fail-open.
- После discovery остаются только строки, URI и маленькие immutable bindings: без `Class<?>`,
  игровых объектов, входных byte arrays и `ClassNode`.
- Отдельные hard safety limits ограничивают число sources/classes, размер class entry, суммарные
  распакованные bytes и wall-clock. Они защищают startup от повреждённого/враждебного архива, а не
  служат эвристикой совместимости.
- На текущей референсной установке (~6382 class-файла, ~13,8 МБ) performance target для full parse —
  не более 128 candidates. Превышение желательного shortlist само по себе не делает совместимый
  класс несовместимым; это измеряемая регрессия, а не structural verdict.
- `BASE-01` фиксирует именованные Windows/Linux runners. На них целевой warm-filesystem p95 — до
  одной секунды, cold scan — до двух секунд, retained state — менее 2 МБ, дополнительный peak —
  менее 64 МБ. Время и размеры всегда логируются.

Persistent cache не входит в первый обязательный этап. Если он понадобится после профилирования,
SHA-256 источников используется только для инвалидирования cache, никогда как compatibility
allowlist. Cache hit всё равно повторяет read-only contract и не хранит class bytes.

### Диагностика

Для каждой роли логируются:

- `DISCOVERED`, `UNRESOLVED`, `AMBIGUOUS`, `SOURCE_ERROR`, `SOURCE_MISMATCH` или `TOO_LATE`;
- source URI, internal name и число header/full candidates;
- краткие подтверждённые landmarks или точная причина отказа;
- scan timing и shadowed duplicates.

Существующие per-patch статусы `APPLIED`, `ALREADY_APPLIED` и `SKIPPED_*` сохраняются отдельно.

### Обязательные acceptance criteria

- Все 27 текущих logical targets однозначно обнаруживаются в оригинальной `0.98a-RC8` и в
  используемой русской локализации.
- Каждая из 27 ролей проходит fixture-remap internal name и peer references без production-доступа
  к legacy name.
- Изменение несвязанной строки/constant pool не влияет на discovery.
- Near-match отвергается; два полных candidates дают `AMBIGUOUS` и не патчатся.
- Legacy-named decoy и полноценный renamed candidate вместе также дают `AMBIGUOUS`: историческое
  имя не является tie-break и не останавливает поиск.
- Shadowed copy одного internal name не создаёт ложную ambiguity, но два разных effective names с
  полным contract создают. Неполный custom-launcher classpath даёт явный fail-open/diagnostic.
- Отсутствие одной цели отключает только её patch set.
- Изменение одного patch site даёт локальный `SKIPPED_STRUCTURAL`, но не блокирует независимые
  патчи найденного класса.
- Discovery не вызывает `Class.forName()`, не определяет/инициализирует игровые классы и не пишет
  в игровые JAR.
- CodeSource/classloader mismatch не патчится; уже загруженная цель получает `TOO_LATE` без попытки
  retransform. Проверяется совместная работа с предыдущим javaagent.
- Actual JVM bytes повторно проходят local matcher, ownership, API snapshot и BasicVerifier.
- После scan нет открытых JAR handles и удерживаемых class bytes/ClassNodes.
- На именованных reference runners соблюдаются shortlist/timing/retained/peak budgets; hard resource
  limits имеют отдельные negative fixtures.
- Linux discovery/startup smoke является blocking gate; macOS — informational.

## Formatter и правила изменения кода

1. Закрепить одну точную версию `google-java-format` all-deps, официальный URL и SHA-256.
   Использовать режим `--aosp`, соответствующий текущему четырёхпробельному стилю.
2. Добавить одинаковые wrappers: `format-java.ps1 -Check/-Write` и
   `format-java.sh --check/--write`. Check не скачивает «latest» и работает из проверенного local
   cache/vendor artifact; обновление версии formatter — отдельный осознанный commit.
3. Добавить `.editorconfig` и `.gitattributes`: UTF-8, final newline, LF для Java/Markdown/
   properties/PowerShell/shell и CRLF для `.bat`. Executable bit не задаётся `.gitattributes`:
   `.sh` отдельно переводятся в Git mode `100755`, и этот mode проверяется gate.
4. Первый полный reformat сделать отдельным mechanical-only commit: без moves, переименований и
   logic changes. Последующие commits обязаны проходить formatter check до сборки JAR.
5. Дополнить gate проверками `git diff --check`, `bash -n`, PowerShell AST parse и ShellCheck,
   закреплённым immutable version/digest. GitHub Actions также pin'ятся по commit SHA.

Formatter не заменяет архитектурный review. Для нового крупного файла требуется объяснить одну
связную ответственность; ориентир для `PrepatcherTransformer` после миграции — менее 500 строк,
но главным критерием остаётся отсутствие patch mutation logic, а не формальный лимит строк.

## Linux — обязательная платформа

Linux получает Tier 1 наравне с Windows для следующего архитектурного выпуска.

Необходимые работы:

- добавить idempotent `install-agent.sh` и `uninstall-agent.sh` с определением собственного
  каталога, `--game-root` override и проверкой layout;
- реализовать явные adapters для официального `starsector.sh` и реально подтверждённых вариантов
  пользовательского/AUR `jvm_args`, а не переносить Windows-regex;
- вставлять ровно один shell-quoted `-javaagent`, после других agents; путь должен работать при
  запуске launcher из произвольного cwd и при пробелах/Unicode;
- при zero/multiple anchors ничего не менять и выдавать ручную инструкцию;
- использовать backup, same-directory temporary file, проверку `bash -n`, сохранение mode и
  atomic replace; uninstall удаляет только принадлежащую нам запись;
- не выполнять автоматический `sudo` и не менять системные файлы;
- убрать cwd-dependent runtime fallback из agent. Build/verify уже находят mod root относительно
  собственного script, но получают `--game-root`/`STARSECTOR_HOME` override вместо единственного
  жёсткого предположения о layout;
- installer/uninstaller должны работать на Bash без внешнего developer JDK; startup использует
  bundled game runtime;
- запускать build, synthetic discovery, installer fixtures и реальный startup smoke на
  case-sensitive filesystem.

Linux acceptance:

- установка в путь с пробелами и запуск обычным launcher доходят до bootstrap/game log:
  фиксируются `transformer-installed`, ожидаемые per-patch `APPLIED`/`ALREADY_APPLIED` и отсутствие
  `SKIPPED_ERROR`;
- повторная установка — no-op, после других agents остаётся ровно одна наша запись;
- backup и все посторонние JVM arguments/mode сохранены;
- запуск вне каталога игры работает;
- повторное удаление безопасно и не трогает чужие agents;
- zero/multiple anchors, read-only destination, symlink, interrupted/temp-validation failure и
  повторные install/uninstall проверяются транзакционными fixtures;
- финальный пользовательский ZIP распаковывается на clean Linux и проходит
  `bash install-agent.sh → game activity → bash uninstall-agent.sh`. Запуск через `bash` обязателен,
  поскольку ZIP, созданный на другой ОС, может не сохранить executable metadata;
- developer build/verify проходят из чистого checkout на закреплённом vendor и patch level JDK 17.

## macOS — best effort

Общий Java discovery/path core должен оставаться переносимым, а shell syntax и synthetic fixtures
могут проверяться на macOS runner. На первом этапе установка документируется вручную. Автоматически
редактировать `.app` bundle до реальной проверки launcher layout, подписи и Gatekeeper не следует.
Ошибки macOS фиксируются, но не блокируют выпуск до отдельного объявления Tier 1/2 поддержки.

## Тесты, CI и поставка

Публичный CI на закреплённом JDK 17 выполняет без игровых файлов:

- formatter, line endings, executable bits и static shell/PowerShell checks;
- unit/synthetic discovery: rename, zero/multiple matches, near-match, source precedence;
- patch engine isolation, ownership/idempotency, API invariance и ASM fixtures;
- installer fixtures для путей, quoting и повторного запуска;
- synthetic runtime-manifest/checksum/packager fixtures.

Полная компиляция нынешнего agent/bootstrap зависит от Starsector API/implementation JAR. Поэтому
public runner не изображает полный release build: он компилирует только независимые модули и
легальные synthetic fixtures. Если будут добавлены собственные compile stubs, их происхождение и
границы документируются отдельно.

Полная structural проверка original/localized game JAR выполняется только на доверенных
self-hosted Windows и Linux runners либо локально перед релизом. Там же выполняются реальная
компиляция и startup/activity smoke, при необходимости через Xvfb. Proprietary JAR не загружаются
в GitHub artifacts, cache или release.

Для поставки вводится явный release manifest. Dev-only formatter/test artifacts не попадают в ZIP,
а каждый вошедший в ZIP файл, кроме самого manifest, покрывается `SHA256SUMS.txt`. На self-hosted
runner чистая сборка выполняется дважды с одним точным JDK vendor/patch и `SOURCE_DATE_EPOCH`;
порядок JAR/ZIP entries и timestamps фиксируется, результаты должны быть побайтно одинаковы.
Cross-OS byte equality не обещается, пока она не объявлена и не доказана отдельным gate.

## Пакеты работ

| ID | Контур ответственности | Результат | Зависит от | Gate |
| --- | --- | --- | --- | --- |
| `BASE-01` | Compatibility/testing | Differential baseline `0.8.0` для original/localized: отдельные JVM, statuses, hooks, API snapshots, transformed bytes | — | P0 |
| `FMT-01` | Repository/tooling | Pinned formatter, wrappers, attributes, отдельный mechanical reformat | `BASE-01` | P1 |
| `CI-BOOT` | Tooling | Formatter/compile/synthetic engine gates для безопасного раннего refactoring | `FMT-01` | P1 |
| `CORE-01` | Transformer core | `PatchEngine`, `TargetCatalog`, `PatchCatalog`, сохранённые transaction/status/marker semantics | `FMT-01`, `CI-BOOT` | P0 |
| `CORE-02` | Patch API | Read-only inspection, immutable `PatchPlan`, общий hyperspace contract | `CORE-01` | P0 |
| `ARCH-01` | Patch domains | Финальные domain packages: hyperspace → loading/save → map → Intel → campaign → economy → combat | `CORE-02` | P0 |
| `DISC-01` | Discovery | Classpath locator, header index, diagnostics и shadow-mode registry | `CORE-01`, `ARCH-01` | P0 |
| `DISC-02` | Discovery + patch domains | Intrinsic/relational contracts, `RuntimeBindings` и symbols для всех 27 roles | `DISC-01` | P0 |
| `HOOK-ABI-01` | Runtime/transform | Инвентаризация private game types и отдельные необходимые ABI-миграции | `DISC-02` | P0 при наличии нестабильных типов |
| `DISC-03` | Compatibility | Authoritative discovery routing; legacy names только в hints/fixtures | `DISC-02`, `HOOK-ABI-01`, original/localized gates | P0 |
| `HOOK-01` | Runtime | Hook-фасады и lifecycle-scoped domain services, GC/runtime regressions | `BASE-01`; после стабильного binding contract | P1 |
| `ARCH-02` | Cleanup | Удаление adapters и остаточного дублирования после authoritative routing | `DISC-03`, `HOOK-01` | P1 |
| `LNX-01` | Platform | Portable paths, Linux installer/uninstaller, fixtures | `FMT-01`; параллельно `CORE/DISC` | P0 |
| `CI-FULL` | Tooling/release | Discovery gates и private Windows/Linux full/startup jobs | `DISC-01`, `LNX-01` | P0 |
| `PKG-01` | Release | Deterministic release manifest, JAR/ZIP и checksums | `CI-FULL` | P1 |
| `MAC-01` | Platform | macOS syntax/synthetic checks и manual install notes | `LNX-01` | P3 |

Это разделение — не предложение снова создать два независимых agents. Контуры могут выполняться
параллельно, но результат остаётся одним agent, согласованными Target/Patch catalogs и одним
discovery registry.

## Порядок миграции

1. Зафиксировать differential baseline и отрицательные fixtures. Старый и новый transformer
   запускаются в отдельных JVM, чтобы static state, properties и hooks не загрязняли сравнение.
2. В отдельном commit внедрить formatter и только затем механически переформатировать код.
3. Поднять минимальный public CI: formatter и synthetic engine/ownership tests.
4. Выделить patch engine и два каталога без изменения transformed output.
5. Разделить `inspect/apply/verify`, перевести hyperspace на общий контракт и разложить TargetSpecs/
   PatchSets по финальным domain packages до переключения production routing.
6. Запустить discovery в shadow mode. На original/localized mapping обязана совпасть с legacy
   dispatch, но production routing пока остаётся прежним.
7. Перевести все logical roles, cross-target symbols и `RuntimeBindings`; отдельно мигрировать только
   те hook descriptors, где private game type действительно мешает независимости от имени.
8. Сделать discovery authoritative после original/localized/rename/ambiguity/platform gates.
9. Переносить runtime state по одному lifecycle-домену за hook-фасадами, выполняя GC/runtime/
   performance gates после каждого переноса.
10. Параллельно с пунктами 4–9 довести Linux installer, paths и CI; Linux не откладывается на конец.
11. Удалить legacy dispatch/adapters только после authoritative discovery и двух платформенных gates.
12. Профилировать cold scan. Persistent cache добавлять только при доказанной необходимости.

## Definition of done для архитектурного milestone

- Structural discovery является production routing для всех 27 текущих целей; точные имена не
  участвуют в решении о совместимости.
- Original и localized installations, remap всех 27 roles, near-match, legacy-decoy и ambiguity
  fixtures проходят.
- Один mismatch остаётся локальным; functionality и измеренная производительность `0.8.0` не
  регрессируют.
- `PrepatcherTransformer` — тонкий adapter без patch implementation; Target/Patch catalogs не
  дублируют друг друга.
- Runtime state разделён по lifecycle за стабильными фасадами; необходимые изменения private-type
  hook ABI выполнены отдельно и атомарно. Campaign reload/GC regressions зелёные.
- Discovery соблюдает измеренные timing/memory budgets и не удерживает class bytes/ASM trees.
- Formatter обязателен локально и в CI, а исходники имеют стабильные line endings/modes.
- Windows и Linux проходят реальную установку, game activity и full structural gate на JDK 17;
  финальный ZIP проверен после чистой распаковки.
- macOS остаётся документированным best effort и не блокирует релиз.
- Release artifact воспроизводим, не содержит dev tools и полностью покрыт checksums.
