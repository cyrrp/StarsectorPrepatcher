param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $CoreJars
)

$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$verifyMutex = [Threading.Mutex]::new(
    $false,
    'Local\StarsectorPrepatcher.verify-structural')
$verifyMutexTaken = $false
try {
try {
    $verifyMutexTaken = $verifyMutex.WaitOne([TimeSpan]::FromMinutes(5))
} catch [Threading.AbandonedMutexException] {
    $verifyMutexTaken = $true
}
if (-not $verifyMutexTaken) {
    throw 'Timed out waiting for another StarsectorPrepatcher verification process.'
}

$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$core = Join-Path $gameRoot 'starsector-core'
$build = Join-Path $modRoot '.build'
$agentClasses = Join-Path $build 'agent-classes'
$testClasses = Join-Path $build 'test-classes'
$frSmokeClasses = Join-Path $build 'fr-smoke-classes'
$reportDir = Join-Path $build 'reports'
$utf8 = New-Object Text.UTF8Encoding($false)
$exports = @(
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED',
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED',
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED'
)

& (Join-Path $modRoot 'build-agent.ps1')
if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force -Path $testClasses, $frSmokeClasses, $reportDir | Out-Null

$testCp = @(
    $agentClasses,
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'fs.sound_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar'),
    (Join-Path $core 'log4j-1.2.9.jar'),
    (Join-Path $core 'xstream-1.4.10.jar'),
    (Join-Path $core 'jaxb-api-2.4.0-b180830.0359.jar'),
    (Join-Path $core 'json.jar')
) -join [IO.Path]::PathSeparator
$frSmokeSource = Join-Path $modRoot `
    'source\test\com\starsector\prepatcher\fr\FasterRenderingLoaderSmokeTest.java'
$testSources = Get-ChildItem -Path (Join-Path $modRoot 'source\test') -Filter '*.java' -Recurse |
    Where-Object { $_.FullName -ne $frSmokeSource } |
    ForEach-Object FullName
& javac -encoding UTF-8 -source 17 -target 17 @exports -cp $testCp -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& javac -encoding UTF-8 -source 17 -target 17 -d $frSmokeClasses $frSmokeSource
if ($LASTEXITCODE -ne 0) { throw 'FR smoke harness compilation failed.' }

$savedErrorActionPreference = $ErrorActionPreference
$documentationReport = Join-Path $reportDir 'documentation-consistency.txt'
$ErrorActionPreference = 'Continue'
try {
    $documentationOutput = @(& java -cp $testClasses com.starsector.prepatcher.docs.DocumentationConsistencyTest $modRoot 2>&1)
    $documentationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$documentationLines = @($documentationOutput | ForEach-Object { $_.ToString() })
$documentationLines
[IO.File]::WriteAllLines($documentationReport, [string[]] $documentationLines, $utf8)
if ($documentationExitCode -ne 0) { throw 'Documentation consistency verification failed.' }

if ($CoreJars.Count -gt 0) {
    $selectedCoreJars = $CoreJars
} else {
    $selectedCoreJars = @(
        (Join-Path $core 'starfarer_obf.jar'),
        (Join-Path $core 'fs.common_obf.jar'),
        (Join-Path $core 'fs.sound_obf.jar'),
        (Join-Path $core 'starfarer.api.jar')
    )
}
$classPath = @($agentClasses, $testClasses) -join [IO.Path]::PathSeparator
$structuralReport = Join-Path $reportDir 'structural-verification.txt'
$verificationConfig = Join-Path $build 'structural-all-enabled.properties'
$verificationText = [IO.File]::ReadAllText(
    (Join-Path $modRoot 'profiles\aggressive.properties'),
    [Text.Encoding]::UTF8)
foreach ($key in @('patch.loadingTextReader', 'patch.startupLogAggregation')) {
    $pattern = '(?m)^' + [regex]::Escape($key) + '[ \t]*=[ \t]*false[ \t]*(\r?)$'
    $matches = [regex]::Matches($verificationText, $pattern)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one known-disabled $key=false in aggressive profile."
    }
    $verificationText = [regex]::Replace(
        $verificationText,
        $pattern,
        { param($match) "$key=true" + $match.Groups[1].Value })
}
$verificationText += "`npatch.directMarketObservation=true`nlogging.statsIntervalSeconds=1`n"
[IO.File]::WriteAllText(
    $verificationConfig,
    "# Generated for structural coverage only; never shipped or used by startup smoke.`n" +
        $verificationText,
    $utf8)
$ErrorActionPreference = 'Continue'
try {
    # Windows PowerShell wraps native stderr as ErrorRecord. The structural
    # harness intentionally logs agent diagnostics there, so success must be
    # decided from the native exit code rather than ErrorActionPreference=Stop.
    $structuralOutput = @(& java @exports -cp $classPath com.starsector.prepatcher.agent.StructuralCompatibilityTest $verificationConfig @selectedCoreJars 2>&1)
    $structuralExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$structuralLines = @($structuralOutput | ForEach-Object { $_.ToString() })
$structuralLines
[IO.File]::WriteAllLines($structuralReport, [string[]] $structuralLines, $utf8)
if ($structuralExitCode -ne 0) { throw 'Structural compatibility verification failed.' }

$coreWorldsStructuralReport = Join-Path $reportDir 'core-worlds-structural-matcher.txt'
$ErrorActionPreference = 'Continue'
try {
    $coreWorldsStructuralOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.CoreWorldsStructuralMatcherTest `
        $verificationConfig (Join-Path $core 'starfarer.api.jar') `
        (Join-Path $core 'starfarer_obf.jar') 2>&1)
    $coreWorldsStructuralExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$coreWorldsStructuralLines = @(
    $coreWorldsStructuralOutput | ForEach-Object { $_.ToString() })
$coreWorldsStructuralLines
[IO.File]::WriteAllLines(
    $coreWorldsStructuralReport,
    [string[]] $coreWorldsStructuralLines,
    $utf8)
if ($coreWorldsStructuralExitCode -ne 0) {
    throw 'Core-worlds structural matcher verification failed.'
}

$nexJar = if ([string]::IsNullOrWhiteSpace($env:NEXERELIN_JAR)) {
    Join-Path $gameRoot 'mods\Nexerelin\jars\ExerelinCore.jar'
} else {
    $env:NEXERELIN_JAR
}
$nexAvailable = Test-Path -LiteralPath $nexJar -PathType Leaf
$aotdJar = if ([string]::IsNullOrWhiteSpace($env:AOTD_TOOLBOX_JAR)) {
    Join-Path $gameRoot 'mods\AoTD-Theory-Of-Toolbox-Scheduler-Fork\jars\AoTDToolboxTheory.jar'
} else {
    $env:AOTD_TOOLBOX_JAR
}
$aotdAvailable = Test-Path -LiteralPath $aotdJar -PathType Leaf
$marketShareOptionalReport =
    Join-Path $reportDir 'market-share-optional-nex.txt'
if ($nexAvailable) {
    $ErrorActionPreference = 'Continue'
    try {
        $marketShareOptionalOutput = @(& java @exports -cp $classPath `
            com.starsector.prepatcher.agent.MarketShareOptionalCompatibilityTest `
            $nexJar 2>&1)
        $marketShareOptionalExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $marketShareOptionalLines = @(
        $marketShareOptionalOutput | ForEach-Object { $_.ToString() })
    $marketShareOptionalLines
    [IO.File]::WriteAllLines(
        $marketShareOptionalReport,
        [string[]] $marketShareOptionalLines,
        $utf8)
    if ($marketShareOptionalExitCode -ne 0) {
        throw 'Optional Nexerelin market-share compatibility verification failed.'
    }
} else {
    $marketShareOptionalLines = @(
        "SKIPPED optional Nexerelin market-share compatibility: $nexJar not found")
    $marketShareOptionalLines
    [IO.File]::WriteAllLines(
        $marketShareOptionalReport,
        [string[]] $marketShareOptionalLines,
        $utf8)
}

$marketShareLoadArgs = @(
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'starfarer.api.jar')
)
if ($nexAvailable) {
    $marketShareLoadArgs += $nexJar
}
$marketShareClassLoadReport =
    Join-Path $reportDir 'market-share-class-load.txt'
$ErrorActionPreference = 'Continue'
try {
    $marketShareClassLoadCp =
        @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    $marketShareClassLoadOutput = @(& java @exports -Xverify:all `
        -cp $marketShareClassLoadCp `
        com.starsector.prepatcher.agent.MarketShareClassLoadSmokeTest `
        @marketShareLoadArgs 2>&1)
    $marketShareClassLoadExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketShareClassLoadLines = @(
    $marketShareClassLoadOutput | ForEach-Object { $_.ToString() })
$marketShareClassLoadLines
[IO.File]::WriteAllLines(
    $marketShareClassLoadReport,
    [string[]] $marketShareClassLoadLines,
    $utf8)
if ($marketShareClassLoadExitCode -ne 0) {
    throw 'Market-share class-loading verification failed.'
}

$marketShareAlgorithmReport =
    Join-Path $reportDir 'market-share-algorithm-differential.txt'
$ErrorActionPreference = 'Continue'
try {
    $marketShareAlgorithmOutput = @(& java -cp $testClasses `
        com.starsector.prepatcher.agent.MarketShareAlgorithmDifferentialTest 2>&1)
    $marketShareAlgorithmExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketShareAlgorithmLines = @(
    $marketShareAlgorithmOutput | ForEach-Object { $_.ToString() })
$marketShareAlgorithmLines
[IO.File]::WriteAllLines(
    $marketShareAlgorithmReport,
    [string[]] $marketShareAlgorithmLines,
    $utf8)
if ($marketShareAlgorithmExitCode -ne 0) {
    throw 'Market-share algorithm differential verification failed.'
}

$marketShareAoTDReport = Join-Path $reportDir 'market-share-aotd-fork.txt'
if ($aotdAvailable) {
    $marketShareAoTDCp =
        @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    $ErrorActionPreference = 'Continue'
    try {
        $marketShareAoTDOutput = @(& java @exports -cp $marketShareAoTDCp `
            com.starsector.prepatcher.agent.MarketShareAoTDForkCompatibilityTest `
            $aotdJar 2>&1)
        $marketShareAoTDExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $marketShareAoTDLines = @(
        $marketShareAoTDOutput | ForEach-Object { $_.ToString() })
    $marketShareAoTDLines
    [IO.File]::WriteAllLines(
        $marketShareAoTDReport,
        [string[]] $marketShareAoTDLines,
        $utf8)
    if ($marketShareAoTDExitCode -ne 0) {
        throw 'AoTD market-share compatibility verification failed.'
    }
} else {
    $marketShareAoTDLines = @(
        "SKIPPED AoTD market-share compatibility: $aotdJar not found")
    $marketShareAoTDLines
    [IO.File]::WriteAllLines(
        $marketShareAoTDReport,
        [string[]] $marketShareAoTDLines,
        $utf8)
}

$economyHotpathAoTDReport = Join-Path $reportDir 'economy-hotpath-aotd-fork.txt'
$aotdForkOwnedTransformerReport =
    Join-Path $reportDir 'aotd-fork-owned-transformer.txt'
$aotdSchedulerBridgeReport = Join-Path $reportDir 'aotd-scheduler-bridge.txt'
$aotdMarketOpenContextReport = Join-Path $reportDir 'aotd-market-open-context.txt'
$vanillaDetachedCargoContractReport =
    Join-Path $reportDir 'vanilla-detached-cargo-economy-contract.txt'
$vanillaMarketOpenLocalizationContractReport =
    Join-Path $reportDir 'vanilla-market-open-localization-contract.txt'
$commodityMarketDataContractReport =
    Join-Path $reportDir 'commodity-market-data-contract.txt'
$marketOverviewMutationTransformerReport =
    Join-Path $reportDir 'market-overview-mutation-transformer.txt'
$tradeMarketMutationTransformerReport =
    Join-Path $reportDir 'trade-market-mutation-transformer.txt'
$industryMarketMutationTransformerReport =
    Join-Path $reportDir 'industry-market-mutation-transformer.txt'
$marketMutationContextRuntimeReport =
    Join-Path $reportDir 'market-mutation-context-runtime.txt'
$marketMutationFailOpenRuntimeReport =
    Join-Path $reportDir 'market-mutation-fail-open-runtime.txt'
$tradeMutationPreparationRuntimeReport =
    Join-Path $reportDir 'trade-mutation-preparation-runtime.txt'
$industryMutationRuntimeReport =
    Join-Path $reportDir 'industry-mutation-runtime.txt'
$aotdDetachedCargoContextReport =
    Join-Path $reportDir 'aotd-detached-cargo-context.txt'
$uiEconomyScenarioReport = Join-Path $reportDir 'ui-economy-scenario-contract.txt'
$readOnlyUiEconomyReport = Join-Path $reportDir 'read-only-ui-economy-step.txt'
$aotdEconomyRestoreReport =
    Join-Path $reportDir 'aotd-economy-restore-completion.txt'
$aotdEconomyRestoreRuntimeReport =
    Join-Path $reportDir 'aotd-economy-restore-runtime.txt'
$aotdUiEconomyBehaviorReport = Join-Path $reportDir 'aotd-ui-economy-behavior.txt'
$aotdMarkerScannerReport = Join-Path $reportDir 'aotd-marker-scanner.txt'

$ErrorActionPreference = 'Continue'
try {
    $aotdMarketOpenContextOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.AoTDMarketOpenContextTransformerTest `
        (Join-Path $core 'starfarer_obf.jar') $verificationConfig 2>&1)
    $aotdMarketOpenContextExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$aotdMarketOpenContextLines = @(
    $aotdMarketOpenContextOutput | ForEach-Object { $_.ToString() })
$aotdMarketOpenContextLines
[IO.File]::WriteAllLines(
    $aotdMarketOpenContextReport,
    [string[]] $aotdMarketOpenContextLines,
    $utf8)
if ($aotdMarketOpenContextExitCode -ne 0) {
    throw 'AoTD market-open context verification failed.'
}

foreach ($case in @(
    @('com.starsector.prepatcher.agent.VanillaDetachedCargoEconomyContractTransformerTest',
      $vanillaDetachedCargoContractReport,
      (Join-Path $core 'starfarer_obf.jar')),
    @('com.starsector.prepatcher.agent.VanillaMarketOpenLocalizationContractTransformerTest',
      $vanillaMarketOpenLocalizationContractReport,
      (Join-Path $core 'starfarer_obf.jar')),
    @('com.starsector.prepatcher.agent.CommodityMarketDataContractTransformerTest',
      $commodityMarketDataContractReport,
      (Join-Path $core 'starfarer_obf.jar')),
    @('com.starsector.prepatcher.agent.MarketOverviewMutationTransformerTest',
      $marketOverviewMutationTransformerReport,
      (Join-Path $core 'starfarer_obf.jar')),
    @('com.starsector.prepatcher.agent.TradeMarketMutationTransformerTest',
      $tradeMarketMutationTransformerReport,
      (Join-Path $core 'starfarer_obf.jar')),
    @('com.starsector.prepatcher.agent.IndustryMarketMutationTransformerTest',
      $industryMarketMutationTransformerReport,
      (Join-Path $core 'starfarer_obf.jar')),
    @('com.starsector.prepatcher.agent.AoTDDetachedCargoContextTransformerTest',
      $aotdDetachedCargoContextReport,
      (Join-Path $core 'starfarer_obf.jar'))
)) {
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& java @exports -cp $classPath $case[0] $case[2] 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $lines = @($output | ForEach-Object { $_.ToString() })
    $lines
    [IO.File]::WriteAllLines($case[1], [string[]] $lines, $utf8)
    if ($exitCode -ne 0) {
        throw "Detached-Cargo compatibility test failed: $($case[0])"
    }
}

$marketMutationContextCp = @($testClasses, $testCp) -join [IO.Path]::PathSeparator
$ErrorActionPreference = 'Continue'
try {
    $marketMutationContextOutput = @(& java @exports -cp $marketMutationContextCp `
        com.fs.starfarer.api.UiMarketMutationContextRuntimeTest 2>&1)
    $marketMutationContextExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketMutationContextLines = @(
    $marketMutationContextOutput | ForEach-Object { $_.ToString() })
$marketMutationContextLines
[IO.File]::WriteAllLines(
    $marketMutationContextRuntimeReport,
    [string[]] $marketMutationContextLines,
    $utf8)
if ($marketMutationContextExitCode -ne 0) {
    throw 'UI market-mutation context runtime verification failed.'
}

$ErrorActionPreference = 'Continue'
try {
    $marketMutationFailOpenOutput = @(& java @exports -cp $marketMutationContextCp `
        com.fs.starfarer.api.UiMarketMutationFailOpenRuntimeTest 2>&1)
    $marketMutationFailOpenExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketMutationFailOpenLines = @(
    $marketMutationFailOpenOutput | ForEach-Object { $_.ToString() })
$marketMutationFailOpenLines
[IO.File]::WriteAllLines(
    $marketMutationFailOpenRuntimeReport,
    [string[]] $marketMutationFailOpenLines,
    $utf8)
if ($marketMutationFailOpenExitCode -ne 0) {
    throw 'UI market-mutation fail-open runtime verification failed.'
}

$ErrorActionPreference = 'Continue'
try {
    $tradeMutationPreparationOutput = @(& java @exports -cp $marketMutationContextCp `
        com.fs.starfarer.api.TradeMutationPreparationRuntimeTest 2>&1)
    $tradeMutationPreparationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$tradeMutationPreparationLines = @(
    $tradeMutationPreparationOutput | ForEach-Object { $_.ToString() })
$tradeMutationPreparationLines
[IO.File]::WriteAllLines(
    $tradeMutationPreparationRuntimeReport,
    [string[]] $tradeMutationPreparationLines,
    $utf8)
if ($tradeMutationPreparationExitCode -ne 0) {
    throw 'Trade-mutation preparation runtime verification failed.'
}

$ErrorActionPreference = 'Continue'
try {
    $industryMutationRuntimeOutput = @(& java @exports -cp $marketMutationContextCp `
        com.fs.starfarer.api.IndustryMutationRuntimeTest 2>&1)
    $industryMutationRuntimeExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$industryMutationRuntimeLines = @(
    $industryMutationRuntimeOutput | ForEach-Object { $_.ToString() })
$industryMutationRuntimeLines
[IO.File]::WriteAllLines(
    $industryMutationRuntimeReport,
    [string[]] $industryMutationRuntimeLines,
    $utf8)
if ($industryMutationRuntimeExitCode -ne 0) {
    throw 'Industry-mutation runtime verification failed.'
}

$ErrorActionPreference = 'Continue'
try {
    $uiEconomyScenarioOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.UiEconomyScenarioContractTest `
        (Join-Path $core 'starfarer_obf.jar') `
        (Join-Path $core 'starfarer.api.jar') 2>&1)
    $uiEconomyScenarioExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$uiEconomyScenarioLines = @($uiEconomyScenarioOutput | ForEach-Object { $_.ToString() })
$uiEconomyScenarioLines
[IO.File]::WriteAllLines($uiEconomyScenarioReport, [string[]] $uiEconomyScenarioLines, $utf8)
if ($uiEconomyScenarioExitCode -ne 0) {
    throw 'UI economy scenario contract verification failed.'
}

$ErrorActionPreference = 'Continue'
$readOnlyUiEconomyArgs = @(
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'starfarer.api.jar'))
if ($nexAvailable) { $readOnlyUiEconomyArgs += $nexJar }
try {
    $readOnlyUiEconomyOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.ReadOnlyUiEconomyStepTransformerTest `
        @readOnlyUiEconomyArgs 2>&1)
    $readOnlyUiEconomyExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$readOnlyUiEconomyLines = @(
    $readOnlyUiEconomyOutput | ForEach-Object { $_.ToString() })
$readOnlyUiEconomyLines
[IO.File]::WriteAllLines(
    $readOnlyUiEconomyReport, [string[]] $readOnlyUiEconomyLines, $utf8)
if ($readOnlyUiEconomyExitCode -ne 0) {
    throw 'Read-only UI economy-step verification failed.'
}

$ErrorActionPreference = 'Continue'
try {
    $aotdEconomyRestoreOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.AoTDEconomyRestoreCompletionTransformerTest `
        (Join-Path $core 'starfarer.api.jar') 2>&1)
    $aotdEconomyRestoreExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$aotdEconomyRestoreLines = @(
    $aotdEconomyRestoreOutput | ForEach-Object { $_.ToString() })
$aotdEconomyRestoreLines
[IO.File]::WriteAllLines(
    $aotdEconomyRestoreReport, [string[]] $aotdEconomyRestoreLines, $utf8)
if ($aotdEconomyRestoreExitCode -ne 0) {
    throw 'AoTD economy-restore completion verification failed.'
}

if ($aotdAvailable) {
    $aotdTestCp = @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    foreach ($case in @(
        @('com.starsector.prepatcher.agent.EconomyHotpathAoTDForkCompatibilityTest',
          $economyHotpathAoTDReport, $aotdJar),
        @('com.starsector.prepatcher.agent.AoTDForkCompatibilityTransformerTest',
          $aotdForkOwnedTransformerReport, $aotdJar),
        @('com.starsector.prepatcher.agent.AoTDSchedulerBridgeTransformerTest',
          $aotdSchedulerBridgeReport, $aotdJar),
        @('com.starsector.prepatcher.agent.AoTDUIEconomyBehaviorCompatibilityTest',
          $aotdUiEconomyBehaviorReport, $aotdJar),
        @('com.starsector.prepatcher.agent.AoTDForkMarkerScannerTest',
          $aotdMarkerScannerReport,
          (Split-Path -Parent (Split-Path -Parent $aotdJar)))
    )) {
        $ErrorActionPreference = 'Continue'
        try {
            $output = @(& java @exports -cp $aotdTestCp $case[0] $case[2] 2>&1)
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $savedErrorActionPreference
        }
        $lines = @($output | ForEach-Object { $_.ToString() })
        $lines
        [IO.File]::WriteAllLines($case[1], [string[]] $lines, $utf8)
        if ($exitCode -ne 0) { throw "AoTD compatibility test failed: $($case[0])" }
    }
} else {
    foreach ($entry in @(
        @($economyHotpathAoTDReport, 'economy-hotpath compatibility'),
        @($aotdForkOwnedTransformerReport, 'fork-owned transformer compatibility'),
        @($aotdSchedulerBridgeReport, 'scheduler bridge'),
        @($aotdUiEconomyBehaviorReport, 'UI economy behavior'),
        @($aotdMarkerScannerReport, 'marker scanner')
    )) {
        $lines = @("SKIPPED AoTD $($entry[1]): $aotdJar not found")
        $lines
        [IO.File]::WriteAllLines($entry[0], [string[]] $lines, $utf8)
    }
}

$strategicJumpReport =
    Join-Path $reportDir 'strategic-jump-destination-first.txt'
$ErrorActionPreference = 'Continue'
try {
    $strategicJumpOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.StrategicJumpDestinationFirstTransformerTest `
        $verificationConfig (Join-Path $core 'starfarer_obf.jar') 2>&1)
    $strategicJumpExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$strategicJumpLines = @(
    $strategicJumpOutput | ForEach-Object { $_.ToString() })
$strategicJumpLines
[IO.File]::WriteAllLines(
    $strategicJumpReport,
    [string[]] $strategicJumpLines,
    $utf8)
if ($strategicJumpExitCode -ne 0) {
    throw 'Strategic jump destination-first verification failed.'
}

$strategicJumpIndexReport = Join-Path $reportDir 'strategic-jump-destination-index.txt'
$ErrorActionPreference = 'Continue'
try {
    $strategicJumpIndexOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.StrategicJumpDestinationIndexTransformerTest `
        $verificationConfig (Join-Path $core 'starfarer_obf.jar') `
        (Join-Path $core 'starfarer.api.jar') 2>&1)
    $strategicJumpIndexExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$strategicJumpIndexLines = @(
    $strategicJumpIndexOutput | ForEach-Object { $_.ToString() })
$strategicJumpIndexLines
[IO.File]::WriteAllLines(
    $strategicJumpIndexReport,
    [string[]] $strategicJumpIndexLines,
    $utf8)
if ($strategicJumpIndexExitCode -ne 0) {
    throw 'Strategic jump destination index verification failed.'
}

$presentationStructuralPlanReport =
    Join-Path $reportDir 'fast-forward-presentation-structural-plan.txt'
$ErrorActionPreference = 'Continue'
try {
    $presentationStructuralPlanOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.FastForwardPresentationStructuralPlanTest 2>&1)
    $presentationStructuralPlanExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$presentationStructuralPlanLines = @(
    $presentationStructuralPlanOutput | ForEach-Object { $_.ToString() })
$presentationStructuralPlanLines
[IO.File]::WriteAllLines(
    $presentationStructuralPlanReport,
    [string[]] $presentationStructuralPlanLines,
    $utf8)
if ($presentationStructuralPlanExitCode -ne 0) {
    throw 'Fast-forward presentation structural-plan verification failed.'
}

$presentationCompatibilityReport = Join-Path $reportDir 'fast-forward-presentation-compatibility.txt'
$ErrorActionPreference = 'Continue'
try {
    $presentationCompatibilityOutput = @(& java @exports -cp $classPath `
        com.starsector.prepatcher.agent.FastForwardPresentationCompatibilityTest `
        (Join-Path $core 'starfarer_obf.jar') `
        (Join-Path $core 'starfarer.api.jar') 2>&1)
    $presentationCompatibilityExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$presentationCompatibilityLines = @(
    $presentationCompatibilityOutput | ForEach-Object { $_.ToString() })
$presentationCompatibilityLines
[IO.File]::WriteAllLines(
    $presentationCompatibilityReport, [string[]] $presentationCompatibilityLines, $utf8)
if ($presentationCompatibilityExitCode -ne 0) {
    throw 'Fast-forward presentation compatibility verification failed.'
}

$directMarketTransformerReport = Join-Path $reportDir 'direct-market-transformer.txt'
$ErrorActionPreference = 'Continue'
try {
    $directMarketTransformerCp = @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    $directMarketTransformerOutput = @(& java @exports -cp $directMarketTransformerCp `
        com.starsector.prepatcher.agent.DirectMarketObserveTransformerTest 2>&1)
    $directMarketTransformerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$directMarketTransformerLines = @(
    $directMarketTransformerOutput | ForEach-Object { $_.ToString() })
$directMarketTransformerLines
[IO.File]::WriteAllLines(
    $directMarketTransformerReport, [string[]] $directMarketTransformerLines, $utf8)
if ($directMarketTransformerExitCode -ne 0) {
    throw 'Direct Market.advance transformer verification failed.'
}

$runtimeCp = @(
    $testClasses,
    (Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'),
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'fs.sound_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar'),
    (Join-Path $core 'xstream-1.4.10.jar'),
    (Join-Path $core 'jaxb-api-2.4.0-b180830.0359.jar'),
    (Join-Path $core 'json.jar')
) -join [IO.Path]::PathSeparator
$runtimeReport = Join-Path $reportDir 'runtime-regression.txt'
$ErrorActionPreference = 'Continue'
try {
    $aotdEconomyRestoreRuntimeOutput = @(& java -cp $runtimeCp `
        com.starsector.prepatcher.runtime.AoTDEconomyRestoreRuntimeTest 2>&1)
    $aotdEconomyRestoreRuntimeExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$aotdEconomyRestoreRuntimeLines = @(
    $aotdEconomyRestoreRuntimeOutput | ForEach-Object { $_.ToString() })
$aotdEconomyRestoreRuntimeLines
[IO.File]::WriteAllLines(
    $aotdEconomyRestoreRuntimeReport,
    [string[]] $aotdEconomyRestoreRuntimeLines,
    $utf8)
if ($aotdEconomyRestoreRuntimeExitCode -ne 0) {
    throw 'AoTD economy-restore callback runtime verification failed.'
}

$runtimeLines = [System.Collections.Generic.List[string]]::new()
foreach ($test in @(
    'com.starsector.prepatcher.runtime.LifecycleGcRegressionTest',
    'com.starsector.prepatcher.runtime.CacheMaintenanceRuntimeTest',
    'com.starsector.prepatcher.runtime.CoreWorldsRuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.Exp6RuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.Exp8RuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.MarketSchedulerRuntimeTest',
    'com.starsector.prepatcher.runtime.DirectMarketObservationRuntimeTest',
    'com.starsector.prepatcher.runtime.PersistentEconomyRuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.MarketNoOpRuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.TempModExpiryRuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.LoadingSaveRuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.AoTDDomainRevisionRuntimeTest',
    'com.starsector.prepatcher.runtime.AoTDDeliveryListenerFailStopTest',
    'com.starsector.prepatcher.runtime.AoTDCurrentContractNegotiationTest',
    'com.starsector.prepatcher.runtime.AoTDExplicitUiEconomyDispatchRuntimeTest',
    'com.starsector.prepatcher.runtime.AoTDScriptLoaderUiDispatchRuntimeTest',
    'com.starsector.prepatcher.runtime.AoTDDetachedCargoContextRuntimeTest',
    'com.starsector.prepatcher.runtime.ConditionOnlyMarketOpenRuntimeTest',
    'com.fs.starfarer.api.VanillaMarketOpenCoalescingRuntimeTest',
    'com.fs.starfarer.api.EconomyHotpathRuntimeTest',
    'com.fs.starfarer.api.StrategicJumpDestinationIndexRuntimeTest'
)) {
    $runtimeLines.Add("== $test ==")
    $ErrorActionPreference = 'Continue'
    try {
        $out = @(& java -cp $runtimeCp $test 2>&1)
        $runtimeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $runtimeLines.AddRange([string[]] $out)
    if ($runtimeExitCode -ne 0) {
        [IO.File]::WriteAllLines($runtimeReport, $runtimeLines, $utf8)
        throw "Runtime test failed: $test"
    }
}
$runtimeLines
[IO.File]::WriteAllLines($runtimeReport, $runtimeLines, $utf8)

$semanticBaselineReport = Join-Path $reportDir 'aotd-semantic-baseline.txt'
$ErrorActionPreference = 'Continue'
try {
    $semanticBaselineOutput = @(& java -cp $runtimeCp `
        com.starsector.prepatcher.aotd.AoTDSemanticBaselineTest `
        (Join-Path $modRoot 'baseline\aotd\deficit-scenarios.csv') 2>&1)
    $semanticBaselineExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$semanticBaselineLines = @($semanticBaselineOutput | ForEach-Object { $_.ToString() })
$semanticBaselineLines
[IO.File]::WriteAllLines(
    $semanticBaselineReport, [string[]] $semanticBaselineLines, $utf8)
if ($semanticBaselineExitCode -ne 0) { throw 'AoTD semantic baseline failed.' }

$presentationRuntimeReport = Join-Path $reportDir 'fast-forward-presentation-runtime.txt'
$presentationRuntimeLines = [System.Collections.Generic.List[string]]::new()
foreach ($test in @(
    'com.fs.starfarer.api.FastForwardPresentationRuntimeTest',
    'com.starsector.prepatcher.agent.FastForwardPresentationLoadedTargetPolicyTest'
)) {
    $presentationRuntimeLines.Add("== $test ==")
    $ErrorActionPreference = 'Continue'
    try {
        $presentationRuntimeOutput = @(& java -noverify -cp $runtimeCp $test 2>&1)
        $presentationRuntimeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $presentationRuntimeLines.AddRange([string[]] @(
        $presentationRuntimeOutput | ForEach-Object { $_.ToString() }))
    if ($presentationRuntimeExitCode -ne 0) {
        [IO.File]::WriteAllLines(
            $presentationRuntimeReport, $presentationRuntimeLines, $utf8)
        throw "Fast-forward presentation runtime test failed: $test"
    }
}
$presentationRuntimeLines
[IO.File]::WriteAllLines(
    $presentationRuntimeReport, $presentationRuntimeLines, $utf8)

$startupAuditReport = Join-Path $reportDir 'startup-audit-coverage.txt'
$ErrorActionPreference = 'Continue'
try {
    $startupAuditOutput = @(& java @exports -cp $runtimeCp `
        com.starsector.prepatcher.agent.StartupAuditCoverageTest 2>&1)
    $startupAuditExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$startupAuditLines = @($startupAuditOutput | ForEach-Object { $_.ToString() })
$startupAuditLines
[IO.File]::WriteAllLines($startupAuditReport, [string[]] $startupAuditLines, $utf8)
if ($startupAuditExitCode -ne 0) { throw 'Startup audit coverage test failed.' }

$mainAgentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
$aotdEconomyRestoreActualAgentCases = @(
    @('core-first',
      (Join-Path $reportDir 'aotd-economy-restore-actual-agent-core-first.txt')),
    @('fork-first',
      (Join-Path $reportDir 'aotd-economy-restore-actual-agent-fork-first.txt')))
if ($aotdAvailable) {
    $aotdEconomyRestoreActualAgentCp =
        @($runtimeCp, $aotdJar) -join [IO.Path]::PathSeparator
    foreach ($restoreCase in $aotdEconomyRestoreActualAgentCases) {
        $ErrorActionPreference = 'Continue'
        try {
            $aotdEconomyRestoreActualAgentOutput = @(& java `
                "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
                -cp $aotdEconomyRestoreActualAgentCp `
                com.starsector.prepatcher.runtime.AoTDEconomyRestoreActualAgentSmokeTest `
                $restoreCase[0] 2>&1)
            $aotdEconomyRestoreActualAgentExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $savedErrorActionPreference
        }
        $aotdEconomyRestoreActualAgentLines = @(
            $aotdEconomyRestoreActualAgentOutput | ForEach-Object { $_.ToString() })
        $aotdEconomyRestoreActualAgentLines
        [IO.File]::WriteAllLines(
            $restoreCase[1],
            [string[]] $aotdEconomyRestoreActualAgentLines,
            $utf8)
        if ($aotdEconomyRestoreActualAgentExitCode -ne 0) {
            throw "AoTD economy-restore actual-agent smoke failed: $($restoreCase[0])"
        }
    }
} else {
    foreach ($restoreCase in $aotdEconomyRestoreActualAgentCases) {
        $aotdEconomyRestoreActualAgentLines = @(
            "SKIPPED AoTD economy-restore $($restoreCase[0]) actual-agent smoke: "
                    + "$aotdJar not found")
        $aotdEconomyRestoreActualAgentLines
        [IO.File]::WriteAllLines(
            $restoreCase[1],
            [string[]] $aotdEconomyRestoreActualAgentLines,
            $utf8)
    }
}

$aotdSupplyDemandXStreamReport =
    Join-Path $reportDir 'aotd-supply-demand-xstream-migration.txt'
if ($aotdAvailable) {
    $ErrorActionPreference = 'Continue'
    try {
        $aotdSupplyDemandXStreamOutput = @(& java `
            '--add-opens=java.base/java.util=ALL-UNNAMED' `
            '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED' `
            '--add-opens=java.base/java.text=ALL-UNNAMED' `
            '--add-opens=java.desktop/java.awt.font=ALL-UNNAMED' `
            '--add-opens=java.desktop/java.awt=ALL-UNNAMED' `
            '-Dstarsector.prepatcher.sessionOrigin=aotd-supply-demand-xstream' `
            "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
            -cp $aotdEconomyRestoreActualAgentCp `
            com.starsector.prepatcher.runtime.AoTDSupplyDemandXStreamMigrationTest `
            2>&1)
        $aotdSupplyDemandXStreamExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $aotdSupplyDemandXStreamLines = @(
        $aotdSupplyDemandXStreamOutput | ForEach-Object { $_.ToString() })
    $aotdSupplyDemandXStreamLines
    [IO.File]::WriteAllLines(
        $aotdSupplyDemandXStreamReport,
        [string[]] $aotdSupplyDemandXStreamLines,
        $utf8)
    if ($aotdSupplyDemandXStreamExitCode -ne 0) {
        throw 'AoTD supply/demand XStream migration verification failed.'
    }
} else {
    $aotdSupplyDemandXStreamLines = @(
        "SKIPPED AoTD supply/demand XStream migration: $aotdJar not found")
    $aotdSupplyDemandXStreamLines
    [IO.File]::WriteAllLines(
        $aotdSupplyDemandXStreamReport,
        [string[]] $aotdSupplyDemandXStreamLines,
        $utf8)
}

$aotdRuntimeTaskXStreamReport =
    Join-Path $reportDir 'aotd-runtime-task-xstream-sanitization.txt'
if ($aotdAvailable) {
    $ErrorActionPreference = 'Continue'
    try {
        $aotdRuntimeTaskXStreamOutput = @(& java `
            '--add-opens=java.base/java.util=ALL-UNNAMED' `
            '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED' `
            '--add-opens=java.base/java.text=ALL-UNNAMED' `
            '--add-opens=java.desktop/java.awt.font=ALL-UNNAMED' `
            '--add-opens=java.desktop/java.awt=ALL-UNNAMED' `
            '-Dstarsector.prepatcher.sessionOrigin=aotd-runtime-task-xstream' `
            "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
            -cp $aotdEconomyRestoreActualAgentCp `
            com.starsector.prepatcher.runtime.AoTDRuntimeTaskXStreamSanitizationTest `
            2>&1)
        $aotdRuntimeTaskXStreamExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $aotdRuntimeTaskXStreamLines = @(
        $aotdRuntimeTaskXStreamOutput | ForEach-Object { $_.ToString() })
    $aotdRuntimeTaskXStreamLines
    [IO.File]::WriteAllLines(
        $aotdRuntimeTaskXStreamReport,
        [string[]] $aotdRuntimeTaskXStreamLines,
        $utf8)
    if ($aotdRuntimeTaskXStreamExitCode -ne 0) {
        throw 'AoTD runtime-task XStream sanitization verification failed.'
    }
} else {
    $aotdRuntimeTaskXStreamLines = @(
        "SKIPPED AoTD runtime-task XStream sanitization: $aotdJar not found")
    $aotdRuntimeTaskXStreamLines
    [IO.File]::WriteAllLines(
        $aotdRuntimeTaskXStreamReport,
        [string[]] $aotdRuntimeTaskXStreamLines,
        $utf8)
}

$coreWorldsAgentReport = Join-Path $reportDir 'core-worlds-actual-agent.txt'
$ErrorActionPreference = 'Continue'
try {
    $coreWorldsAgentOutput = @(& java `
        "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
        -cp $runtimeCp `
        com.starsector.prepatcher.runtime.CoreWorldsActualAgentSmokeTest 2>&1)
    $coreWorldsAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$coreWorldsAgentLines = @($coreWorldsAgentOutput | ForEach-Object { $_.ToString() })
$coreWorldsAgentLines
[IO.File]::WriteAllLines(
    $coreWorldsAgentReport, [string[]] $coreWorldsAgentLines, $utf8)
if ($coreWorldsAgentExitCode -ne 0) {
    throw 'Core-worlds actual-agent smoke failed.'
}

$vanillaDetachedCargoAgentReport =
    Join-Path $reportDir 'vanilla-detached-cargo-actual-agent.txt'
$ErrorActionPreference = 'Continue'
try {
    $vanillaDetachedCargoAgentOutput = @(& java `
        "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
        -cp $runtimeCp `
        com.starsector.prepatcher.runtime.VanillaDetachedCargoContractActualAgentSmokeTest `
        2>&1)
    $vanillaDetachedCargoAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$vanillaDetachedCargoAgentLines = @(
    $vanillaDetachedCargoAgentOutput | ForEach-Object { $_.ToString() })
$vanillaDetachedCargoAgentLines
[IO.File]::WriteAllLines(
    $vanillaDetachedCargoAgentReport,
    [string[]] $vanillaDetachedCargoAgentLines,
    $utf8)
if ($vanillaDetachedCargoAgentExitCode -ne 0) {
    throw 'Vanilla detached-Cargo actual-agent smoke failed.'
}

$uiEconomyAgentReport = Join-Path $reportDir 'ui-economy-actual-agent.txt'
$ErrorActionPreference = 'Continue'
$uiEconomyAgentArgs = @()
if ($nexAvailable) { $uiEconomyAgentArgs += $nexJar }
try {
    $uiEconomyAgentOutput = @(& java `
        "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
        -cp $runtimeCp `
        com.starsector.prepatcher.runtime.UiEconomyActualAgentSmokeTest `
        @uiEconomyAgentArgs 2>&1)
    $uiEconomyAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$uiEconomyAgentLines = @($uiEconomyAgentOutput | ForEach-Object { $_.ToString() })
$uiEconomyAgentLines
[IO.File]::WriteAllLines($uiEconomyAgentReport, [string[]] $uiEconomyAgentLines, $utf8)
if ($uiEconomyAgentExitCode -ne 0) {
    throw 'UI economy actual-agent smoke failed.'
}

$presentationAgentReport = Join-Path $reportDir 'fast-forward-presentation-actual-agent.txt'
$ErrorActionPreference = 'Continue'
try {
    $presentationAgentOutput = @(& java -noverify `
        "-javaagent:$mainAgentJar=config=$(Join-Path $modRoot 'profiles\aggressive.properties')" `
        -cp $runtimeCp `
        com.starsector.prepatcher.runtime.FastForwardPresentationActualAgentSmokeTest 2>&1)
    $presentationAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$presentationAgentLines = @($presentationAgentOutput | ForEach-Object { $_.ToString() })
$presentationAgentLines
[IO.File]::WriteAllLines(
    $presentationAgentReport, [string[]] $presentationAgentLines, $utf8)
if ($presentationAgentExitCode -ne 0) {
    throw 'Fast-forward presentation actual-agent smoke failed.'
}

$tempModAgentReport = Join-Path $reportDir 'temp-mod-actual-agent-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $tempModAgentOutput = @(& java `
        '-Dstarsector.prepatcher.sessionOrigin=temp-mod-smoke' `
        "-javaagent:$mainAgentJar" -cp $runtimeCp `
        com.starsector.prepatcher.runtime.TempModActualAgentSmokeTest 2>&1)
    $tempModAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$tempModAgentLines = @($tempModAgentOutput | ForEach-Object { $_.ToString() })
$tempModAgentLines
[IO.File]::WriteAllLines($tempModAgentReport, [string[]] $tempModAgentLines, $utf8)
if ($tempModAgentExitCode -ne 0) { throw 'Temp-mod actual-agent smoke failed.' }

$marketStepReplayReport = Join-Path $reportDir 'market-step-replay-actual-agent-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $marketStepReplayOutput = @(& java `
        '-Dstarsector.prepatcher.sessionOrigin=market-step-replay-smoke' `
        "-javaagent:$mainAgentJar" -cp $runtimeCp `
        com.starsector.prepatcher.runtime.MarketStepReplayActualAgentSmokeTest 2>&1)
    $marketStepReplayExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketStepReplayLines = @($marketStepReplayOutput | ForEach-Object { $_.ToString() })
$marketStepReplayLines
[IO.File]::WriteAllLines(
    $marketStepReplayReport, [string[]] $marketStepReplayLines, $utf8)
if ($marketStepReplayExitCode -ne 0) {
    throw 'Market step-replay actual-agent smoke failed.'
}

# Exercise the read-only UI, condition-only opening and localization paths through
# the real javaagent with all unrelated
# bytecode patches disabled. Nexerelin remains an optional target.
$marketShareAgentConfig = Join-Path $build 'market-share-agent-smoke.properties'
$marketShareAgentText = [IO.File]::ReadAllText(
    (Join-Path $modRoot 'prepatcher.properties'))
$marketShareAgentText = [regex]::Replace(
    $marketShareAgentText,
    '(?m)^(patch\.[^=\r\n]+)=.*$',
    { param($match) $match.Groups[1].Value + '=false' })
foreach ($key in @(
    'patch.marketShareLinearAggregation',
    'patch.marketShareDataPutElision',
    'patch.punitivePlayerShareLocalCache',
    'patch.nexPunitivePlayerShareLocalCache'
)) {
    $marketShareAgentText = [regex]::Replace(
        $marketShareAgentText,
        '(?m)^' + [regex]::Escape($key) + '=false$',
        "$key=true")
}
$marketShareAgentText = [regex]::Replace(
    $marketShareAgentText,
    '(?m)^logging\.statsIntervalSeconds=.*$',
    'logging.statsIntervalSeconds=0')
[IO.File]::WriteAllText(
    $marketShareAgentConfig,
    $marketShareAgentText,
    $utf8)

$marketShareAgentCp = $runtimeCp
$marketShareAgentArgs = @()
if ($nexAvailable) {
    $marketShareAgentCp += [IO.Path]::PathSeparator + $nexJar
    $marketShareAgentArgs += 'nex'
}
if ($aotdAvailable) {
    $marketShareAgentCp += [IO.Path]::PathSeparator + $aotdJar
    $marketShareAgentArgs += 'aotd'
}
$marketShareAgentReport =
    Join-Path $reportDir 'market-share-actual-agent-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $marketShareAgentOutput = @(& java -Xverify:all `
        '-Dstarsector.prepatcher.sessionOrigin=market-share-smoke' `
        "-javaagent:$mainAgentJar=config=$marketShareAgentConfig" `
        -cp $marketShareAgentCp `
        com.starsector.prepatcher.runtime.MarketShareActualAgentSmokeTest `
        @marketShareAgentArgs 2>&1)
    $marketShareAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketShareAgentLines = @(
    $marketShareAgentOutput | ForEach-Object { $_.ToString() })
$marketShareAgentLines
[IO.File]::WriteAllLines(
    $marketShareAgentReport,
    [string[]] $marketShareAgentLines,
    $utf8)
if ($marketShareAgentExitCode -ne 0) {
    throw 'Market-share actual-agent smoke failed.'
}

# Exercise Local Resources legality and the owner-local econ-group index with unrelated
# transformations disabled. Include exact AoTDReachEconomy when available.
$economyHotpathAgentConfig = Join-Path $build 'economy-hotpath-agent-smoke.properties'
$economyHotpathAgentText = [IO.File]::ReadAllText(
    (Join-Path $modRoot 'prepatcher.properties'))
$economyHotpathAgentText = [regex]::Replace(
    $economyHotpathAgentText,
    '(?m)^(patch\.[^=\r\n]+)=.*$',
    { param($match) $match.Groups[1].Value + '=false' })
foreach ($key in @(
    'patch.localResourcesNoColdMarketData',
    'patch.localResourcesTooltipSnapshot',
    'patch.economyGroupIndex'
)) {
    $economyHotpathAgentText = [regex]::Replace(
        $economyHotpathAgentText,
        '(?m)^' + [regex]::Escape($key) + '=false$',
        "$key=true")
}
$economyHotpathAgentText = [regex]::Replace(
    $economyHotpathAgentText,
    '(?m)^economy\.structureAuditMs=.*$',
    'economy.structureAuditMs=0')
$economyHotpathAgentText = [regex]::Replace(
    $economyHotpathAgentText,
    '(?m)^logging\.statsIntervalSeconds=.*$',
    'logging.statsIntervalSeconds=0')
[IO.File]::WriteAllText(
    $economyHotpathAgentConfig, $economyHotpathAgentText, $utf8)
$economyHotpathAgentCp = $runtimeCp
$economyHotpathAgentArgs = @()
if ($aotdAvailable) {
    $economyHotpathAgentCp += [IO.Path]::PathSeparator + $aotdJar
    $economyHotpathAgentArgs += 'aotd'
}
$economyHotpathAgentReport =
    Join-Path $reportDir 'economy-hotpath-actual-agent-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $economyHotpathAgentOutput = @(& java -Xverify:all `
        '-Dstarsector.prepatcher.sessionOrigin=economy-hotpath-smoke' `
        "-javaagent:$mainAgentJar=config=$economyHotpathAgentConfig" `
        -cp $economyHotpathAgentCp `
        com.starsector.prepatcher.runtime.EconomyHotpathActualAgentSmokeTest `
        @economyHotpathAgentArgs 2>&1)
    $economyHotpathAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$economyHotpathAgentLines = @(
    $economyHotpathAgentOutput | ForEach-Object { $_.ToString() })
$economyHotpathAgentLines
[IO.File]::WriteAllLines(
    $economyHotpathAgentReport,
    [string[]] $economyHotpathAgentLines,
    $utf8)
if ($economyHotpathAgentExitCode -ne 0) {
    throw 'Economy-hotpath actual-agent smoke failed.'
}

$commoditySmokeConfig = Join-Path $build 'commodity-temporal-agent-smoke.properties'
$commoditySmokeText = [IO.File]::ReadAllText((Join-Path $modRoot 'prepatcher.properties'))
$commoditySmokeText = [regex]::Replace(
    $commoditySmokeText,
    '(?m)^(patch\.[^=\r\n]+)=.*$',
    { param($match) $match.Groups[1].Value + '=false' })
$commoditySmokeText = [regex]::Replace(
    $commoditySmokeText,
    '(?m)^patch\.commodityTemporalFastPath=false$',
    'patch.commodityTemporalFastPath=true')
$commoditySmokeText = [regex]::Replace(
    $commoditySmokeText,
    '(?m)^commodity\.temporalAuditFrames=.*$',
    'commodity.temporalAuditFrames=7')
$commoditySmokeText = [regex]::Replace(
    $commoditySmokeText,
    '(?m)^logging\.statsIntervalSeconds=.*$',
    'logging.statsIntervalSeconds=0')
[IO.File]::WriteAllText($commoditySmokeConfig, $commoditySmokeText, $utf8)

$commodityTemporalAgentReport = Join-Path $reportDir 'commodity-temporal-agent-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $commodityTemporalAgentOutput = @(& java `
        "-javaagent:$mainAgentJar=config=$commoditySmokeConfig" -cp $runtimeCp `
        com.starsector.prepatcher.runtime.CommodityTemporalAgentSmokeTest 2>&1)
    $commodityTemporalAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$commodityTemporalAgentLines = @($commodityTemporalAgentOutput | ForEach-Object { $_.ToString() })
$commodityTemporalAgentLines
[IO.File]::WriteAllLines($commodityTemporalAgentReport, [string[]] $commodityTemporalAgentLines, $utf8)
if ($commodityTemporalAgentExitCode -ne 0) { throw 'Commodity-temporal actual-agent smoke failed.' }

# Exercise the direct dormant BaseIndustry wrapper in isolation. The test
# expects exactly two skipped callbacks between full vanilla audits.
$marketNoOpSmokeConfig = Join-Path $build 'market-noop-agent-smoke.properties'
$marketNoOpSmokeText = [IO.File]::ReadAllText((Join-Path $modRoot 'prepatcher.properties'))
$marketNoOpSmokeText = [regex]::Replace(
    $marketNoOpSmokeText,
    '(?m)^(patch\.[^=\r\n]+)=.*$',
    { param($match) $match.Groups[1].Value + '=false' })
$marketNoOpSmokeText = [regex]::Replace(
    $marketNoOpSmokeText,
    '(?m)^patch\.marketNoOpCallbacks=false$',
    'patch.marketNoOpCallbacks=true')
$marketNoOpSmokeText = [regex]::Replace(
    $marketNoOpSmokeText,
    '(?m)^market\.noOpIndustryAuditFrames=.*$',
    'market.noOpIndustryAuditFrames=2')
$marketNoOpSmokeText = [regex]::Replace(
    $marketNoOpSmokeText,
    '(?m)^logging\.statsIntervalSeconds=.*$',
    'logging.statsIntervalSeconds=0')
[IO.File]::WriteAllText($marketNoOpSmokeConfig, $marketNoOpSmokeText, $utf8)

$marketNoOpAgentReport = Join-Path $reportDir 'market-noop-actual-agent-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $marketNoOpAgentOutput = @(& java `
        "-javaagent:$mainAgentJar=config=$marketNoOpSmokeConfig" -cp $runtimeCp `
        com.starsector.prepatcher.runtime.MarketNoOpActualAgentSmokeTest 2>&1)
    $marketNoOpAgentExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$marketNoOpAgentLines = @($marketNoOpAgentOutput | ForEach-Object { $_.ToString() })
$marketNoOpAgentLines
[IO.File]::WriteAllLines($marketNoOpAgentReport, [string[]] $marketNoOpAgentLines, $utf8)
if ($marketNoOpAgentExitCode -ne 0) { throw 'Market no-op actual-agent smoke failed.' }

$tempModXStreamReport = Join-Path $reportDir 'temp-mod-xstream-save-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $tempModXStreamOutput = @(& java `
        '--add-opens=java.base/java.util=ALL-UNNAMED' `
        '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED' `
        '--add-opens=java.base/java.text=ALL-UNNAMED' `
        '--add-opens=java.desktop/java.awt.font=ALL-UNNAMED' `
        '--add-opens=java.desktop/java.awt=ALL-UNNAMED' `
        '-Dstarsector.prepatcher.sessionOrigin=temp-mod-xstream' `
        "-javaagent:$mainAgentJar" -cp $runtimeCp `
        com.starsector.prepatcher.runtime.TempModXStreamSaveSmokeTest 2>&1)
    $tempModXStreamExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$tempModXStreamLines = @($tempModXStreamOutput | ForEach-Object { $_.ToString() })
$tempModXStreamLines
[IO.File]::WriteAllLines($tempModXStreamReport, [string[]] $tempModXStreamLines, $utf8)
if ($tempModXStreamExitCode -ne 0) { throw 'Temp-mod XStream save smoke failed.' }

$hyperReport = Join-Path $reportDir 'hyperspace-verification.txt'
$ErrorActionPreference = 'Continue'
try {
    $hyperCp = @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    $hyperOutput = @(& java @exports '-Dstarsector.prepatcher.sessionOrigin=structural-hyperspace' -cp $hyperCp com.starsector.prepatcher.agent.HyperspaceCompatibilityTest $verificationConfig (Join-Path $core 'starfarer_obf.jar') (Join-Path $core 'starfarer.api.jar') $hyperReport 2>&1)
    $hyperExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$hyperLines = @($hyperOutput | ForEach-Object { $_.ToString() })
$hyperLines
if ($hyperExitCode -ne 0) { throw 'Hyperspace offline verification failed.' }

$startupReport = Join-Path $reportDir 'startup-smoke.txt'
$ErrorActionPreference = 'Continue'
try {
    $startupOutput = @(& java '-Dstarsector.prepatcher.sessionOrigin=startup-smoke' "-javaagent:$mainAgentJar" -version 2>&1)
    $startupExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$startupLines = @($startupOutput | ForEach-Object { $_.ToString() })
$startupLines
[IO.File]::WriteAllLines($startupReport, [string[]] $startupLines, $utf8)
if ($startupExitCode -ne 0) { throw 'Javaagent startup smoke failed.' }

$frSmokeReport = Join-Path $reportDir 'faster-rendering-loader-smoke.txt'
$frJar = Join-Path $core 'fr.jar'
if (-not (Test-Path -LiteralPath $frJar -PathType Leaf)) {
    $frSmokeLines = @("SKIPPED Faster Rendering loader smoke: fr.jar not found at $frJar")
    $frSmokeLines
    [IO.File]::WriteAllLines($frSmokeReport, [string[]] $frSmokeLines, $utf8)
} else {
    # Keep agent classes out of this classpath. Faster Rendering must place the
    # javaagent in JavaAgentLoader while defining the injected runtime hooks in
    # its custom system loader; adding agent-classes here would hide that split.
    $frSmokeCp = @(
        $frJar,
        $frSmokeClasses,
        (Join-Path $core 'janino.jar'),
        (Join-Path $core 'commons-compiler.jar'),
        (Join-Path $core 'commons-compiler-jdk.jar'),
        (Join-Path $core 'starfarer.api.jar'),
        (Join-Path $core 'starfarer_obf.jar'),
        (Join-Path $core 'jogg-0.0.7.jar'),
        (Join-Path $core 'jorbis-0.0.15.jar'),
        (Join-Path $core 'json.jar'),
        (Join-Path $core 'lwjgl.jar'),
        (Join-Path $core 'jinput.jar'),
        (Join-Path $core 'log4j-1.2.9.jar'),
        (Join-Path $core 'lwjgl_util.jar'),
        (Join-Path $core 'fs.sound_obf.jar'),
        (Join-Path $core 'fs.common_obf.jar'),
        (Join-Path $core 'xstream-1.4.10.jar'),
        (Join-Path $core 'txw2-3.0.2.jar'),
        (Join-Path $core 'jaxb-api-2.4.0-b180830.0359.jar'),
        (Join-Path $core 'webp-imageio-0.1.6.jar')
    ) -join [IO.Path]::PathSeparator
    $ErrorActionPreference = 'Continue'
    try {
        $frSmokeOutput = @(& java `
            '-Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader' `
            '-Dstarsector.prepatcher.sessionOrigin=fr-smoke' `
            "-javaagent:$mainAgentJar=config=$verificationConfig" `
            -cp $frSmokeCp `
            com.starsector.prepatcher.fr.FasterRenderingLoaderSmokeTest `
            $mainAgentJar 2>&1)
        $frSmokeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $frSmokeLines = @($frSmokeOutput | ForEach-Object { $_.ToString() })
    $frSmokeLines
    [IO.File]::WriteAllLines($frSmokeReport, [string[]] $frSmokeLines, $utf8)
    if ($frSmokeExitCode -ne 0) { throw 'Faster Rendering loader smoke failed.' }
}

Write-Host 'Documentation/structural/runtime/hyperspace/startup/FR verification completed.' -ForegroundColor Green
} finally {
    if ($verifyMutexTaken) {
        $verifyMutex.ReleaseMutex()
    }
    $verifyMutex.Dispose()
}
