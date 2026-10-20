# Runs Forge four-player Commander simulations in parallel batches, rotating
# seat order between batches, then summarises win rates.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\run-sims.ps1 `
#     -Decks "Captain America","Doom Prevails","Mr House","Tidus" -Games 20 -Parallel 4

param(
    [Parameter(Mandatory)] [string[]] $Decks,
    [int] $Games = 20,
    [int] $Parallel = 4,
    [int] $ClockSeconds = 900,
    # Runtime AI additions (scripts\tools\AiPatches.java), comma list, applied through
    # scripts\tools\SimLauncher. "catch" = Captain America attaches protection first;
    # "counter" = his counterspells are held for dangerous spells ("counterall" = everyone).
    # "rolldice" is no longer needed; "textbox" (Deadpool) caused out-of-memory crashes.
    # Pass -AiPatches "" to run plain Forge.
    # Default since 2026-10-10: "counterall" (smarter counterspells + held mana for every
    # player), "threat" (dynamic threat values, removal aimed at proven threats) and
    # "rampfirst" (mana rocks cast in the first main phase). Earlier runs used
    # "catch,counter" (Captain America only). Added later on 2026-10-10: "cmdrfirst"
    # (commanders cast as soon as affordable) and "captain" (Captain America saves Throw for
    # attackers or lethal, attacks only when lethal / unblockable / Battle-Mace).
    [string] $AiPatches = 'catch,counterall,threat,rampfirst,cmdrfirst,captain,humanlike,abilities,deploy,deathsave',
    # Forge AI profile for every player (res\ai\<name>.ai): Default, Cautious, Reckless,
    # Experimental, or Realistic (ours, tuned for 40-life Commander; default since
    # 2026-10-10). "" = Forge's own default, as in runs before that date.
    [string] $AiProfile = 'Realistic',
    # Extra JVM options (e.g. profiling). {batch} is replaced by the batch number.
    [string] $JvmArgs = '',
    # Max heap per Java process (MB).
    [int] $HeapMB = 4096,
    # Cache Forge's board evaluation across priority passes (scripts\tools\EvalCacheAgent;
    # needs our launcher, i.e. any -AiPatches).
    [bool] $EvalCache = $true,
    # Also drive the 3D table's snapshot reader over every game, with no window and no human,
    # and report anything it throws or any snapshot that comes out malformed. This is how the
    # 3D client gets exercised against thousands of real board states without somebody sitting
    # there playing it. See ui3d\src\mtg3d\SoakListener.java.
    [switch] $Soak
)

$ErrorActionPreference = 'Stop'
# "powershell -File" passes a comma list as one string, so split it here.
$Decks = @($Decks | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$root     = Split-Path -Parent $PSScriptRoot
$forgeDir = Join-Path $root 'forge-installer-2.0.15'
$deckDir  = Join-Path $root 'decks'
$java     = 'C:\Program Files\BellSoft\LibericaJDK-17\bin\java.exe'
# Fall back to the JDK on PATH when Liberica 17 is not installed (JDK 21 works too).
if (-not (Test-Path $java)) {
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { $java = $c.Source } else { throw "No java.exe found (install BellSoft Liberica JDK 17 or put a JDK on PATH)." }
}
$jar      = 'forge-gui-desktop-2.0.15-jar-with-dependencies.jar'
# SimLauncher applies runtime AI additions (scripts\tools\AiPatches.java) and then
# starts Forge as usual. Running from -cp loses the jar manifest's Add-Opens, so
# they are passed explicitly.
$toolClasses = Join-Path $PSScriptRoot 'tools\classes'
# Soaking puts the 3D table's classes (and the libGDX jar they were compiled against) on the
# simulator's classpath; SimRunner finds mtg3d.SoakListener there reflectively, so an ordinary
# run never needs them.
$soakCp = ''
$soakArgs = ''
if ($Soak) {
    $ui3dClasses = Join-Path $root 'ui3d\classes'
    if (-not (Test-Path $ui3dClasses)) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-ui3d.ps1') | Out-Null
    }
    $mobileJar = Join-Path $forgeDir 'forge-gui-mobile-dev-2.0.15-jar-with-dependencies.jar'
    $soakCp   = ";$ui3dClasses;$mobileJar"
    $soakArgs = '-Dforge.ui3d.soak=true '
}
$opens = @('java.desktop/java.beans','java.desktop/javax.swing.border','java.desktop/javax.swing.event','java.desktop/sun.swing',
           'java.desktop/java.awt.image','java.desktop/java.awt.color','java.desktop/sun.awt.image','java.desktop/javax.swing',
           'java.desktop/java.awt','java.base/java.util','java.base/java.lang','java.base/java.lang.reflect','java.base/java.text',
           'java.desktop/java.awt.font','java.base/jdk.internal.misc','java.base/sun.nio.ch','java.base/java.nio',
           'java.base/java.math','java.base/java.util.concurrent','java.base/java.net') |
         ForEach-Object { "--add-opens $_=ALL-UNNAMED" }

$runDir = Join-Path $root ("results\" + (Get-Date -Format 'yyyy-MM-dd_HHmmss'))
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

# With our launcher (any -AiPatches), all processes share one game queue: each keeps
# taking the next game until $Games are played, and seat order rotates per game. Plain
# Forge (-AiPatches "") can't do that, so it splits the games up front and rotates the
# seat order per batch.
$useQueue = [bool]$AiPatches
$perBatch = if ($useQueue) { $Games } else { [math]::Ceiling($Games / $Parallel) }
$procs = @()
for ($k = 0; $k -lt [math]::Min($Parallel, $Games); $k++) {
    $n = if ($useQueue) { $Games } else { [math]::Min($perBatch, $Games - $k * $perBatch) }
    if ($n -le 0) { break }
    $order = if ($useQueue) { $Decks } else { for ($i = 0; $i -lt $Decks.Count; $i++) { $Decks[($i + $k) % $Decks.Count] } }
    $deckArgs = ($order | ForEach-Object { '"' + $_ + '.dck"' }) -join ' '
    $queueArgs = if ($useQueue) { "-Dforge.queue.dir=`"$(Join-Path $runDir 'queue')`" -Dforge.queue.total=$Games" } else { '' }
    $agentArgs = if ($EvalCache) { "-javaagent:`"$(Join-Path $PSScriptRoot 'tools\evalcache-agent.jar')`" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED" } else { '' }
    $launch = if ($AiPatches) { "$($opens -join ' ') $agentArgs $soakArgs-Dforge.aipatches=$AiPatches $queueArgs -cp `"$toolClasses;$jar$soakCp`" SimLauncher" } else { "-jar $jar" }
    $profileArgs = if ($AiProfile) { ' -a ' + (@($order | ForEach-Object { $AiProfile }) -join ' ') } else { '' }
    $extra = $JvmArgs -replace '\{batch\}', ($k + 1)
    $argLine = "-Xmx$($HeapMB)m $extra -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 $launch sim " +
               "-D `"$deckDir`" -d $deckArgs -f Commander -n $n -c $ClockSeconds$profileArgs"
    $log = Join-Path $runDir "batch$($k + 1).log"
    $proc = Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $forgeDir `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    # Below-normal priority so the PC stays responsive (the user's own programs come first).
    try { $proc.PriorityClass = 'BelowNormal' } catch { }
    $procs += $proc
    if ($useQueue) { "Process $($k + 1): shared queue" } else { "Batch $($k + 1): $n games, seats: $($order -join ' | ')" }
    Start-Sleep -Seconds 3   # stagger start-up so the instances don't fight over settings files
}
if ($useQueue) { "Shared queue of $Games games; seat order rotates every game." }

# Progress bar until every batch has exited.
$start = Get-Date
do {
    Start-Sleep -Seconds 15
    $done = 0
    foreach ($f in Get-ChildItem $runDir -Filter 'batch*.log') {
        $done += @(Select-String -Path $f.FullName -Pattern '^Game Result:').Count
    }
    $pct = [math]::Min(1.0, $done / $Games)
    $bar = ('#' * [int]($pct * 30)).PadRight(30, '-')
    $line = "[{0}] {1}/{2} games  ({3:mm\:ss} elapsed)" -f $bar, $done, $Games, ((Get-Date) - $start)

    # Watchdog: Forge occasionally gets stuck in an AI loop that spams stderr
    # (hundreds of MB) or stops making progress. Kill such a batch; the games it
    # already finished are kept.
    for ($b = 0; $b -lt $procs.Count; $b++) {
        $p = $procs[$b]
        if ($p.HasExited) { continue }
        $log = Join-Path $runDir "batch$($b + 1).log"
        $err = Get-Item "$log.err" -ErrorAction SilentlyContinue
        $stalledMin = ((Get-Date) - (Get-Item $log).LastWriteTime).TotalMinutes
        $reason = $null
        if ($err -and $err.Length -gt 100MB) { $reason = 'runaway error log (>100 MB)' }
        elseif ($stalledMin -gt 20) { $reason = "no progress for $([int]$stalledMin) min" }
        if ($reason) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            [void]$p.WaitForExit(15000)   # release the log files before touching them
            if ($err -and $err.Length -gt 100MB) {
                # Keep the most frequent lines (they name the looping card) before truncating.
                try {
                    $top = Get-Content "$log.err" -TotalCount 20000 |
                        Where-Object { $_ -notmatch 'not assigned to any set|^folder|Upcoming set|^\s*$' } |
                        Group-Object | Sort-Object Count -Descending | Select-Object -First 8 |
                        ForEach-Object { "$($_.Count)x $($_.Name)" }
                    Set-Content "$log.err" (@("(truncated by watchdog: $reason) most frequent lines:") + $top) -ErrorAction Stop
                } catch { }
            }
            $msg = "WATCHDOG: stopped batch$($b + 1) - $reason"
            Add-Content (Join-Path $runDir 'watchdog.txt') "$(Get-Date -Format 'HH:mm:ss') $msg"
            $line += "  $msg"
        }
    }
    $line | Set-Content (Join-Path $runDir 'progress.txt')
    $line
} while ($procs | Where-Object { -not $_.HasExited })

# Summarise.
& (Join-Path $PSScriptRoot 'summarise.ps1') -RunDir $runDir
"Results folder: $runDir"
