# Compiles the 3D table UI (ui3d\src) into ui3d\classes.
#
# The 3D stack is LibGDX, which Forge already ships: forge-gui-mobile-dev-2.0.15-jar-with-
# dependencies.jar is Forge's own libGDX UI built for desktop, so it carries libgdx, the LWJGL3
# backend and the native libraries. Nothing is downloaded and nothing is installed outside this
# folder. The desktop jar is on the classpath too, for the classes that read Forge's game state.
#
#   powershell -ExecutionPolicy Bypass -File scripts\build-ui3d.ps1

param(
    [switch] $Clean      # wipe the output first; only needed after deleting a source file
)

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

# The JDK setup.ps1 settled on, if it said; otherwise Liberica 17 where it is installed, then
# whatever is on PATH. Setup's choice has to come first or a machine with 17 installed compiles
# the 3D table on 17 while the hooks were built on 21.
$javac = $env:MTG_JAVAC
if (-not ($javac -and (Test-Path $javac))) { $javac = 'C:\Program Files\BellSoft\LibericaJDK-17\bin\javac.exe' }
if (-not (Test-Path $javac)) {
    $c = Get-Command javac -ErrorAction SilentlyContinue
    if ($c) { $javac = $c.Source } else { throw "No javac found (install a JDK, or Liberica 17)." }
}

# Do NOT wipe the output first. javac overwrites what it rebuilds, and wiping leaves a second or
# two where the folder is empty - long enough for a 3D window launched in that window to die with
# NoClassDefFoundError, which happened and looks exactly like a code fault. -Clean when a class
# whose source was deleted needs clearing out.
if ($Clean -and (Test-Path $out)) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName }
if (-not $files) { throw "No sources under $src" }

$cp = "$desktop;$mobile"
& $javac -nowarn -encoding UTF-8 -cp $cp -d $out $files
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Host "Compiled $($files.Count) sources into $out"
