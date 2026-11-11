[CmdletBinding()]
param([string[]] $GaiusConfigurator = @())

$ErrorActionPreference = 'Stop'
$modRoot = Split-Path $PSScriptRoot -Parent
$fixtureRoot = Join-Path $modRoot ('.build\installer-tests-' + [guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
$agent = '-javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Assert-That([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Write-FixtureFile([string] $Game, [string] $Path, [string] $Content) {
    $destination = Join-Path $Game $Path
    [IO.Directory]::CreateDirectory((Split-Path $destination -Parent)) | Out-Null
    [IO.File]::WriteAllText($destination, $Content.Replace("`r`n", "`n").Replace("`n", "`r`n"), $utf8)
}

function New-Game([string] $Name) {
    $game = Join-Path $fixtureRoot "$Name with spaces"
    $mod = Join-Path $game 'mods\StarsectorPrepatcher'
    [IO.Directory]::CreateDirectory((Join-Path $mod 'agent')) | Out-Null
    foreach ($file in @('install-agent.ps1', 'uninstall-agent.ps1', 'StarsectorPrepatcher.bat')) {
        Copy-Item -LiteralPath (Join-Path $modRoot $file) -Destination $mod
    }
    # Installation edits launch files; it never executes the agent or the game.
    Write-FixtureFile $game 'mods\StarsectorPrepatcher\agent\StarsectorPrepatcherAgent.jar' 'fixture'
    Write-FixtureFile $game 'vmparams' 'java.exe -noverify -javaagent:other.jar -classpath game.jar Main'
    Write-FixtureFile $game 'starsector-core\fr.vmparams' "-javaagent:other.jar`n-classpath game.jar`nMain`n"
    return $game
}

function Get-Snapshot([string] $Game) {
    return ((Get-ChildItem -LiteralPath $Game -File -Recurse | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($Game.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName).Hash
    }) -join "`n")
}

function New-FixtureJar([string] $Path, [hashtable] $Entries) {
    $archive = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Entries.Keys) {
            $writer = [IO.StreamWriter]::new($archive.CreateEntry($name).Open())
            try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
}

function Invoke-Installer([string] $Game, [string] $Script, [string] $Target, [int] $ExpectedExit = 0) {
    $scriptPath = Join-Path $Game "mods\StarsectorPrepatcher\$Script"
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Target $Target 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    Assert-That ($code -eq $ExpectedExit) "$Script $Target returned $code instead of ${ExpectedExit}: $output"
    return $output
}

function Assert-AgentOrder([string] $Content) {
    $entries = [regex]::Matches($Content, '(?im)^\s*"?-javaagent:.*$')
    Assert-That (([regex]::Matches($Content, 'StarsectorPrepatcherAgent\.jar')).Count -eq 1) 'Expected one Prepatcher agent'
    Assert-That ($entries[$entries.Count - 1].Value.Trim() -eq $agent) 'Prepatcher must be the last agent'
    Assert-That ($Content.IndexOf($agent) -lt $Content.IndexOf('-classpath')) 'Prepatcher must precede classpath'
}

$nativeSources = @(
    @'
set "PrepatcherAgent=-javaagent:../mods/%~1/agent/StarsectorPrepatcherAgent.jar"
set "PrepatcherStatus=Disabled"
:AppendAgentsAndClasspath
if /I "!PrepatcherStatus!"=="Enabled" >>"!SimpleOutputFile!" echo "!PrepatcherAgent!"
'@,
    @'
set "GenerationModule=!WindowsConfigRoot!\Configurator.Generation.cmd"
set "PrepatcherStatus=Disabled"
call "!GenerationModule!" BuildPendingFiles
'@
)
foreach ($path in $GaiusConfigurator) { $nativeSources += [IO.File]::ReadAllText((Resolve-Path -LiteralPath $path).Path) }

$case = 0
foreach ($source in $nativeSources) {
    foreach ($state in @('unconfigured', 'enabled', 'disabled-stale-template', 'other-installation')) {
        $case++
        $game = New-Game "gaius-$case"
        Write-FixtureFile $game 'Configure_Me.cmd' $source
        if ($state -ne 'unconfigured') {
            $entry = if ($state -eq 'enabled') { '"' + $agent + '"' + "`n" } elseif ($state -eq 'other-installation') {
                '"-javaagent:../mods/StarsectorPrepatcher-other/agent/StarsectorPrepatcherAgent.jar"' + "`n"
            } else { '' }
            Write-FixtureFile $game 'Miko_Simple.txt' ($entry + "-javaagent:telemetry.jar`n-classpath game.jar`nMain`n")
            Write-FixtureFile $game 'Miko_Rouge.bat' 'original launcher'
        }
        if ($state -eq 'disabled-stale-template') { Write-FixtureFile $game 'mikohime\DefaultVM' 'stale template' }
        $before = Get-Snapshot $game
        foreach ($script in @('install-agent.ps1', 'uninstall-agent.ps1')) {
            $output = Invoke-Installer $game $script 'Mikohime'
            Assert-That ($output.Contains('Mikohime (Gaius)')) 'Native integration was not recognized'
            Assert-That ($output.Contains('Configure_Me.cmd')) 'Missing configurator instructions'
            Assert-That (-not $output.Contains('installed the StarsectorPrepatcher')) 'Guidance claimed installation'
            Assert-That ((Get-Snapshot $game) -eq $before) 'Gaius files or backups changed'
        }
    }
}
Write-Host "PASS: $case Gaius layout/state combinations, install and uninstall preserve every file"

$game = New-Game 'mixed'
Write-FixtureFile $game 'Configure_Me.cmd' $nativeSources[1]
$beforeConfig = [IO.File]::ReadAllText((Join-Path $game 'Configure_Me.cmd'))
foreach ($target in @('Vanilla,Mikohime', 'All')) {
    $output = Invoke-Installer $game 'install-agent.ps1' $target
    Assert-That ($output.Contains('Mikohime (Gaius)')) 'Mixed selection lost Gaius instructions'
    $vanilla = [IO.File]::ReadAllText((Join-Path $game 'vmparams'))
    Assert-That ($vanilla.Contains('-javaagent:other.jar ' + $agent)) 'Vanilla agent order changed'
    if ($target -eq 'All') { Assert-AgentOrder ([IO.File]::ReadAllText((Join-Path $game 'starsector-core\fr.vmparams'))) }
    Invoke-Installer $game 'uninstall-agent.ps1' $target | Out-Null
    Assert-That (-not ([IO.File]::ReadAllText((Join-Path $game 'vmparams'))).Contains($agent)) 'Vanilla removal failed'
    Assert-That ([IO.File]::ReadAllText((Join-Path $game 'Configure_Me.cmd')) -eq $beforeConfig) 'Mixed selection modified Gaius'
}
Write-Host 'PASS: mixed selections and All process independent launch paths'

$original = @'
@echo off
set FR_status=Enabled
set FR1=-Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader
set FR2=-classpath fr.jar;game.jar
set A46=-classpath game.jar
set JavaPath=fixture-java
break>Miko_Simple.txt
type mikohime\DefaultVM>>Miko_Simple.txt
IF %FR_status% == Enabled (
echo %FR1%>>Miko_Simple.txt
echo %FR2%>>Miko_Simple.txt
) else (
echo %A46%>>Miko_Simple.txt
)
echo Main>>Miko_Simple.txt
break>Miko_Rouge.bat
type mikohime\DefaultPath>>Miko_Rouge.bat
echo ..\%JavaPath%\bin\java.exe @..\Miko_Simple.txt>>Miko_Rouge.bat
echo if %errorlevel% neq 0 pause>>Miko_Rouge.bat
'@
foreach ($quote in @('plain', 'path', 'whole')) {
    $game = New-Game "original-$quote"
    $renderEnabled = $quote -ne 'path'
    $generator = if ($renderEnabled) { $original } else { $original.Replace('FR_status=Enabled', 'FR_status=Disabled') }
    Write-FixtureFile $game 'Configure_Me.cmd' $generator
    Write-FixtureFile $game 'mikohime\DefaultVM' "-Xmx8192m`n"
    Write-FixtureFile $game 'mikohime\DefaultPath' "@echo off`ncd starsector-core`n"
    $entry = switch ($quote) {
        'plain' { $agent }
        'path' { '-javaagent:"../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar"' }
        'whole' { '"' + $agent + '"' }
    }
    Write-FixtureFile $game 'Miko_Simple.txt' ("-Xmx8192m`n-javaagent:telemetry.jar`n" + $entry + "`n-classpath game.jar`nMain`n")
    Write-FixtureFile $game 'Miko_Rouge.bat' "@echo off`n..\fixture-java\bin\java.exe @..\Miko_Simple.txt`nif errorlevel 1 pause`n"
    New-FixtureJar (Join-Path $game 'starsector-core\fr.jar') @{
        'com/genir/renderer/loaders/AppClassLoader.class' = 'fixture'
        'com/genir/renderer/loaders/ClassTransformer.class' = 'fixture'
    }
    if ($quote -eq 'whole') {
        New-FixtureJar (Join-Path $game 'starsector-core\fr.agent.jar') @{
            'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`r`nPremain-Class: Fixture`r`n"
        }
    }
    $targets = @('Miko_Simple.txt', 'mikohime\DefaultVM', 'Configure_Me.cmd', 'Miko_Rouge.bat')
    $originalContents = @{}
    foreach ($path in $targets) { $originalContents[$path] = [IO.File]::ReadAllText((Join-Path $game $path)) }
    Invoke-Installer $game 'install-agent.ps1' 'Mikohime' | Out-Null
    $installed = [IO.File]::ReadAllText((Join-Path $game 'Miko_Simple.txt'))
    Assert-AgentOrder $installed
    Assert-That ($installed.Contains('-javaagent:telemetry.jar')) 'Original installation lost another agent'
    Assert-That ($installed.Contains('-Xmx8192m')) 'Original installation changed memory settings'
    foreach ($path in $targets) {
        $fullPath = Join-Path $game $path
        if ([IO.File]::ReadAllText($fullPath) -ne $originalContents[$path]) {
            $backups = @(Get-ChildItem -LiteralPath (Split-Path $fullPath -Parent) -Filter ((Split-Path $fullPath -Leaf) + '.spp-backup-*'))
            Assert-That ($backups.Count -eq 1) "Missing backup for changed file $path"
            Assert-That ([IO.File]::ReadAllText($backups[0].FullName) -eq $originalContents[$path]) "Backup differs from original $path"
        }
    }
    $backupCount = @(Get-ChildItem -LiteralPath $game -Filter '*.spp-backup-*' -Recurse).Count
    $hashes = @($targets | ForEach-Object { (Get-FileHash -LiteralPath (Join-Path $game $_)).Hash }) -join ':'
    Invoke-Installer $game 'install-agent.ps1' 'Mikohime' | Out-Null
    Assert-That ((@($targets | ForEach-Object { (Get-FileHash -LiteralPath (Join-Path $game $_)).Hash }) -join ':') -eq $hashes) 'Original reinstall is not idempotent'
    Assert-That (@(Get-ChildItem -LiteralPath $game -Filter '*.spp-backup-*' -Recurse).Count -eq $backupCount) 'Idempotent reinstall created backups'
    # Execute only the synthetic generator, never Java or the installed game's scripts.
    Push-Location $game
    try { & cmd.exe /d /c Configure_Me.cmd | Out-Null; $generatorExit = $LASTEXITCODE } finally { Pop-Location }
    Assert-That ($generatorExit -eq 0) 'Original generator failed'
    $generated = [IO.File]::ReadAllText((Join-Path $game 'Miko_Simple.txt'))
    Assert-AgentOrder $generated
    $renderingOption = if ($quote -eq 'whole') { '-javaagent:fr.agent.jar' } else {
        '-Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader'
    }
    if ($renderEnabled) {
        Assert-That ($generated.Contains($renderingOption)) 'Generator lost the inspected rendering layout'
        Assert-That ($generated.IndexOf($renderingOption) -lt $generated.IndexOf($agent)) 'Rendering must precede Prepatcher'
    } else {
        Assert-That (-not $generated.Contains($renderingOption)) 'Generator enabled rendering against the user selection'
    }
    Assert-That ($generated.Contains('-Xmx8192m')) 'Generator changed template settings'
    Invoke-Installer $game 'uninstall-agent.ps1' 'Mikohime' | Out-Null
    Assert-That (-not ([IO.File]::ReadAllText((Join-Path $game 'Miko_Simple.txt'))).Contains($agent)) 'Original removal failed'
    # Also remove the original quoted form before any normalization by installation.
    Write-FixtureFile $game 'Miko_Simple.txt' ($entry + "`n-javaagent:telemetry.jar`n-classpath game.jar`nMain`n")
    Invoke-Installer $game 'uninstall-agent.ps1' 'Mikohime' | Out-Null
    $removed = [IO.File]::ReadAllText((Join-Path $game 'Miko_Simple.txt'))
    Assert-That (-not $removed.Contains('StarsectorPrepatcherAgent.jar')) 'Quoted agent removal failed'
    Assert-That ($removed.Contains('-javaagent:telemetry.jar')) 'Uninstall removed another agent'
}
Write-Host 'PASS: original install/reinstall/generation/uninstall, backups, foreign agents and three quoting forms'

$game = New-Game 'invalid'
Write-FixtureFile $game 'Configure_Me.cmd' $original
Write-FixtureFile $game 'Miko_Simple.txt' "-classpath game.jar`nMain`n"
$before = Get-Snapshot $game
$output = Invoke-Installer $game 'install-agent.ps1' 'Vanilla,Mikohime' 1
Assert-That ($output.Contains('launch file not found')) 'Missing original file was silently accepted'
Assert-That (-not $output.Contains('The mod must be inside')) 'Missing file blamed mod location'
Assert-That ((Get-Snapshot $game) -eq $before) 'Failed original preflight changed files'
Write-FixtureFile $game 'mikohime\DefaultVM' '-Xmx8192m'
Write-FixtureFile $game 'Configure_Me.cmd' '@echo off'
$before = Get-Snapshot $game
Invoke-Installer $game 'install-agent.ps1' 'Vanilla,Mikohime' 1 | Out-Null
Assert-That ((Get-Snapshot $game) -eq $before) 'Unknown generator changed files'
Invoke-Installer $game 'install-agent.ps1' 'Invalid,Mikohime' 1 | Out-Null
Assert-That ((Get-Snapshot $game) -eq $before) 'Invalid target changed files'
Write-Host 'PASS: missing/unknown original layouts and invalid targets fail before writes'
Write-Host "Installer regression passed. Fixtures: $fixtureRoot"
