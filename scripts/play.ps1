# Starts Forge's normal desktop app so you can play yourself against AI opponents,
# with our AI patches (scripts\tools\AiPatches.java), the board-evaluation cache, the
# Realistic AI profile and the custom card fixes in forge-data\custom\cards. Launched by
# "Play Forge.cmd" in the project folder. Games with a human player get the same per-game
# helpers as the simulations (ThreatTracker, AiPatches.TurnHook, BoardRecorder snapshots)
# through scripts\tools\PlayHook.java, and each finished game's full log is saved to
# scratch\play-logs\game_<time>.log so the user's own play can be studied.
#
# Before starting it copies decks\*.dck into forge-data\decks\commander (the folder the
# desktop app reads; the originals in decks\ are never touched).

param(
    [string] $AiPatches = 'catch,counterall,threat,rampfirst,cmdrfirst,captain,humanlike,abilities,deploy,deathsave',
    [string] $AiProfile = 'Realistic',
    [int] $HeapMB = 4096
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$root     = Split-Path -Parent $PSScriptRoot
$forgeDir = Join-Path $root 'forge-installer-2.0.15'
$java     = 'C:\Program Files\BellSoft\LibericaJDK-17\bin\java.exe'
# Fall back to the JDK on PATH when Liberica 17 is not installed (JDK 21 works too).
if (-not (Test-Path $java)) {
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { $java = $c.Source } else { throw "No java.exe found (install BellSoft Liberica JDK 17 or put a JDK on PATH)." }
}
$jar      = 'forge-gui-desktop-2.0.15-jar-with-dependencies.jar'
$toolClasses = Join-Path $PSScriptRoot 'tools\classes'

# Simulations still running? Playing alongside them slows both down.
$sims = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'SimLauncher sim ' })
if ($sims.Count -gt 0) {
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "$($sims.Count) simulation processes are still running.`n`nStart Forge anyway? (It will be slower until they finish.)",
        'Play Forge', 'YesNo', 'Warning')
    if ($answer -ne 'Yes') { exit 0 }
}

# Decks: copy new or changed .dck files into the desktop app's Commander deck folder.
$cmdrDecks = Join-Path $root 'forge-data\decks\commander'
New-Item -ItemType Directory -Force -Path $cmdrDecks | Out-Null
foreach ($d in Get-ChildItem (Join-Path $root 'decks') -Filter '*.dck') {
    $dest = Join-Path $cmdrDecks $d.Name
    if (-not (Test-Path $dest) -or (Get-Item $dest).LastWriteTime -lt $d.LastWriteTime) {
        Copy-Item $d.FullName $dest -Force
    }
}

# Preferences the desktop app reads from forge.preferences (created on first run): the AI
# profile, and custom cards allowed in decks - our AI-fix card files (forge-data\custom\cards,
# rules text unchanged) count as "custom", and without this the decks show as not legal.
$prefFile = Join-Path $root 'forge-data\preferences\forge.preferences'
New-Item -ItemType Directory -Force -Path (Split-Path $prefFile) | Out-Null
$prefs = if (Test-Path $prefFile) { @([IO.File]::ReadAllLines($prefFile)) } else { @() }
$prefs = @($prefs | Where-Object { $_ -notmatch '^(UI_CURRENT_AI_PROFILE|ALLOW_CUSTOM_CARDS_IN_DECKS_CONFORMANCE)=' }) +
         "UI_CURRENT_AI_PROFILE=$AiProfile" + 'ALLOW_CUSTOM_CARDS_IN_DECKS_CONFORMANCE=true'
# Show every player's battlefield at once (Rows: you at the bottom, all opponents side by side
# on top, each in their own panel). Only replaces Forge's default "OFF", so a layout picked in
# Forge is kept. GRID was tried first (2026-10-12) but Forge's grid code crashed twice ("Gap
# was not filled" in VMatchUI.cleanupDynamicCells): when players were knocked out, then at the
# start of the next game from the saved layout.
if (-not ($prefs -match '^UI_MULTIPLAYER_FIELD_LAYOUT=(GRID|ROWS)$')) {
    $prefs = @($prefs | Where-Object { $_ -notmatch '^UI_MULTIPLAYER_FIELD_(LAYOUT|PANELS)=' }) +
             'UI_MULTIPLAYER_FIELD_LAYOUT=ROWS' + 'UI_MULTIPLAYER_FIELD_PANELS=SPLIT'
}
# If the last session hit that layout crash, the saved match layout (match.xml) can make the
# next game crash at start: move it aside so Forge builds a fresh one.
$lastErr = Get-ChildItem (Join-Path $root 'scratch\play-logs') -Filter 'console_*.log.err' -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime | Select-Object -Last 1
$matchXml = Join-Path $root 'forge-data\preferences\match.xml'
if ($lastErr -and (Test-Path $matchXml) -and (Select-String -Path $lastErr.FullName -Pattern 'Gap was not filled' -Quiet)) {
    $bk = Join-Path $root 'scratch\layout-backup'
    New-Item -ItemType Directory -Force -Path $bk | Out-Null
    Move-Item $matchXml (Join-Path $bk ('match_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.xml')) -Force
}
# One-time "don't skip my turn by accident" settings (2026-10-12), applied once so later
# changes made in Forge's own preferences are kept: remind when getting priority, also stop
# at your beginning of combat and end step, and auto-pass moved from the bare P key to Ctrl+P.
$marker = Join-Path $root 'scratch\play-prefs-turnsafety.done'
if (-not (Test-Path $marker)) {
    $set = [ordered]@{ UI_REMIND_ON_PRIORITY = 'true'; PHASE_HUMAN_BEGINCOMBAT = 'true'; PHASE_HUMAN_EOT = 'true'; SHORTCUT_YIELD_AUTO_PASS = '17 80' }
    $prefs = @($prefs | Where-Object { $k = ($_ -split '=', 2)[0]; -not $set.Contains($k) }) + @($set.Keys | ForEach-Object { "$_=$($set[$_])" })
    Set-Content -Path $marker -Value (Get-Date -Format s) -Encoding UTF8
}
# One-time "back button" settings (2026-10-13, the user asked for a way back after misclicks):
# Developer mode shows Forge's Dev panel, whose "Rollback Phase" returns the game to the start of
# the current phase; the experimental restore lets Ctrl+Z undo the last spell or ability.
$marker2 = Join-Path $root 'scratch\play-prefs-rollback.done'
if (-not (Test-Path $marker2)) {
    $set = [ordered]@{ DEV_MODE_ENABLED = 'true'; MATCH_EXPERIMENTAL_RESTORE = 'true' }
    $prefs = @($prefs | Where-Object { $k = ($_ -split '=', 2)[0]; -not $set.Contains($k) }) + @($set.Keys | ForEach-Object { "$_=$($set[$_])" })
    Set-Content -Path $marker2 -Value (Get-Date -Format s) -Encoding UTF8
}
# One-time "feel more like Hearthstone" settings (2026-10-20): Forge's own options, off by
# default. Playable cards are highlighted; priority is passed automatically when there's
# nothing you could do, but still stops for attackers, opponents' spells, targeting and board
# wipes (when you could respond). Ctrl+E (SHORTCUT_ENDTURN) is "end turn".
$marker3 = Join-Path $root 'scratch\play-prefs-hearthstone.done'
if (-not (Test-Path $marker3)) {
    $set = [ordered]@{ UI_SHOW_ACTIONABLE_HIGHLIGHTS = 'true'; YIELD_AUTO_PASS_NO_ACTIONS = 'true'; YIELD_AUTO_PASS_RESPECTS_INTERRUPTS = 'true';
                       YIELD_INTERRUPT_ON_ATTACKERS = 'true'; YIELD_INTERRUPT_ON_OPPONENT_SPELL = 'true'; YIELD_INTERRUPT_ON_TARGETING = 'true'; YIELD_INTERRUPT_ON_MASS_REMOVAL = 'true' }
    $prefs = @($prefs | Where-Object { $k = ($_ -split '=', 2)[0]; -not $set.Contains($k) }) + @($set.Keys | ForEach-Object { "$_=$($set[$_])" })
    Set-Content -Path $marker3 -Value (Get-Date -Format s) -Encoding UTF8
}
[IO.File]::WriteAllLines($prefFile, [string[]]$prefs)

# Same launch as run-sims.ps1, minus the "sim" arguments: SimLauncher applies the AI
# patches and then opens the normal Forge window.
$opens = @('java.desktop/java.beans','java.desktop/javax.swing.border','java.desktop/javax.swing.event','java.desktop/sun.swing',
           'java.desktop/java.awt.image','java.desktop/java.awt.color','java.desktop/sun.awt.image','java.desktop/javax.swing',
           'java.desktop/java.awt','java.base/java.util','java.base/java.lang','java.base/java.lang.reflect','java.base/java.text',
           'java.desktop/java.awt.font','java.base/jdk.internal.misc','java.base/sun.nio.ch','java.base/java.nio',
           'java.base/java.math','java.base/java.util.concurrent','java.base/java.net') |
         ForEach-Object { "--add-opens $_=ALL-UNNAMED" }
$agentArgs = "-javaagent:`"$(Join-Path $PSScriptRoot 'tools\evalcache-agent.jar')`" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"
# Game logs (PlayHook) and Forge's console output (incl. the AI's COUNTERAI / THREATAI /
# ATTACKAI decisions) both go to scratch\play-logs.
$logDir = Join-Path $root 'scratch\play-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$launcher = 'SimLauncher'
$argLine = "-Xmx$($HeapMB)m -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 $($opens -join ' ') $agentArgs " +
           "-Dforge.aipatches=$AiPatches -Dforge.playlog.dir=`"$logDir`" -cp `"$toolClasses;$jar`" $launcher"
$log = Join-Path $logDir ('console_' + (Get-Date -Format 'yyyy-MM-dd_HHmmss') + '.log')
Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $forgeDir `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden | Out-Null
