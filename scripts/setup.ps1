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
    [switch] $NoUi3d,      # launch Forge's own window instead of the 3D table
    [switch] $Strict,      # say on screen whenever Forge answers something the 3D table should own
    [switch] $NoForgeHelp  # same as -Strict, named for what it is about: not leaning on Forge's window
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
# A JDK already fetched into this folder by an earlier run.
$localJdk = Join-Path $root 'tools\jdk'
if (-not $javac -and (Test-Path $localJdk)) {
    $c = Get-ChildItem -Path $localJdk -Filter javac.exe -Recurse -ErrorAction SilentlyContinue |
         Select-Object -First 1
    if ($c) { $javac = $c.FullName; $java = Join-Path $c.DirectoryName 'java.exe' }
}

# Nothing on the machine: fetch a portable JDK into this folder. Deliberately NOT an install -
# no admin prompt, no PATH change, nothing outside this directory, and deleting the folder
# undoes it completely. Only ever downloaded once.
if (-not $javac) {
    Work "No JDK found. Fetching a portable one into tools\jdk (about 180 MB, once)..."
    $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse'
    $zip = Join-Path $env:TEMP 'mtg-temurin-21.zip'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'   # the progress bar makes this several times slower
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
        New-Item -ItemType Directory -Force -Path $localJdk | Out-Null
        Expand-Archive -Path $zip -DestinationPath $localJdk -Force
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        $c = Get-ChildItem -Path $localJdk -Filter javac.exe -Recurse -ErrorAction SilentlyContinue |
             Select-Object -First 1
        if ($c) { $javac = $c.FullName; $java = Join-Path $c.DirectoryName 'java.exe' }
    } catch {
        Bad "Could not download a JDK: $($_.Exception.Message)"
    }
}

if (-not $javac) {
    Bad "No JDK found, and one could not be fetched."
    Write-Host ""
    Write-Host "  This needs an internet connection the first time. If you are offline, or the"
    Write-Host "  download is blocked, install a JDK 17 or newer yourself and run this again:"
    Write-Host "    winget install EclipseAdoptium.Temurin.21.JDK"
    Write-Host "    winget install BellSoft.LibericaJDK.17.Full"
    Write-Host ""
    Write-Host "  A JRE is not enough - this compiles the AI hooks and the 3D UI from source."
    exit 1
}
$ver = (& $javac -version 2>&1) -join ''
# build-tools, build-ui3d and play all find Java by looking on PATH after a hardcoded Liberica
# path. Putting whichever JDK we settled on at the front of PATH for this process means they all
# inherit it and need no changes - and it is what makes a portable JDK in tools\jdk work for the
# whole pipeline rather than only here. Only affects this process and its children.
$jdkBin = Split-Path -Parent $javac
$env:PATH = "$jdkBin;$env:PATH"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Split-Path -Parent $jdkBin }
if ($javac -like "$localJdk*") { Ok "JDK: $ver (portable, in tools\jdk)" } else { Ok "JDK: $ver" }

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
Write-Host "  Play in the 3D table. Forge's own window minimises itself once the game starts -" -ForegroundColor DarkGray
Write-Host "  the game runs inside it, so do not close it; it comes back if Forge needs it" -ForegroundColor DarkGray
Write-Host "  (or press H). See ui3d\README.md for the controls." -ForegroundColor DarkGray
Write-Host ""

if ($NoUi3d) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'play.ps1')
} elseif ($Strict -or $NoForgeHelp) {
    # Forge's window still answers what the 3D table cannot - taking that away would hang the
    # game on the first unhandled decision. What this does is make every such hand-off VISIBLE,
    # on screen as it happens and tallied in any bug report, so the remaining gaps are a list
    # rather than "it pulled me to the other window again".
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'play.ps1') -Ui3d `
        -JvmArgs "-Dforge.ui3d.strict=true"
} else {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'play.ps1') -Ui3d
}
