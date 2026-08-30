[CmdletBinding()]
param(
    [string] $Target = 'Vanilla'
)

$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$agentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
$agentArg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar"
$managedAgentTokenPattern = '-javaagent:"?\.\.[\\/]+mods[\\/]+' +
        [regex]::Escape($modFolder) +
        '[\\/]+agent[\\/]+StarsectorPrepatcherAgent\.jar"?'
$managedAgentLinePattern = '(?i)^\s*' + $managedAgentTokenPattern + '\s*$'
$frAgentArg = '-javaagent:fr.agent.jar'
$legacyFrLoaderArg = '-Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader'

if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw "Agent JAR not found: $agentJar"
}
if ($modFolder -ne 'StarsectorPrepatcher') {
    Write-Warning "The mod folder is named '$modFolder'. The generated javaagent paths will use that name; do not rename it after installation."
}
if ($modFolder -match '\s') {
    throw "The mod folder path contains whitespace. Rename the folder to 'StarsectorPrepatcher' and run this installer again."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Test-JarEntry {
    param([string] $JarPath, [string] $EntryName)
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { return $false }
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try { return $null -ne $archive.GetEntry($EntryName) }
        finally { $archive.Dispose() }
    } catch { return $false }
}
function Test-JavaAgentJar {
    param([string] $JarPath)
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { return $false }
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
            if ($null -eq $entry) { return $false }
            $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8, $true)
            try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            return $manifest -match '(?im)^Premain-Class\s*:\s*\S+'
        } finally { $archive.Dispose() }
    } catch { return $false }
}

$frAgentJar = Join-Path $gameRoot 'starsector-core\fr.agent.jar'
$frRuntimeJar = Join-Path $gameRoot 'starsector-core\fr.jar'
$fasterRenderingLayout = if (Test-JavaAgentJar $frAgentJar) {
    'Agent'
} elseif ((Test-JarEntry $frRuntimeJar 'com/genir/renderer/loaders/AppClassLoader.class') -and
          (Test-JarEntry $frRuntimeJar 'com/genir/renderer/loaders/ClassTransformer.class')) {
    'LegacyLoader'
} else {
    'Unavailable'
}

$availableTargets = @(
    [pscustomobject]@{
        Name = 'Vanilla'
        Path = Join-Path $gameRoot 'vmparams'
        RequiresJavaPrefix = $true
        IsArgumentFile = $false
        RequiresClasspath = $false
    },
    [pscustomobject]@{
        Name = 'FasterRendering'
        Path = Join-Path $gameRoot 'starsector-core\fr.vmparams'
        RequiresJavaPrefix = $false
        IsArgumentFile = $true
        RequiresClasspath = $true
    },
    [pscustomobject]@{
        Name = 'MikohimeSimple'
        Path = Join-Path $gameRoot 'Miko_Simple.txt'
        RequiresJavaPrefix = $false
        IsArgumentFile = $true
        RequiresClasspath = $true
    },
    [pscustomobject]@{
        Name = 'MikohimeTemplate'
        Path = Join-Path $gameRoot 'mikohime\DefaultVM'
        RequiresJavaPrefix = $false
        IsArgumentFile = $true
        # DefaultVM is the persistent template that Configure_Me.cmd concatenates into
        # Miko_Simple.txt. It has no -classpath line (the classpath is appended dynamically
        # afterwards), so the managed entry is appended at the end of the template.
        RequiresClasspath = $false
    },
    [pscustomobject]@{
        Name = 'MikohimeGenerator'
        Path = Join-Path $gameRoot 'Configure_Me.cmd'
        RequiresJavaPrefix = $false
        IsArgumentFile = $false
        RequiresClasspath = $false
    },
    [pscustomobject]@{
        Name = 'MikohimeLauncher'
        Path = Join-Path $gameRoot 'Miko_Rouge.bat'
        RequiresJavaPrefix = $false
        IsArgumentFile = $false
        RequiresClasspath = $false
    }
)
$mikohimeGeneratorSpec = $availableTargets | Where-Object Name -eq 'MikohimeGenerator'
$modernMikohimeGenerator = $false
if (Test-Path -LiteralPath $mikohimeGeneratorSpec.Path -PathType Leaf) {
    $generatorProbe = [IO.File]::ReadAllText($mikohimeGeneratorSpec.Path)
    $modernMikohimeGenerator = $generatorProbe -match
            '(?im)^:AppendAgentsAndClasspath[ \t]*\r?$' -and
            $generatorProbe -match '(?im)^set[ \t]+"?OutputFile='
}
$mikohimeTargets = if ($modernMikohimeGenerator) {
    $availableTargets | Where-Object Name -in @('MikohimeSimple', 'MikohimeGenerator')
} else {
    $availableTargets | Where-Object Name -like 'Mikohime*'
}
if ($Target -eq 'All') {
    $selectedTargets = $availableTargets | Where-Object {
        -not $modernMikohimeGenerator -or
        $_.Name -notin @('MikohimeTemplate', 'MikohimeLauncher')
    }
} else {
    $requestedNames = $Target -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }
    foreach ($name in $requestedNames) {
        if ($name -notin @('Vanilla', 'FasterRendering', 'Mikohime')) {
            throw "Unknown target '$name'. Valid targets: Vanilla, FasterRendering, Mikohime (comma-separated), or All."
        }
    }
    $selectedTargets = foreach ($name in $requestedNames) {
        switch ($name) {
            'Vanilla' { $availableTargets | Where-Object Name -eq 'Vanilla' }
            'FasterRendering' { $availableTargets | Where-Object Name -eq 'FasterRendering' }
            'Mikohime' { $mikohimeTargets }
        }
    }
}

function Normalize-MikohimeTemplate {
    param([string] $Content)
    $working = [regex]::Replace($Content,
            '(?im)^[ \t]*' + $managedAgentTokenPattern + '[ \t]*(?:\r?\n|$)', '')
    $working = [regex]::Replace($working,
            '(?im)^[ \t]*-Dspp\.dump\.campaignState=.*(?:\r?\n|$)', '')
    return $working.TrimEnd("`r", "`n") + "`r`n"
}

function Normalize-MikohimeSimple {
    param([string] $Content)
    $lineBreak = if ($Content.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($line in ($Content -split '\r?\n')) {
        if ($line -match '(?i)^\s*-Dspp\.dump\.campaignState=') { continue }
        if ($line -match $managedAgentLinePattern) { continue }
        if ($line -match '(?i)^\s*--patch-module=java\.base="?\.\.[\\/]+Java28PrepatcherCompat[\\/]+prepatcher-java-base-asm\.jar"?\s*$') { continue }
        if ($line -match '(?i)^\s*-javaagent:"?\.\.[\\/]+Java28PrepatcherCompat[\\/]+prepatcher-java28-frame-repair-agent\.jar"?\s*$') { continue }
        if ($line.Trim().Equals($frAgentArg, [StringComparison]::OrdinalIgnoreCase)) { continue }
        if ($fasterRenderingLayout -eq 'Agent' -and
                $line.Trim().Equals($legacyFrLoaderArg, [StringComparison]::OrdinalIgnoreCase)) { continue }
        if ($line.Length -eq 0 -and $lines.Count -gt 0 -and
                $lines[$lines.Count - 1].Length -eq 0) { continue }
        $lines.Add($line)
    }
    while ($lines.Count -gt 0 -and $lines[$lines.Count - 1].Length -eq 0) {
        $lines.RemoveAt($lines.Count - 1)
    }
    $classpath = -1
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match '(?i)^\s*(?:-classpath|-cp|--class-path)(?:\s|=|$)') {
            $classpath = $index
            break
        }
    }
    if ($classpath -lt 0) { throw 'Miko_Simple.txt has no classpath anchor.' }
    for ($index = $classpath + 1; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match '(?i)^\s*-javaagent:') {
            throw 'Miko_Simple.txt contains a javaagent after -classpath.'
        }
    }
    $usesFr = $Content -match '(?im)^\s*(?:-classpath\s+)?fr\.jar(?:;|\s|$)' -or
            $Content -match [regex]::Escape($legacyFrLoaderArg) -or
            $Content -match [regex]::Escape($frAgentArg)
    if ($usesFr -and $fasterRenderingLayout -eq 'LegacyLoader' -and
            -not ($lines | Where-Object { $_.Trim().Equals(
                    $legacyFrLoaderArg, [StringComparison]::OrdinalIgnoreCase) })) {
        $lines.Insert($classpath, $legacyFrLoaderArg)
        $classpath++
    }
    if ($usesFr -and $fasterRenderingLayout -eq 'Agent') {
        $lines.Insert($classpath, $frAgentArg)
        $classpath++
    }
    $lines.Insert($classpath, $agentArg)
    return (($lines -join $lineBreak).TrimEnd("`r", "`n") + $lineBreak)
}

function Update-MikohimeGenerator {
    param([string] $Content)
    $lineBreak = if ($Content.Contains("`r`n")) { "`r`n" } else { "`n" }
    $isModernGenerator = $Content -match '(?im)^:AppendAgentsAndClasspath[ \t]*\r?$' -and
            $Content -match '(?im)^set[ \t]+"?OutputFile='
    $hasObsoleteCompat = $Content -match '(?i)Java28PrepatcherCompat|PrepatcherFrameRepairAgent'
    $working = [regex]::Replace($Content,
            '(?im)^set SPP_AGENT=.*(?:\r?\n|$)', '')
    $working = [regex]::Replace($working,
            '(?im)^[ \t]*echo[ \t]+%SPP_AGENT%[ \t]*>>[ \t]*(?:"?Miko_Simple\.txt"?|"?%OutputFile%"?)[ \t]*(?:\r?\n|$)', '')
    $working = [regex]::Replace($working,
            '(?im)^[ \t]*if[ \t]+defined[ \t]+SPP_AGENT[ \t]+exit[ \t]+/b[ \t]+0[ \t]*(?:\r?\n|$)', '')
    $working = [regex]::Replace($working,
            '(?im)^IF EXIST "starsector-core\\fr\.agent\.jar" set FR1=.*(?:\r?\n|$)', '')
    $frGeneratorArg = switch ($fasterRenderingLayout) {
        'Agent' { $frAgentArg }
        'LegacyLoader' { $legacyFrLoaderArg }
        default { '' }
    }
    # Use the same inspected file layout as the direct Miko_Simple update. A
    # mere file named fr.agent.jar is not enough to put it on the command line;
    # Test-JavaAgentJar above must first prove that it has a Premain-Class.
    $frSetup = 'set FR1=' + $frGeneratorArg + $lineBreak +
            'set SPP_AGENT=' + $agentArg
    $working = [regex]::Replace($working,
            '(?im)^set FR1=.*$', [Text.RegularExpressions.MatchEvaluator]{ param($match) $frSetup }, 1)
    if ($working -notmatch '(?im)^set SPP_AGENT=') {
        throw 'Could not update the Faster Rendering variables in Configure_Me.cmd.'
    }

    if ($isModernGenerator) {
        # OutputFile-based generators may have an optional integration for the
        # retired Java28PrepatcherCompat two-agent package. Keep that path
        # dormant while this installer is active and emit the current single
        # agent directly. Detection is structural and has no version gate.
        $appendAnchor = '(?im)^(?<indent>[ \t]*)if[ \t]+/I[ \t]+"?%FR_status%"?[ \t]*==[ \t]*"?Enabled"?[ \t]+echo[ \t]+%FR1%[ \t]*>>[ \t]*"?%OutputFile%"?[ \t]*\r?$'
        $appendMatches = [regex]::Matches($working, $appendAnchor)
        if ($appendMatches.Count -ne 1) {
            throw 'Could not update agent ordering in Configure_Me.cmd.'
        }
        $append = $appendMatches[0]
        $appendReplacement = $append.Value.TrimEnd("`r") + $lineBreak + $append.Groups['indent'].Value +
                'echo %SPP_AGENT%>>"%OutputFile%"'
        $working = $working.Substring(0, $append.Index) + $appendReplacement +
                $working.Substring($append.Index + $append.Length)

        $choiceLabel = [regex]::Matches($working, '(?im)^:ChoosePrepatcher[ \t]*\r?$')
        if ($choiceLabel.Count -gt 1 -or
                ($choiceLabel.Count -eq 0 -and $hasObsoleteCompat)) {
            throw 'Could not disable the obsolete Prepatcher compatibility prompt in Configure_Me.cmd.'
        }
        if ($choiceLabel.Count -eq 1) {
            $choice = $choiceLabel[0]
            $choiceReplacement = $choice.Value.TrimEnd("`r") + $lineBreak +
                    'if defined SPP_AGENT exit /b 0'
            $working = $working.Substring(0, $choice.Index) + $choiceReplacement +
                    $working.Substring($choice.Index + $choice.Length)
        }

        $launcherPattern = '(?im)^(?<indent>[ \t]*)echo[ \t]+"?\.\.\\%JavaPath%\\bin\\java\.exe"?[ \t]+@\.\.\\Miko_Simple\.txt[ \t]*>>[ \t]*"?%LauncherOutputFile%"?[ \t]*\r?\n[ \t]*echo[ \t]+if[ \t]+(?:%errorlevel%[ \t]+neq[ \t]+0|errorlevel[ \t]+1)[ \t]+pause[ \t]*>>[ \t]*"?%LauncherOutputFile%"?[ \t]*\r?$'
        $launcherMatches = [regex]::Matches($working, $launcherPattern)
        if ($launcherMatches.Count -eq 1) {
            $launcher = $launcherMatches[0]
            $indent = $launcher.Groups['indent'].Value
            $launcherReplacement = $indent + 'echo "..\%JavaPath%\bin\java.exe" @..\Miko_Simple.txt>>"%LauncherOutputFile%"' + $lineBreak +
                    $indent + 'echo set "MIKO_JAVA_EXIT=%%ERRORLEVEL%%">>"%LauncherOutputFile%"' + $lineBreak +
                    $indent + 'echo if not "%%MIKO_JAVA_EXIT%%"=="0" pause>>"%LauncherOutputFile%"' + $lineBreak +
                    $indent + 'echo exit /b %%MIKO_JAVA_EXIT%%>>"%LauncherOutputFile%"'
            $working = $working.Substring(0, $launcher.Index) + $launcherReplacement +
                    $working.Substring($launcher.Index + $launcher.Length)
        } elseif ($launcherMatches.Count -ne 0 -or $working -notmatch '(?im)MIKO_JAVA_EXIT') {
            throw 'Could not update Miko_Rouge.bat generation in Configure_Me.cmd.'
        }

        $managedOutputEchoes = [regex]::Matches($working,
                '(?im)^[ \t]*echo[ \t]+%SPP_AGENT%[ \t]*>>[ \t]*"?%OutputFile%"?[ \t]*\r?$')
        $managedGuards = [regex]::Matches($working,
                '(?im)^[ \t]*if[ \t]+defined[ \t]+SPP_AGENT[ \t]+exit[ \t]+/b[ \t]+0[ \t]*\r?$')
        $expectedGuardCount = if ($choiceLabel.Count -eq 1) { 1 } else { 0 }
        $appendSections = [regex]::Matches($working,
                '(?ims)^:AppendAgentsAndClasspath[ \t]*\r?\n(?<body>.*?)^exit[ \t]+/b[ \t]*\r?$')
        $appendBody = if ($appendSections.Count -eq 1) {
            $appendSections[0].Groups['body'].Value
        } else { '' }
        $frIndex = $appendBody.IndexOf('%FR1%', [StringComparison]::OrdinalIgnoreCase)
        $sppIndex = $appendBody.IndexOf('%SPP_AGENT%', [StringComparison]::OrdinalIgnoreCase)
        $frClasspathIndex = $appendBody.IndexOf('%FR2%', [StringComparison]::OrdinalIgnoreCase)
        $plainClasspathIndex = $appendBody.IndexOf('%A46%', [StringComparison]::OrdinalIgnoreCase)
        if ($managedOutputEchoes.Count -ne 1 -or
                $managedGuards.Count -ne $expectedGuardCount -or
                $frIndex -lt 0 -or $sppIndex -le $frIndex -or
                $frClasspathIndex -le $sppIndex -or $plainClasspathIndex -le $sppIndex -or
                $working -notmatch '(?im)^[ \t]*echo exit /b %%MIKO_JAVA_EXIT%%>>"%LauncherOutputFile%"[ \t]*\r?$') {
            throw 'Could not validate the modern Configure_Me.cmd integration.'
        }
        return $working.TrimEnd("`r", "`n") + $lineBreak
    }

    # Mikohime releases differ in whitespace, quoting and the extra commands in
    # these branches. Match the control-flow boundary and its semantic output
    # anchors instead of replacing one complete release-specific block.
    $setupPattern = '(?ims)^(?<if>[ \t]*IF[ \t]+(?:/I[ \t]+)?(?:"?%FR_status%"?[ \t]*==[ \t]*"?Enabled"?|"?Enabled"?[ \t]*==[ \t]*"?%FR_status%"?)[ \t]*\([ \t]*\r?\n)(?<enabled>.*?)(?<else>^[ \t]*\)[ \t]*else[ \t]*\([ \t]*\r?\n)(?<disabled>.*?)(?<end>^[ \t]*\)[ \t]*\r?$)'
    $setupMatches = [regex]::Matches($working, $setupPattern)
    if ($setupMatches.Count -ne 1) {
        throw 'Could not update agent ordering in Configure_Me.cmd.'
    }
    $setupMatch = $setupMatches[0]
    $echoPattern = '(?im)^(?<indent>[ \t]*)echo[ \t]+%{0}%[ \t]*>>[ \t]*"?Miko_Simple\.txt"?[ \t]*\r?$'
    $fr1Matches = [regex]::Matches($setupMatch.Groups['enabled'].Value,
            ($echoPattern -f 'FR1'))
    $fr2Matches = [regex]::Matches($setupMatch.Groups['enabled'].Value,
            ($echoPattern -f 'FR2'))
    $a46Matches = [regex]::Matches($setupMatch.Groups['disabled'].Value,
            ($echoPattern -f 'A46'))
    if ($fr1Matches.Count -ne 1 -or $fr2Matches.Count -ne 1 -or
            $a46Matches.Count -ne 1 -or
            $fr1Matches[0].Index -ge $fr2Matches[0].Index) {
        throw 'Could not update agent ordering in Configure_Me.cmd.'
    }
    $enabled = $setupMatch.Groups['enabled'].Value
    $fr2 = $fr2Matches[0]
    $enabled = $enabled.Substring(0, $fr2.Index) +
            $fr2.Groups['indent'].Value + 'echo %SPP_AGENT%>>Miko_Simple.txt' +
            $lineBreak + $enabled.Substring($fr2.Index)
    $disabled = $setupMatch.Groups['disabled'].Value
    $a46 = $a46Matches[0]
    $disabled = $disabled.Substring(0, $a46.Index) +
            $a46.Groups['indent'].Value + 'echo %SPP_AGENT%>>Miko_Simple.txt' +
            $lineBreak + $disabled.Substring($a46.Index)
    $setupReplacement = $setupMatch.Groups['if'].Value + $enabled +
            $setupMatch.Groups['else'].Value + $disabled +
            $setupMatch.Groups['end'].Value
    $working = $working.Substring(0, $setupMatch.Index) + $setupReplacement +
            $working.Substring($setupMatch.Index + $setupMatch.Length)

    $managedEchoes = [regex]::Matches($working,
            '(?im)^[ \t]*echo[ \t]+%SPP_AGENT%[ \t]*>>[ \t]*"?Miko_Simple\.txt"?[ \t]*\r?$')
    if ($managedEchoes.Count -ne 2) {
        throw 'Could not update agent ordering in Configure_Me.cmd.'
    }
    $launcherPattern = '(?im)^echo \.\.\\%JavaPath%\\bin\\java\.exe @\.\.\\Miko_Simple\.txt>>Miko_Rouge\.bat\s*\r?\necho if .*?pause>>Miko_Rouge\.bat'
    $launcherReplacement = 'echo ..\%JavaPath%\bin\java.exe @..\Miko_Simple.txt>>Miko_Rouge.bat' + $lineBreak +
            'echo set "MIKO_JAVA_EXIT=%%ERRORLEVEL%%">>Miko_Rouge.bat' + $lineBreak +
            'echo if not "%%MIKO_JAVA_EXIT%%"=="0" pause>>Miko_Rouge.bat' + $lineBreak +
            'echo exit /b %%MIKO_JAVA_EXIT%%>>Miko_Rouge.bat'
    $updated = [regex]::Replace($working, $launcherPattern,
            [Text.RegularExpressions.MatchEvaluator]{ param($match) $launcherReplacement }, 1)
    if ($updated -eq $working -and $working -notmatch 'MIKO_JAVA_EXIT') {
        throw 'Could not update Miko_Rouge.bat generation in Configure_Me.cmd.'
    }
    return $updated.TrimEnd("`r", "`n") + $lineBreak
}

function Update-MikohimeLauncher {
    param([string] $Content)
    $lineBreak = if ($Content.Contains("`r`n")) { "`r`n" } else { "`n" }
    $working = [regex]::Replace($Content,
            '(?im)^set "MIKO_JAVA_EXIT=.*(?:\r?\n|$)', '')
    $working = [regex]::Replace($working,
            '(?im)^if (?:not )?.*pause[ \t]*(?:\r?\n|$)', '')
    $working = [regex]::Replace($working,
            '(?im)^exit /b (?:%MIKO_JAVA_EXIT%|%ERRORLEVEL%)[ \t]*(?:\r?\n|$)', '')
    $javaPattern = '(?im)^(.*\\bin\\java\.exe"?[ \t]+@\.\.\\Miko_Simple\.txt)[ \t]*\r?$'
    $match = [regex]::Match($working, $javaPattern)
    if (-not $match.Success) { throw 'Could not find the Java invocation in Miko_Rouge.bat.' }
    $replacement = $match.Groups[1].Value + $lineBreak +
            'set "MIKO_JAVA_EXIT=%ERRORLEVEL%"' + $lineBreak +
            'if not "%MIKO_JAVA_EXIT%"=="0" pause' + $lineBreak +
            'exit /b %MIKO_JAVA_EXIT%'
    $working = [regex]::Replace($working, $javaPattern,
            [Text.RegularExpressions.MatchEvaluator]{ param($found) $replacement }, 1)
    return $working.TrimEnd("`r", "`n") + $lineBreak
}

function New-InstallationPlan {
    param(
        [Parameter(Mandatory)] [pscustomobject] $TargetSpec,
        [Parameter(Mandatory)] [string] $Content
    )

    if ($TargetSpec.Name -eq 'MikohimeTemplate') {
        return [pscustomobject]@{
            TargetSpec = $TargetSpec
            OriginalContent = $Content
            NewContent = Normalize-MikohimeTemplate $Content
            ExistingAgentCount = 0
        }
    }
    if ($TargetSpec.Name -eq 'MikohimeSimple') {
        return [pscustomobject]@{
            TargetSpec = $TargetSpec
            OriginalContent = $Content
            NewContent = Normalize-MikohimeSimple $Content
            ExistingAgentCount = ([regex]::Matches($Content, '(?im)^\s*-javaagent:')).Count
        }
    }
    if ($TargetSpec.Name -eq 'MikohimeGenerator') {
        return [pscustomobject]@{
            TargetSpec = $TargetSpec
            OriginalContent = $Content
            NewContent = Update-MikohimeGenerator $Content
            ExistingAgentCount = 0
        }
    }
    if ($TargetSpec.Name -eq 'MikohimeLauncher') {
        return [pscustomobject]@{
            TargetSpec = $TargetSpec
            OriginalContent = $Content
            NewContent = Update-MikohimeLauncher $Content
            ExistingAgentCount = 0
        }
    }

    $javaPrefixPattern = '^\s*(?:"[^"]*javaw?\.exe"|[^\s]*javaw?\.exe)\s+'
    # Accept both common quoting forms used by Windows launch commands:
    # -javaagent:"path with spaces" and "-javaagent:path with spaces".
    # Missing the latter would let us insert before an existing javaagent while
    # still passing the final "Prepatcher is last" check.
    $javaAgentPattern = '(?i)(?<!\S)(?:"-javaagent:[^"]+"|-javaagent:(?:"[^"]*"|[^\s"]+))'
    $tokenPattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)[ \t]*'
    if ($TargetSpec.IsArgumentFile) {
        # Remove a managed entry that occupies its own argfile line together
        # with that line break. Otherwise every idempotent reinstall would
        # accumulate an extra blank line before -classpath.
        $managedLinePattern = '(?im)^[ \t]*' + [regex]::Escape($agentArg) + '[ \t]*(?:\r?\n|$)'
        $working = [regex]::Replace($Content, $managedLinePattern, '')
        $working = [regex]::Replace($working, $tokenPattern, '')
    } else {
        $working = [regex]::Replace($Content, $tokenPattern, '')
    }

    if ($TargetSpec.RequiresJavaPrefix) {
        $prefixMatch = [regex]::Match(
            $working,
            $javaPrefixPattern,
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if (-not $prefixMatch.Success) {
            throw "Could not find java.exe/javaw.exe at the beginning of $($TargetSpec.Path). No changes were made."
        }
        $javaOptionsStart = $prefixMatch.Index + $prefixMatch.Length
    } else {
        # Faster Rendering launches Java with @fr.vmparams, so this file starts
        # directly with JVM options and intentionally has no java.exe prefix.
        $javaOptionsStart = 0
    }

    $classpathMatch = $null
    if ($TargetSpec.IsArgumentFile -and $TargetSpec.RequiresClasspath) {
        $classpathMatch = [regex]::Match(
            $working,
            '^[ \t]*(?:-classpath|-cp|--class-path)(?=[ \t=\r\n]|$)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
                [System.Text.RegularExpressions.RegexOptions]::Multiline
        )
        if (-not $classpathMatch.Success) {
            throw "Could not find the Java classpath option in $($TargetSpec.Path). No changes were made."
        }
    }

    $otherAgents = [regex]::Matches($working.Substring($javaOptionsStart), $javaAgentPattern)
    if ($otherAgents.Count -gt 0) {
        $lastAgent = $otherAgents[$otherAgents.Count - 1]
        $insertAt = $javaOptionsStart + $lastAgent.Index + $lastAgent.Length
        if ($classpathMatch -ne $null -and $classpathMatch.Success -and $insertAt -gt $classpathMatch.Index) {
            throw "Found a javaagent after the classpath option in $($TargetSpec.Path). No changes were made."
        }
        if ($TargetSpec.IsArgumentFile) {
            $lineBreak = if ($working.Contains("`r`n")) { "`r`n" } else { "`n" }
            $newContent = $working.Substring(0, $insertAt) + $lineBreak + $agentArg + $working.Substring($insertAt)
        } else {
            $newContent = $working.Substring(0, $insertAt) + ' ' + $agentArg + $working.Substring($insertAt)
        }
    } elseif ($TargetSpec.IsArgumentFile) {
        $lineBreak = if ($working.Contains("`r`n")) { "`r`n" } else { "`n" }
        if ($TargetSpec.RequiresClasspath) {
            $insertAt = $classpathMatch.Index
            $before = $working.Substring(0, $insertAt)
            $after = $working.Substring($insertAt)
            $beforeSeparator = if ($before.Length -eq 0 -or $before.EndsWith("`n")) { '' } else { $lineBreak }
            $afterSeparator = if ($after.Length -eq 0 -or $after.StartsWith("`r") -or $after.StartsWith("`n")) { '' } else { $lineBreak }
            $newContent = $before + $beforeSeparator + $agentArg + $afterSeparator + $after
        } else {
            # No classpath anchor (e.g. the mikohime DefaultVM template). Append the
            # managed entry on its own line at the end so the dynamically generated
            # Miko_Simple.txt receives it before the appended -classpath/main class.
            $separator = if ($working.Length -eq 0 -or $working.EndsWith("`n")) { '' } else { $lineBreak }
            $newContent = $working + $separator + $agentArg
        }
    } else {
        $newContent = $working.Substring(0, $javaOptionsStart) + $agentArg + ' ' + $working.Substring($javaOptionsStart)
    }

    if ($TargetSpec.RequiresJavaPrefix) {
        $finalPrefix = [regex]::Match(
            $newContent,
            $javaPrefixPattern,
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if (-not $finalPrefix.Success) {
            throw "Could not safely install the prepatcher agent in $($TargetSpec.Path). No changes were made."
        }
        $finalOptionsStart = $finalPrefix.Index + $finalPrefix.Length
    } else {
        $finalOptionsStart = 0
    }
    $orderedAgents = [regex]::Matches($newContent.Substring($finalOptionsStart), $javaAgentPattern)
    if ($orderedAgents.Count -lt 1) {
        throw "Could not safely install the prepatcher agent in $($TargetSpec.Path). No changes were made."
    }
    $actual = $orderedAgents[$orderedAgents.Count - 1].Value
    if (-not $actual.Equals($agentArg, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Could not safely place the prepatcher agent after the existing javaagents in $($TargetSpec.Path). No changes were made."
    }

    [pscustomobject]@{
        TargetSpec = $TargetSpec
        OriginalContent = $Content
        NewContent = $newContent
        ExistingAgentCount = $otherAgents.Count
    }
}

# Preflight every selected file before writing any of them. This avoids a
# predictable half-installed multi-target result when one target is missing or malformed.
$plans = foreach ($targetSpec in $selectedTargets) {
    if (-not (Test-Path -LiteralPath $targetSpec.Path -PathType Leaf)) {
        throw "$($targetSpec.Name) vmparams not found: $($targetSpec.Path)`nThe mod must be inside <Starsector>\mods\$modFolder."
    }
    $content = [System.IO.File]::ReadAllText($targetSpec.Path)
    if ($targetSpec.RequiresJavaPrefix -and $content -notmatch '(?i)-noverify') {
        Write-Warning "$($targetSpec.Path) does not contain -noverify. Vanilla 0.98a-RC8 normally has it; keep it enabled because the obfuscated core contains identifiers rejected by full bytecode verification."
    }
    New-InstallationPlan -TargetSpec $targetSpec -Content $content
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$changed = 0
$writtenPlans = [Collections.Generic.List[object]]::new()
try {
    foreach ($plan in $plans) {
        $path = $plan.TargetSpec.Path
        if ($plan.NewContent -eq $plan.OriginalContent) {
            Write-Host "$($plan.TargetSpec.Name): the StarsectorPrepatcher javaagent is already installed in the correct order." -ForegroundColor Yellow
            continue
        }

        $backup = "$path.spp-backup-$timestamp"
        Copy-Item -LiteralPath $path -Destination $backup
        [System.IO.File]::WriteAllText($path, $plan.NewContent, $encoding)
        $writtenPlans.Add($plan)
        $changed++

        Write-Host "$($plan.TargetSpec.Name): installed the StarsectorPrepatcher javaagent." -ForegroundColor Green
        Write-Host "vmparams backup: $backup"
        Write-Host "Placed after existing javaagents: $($plan.ExistingAgentCount)"
    }
} catch {
    foreach ($written in $writtenPlans) {
        [System.IO.File]::WriteAllText(
                $written.TargetSpec.Path, $written.OriginalContent, $encoding)
    }
    throw "Installation failed and all files changed in this transaction were restored: $($_.Exception.Message)"
}

if ($changed -eq 0) {
    Write-Host 'No files changed.' -ForegroundColor Yellow
}
Write-Host "Managed entry: $agentArg"
Write-Host "Detected Faster Rendering layout: $fasterRenderingLayout (runtime behavior probe remains authoritative)."
Write-Host 'No --add-exports flags are required; ASM is isolated inside the agent JAR.'
