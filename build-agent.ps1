$ErrorActionPreference = 'Stop'
$modRoot = (Resolve-Path $PSScriptRoot).Path
function Find-GameRoot([string] $startPath) {
    $candidate = [IO.DirectoryInfo]::new($startPath)
    while ($null -ne $candidate) {
        if (Test-Path -LiteralPath (Join-Path $candidate.FullName 'starsector-core') -PathType Container) {
            return $candidate.FullName
        }
        $candidate = $candidate.Parent
    }
    throw "Could not find a Starsector root above $startPath."
}
$gameRoot = Find-GameRoot $modRoot
$core = Join-Path $gameRoot 'starsector-core'
$build = Join-Path $modRoot '.build'
$agentRawClasses = Join-Path $build 'agent-raw-classes'
$asmRawClasses = Join-Path $build 'asm-raw-classes'
$agentClasses = Join-Path $build 'agent-classes'
$buildClasses = Join-Path $build 'build-classes'
$bootstrapClasses = Join-Path $build 'bootstrap-classes'
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $agentRawClasses, $asmRawClasses, $agentClasses, $buildClasses, $bootstrapClasses, (Join-Path $modRoot 'agent'), (Join-Path $modRoot 'jars') | Out-Null
$agentSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\agent') | ForEach-Object FullName
$buildSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\build') | ForEach-Object FullName
$bootstrapSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\bootstrap') | ForEach-Object FullName
$asmCore = Join-Path $modRoot 'lib\asm-9.10.1.jar'
$asmTree = Join-Path $modRoot 'lib\asm-tree-9.10.1.jar'
$asmAnalysis = Join-Path $modRoot 'lib\asm-analysis-9.10.1.jar'
$asmCommons = Join-Path $modRoot 'lib\asm-commons-9.10.1.jar'
$agentCp = @((Join-Path $core 'starfarer.api.jar'),(Join-Path $core 'starfarer_obf.jar'),(Join-Path $core 'fs.common_obf.jar'),(Join-Path $core 'fs.sound_obf.jar'),(Join-Path $core 'lwjgl.jar'),(Join-Path $core 'lwjgl_util.jar'),$asmCore,$asmTree,$asmAnalysis) -join ';'
$buildCp = @($asmCore,$asmCommons) -join ';'
$bootstrapCp = @((Join-Path $core 'starfarer.api.jar'),(Join-Path $core 'starfarer_obf.jar'),(Join-Path $core 'log4j-1.2.9.jar')) -join ';'
& javac -encoding UTF-8 --release 17 -cp $agentCp -d $agentRawClasses $agentSources
if ($LASTEXITCODE -ne 0) { throw 'Agent compilation failed.' }
& javac -encoding UTF-8 --release 17 -cp $buildCp -d $buildClasses $buildSources
if ($LASTEXITCODE -ne 0) { throw 'ASM relocator compilation failed.' }
& javac -encoding UTF-8 --release 17 -cp $bootstrapCp -d $bootstrapClasses $bootstrapSources
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap compilation failed.' }

# Extract only the runtime ASM components. A build-only ASM Commons pass then moves
# every ASM class and every agent reference into the private Prepatcher namespace.
foreach ($asmJarName in @('asm-9.10.1.jar','asm-tree-9.10.1.jar','asm-analysis-9.10.1.jar')) {
    $asmJarPath = Join-Path $modRoot "lib\$asmJarName"
    if (-not (Test-Path -LiteralPath $asmJarPath -PathType Leaf)) {
        throw "Required ASM library is missing: $asmJarPath"
    }
    Push-Location $asmRawClasses
    try {
        & jar xf $asmJarPath
        if ($LASTEXITCODE -ne 0) { throw "Failed to extract $asmJarName into agent class tree." }
    } finally {
        Pop-Location
    }
}
& java -cp "$buildClasses;$buildCp" com.starsector.prepatcher.build.AsmRelocator $agentClasses $agentRawClasses $asmRawClasses
if ($LASTEXITCODE -ne 0) { throw 'ASM relocation failed.' }
$asmLicenseTarget = Join-Path $agentClasses 'META-INF\LICENSES\ASM.txt'
New-Item -ItemType Directory -Force (Split-Path -Parent $asmLicenseTarget) | Out-Null
Copy-Item -LiteralPath (Join-Path $modRoot 'lib\ASM-LICENSE.txt') -Destination $asmLicenseTarget -Force

$binaryEncoding = [Text.Encoding]::GetEncoding(28591)
foreach ($classFile in Get-ChildItem -LiteralPath $agentClasses -Filter *.class -File -Recurse) {
    $classText = $binaryEncoding.GetString([IO.File]::ReadAllBytes($classFile.FullName))
    if ($classText.Contains('org/objectweb/asm') -or $classText.Contains('jdk/internal/org/objectweb/asm')) {
        throw "Unrelocated ASM reference remains in $($classFile.FullName)"
    }
}
$utf8 = New-Object Text.UTF8Encoding($false)
$agentManifest = Join-Path $build 'agent.mf'
$bootstrapManifest = Join-Path $build 'bootstrap.mf'
[IO.File]::WriteAllText($agentManifest, "Manifest-Version: 1.0`nImplementation-Title: StarsectorPrepatcher Agent`nImplementation-Version: 0.18.4`nPremain-Class: com.starsector.prepatcher.agent.PrepatcherAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: true`n`n", $utf8)
[IO.File]::WriteAllText($bootstrapManifest, "Manifest-Version: 1.0`nImplementation-Title: StarsectorPrepatcher Bootstrap`nImplementation-Version: 0.18.4`n`n", $utf8)
$agentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
& jar --create --file $agentJar --manifest $agentManifest --date=2026-01-01T00:00:00Z -C $agentClasses .
if ($LASTEXITCODE -ne 0) { throw 'Agent JAR creation failed.' }
& jar --create --file (Join-Path $modRoot 'jars\StarsectorPrepatcherBootstrap.jar') --manifest $bootstrapManifest --date=2026-01-01T00:00:00Z -C $bootstrapClasses .
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap JAR creation failed.' }

# RuntimeInstaller reads these classfiles as bytes from the agent JAR and
# defines them in the target game loader. Keep them as normal class entries,
# but never link to them from the agent control-loader classes.
$requiredRuntimePayload = @(
    'com/fs/starfarer/api/StarsectorPrepatcherHooks.class',
    'com/fs/starfarer/api/StarsectorPrepatcherHyperspaceHooks.class',
    'com/fs/starfarer/api/StarsectorPrepatcherRuntimeBridge.class',
    'com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime.class',
    'com/fs/starfarer/api/StarsectorPrepatcherTempModHooks.class',
    'com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks.class',
    'com/fs/starfarer/api/StarsectorPrepatcherStrategicJumpIndex.class',
    'com/fs/starfarer/api/StarsectorPrepatcherMarketShareRuntime.class'
    'com/fs/starfarer/api/StarsectorPrepatcherEconomyHotpathRuntime.class'
)
$agentEntries = @(& jar tf $agentJar)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the agent JAR.' }
foreach ($entry in $requiredRuntimePayload) {
    if ($agentEntries -cnotcontains $entry) {
        throw "Required target-loader runtime payload is missing from the agent JAR: $entry"
    }
}
$expectedRuntimePayloadCount = 113
$runtimePayloadEntries = @($agentEntries | Where-Object {
    $_ -cmatch '^com/fs/starfarer/api/StarsectorPrepatcher[^/]*\.class$'
})
if ($runtimePayloadEntries.Count -ne $expectedRuntimePayloadCount) {
    throw "Target-loader runtime payload inventory changed: expected $expectedRuntimePayloadCount class entries, found $($runtimePayloadEntries.Count)."
}

# Keep the release manifest synchronized with the exact tree produced by this build.
# Runtime logs, build intermediates and SHA256SUMS.txt itself are intentionally excluded.
$checksumFiles = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
Get-ChildItem -LiteralPath $modRoot -File -Force | Where-Object {
    $_.Name -notin @('SHA256SUMS.txt', '.git')
} | ForEach-Object { $checksumFiles.Add($_) }
foreach ($directory in @('agent', 'baseline', 'docs', 'jars', 'lib', 'media', 'profiles', 'source')) {
    Get-ChildItem -LiteralPath (Join-Path $modRoot $directory) -File -Force -Recurse |
        ForEach-Object { $checksumFiles.Add($_) }
}
$logsReadme = Join-Path $modRoot 'logs\README.txt'
if (-not (Test-Path -LiteralPath $logsReadme -PathType Leaf)) {
    throw 'Required checksum input logs\README.txt is missing.'
}
$checksumFiles.Add((Get-Item -LiteralPath $logsReadme -Force))

[string[]] $checksumRelativePaths = $checksumFiles | ForEach-Object {
    $_.FullName.Substring($modRoot.Length + 1).Replace('\', '/')
}
[Array]::Sort($checksumRelativePaths, [StringComparer]::Ordinal)
$checksumLines = foreach ($relativePath in $checksumRelativePaths) {
    $nativePath = $relativePath.Replace('/', [IO.Path]::DirectorySeparatorChar)
    $digest = (Get-FileHash -LiteralPath (Join-Path $modRoot $nativePath) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  $relativePath"
}
$checksumTarget = Join-Path $modRoot 'SHA256SUMS.txt'
$checksumTemp = Join-Path $build 'SHA256SUMS.txt.tmp'
$checksumBackup = Join-Path $build 'SHA256SUMS.txt.bak'
[IO.File]::WriteAllText($checksumTemp, (($checksumLines -join "`n") + "`n"), $utf8)
if (Test-Path -LiteralPath $checksumTarget -PathType Leaf) {
    [IO.File]::Replace($checksumTemp, $checksumTarget, $checksumBackup)
    Remove-Item -LiteralPath $checksumBackup -Force
} else {
    [IO.File]::Move($checksumTemp, $checksumTarget)
}

Write-Host 'Build and checksum manifest completed.' -ForegroundColor Green
