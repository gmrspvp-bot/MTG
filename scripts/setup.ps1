# Everything a fresh clone needs, then start the 3D table. Run by "Play 3D.cmd".
#
# Forge itself is committed, so there is nothing to download: this checks what is present,
# builds what is missing, and launches. It is safe to run every time - each step is skipped
# when it is already done, which is why the .cmd can just call it unconditionally.
#
#   powershell -ExecutionPolicy Bypass -File scripts\setup.ps1          # set up and play
#   powershell -ExecutionPolicy Bypass -File scripts\setup.ps1 -CheckOnly
#   powershell -ExecutionPolicy Bypass -File scripts\setup.ps1 -Rebuild

param(
    [switch] $CheckOnly,   # report what is missing and stop
    [switch] $Rebuild,     # force a recompile even if classes look current
    [switch] $NoUi3d       # launch Forge's own window instead of the 3D table
)

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$forge = Join-Path $root 'forge-installer-2.0.15'

function Step($text)  { Write-Host "  $text" }
function Ok($text)    { Write-Host "  [ok]   $text" -ForegroundColor Green }
function Work($text)  { Write-Host "  [..]   $text" -ForegroundColor Cyan }
function Bad($text)   { Write-Host "  [!]    $text" -ForegroundColor Red }

Write-Host ""
Write-Host "MTG 3D table - setup" -ForegroundColor Cyan
Write-Host ""

# ---- 1. Java ---------------------------------------------------------------------------------
# A JDK, not just a JRE: the launcher compiles the agent hooks and the 3D UI from source.
$javac = $null
$java  = $null
$candidates = @(
    'C:\Program Files\BellSoft\LibericaJDK-17',
    'C:\Program Files\BellSoft\LibericaJDK-21',
    $env:JAVA_HOME
) | Where-Object { $_ }

foreach ($jdk in $candidates) {
    $c = Join-Path $jdk 'bin\javac.exe'
    if (Test-Path $c) { $javac = $c; $java = Join-Path $jdk 'bin\java.exe'; break }
}
if (-not $javac) {
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { $javac = $onPath.Source; $java = (Get-Command java).Source }
}
if (-not $javac) {
    Bad "No JDK found."
    Write-Host ""
    Write-Host "  Install a JDK 17 or newer, then run this again. Either of these is fine:"
    Write-Host "    winget install BellSoft.LibericaJDK.17.Full"
    Write-Host "    winget install EclipseAdoptium.Temurin.21.JDK"
    Write-Host ""
    Write-Host "  A JRE is not enough - this compiles the AI hooks and the 3D UI from source."
    exit 1
}
$ver = (& $javac -version 2>&1) -join ''
Ok "JDK: $ver"

# ---- 2. Forge --------------------------------------------------------------------------------
# Committed to this repo on purpose, so that a clone runs without fetching anything and Forge
# cannot change underneath the decks and AI patches that were tested against it.
$desktopJar = Join-Path $forge 'forge-gui-desktop-2.0.15-jar-with-dependencies.jar'
$mobileJar  = Join-Path $forge 'forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar'
foreach ($jar in @($desktopJar, $mobileJar)) {
    if (-not (Test-Path $jar)) {
        Bad "Missing: $jar"
        Write-Host ""
        Write-Host "  Forge is committed to this repo, so this should never happen on a clean"
        Write-Host "  clone. If the file was deleted locally, restore it with:"
        Write-Host "    git checkout -- forge-installer-2.0.15"
        exit 1
    }
}
Ok "Forge 2.0.15 present (desktop and mobile jars)"

# ---- 3. Our overrides on top of the Forge install ---------------------------------------------
# forge.profile.properties points Forge's user data at ..\forge-data so everything stays in the
# project folder; Realistic.ai is the AI profile every sim and game uses.
$overrides = Join-Path $root 'setup\forge-installer-2.0.15'
if (Test-Path $overrides) {
    Copy-Item -Recurse -Force (Join-Path $overrides '*') $forge
    Ok "Applied setup overrides (profile paths, Realistic AI)"
}

# ---- 4. The Jarvis skin ------------------------------------------------------------------------
# Generated from Forge's default skin rather than committed, since it is derived.
if (-not (Test-Path (Join-Path $forge 'res\skins\Jarvis'))) {
    Work "Building the Jarvis skin..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'make-jarvis-skin.ps1') | Out-Null
    Ok "Jarvis skin built"
} else {
    Ok "Jarvis skin present"
}

# ---- 5. Compile -------------------------------------------------------------------------------
# Rebuilt when any source is newer than the newest class, so a pull that changes code is picked
# up without anyone having to remember to rebuild.
function Needs-Build($srcDir, $outDir) {
    if ($Rebuild) { return $true }
    if (-not (Test-Path $outDir)) { return $true }
    $newestSrc = Get-ChildItem -Recurse -File -Filter *.java $srcDir -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $newestOut = Get-ChildItem -Recurse -File -Filter *.class $outDir -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newestSrc) { return $false }
    if (-not $newestOut) { return $true }
    return $newestSrc.LastWriteTime -gt $newestOut.LastWriteTime
}

if (Needs-Build (Join-Path $root 'scripts\tools') (Join-Path $root 'scripts\tools\classes')) {
    Work "Compiling the AI hooks and agent..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-tools.ps1') | Out-Null
    Ok "AI hooks built"
} else {
    Ok "AI hooks up to date"
}

if (Needs-Build (Join-Path $root 'ui3d\src') (Join-Path $root 'ui3d\classes')) {
    Work "Compiling the 3D table..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-ui3d.ps1') | Out-Null
    Ok "3D table built"
} else {
    Ok "3D table up to date"
}

Write-Host ""
if ($CheckOnly) {
    Write-Host "Everything is in place. Run 'Play 3D.cmd' to play." -ForegroundColor Green
    exit 0
}

# ---- 6. Play -----------------------------------------------------------------------------------
Write-Host "Starting Forge..." -ForegroundColor Cyan
Write-Host ""
Write-Host "  Two windows open: the 3D table, and Forge's own window behind it." -ForegroundColor DarkGray
Write-Host "  Forge's window still answers anything the 3D table does not handle yet," -ForegroundColor DarkGray
Write-Host "  so leave it running. See ui3d\README.md for the controls." -ForegroundColor DarkGray
Write-Host ""

if ($NoUi3d) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'play.ps1')
} else {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'play.ps1') -Ui3d
}
