# Compiles the 3D table UI (ui3d\src) into ui3d\classes.
#
# The 3D stack is LibGDX, which Forge already ships: forge-gui-mobile-dev-2.0.15-jar-with-
# dependencies.jar is Forge's own libGDX UI built for desktop, so it carries libgdx, the LWJGL3
# backend and the native libraries. Nothing is downloaded and nothing is installed outside this
# folder. The desktop jar is on the classpath too, for the classes that read Forge's game state.
#
#   powershell -ExecutionPolicy Bypass -File scripts\build-ui3d.ps1

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$forge   = Join-Path $root 'forge-installer-2.0.15'
$desktop = Join-Path $forge 'forge-gui-desktop-2.0.15-jar-with-dependencies.jar'
$mobile  = Join-Path $forge 'forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar'
$src     = Join-Path $root 'ui3d\src'
$out     = Join-Path $root 'ui3d\classes'

foreach ($j in @($desktop, $mobile)) {
    if (-not (Test-Path $j)) { throw "Missing $j - extract Forge 2.0.15 into forge-installer-2.0.15\ first." }
}

# Liberica 17 if it is installed (the rest of the repo prefers it), otherwise whatever is on PATH.
$javac = 'C:\Program Files\BellSoft\LibericaJDK-17\bin\javac.exe'
if (-not (Test-Path $javac)) {
    $c = Get-Command javac -ErrorAction SilentlyContinue
    if ($c) { $javac = $c.Source } else { throw "No javac found (install a JDK, or Liberica 17)." }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName }
if (-not $files) { throw "No sources under $src" }

$cp = "$desktop;$mobile"
& $javac -nowarn -encoding UTF-8 -cp $cp -d $out $files
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Host "Compiled $($files.Count) sources into $out"
