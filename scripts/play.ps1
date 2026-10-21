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
    [string] $AiPatches = 'catch,counterall,threat,rampfirst,cmdrfirst,captain,humanlike,abilities,deploy,deathsave,dice,housestyle',
    [string] $AiProfile = 'Realistic',
    [int] $HeapMB = 4096,
    # Forge skin. 'Jarvis' is ours (scripts/make-jarvis-skin.ps1), built on first use.
    # -Skin Default restores Forge's own look; the skin can also be changed inside Forge.
    [string] $Skin = 'Jarvis',
    # Opens the 3D table (ui3d\) alongside Forge's own UI. Read-only for now.
    [switch] $Ui3d,
    # Extra -D switches passed straight to the JVM, e.g. -JvmArgs "-Dforge.ui3d.strict=true".
    [string] $JvmArgs = '',
    # Line the Commander deck folder up with decks\ and stop there, without starting Forge.
    # Worth running after a pull that changed the decks; a normal launch does it anyway.
    [switch] $DecksOnly
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$root     = Split-Path -Parent $PSScriptRoot
$forgeDir = Join-Path $root 'forge-installer-2.0.15'
# Run on the same JDK the classes were compiled with - setup.ps1 publishes it.
$java     = $env:MTG_JAVA
if (-not ($java -and (Test-Path $java))) { $java = 'C:\Program Files\BellSoft\LibericaJDK-17\bin\java.exe' }
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

# Decks: make the desktop app's Commander folder match decks\ exactly.
#
# This used to copy in anything newer and never take anything out, which left every machine with
# its own pile of leftovers. On 2026-10-22 the owner's picker still listed all 31 decks - the 14
# current ones, the 15 older versions the other owner had moved to decks\archive\, and two that
# had never been in git at all - and his Angels deck had been resaved through Forge's own deck
# editor as "Deck Type=Constructed" with the [commander] section gone, so Seluma was no longer
# his commander. Copying only newer files meant that could never right itself.
#
# So: decks\ is the source of truth, and this folder is made to match it on every launch. A deck
# that is not in decks\, or that differs from it, is MOVED to forge-data\decks-archive\ first -
# nothing is ever deleted, and an edit made in Forge's editor is still there to be picked up and
# committed if it was wanted.
$cmdrDecks = Join-Path $root 'forge-data\decks\commander'
$deckArchive = Join-Path $root 'forge-data\decks-archive'
New-Item -ItemType Directory -Force -Path $cmdrDecks | Out-Null

function Move-ToDeckArchive([System.IO.FileInfo] $file, [string] $why) {
    New-Item -ItemType Directory -Force -Path $deckArchive | Out-Null
    $stamp = $file.LastWriteTime.ToString('yyyyMMdd_HHmmss')
    $dest = Join-Path $deckArchive ("{0} ({1} {2}){3}" -f $file.BaseName, $why, $stamp, $file.Extension)
    Move-Item $file.FullName $dest -Force
    Write-Host ("Deck put aside: {0} - {1}" -f $file.Name, $why) -ForegroundColor DarkYellow
}

# A deck file's card sections, for comparing two copies: section -> sorted "count name" lines,
# with the printing (|SET|[number]) taken off. Section names are lowercased, since Forge's editor
# writes [Commander] / [Main] where our files have [commander] / [main].
function Get-DeckCards([string] $path) {
    $cards = @{}; $sec = ''
    foreach ($l in Get-Content -LiteralPath $path -Encoding UTF8) {
        if ($l -match '^\[(.+)\]\s*$') { $sec = $Matches[1].ToLower(); continue }
        if ($sec -eq 'metadata' -or $l -notmatch '^(\d+)\s+(.+?)\s*(\|.*)?$') { continue }
        if (-not $cards.ContainsKey($sec)) { $cards[$sec] = @{} }
        $name = $Matches[2]
        $cards[$sec][$name] = [int]$cards[$sec][$name] + [int]$Matches[1]
    }
    $flat = @{}
    foreach ($s in $cards.Keys) { $flat[$s] = (($cards[$s].Keys | Sort-Object | ForEach-Object { "$($cards[$s][$_]) $_" }) -join "`n") }
    return $flat
}

# The owner, 2026-10-22: art prints chosen in Forge's deck editor were reverted on every launch,
# because any difference from decks\ was put aside. An edit that keeps exactly the same cards in
# every section and only changes their printings is wanted, and is the one kind of editor save
# that cannot have broken the deck. So that kind is written back: decks\'s own [metadata] (name,
# description - the editor drops the description and adds "Deck Type=Constructed") followed by
# the editor's card sections, commander first. It then shows as a change in git, to commit.
function Merge-DeckPrints([string] $edited, [string] $target) {
    $meta = @()
    foreach ($l in Get-Content -LiteralPath $target -Encoding UTF8) {
        if ($l -match '^\[' -and $l -notmatch '^\[metadata\]') { break }
        $meta += $l
    }
    $sections = [ordered]@{}; $sec = $null
    foreach ($l in Get-Content -LiteralPath $edited -Encoding UTF8) {
        if ($l -match '^\[(.+)\]\s*$') { $sec = $Matches[1].ToLower(); if ($sec -ne 'metadata') { $sections[$sec] = @() }; continue }
        if ($sec -and $sec -ne 'metadata' -and $l.Trim()) { $sections[$sec] += $l }
    }
    $out = $meta
    $order = @('commander', 'main') + @($sections.Keys | Where-Object { $_ -ne 'commander' -and $_ -ne 'main' })
    foreach ($s in $order) {
        if (-not $sections.Contains($s)) { continue }
        $out += "[$s]"
        $out += $sections[$s]
    }
    [IO.File]::WriteAllText($target, (($out -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding $false))
}

$current = @{}
foreach ($d in Get-ChildItem (Join-Path $root 'decks') -Filter '*.dck') {
    $current[$d.Name] = $true
    $dest = Join-Path $cmdrDecks $d.Name
    if (Test-Path $dest) {
        # Compare the contents, not the dates: a deck resaved in Forge's editor is NEWER than
        # the one in git and still the wrong deck.
        $same = (Get-FileHash $dest -Algorithm SHA256).Hash -eq (Get-FileHash $d.FullName -Algorithm SHA256).Hash
        if ($same) { continue }
        $mine = Get-DeckCards $d.FullName; $theirs = Get-DeckCards $dest
        $printsOnly = $mine.Count -eq $theirs.Count -and $mine.ContainsKey('commander') -eq $theirs.ContainsKey('commander')
        if ($printsOnly) { foreach ($s in $mine.Keys) { if ($mine[$s] -ne $theirs[$s]) { $printsOnly = $false; break } } }
        if ($printsOnly -and (Get-Item $dest).LastWriteTime -gt $d.LastWriteTime) {
            # Kept, with a copy of the editor's file put aside as well, in case.
            New-Item -ItemType Directory -Force -Path $deckArchive | Out-Null
            Copy-Item $dest (Join-Path $deckArchive ("{0} (prints kept {1}){2}" -f $d.BaseName,
                (Get-Item $dest).LastWriteTime.ToString('yyyyMMdd_HHmmss'), $d.Extension)) -Force -ErrorAction SilentlyContinue
            Merge-DeckPrints $dest $d.FullName
            Write-Host ("Deck prints kept: {0} - the art you chose in Forge is now in decks\ (commit it to share)" -f $d.Name) -ForegroundColor Green
        } else {
            Move-ToDeckArchive (Get-Item $dest) 'changed here'
        }
    }
    Copy-Item $d.FullName $dest -Force
}
# Anything left that decks\ does not have: an old version the other owner archived, or a deck
# that only ever existed on this machine. Either way it does not belong in the picker, and
# either way it is kept.
foreach ($leftover in Get-ChildItem $cmdrDecks -Filter '*.dck' -File) {
    if (-not $current.ContainsKey($leftover.Name)) { Move-ToDeckArchive $leftover 'not in decks' }
}
if ($DecksOnly) {
    Write-Host ("Commander folder now matches decks\ ({0} decks)." -f $current.Count) -ForegroundColor Green
    exit 0
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
# PassThru so we can tell whether it actually got going. Java is started hidden with its output
# redirected to a file, so when it dies in the first second - a bad classpath, a jar that will not
# load, a JVM option this machine rejects - the launcher used to print "Starting Forge..." and
# then nothing at all, and the window closed with the reason sitting unread in a log file
# (a friend's machine, 2026-10-21).
$proc = Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $forgeDir `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 4
if ($proc.HasExited) {
    Write-Host ""
    Write-Host "  Forge stopped immediately (exit code $($proc.ExitCode))." -ForegroundColor Red
    Write-Host "  The last lines it printed:" -ForegroundColor Red
    foreach ($f in @("$log.err", $log)) {
        if (-not (Test-Path $f)) { continue }
        $tail = Get-Content $f -Tail 12 -ErrorAction SilentlyContinue |
                Where-Object { $_ -and $_ -notmatch "not assigned to any set|Upcoming set" }
        if ($tail) { $tail | ForEach-Object { Write-Host "    $_" } ; break }
    }
    Write-Host ""
    Write-Host "  Full log: $log"
    Write-Host "  Errors:   $log.err"
    exit 1
}

# It is still running four seconds in, which is as close to "it worked" as this can get from
# out here. Worth saying: Forge takes a while to show its window the first time, and a console
# that printed "Starting Forge..." and then sat silent looked like a failure when it was not.
Write-Host ""
Write-Host "  Forge started." -ForegroundColor Green
Write-Host "  The 3D table opens in a few seconds - longer on the first run of a new copy," -ForegroundColor DarkGray
Write-Host "  which reads 34,000 card scripts before it can show you anything." -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Closing this window does NOT close the game." -ForegroundColor DarkGray
Write-Host "  Log: $log" -ForegroundColor DarkGray
