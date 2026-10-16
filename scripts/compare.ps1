# A/B test two versions of a deck against the same opponents. Runs alternating rounds
# (A then B, same pod, seat rotation as run-sims.ps1) and stops when:
#   - one version is clearly better (the likely range of the win-rate difference no
#     longer includes 0), or
#   - the difference is clearly too small to matter (whole range within +/- Negligible
#     percentage points), or
#   - MaxGames per version is reached (inconclusive).
# It never stops before MinGames per version, and uses a stricter range (z = 2.24,
# ~97.5%) because checking after every round would otherwise stop on luck too often.
# Writes results\compare_<time>.md. Each round's games are normal results folders.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\compare.ps1 `
#     -A "Captain America v2" -B "Captain America v3" -Opponents "Doom Prevails,Tidus,Mr House v2"

param(
    [Parameter(Mandatory)] [string] $A,
    [Parameter(Mandatory)] [string] $B,
    [string] $Opponents = 'Doom Prevails,Tidus,Mr House v2',
    [int] $RoundGames = 40,     # games per version per round
    [int] $MinGames = 80,       # per version, before any stop
    [int] $MaxGames = 400,      # per version
    [double] $Negligible = 5,   # percentage points
    [int] $Parallel = 8,
    [string] $AiPatches = 'catch,counterall,threat,rampfirst,cmdrfirst,captain,humanlike,abilities,deploy,deathsave',
    [string] $AiProfile = 'Realistic'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runSims = Join-Path $PSScriptRoot 'run-sims.ps1'
$opps = @($Opponents -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$z = 2.24
$report = Join-Path $root ("results\compare_" + (Get-Date -Format 'yyyy-MM-dd_HHmmss') + '.md')
$stats = @{ $A = @{ Wins = 0; Games = 0; Runs = @() }; $B = @{ Wins = 0; Games = 0; Runs = @() } }

function Invoke-Round([string]$version) {
    $decks = ($opps + $version) -join ','
    $out = & $runSims -Decks $decks -Games $RoundGames -Parallel $Parallel -AiPatches $AiPatches -AiProfile $AiProfile
    $dirLine = $out | Where-Object { $_ -match '^Results folder: ' } | Select-Object -Last 1
    $dir = $dirLine -replace '^Results folder: ', ''
    $games = @(Import-Csv (Join-Path $dir 'games.csv'))
    $stats[$version].Wins += @($games | Where-Object Winner -eq $version).Count
    $stats[$version].Games += $games.Count
    $stats[$version].Runs += (Split-Path $dir -Leaf)
}

function Pct([double]$x) { '{0:0.0}' -f (100.0 * $x) }

$lines = @("# Compare: $A vs $B", '', "Opponents: $($opps -join ', ')  ", "Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm')", '',
           ('| Round | ' + $A + ' | ' + $B + ' | Difference (B - A) | Likely range |'), '|---|---|---|---|---|')
$verdict = $null
$round = 0
while (-not $verdict) {
    $round++
    "Round ${round}: $RoundGames games each..."
    Invoke-Round $A
    Invoke-Round $B
    $nA = $stats[$A].Games; $nB = $stats[$B].Games
    $pA = $stats[$A].Wins / [math]::Max(1, $nA); $pB = $stats[$B].Wins / [math]::Max(1, $nB)
    $diff = $pB - $pA
    $se = [math]::Sqrt($pA * (1 - $pA) / [math]::Max(1, $nA) + $pB * (1 - $pB) / [math]::Max(1, $nB))
    $lo = $diff - $z * $se; $hi = $diff + $z * $se
    $row = "| $round | $(Pct $pA)% ($($stats[$A].Wins)/$nA) | $(Pct $pB)% ($($stats[$B].Wins)/$nB) | $(Pct $diff) pts | $(Pct $lo) to $(Pct $hi) pts |"
    $lines += $row
    "  $A $(Pct $pA)% of $nA, $B $(Pct $pB)% of $nB, difference $(Pct $diff) pts (likely $(Pct $lo) to $(Pct $hi))"
    if ($nA -ge $MinGames -and $nB -ge $MinGames) {
        if ($lo -gt 0) { $verdict = "$B is better (by $(Pct $lo) to $(Pct $hi) percentage points)." }
        elseif ($hi -lt 0) { $verdict = "$A is better (by $(Pct (-$hi)) to $(Pct (-$lo)) percentage points)." }
        elseif ($lo -gt -$Negligible / 100 -and $hi -lt $Negligible / 100) { $verdict = "No meaningful difference (within +/-$Negligible points)." }
        elseif ($nA -ge $MaxGames -and $nB -ge $MaxGames) { $verdict = "Inconclusive after $MaxGames games each: the difference is somewhere between $(Pct $lo) and $(Pct $hi) points." }
    }
    # Save progress after every round, so a stopped comparison still has its report.
    ($lines + @('', "Runs - ${A}: $($stats[$A].Runs -join ', ')  ", "Runs - ${B}: $($stats[$B].Runs -join ', ')")) | Set-Content $report -Encoding UTF8
}
$lines += @('', "**Verdict:** $verdict", '', "Runs - ${A}: $($stats[$A].Runs -join ', ')  ", "Runs - ${B}: $($stats[$B].Runs -join ', ')")
$lines | Set-Content $report -Encoding UTF8
"VERDICT: $verdict"
"Report: $report"
