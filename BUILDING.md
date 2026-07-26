# Сборка StarsectorPrepatcher

## Требования

- JDK 17+;
- установленная Starsector `0.98a-RC8`;
- каталог мода расположен как `<Starsector>/mods/StarsectorPrepatcher`.

Build scripts используют JDK-internal ASM, уже входящий в Java. В собранные JAR не добавляются
сторонние ASM dependencies.

## Сборка

Windows PowerShell:

```powershell
.\build-agent.ps1
```

Linux/macOS:

```bash
./build-agent.sh
```

Создаются:

```text
agent/StarsectorPrepatcherAgent.jar
jars/StarsectorPrepatcherBootstrap.jar
```

Typed runtime находится в исходниках exact-пакета `source/agent/com/fs/starfarer/api` и попадает в
agent JAR как обычные entries `com/fs/starfarer/api/StarsectorPrepatcher*.class`. Control-код agent
не должен иметь на них статических ссылок: `RuntimeInstaller` читает эти entries как bytes и через
`MethodHandles.Lookup.defineClass()` определяет их в system/game loader. Build завершается ошибкой,
если в JAR нет обязательных top-level entries или exact inventory текущего payload отличается от
108 top-level/nested class entries:

```text
com/fs/starfarer/api/StarsectorPrepatcherHooks.class
com/fs/starfarer/api/StarsectorPrepatcherHyperspaceHooks.class
com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks.class
com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge.class
com/fs/starfarer/api/StarsectorPrepatcherTempModHooks.class
com/fs/starfarer/api/StarsectorPrepatcherStrategicJumpIndex.class
```

В конце сборка атомарно регенерирует `SHA256SUMS.txt` для поставляемых файлов. Сам manifest,
runtime-логи и содержимое `.build/` в него не входят.

## Полная проверка

Windows PowerShell:

```powershell
.\verify-structural.ps1
```

Для явного набора core JAR поддерживаются как именованный, так и прежний positional-вызов:

```powershell
$core = (Resolve-Path '..\..\starsector-core').Path
.\verify-structural.ps1 -CoreJars @(
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'fs.sound_obf.jar'),
    (Join-Path $core 'starfarer.api.jar')
)
```

Linux/macOS:

```bash
./verify-structural.sh
```

Проверка:

1. заново собирает единый agent и bootstrap;
2. компилирует tests;
3. проверяет SemVer/changelog, относительные ссылки, достижимость документации и все SHA-256;
4. трансформирует `starfarer_obf.jar`, `fs.common_obf.jar`, `fs.sound_obf.jar` в памяти;
5. выполняет ASM `Analyzer + BasicVerifier` для concrete methods;
6. проверяет idempotency/ownership/negative structural cases, затем exact-hash safe/aggressive
   presentation profiles, 24 target-класса и цепочку с пятью пересекающимися presentation/structural targets;
7. запускает presentation scheduler/actual-agent smoke, lifecycle, exp6/exp8, unified market
   scheduler, direct-market observation, persistent
   economy, commodity temporal, dormant-industry, temp-mod и loading/save runtime suites;
8. отдельно прогоняет actual-agent smokes commodity/temp-mod/no-op и XStream save, затем проверяет
   локальные structural contracts hyperspace targets из `starfarer.api.jar` и
   `starfarer_obf.jar` той установки, где находится мод — оригинальной либо переводной;
9. проверяет target-loader runtime: состав payload, порядок определения nest members, vanilla
   system-loader path, FR-like split agent/game loaders, fail-open при неверном loader и отсутствие
   cross-loader вызова из `sound.Sound`;
10. запускает собранный javaagent и сохраняет startup smoke.

Structural harness создаёт внутренний `.build/structural-all-enabled.properties` из aggressive
profile и только там включает known-disabled loading/startup patches, direct-market observer и
короткий stats interval для полного structural/telemetry coverage. Этот файл не поставляется игроку и не используется startup smoke; во всех
поставляемых профилях, включая debug, оба проблемных переключателя остаются `false`.

Сырые отчёты `documentation-consistency.txt`, `structural-verification.txt`,
`fast-forward-presentation-structural-plan.txt`,
`fast-forward-presentation-compatibility.txt`, `fast-forward-presentation-runtime.txt`,
`fast-forward-presentation-actual-agent.txt`, `core-worlds-structural-matcher.txt`,
`core-worlds-actual-agent.txt`, `direct-market-transformer.txt`,
`runtime-regression.txt`, `temp-mod-actual-agent-smoke.txt`,
`market-step-replay-actual-agent-smoke.txt`,
`commodity-temporal-agent-smoke.txt`, `market-noop-actual-agent-smoke.txt`,
`temp-mod-xstream-save-smoke.txt`, `hyperspace-verification.txt`, `startup-smoke.txt` и
`faster-rendering-loader-smoke.txt` создаются в `.build/reports/` и намеренно не входят в
документацию или дистрибутив. Если `fr.jar` отсутствует, FR smoke явно получает `SKIPPED`; такой
результат допустим для обычной разработки, но не для выпуска с заявленной FR-совместимостью.
Краткие пользовательские изменения фиксируются в [`CHANGELOG.md`](CHANGELOG.md), а причины,
измерения и остаточные риски — в [`docs/releases/`](docs/releases/0.12.0.md). Обязательные regression
gates для новых pre-load патчей описаны в [`docs/VALIDATION.md`](docs/VALIDATION.md).

## Java 17 compatibility

Scripts используют `-encoding UTF-8 -source 17 -target 17`. Из-за доступа к JDK-internal ASM вместо `--release 17`
применяются compile-time `--add-exports`. Runtime vmparams flags не нужны: startup-agent вызывает
`Instrumentation.redefineModule()` до регистрации transformer.

## Проверка с Faster Rendering

Автоматический FR-like harness обязан подтвердить главное identity-условие:

```text
StarsectorPrepatcherHooks.class.getClassLoader()
    == CampaignEngine.class.getClassLoader()
    == ClassLoader.getSystemClassLoader()
```

Перед выпуском дополнительно нужен реальный Windows-прогон той версии Faster Rendering, с которой
заявляется совместимость. Сначала установите telemetry, затем выполните из каталога мода:

```bat
StarsectorPrepatcher.bat install FasterRendering
```

Запустите `starsector-core\fr.bat`, создайте новую кампанию и пройдите сценарии из
[`docs/VALIDATION.md`](docs/VALIDATION.md). В `logs/prepatcher.log` должны присутствовать сообщение
об установленном target-loader runtime и ожидаемые `APPLIED`/`ALREADY_APPLIED`; `LinkageError`,
`SKIPPED_LOADER` и загрузка payload-класса agent loader'ом являются блокирующими результатами.
Сам build/verify workflow не изменяет `fr.vmparams`; это делает только явный вызов installer.
