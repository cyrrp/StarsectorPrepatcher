# format.ps1 — check or apply the repo's Java style via vendored Eclipse JDT.
#   tools\format\format.ps1 check  [path ...]   exit 1 if any file would change
#   tools\format\format.ps1 apply  [path ...]   rewrite files in place
# Options: -Config <file> -JavaHome <jdk dir> (also accepts --config/--java-home).
# Default path: source\.
# Exit codes: 0 clean/applied, 1 differences found (check), 2 usage/tool error.
# Nothing is downloaded: jars come from tools/format/lib/ and the driver is
# compiled with the local JDK into .build/format/classes (cached).
param(
  [Parameter(Position = 0)] [string] $Action,
  [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Paths,
  [string] $Config,
  [string] $JavaHome
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $here '..\..')

# Support --config/--java-home inside the remaining-arguments bag.
$rest = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $Paths.Count; $i++) {
  switch -Regex ($Paths[$i]) {
    '^(--config|-Config)$'  { $Config = $Paths[++$i] }
    '^(--java-home|-JavaHome)$' { $JavaHome = $Paths[++$i] }
    default { $rest.Add($Paths[$i]) }
  }
}
$Paths = $rest.ToArray()
if (-not $Config) { $Config = Join-Path $here 'eclipse-jdt-format.properties' }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($Action -notin @('check', 'apply')) {
  Write-Error 'usage: format.ps1 check|apply [path ...]'
  exit 2
}
if ($Paths.Count -eq 0) { $Paths = @((Join-Path $root 'source')) }

if ($JavaHome -and (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
  $java = Join-Path $JavaHome 'bin\java.exe'
  $javac = Join-Path $JavaHome 'bin\javac.exe'
} elseif ((Get-Command java -ErrorAction SilentlyContinue) -and (Get-Command javac -ErrorAction SilentlyContinue)) {
  $java = 'java'; $javac = 'javac'
} else {
  Write-Error 'No JDK found: set JAVA_HOME or -JavaHome (JDK 17+ required)'
  exit 2
}

$cp = Join-Path $here 'lib\*'
$out = Join-Path $root '.build\format\classes'
$stamp = Join-Path $out 'JdtFormat.class'
$driver = Join-Path $here 'JdtFormat.java'
if (-not (Test-Path $stamp) -or (Get-Item $driver).LastWriteTime -gt (Get-Item $stamp).LastWriteTime) {
  New-Item -ItemType Directory -Force $out | Out-Null
  & $javac -cp $cp -d $out $driver
  if ($LASTEXITCODE -ne 0) { exit 2 }
}
& $java -cp "$cp;$out" JdtFormat --config $Config "--$Action" @Paths
exit $LASTEXITCODE
