# Opens the 3D table on demo data (no Forge, no game) so the view can be looked at quickly.
#
#   powershell -ExecutionPolicy Bypass -File scripts\run-ui3d.ps1
#
# TAB toggles between the seated and overhead camera; ESC hands the camera back to the game
# (in the demo, combat flips on and off every eight seconds so the transition can be watched).
# Hover a card for its details.

param(
    [switch] $Rebuild
)

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$forge   = Join-Path $root 'forge-installer-2.0.15'
$desktop = Join-Path $forge 'forge-gui-desktop-2.0.15-jar-with-dependencies.jar'
$mobile  = Join-Path $forge 'forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar'
$classes = Join-Path $root 'ui3d\classes'

if ($Rebuild -or -not (Test-Path $classes)) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-ui3d.ps1')
}

# The JDK setup.ps1 picked, exactly as the build and play scripts do. Preferring the hardcoded
# Liberica path over the one setup published is what had a second machine building with javac
# 17.0.1 against classes compiled on 21 (2026-10-21).
$java = $env:MTG_JAVA
if (-not ($java -and (Test-Path $java))) { $java = 'C:\Program Files\BellSoft\LibericaJDK-17\bin\java.exe' }
if (-not (Test-Path $java)) {
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { $java = $c.Source } else { throw "No java.exe found." }
}

# LibGDX unpacks its native libraries at startup; keep that inside the project folder.
$natives = Join-Path $root 'ui3d\natives'
New-Item -ItemType Directory -Force -Path $natives | Out-Null

& $java "-Djava.io.tmpdir=$natives" -cp "$classes;$mobile;$desktop" mtg3d.Demo
