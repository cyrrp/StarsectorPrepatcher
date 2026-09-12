# Fetch the Eclipse JDT formatter jars into .toolcache/format/lib, verifying every download against
# SHA256SUMS.txt. These are developer tools only: they are never shipped with the mod, so they are
# downloaded on demand instead of being committed. Re-running is cheap; files already present with
# the right checksum are left alone.
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$modRoot = (Resolve-Path (Join-Path $here '..\..')).Path
$lib = Join-Path $modRoot '.toolcache\format\lib'
$base = if ($env:FORMATTER_MAVEN_BASE) { $env:FORMATTER_MAVEN_BASE } else { 'https://repo1.maven.org/maven2' }

$expected = @{}
foreach ($line in Get-Content (Join-Path $here 'SHA256SUMS.txt')) {
    if ($line -match '^([0-9a-f]{64})\s+(\S+)$') { $expected[$Matches[2]] = $Matches[1] }
}

New-Item -ItemType Directory -Force -Path $lib | Out-Null
$fetched = 0
foreach ($line in Get-Content (Join-Path $here 'artifacts.txt')) {
    if ($line -match '^\s*(#|$)') { continue }
    $parts = $line -split '\s+', 2
    $name = $parts[0]
    $path = $parts[1]
    if (-not $expected.ContainsKey($name)) {
        throw "No checksum recorded for $name in SHA256SUMS.txt; refusing to fetch it."
    }
    $target = Join-Path $lib $name
    if (Test-Path -LiteralPath $target) {
        $have = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($have -eq $expected[$name]) { continue }
    }
    "fetching $name"
    $tmp = "$target.part"
    Invoke-WebRequest -Uri "$base/$path" -OutFile $tmp -UseBasicParsing
    $actual = (Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected[$name]) {
        Remove-Item -LiteralPath $tmp -Force
        throw "Checksum mismatch for ${name}: expected $($expected[$name]), actual $actual"
    }
    Move-Item -LiteralPath $tmp -Destination $target -Force
    $fetched++
}

"formatter libraries ready in $lib ($fetched fetched)"
