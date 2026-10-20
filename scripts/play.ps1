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
    [int] $HeapMB = 4096,
    # Forge skin. 'Jarvis' is ours (scripts/make-jarvis-skin.ps1), built on first use.
    # -Skin Default restores Forge's own look; the skin can also be changed inside Forge.
    [string] $Skin = 'Jarvis',
    # Opens the 3D table (ui3d\) alongside Forge's own UI. Read-only for now.
    [switch] $Ui3d,
    # Extra -D switches passed straight to the JVM, e.g. -JvmArgs "-Dforge.ui3d.strict=true".
    [string] $JvmArgs = ''
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

# One-time: space bar ends the turn (2026-10-20, second owner's request). 32 = VK_SPACE; the
# previous binding was Ctrl+E (17 69), which stays available as the yield-options shortcut is
# untouched. Applied once, so a different binding chosen inside Forge is kept.
$marker4 = Join-Path $root 'scratch\play-prefs-spacebar.done'
if (-not (Test-Path $marker4)) {
    $set = [ordered]@{ SHORTCUT_ENDTURN = '32' }
    $prefs = @($prefs | Where-Object { $k = ($_ -split '=', 2)[0]; -not $set.Contains($k) }) + @($set.Keys | ForEach-Object { "$_=$($set[$_])" })
    Set-Content -Path $marker4 -Value (Get-Date -Format s) -Encoding UTF8
}

# Skin. The Jarvis skin is generated from Forge's default (the install is not in git), so build
# it on first use. Applied once, like the other pref changes, so a skin chosen inside Forge wins.
if ($Skin -and $Skin -ne 'Default') {
    $skinDir = Join-Path $forgeDir "res\skins\$Skin"
    if (-not (Test-Path $skinDir)) {
        Write-Host "Building the $Skin skin..."
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'make-jarvis-skin.ps1') -Name $Skin | Out-Null
    }
}
# The 3D window is the one being played in, so Forge must stop pulling itself in front of it.
# UI_REMIND_ON_PRIORITY raises Forge's window every time priority arrives - right for playing in
# Forge's own window, exactly wrong here. Set per launch rather than once behind a marker,
# because it depends on which window you are playing in, not on a one-time preference.
if ($Ui3d) {
    $prefs = @($prefs | Where-Object { ($_ -split '=', 2)[0] -ne 'UI_REMIND_ON_PRIORITY' }) + 'UI_REMIND_ON_PRIORITY=false'
    # YIELD_AUTO_PASS_NO_ACTIONS passes priority for you whenever Forge thinks you have nothing
    # to do - InputPassPriority.checkHasAvailableActions() -> PlayerView.hasAvailableActions().
    # It saves clicks in Forge's own window. In the 3D table it eats turns, because casting a
    # mana rock taps you out and the rock is still on the stack, so at that instant you really
    # do have no available actions: Forge passes the rest of the turn, and Sol Ring resolves
    # into a turn that is already over. Three bug reports, two of them naming a mana rock
    # (Sol Ring, Fellwar Stone). SPACE already passes here, so the clicks it saves are not
    # worth the turns it costs. Per launch, like the reminder above - it depends on which
    # window you are playing in, so Forge's own window keeps the setting dmara chose.
    $prefs = @($prefs | Where-Object { ($_ -split '=', 2)[0] -ne 'YIELD_AUTO_PASS_NO_ACTIONS' }) + 'YIELD_AUTO_PASS_NO_ACTIONS=false'
}

$marker5 = Join-Path $root 'scratch\play-prefs-skin.done'
if ((Test-Path (Join-Path $forgeDir "res\skins\$Skin")) -and -not (Test-Path $marker5)) {
    $prefs = @($prefs | Where-Object { ($_ -split '=', 2)[0] -ne 'UI_SKIN' }) + "UI_SKIN=$Skin"
    Set-Content -Path $marker5 -Value (Get-Date -Format s) -Encoding UTF8
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
$ui3dCp = ''
$ui3dArg = ''
if ($Ui3d) {
    $ui3dClasses = Join-Path $root 'ui3d\classes'
    if (-not (Test-Path $ui3dClasses)) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-ui3d.ps1') | Out-Null
    }
    $mobileJar = Join-Path $forgeDir 'forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar'
    $ui3dCp = ";$ui3dClasses;$mobileJar"
    $ui3dArg = '-Dforge.ui3d=true '
}
$argLine = "$JvmArgs -Xmx$($HeapMB)m -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 $($opens -join ' ') $agentArgs " +
           "$ui3dArg-Dforge.aipatches=$AiPatches -Dforge.playlog.dir=`"$logDir`" -cp `"$toolClasses;$jar$ui3dCp`" $launcher"
$log = Join-Path $logDir ('console_' + (Get-Date -Format 'yyyy-MM-dd_HHmmss') + '.log')
Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $forgeDir `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden | Out-Null
