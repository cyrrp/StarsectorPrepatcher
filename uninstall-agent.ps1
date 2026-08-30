[CmdletBinding()]
param(
    [string] $Target = 'Vanilla'
)

$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$agentArg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar"
$managedAgentTokenPattern = '-javaagent:"?\.\.[\\/]+mods[\\/]+' +
        [regex]::Escape($modFolder) +
        '[\\/]+agent[\\/]+StarsectorPrepatcherAgent\.jar"?'
$managedAgentLinePattern = '(?i)^\s*' + $managedAgentTokenPattern + '\s*$'
$availableTargets = @(
    [pscustomobject]@{
        Name = 'Vanilla'
        Path = Join-Path $gameRoot 'vmparams'
        IsArgumentFile = $false
    },
    [pscustomobject]@{
        Name = 'FasterRendering'
        Path = Join-Path $gameRoot 'starsector-core\fr.vmparams'
        IsArgumentFile = $true
    },
    [pscustomobject]@{
        Name = 'MikohimeSimple'
        Path = Join-Path $gameRoot 'Miko_Simple.txt'
        IsArgumentFile = $true
    },
    [pscustomobject]@{
        Name = 'MikohimeTemplate'
        Path = Join-Path $gameRoot 'mikohime\DefaultVM'
        IsArgumentFile = $true
    },
    [pscustomobject]@{
        Name = 'MikohimeGenerator'
        Path = Join-Path $gameRoot 'Configure_Me.cmd'
        IsArgumentFile = $false
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
        -not $modernMikohimeGenerator -or $_.Name -ne 'MikohimeTemplate'
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

# Preflight every selected file before removing an entry from any of them.
$plans = foreach ($targetSpec in $selectedTargets) {
    if (-not (Test-Path -LiteralPath $targetSpec.Path -PathType Leaf)) {
        throw "$($targetSpec.Name) vmparams not found: $($targetSpec.Path)"
    }
    $content = [System.IO.File]::ReadAllText($targetSpec.Path)
    if ($targetSpec.Name -eq 'MikohimeGenerator') {
        $newContent = [regex]::Replace($content,
                '(?im)^set SPP_AGENT=.*(?:\r?\n|$)', '')
        $newContent = [regex]::Replace($newContent,
                '(?im)^[ \t]*echo[ \t]+%SPP_AGENT%[ \t]*>>[ \t]*(?:"?Miko_Simple\.txt"?|"?%OutputFile%"?)[ \t]*(?:\r?\n|$)', '')
        $newContent = [regex]::Replace($newContent,
                '(?im)^[ \t]*if[ \t]+defined[ \t]+SPP_AGENT[ \t]+exit[ \t]+/b[ \t]+0[ \t]*(?:\r?\n|$)', '')
        $newContent = $newContent.TrimEnd("`r", "`n") + "`r`n"
        [pscustomobject]@{
            TargetSpec = $targetSpec
            OriginalContent = $content
            NewContent = $newContent
        }
        continue
    }
    $pattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)[ \t]*'
    if ($targetSpec.IsArgumentFile) {
        $managedLinePattern = '(?im)^[ \t]*' +
                $managedAgentTokenPattern + '[ \t]*(?:\r?\n|$)'
        $newContent = [regex]::Replace($content, $managedLinePattern, '')
        $newContent = [regex]::Replace($newContent, $pattern, '')
    } else {
        $newContent = [regex]::Replace($content, $pattern, '')
    }
    [pscustomobject]@{
        TargetSpec = $targetSpec
        OriginalContent = $content
        NewContent = $newContent
    }
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$changed = 0
$writtenPlans = [Collections.Generic.List[object]]::new()
try {
    foreach ($plan in $plans) {
        $path = $plan.TargetSpec.Path
        if ($plan.NewContent -eq $plan.OriginalContent) {
            Write-Host "$($plan.TargetSpec.Name): the Prepatcher javaagent entry was not found; nothing changed." -ForegroundColor Yellow
            continue
        }

        $backup = "$path.spp-uninstall-backup-$timestamp"
        Copy-Item -LiteralPath $path -Destination $backup
        [System.IO.File]::WriteAllText($path, $plan.NewContent, $encoding)
        $writtenPlans.Add($plan)
        $changed++

        Write-Host "$($plan.TargetSpec.Name): removed the StarsectorPrepatcher javaagent." -ForegroundColor Green
        Write-Host "vmparams backup: $backup"
    }
} catch {
    foreach ($written in $writtenPlans) {
        [System.IO.File]::WriteAllText(
                $written.TargetSpec.Path, $written.OriginalContent, $encoding)
    }
    throw "Uninstall failed and all files changed in this transaction were restored: $($_.Exception.Message)"
}

if ($changed -eq 0) {
    Write-Host 'No files changed.' -ForegroundColor Yellow
}
