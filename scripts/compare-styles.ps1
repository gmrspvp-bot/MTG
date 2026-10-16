# Compares Captain America play styles (AiPatches CaptainPolicy, -Dforge.captain.mode)
# with the same deck and opponents. Runs the styles in alternating rounds so they share
# conditions, then reports per style: win rate (with 95% likely range), Captain America's
# combat and throw damage to players, throws made, and how often he is knocked out first.
# Writes results\styles_<time>.md.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\compare-styles.ps1 `
#     -Deck "Captain America v4" -Styles "defend,aggro,face,mix" -Rounds 3 -RoundGames 40

param(
    [string] $Deck = 'Captain America v4',
    [string] $Styles = 'defend,aggro,face,mix',
    # Optional instead of -Styles: "label=jvm options;label2=jvm options" (e.g. timing tests:
    # "forge=-Dforge.timing=forge;equipfirst=-Dforge.timing=equipfirst").
    [string] $Variants = '',
    [string] $Opponents = 'Doom Prevails,Tidus,Mr House v2',
    [int] $Rounds = 3,
    [int] $RoundGames = 40,
    [int] $Parallel = 6
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runSims = Join-Path $PSScriptRoot 'run-sims.ps1'
$styleList = @($Styles -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$jvmFor = @{}
foreach ($s in $styleList) { $jvmFor[$s] = "-Dforge.captain.mode=$s" }
if ($Variants) {
    $styleList = @()
    foreach ($v in ($Variants -split ';' | Where-Object { $_.Trim() })) {
        $label, $jvm = $v -split '=', 2
        $styleList += $label.Trim(); $jvmFor[$label.Trim()] = $jvm.Trim()
    }
}
$opps = @($Opponents -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$report = Join-Path $root ("results\styles_" + (Get-Date -Format 'yyyy-MM-dd_HHmmss') + '.md')
$runs = @{}; foreach ($s in $styleList) { $runs[$s] = @() }

function Get-WinRange([int]$wins, [int]$n) {
    if ($n -le 0) { return '-' }
    $z = 1.96; $p = $wins / $n; $den = 1 + $z * $z / $n
    $centre = ($p + $z * $z / (2 * $n)) / $den
    $half = $z * [math]::Sqrt($p * (1 - $p) / $n + $z * $z / (4 * $n * $n)) / $den
    $lo = 100.0 * [math]::Max(0.0, $centre - $half); $hi = 100.0 * [math]::Min(1.0, $centre + $half)
    '{0:0}-{1:0}%' -f $lo, $hi
}

function Get-Stats([string[]]$dirs) {
    $g = 0; $won = 0; $dmgC = 0; $dmgT = 0; $throws = 0; $firstOut = 0
    foreach ($dir in $dirs) {
        foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
            $own = @{}
            foreach ($line in [IO.File]::ReadLines($f.FullName)) {
                if ($line -match '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \|' -and $matches[1] -eq $matches[2]) { $own[$matches[2]] = 1 + [int]$own[$matches[2]]; continue }
                if ($line -match '^Damage: Captain America, First Avenger \(\d+\) deals (\d+) combat damage to Ai\(') { $dmgC += [int]$matches[1]; continue }
                if ($line -match '^Damage: Captain America, First Avenger \(\d+\) deals (\d+) non-combat damage to Ai\(') { $dmgT += [int]$matches[1]; continue }
                if ($line -match "^Add To Stack: Ai\(\d\)-.+? activated Captain America, First Avenger") { $throws++; continue }
                if ($line -match '^Game Result: Game \d+ ended in .*? (?:Ai\(\d\)-(.+?) has won|a Draw)') {
                    $g++; $w = "$($matches[1])".Trim()
                    if ($w -eq $Deck) { $won++ }
                    $losers = @($own.Keys | Where-Object { $_ -notmatch [regex]::Escape($w) -or -not $w })
                    $fo = $losers | Sort-Object { $own[$_] } | Select-Object -First 1
                    if ($fo -match [regex]::Escape($Deck)) { $firstOut++ }
                    $own = @{}
                }
            }
        }
    }
    [pscustomobject]@{ Games = $g; Wins = $won; DmgCombat = $dmgC; DmgThrow = $dmgT; Throws = $throws; FirstOut = $firstOut }
}

function Write-Report([string]$status) {
    $lines = @("# Captain America play styles: $Deck", '', "Opponents: $($opps -join ', ')  ", "Status: $status", '',
               '| Style | Games | Win rate | Likely range | Combat dmg to players / game | Throw dmg to players / game | Throws / game | Knocked out first |',
               '|---|---|---|---|---|---|---|---|')
    foreach ($s in $styleList) {
        if (-not $runs[$s].Count) { continue }
        $st = Get-Stats $runs[$s]
        if (-not $st.Games) { continue }
        $lines += ('| {0} | {1} | {2:0}% | {3} | {4:0.0} | {5:0.0} | {6:0.0} | {7:0}% |' -f $s, $st.Games, (100.0 * $st.Wins / $st.Games),
            (Get-WinRange $st.Wins $st.Games), ($st.DmgCombat / $st.Games), ($st.DmgThrow / $st.Games), ($st.Throws / $st.Games), (100.0 * $st.FirstOut / $st.Games))
    }
    $lines += @('', 'Styles: defend = throws only at creatures attacking him; aggro = attacks every combat; face = every throw at the lowest-life opponent; mix = high-value kills, attackers, then spare throws at the lowest-life opponent at end of turn, and attacks when no blocker can kill him; pressure = aggro attacks + face throws; highvalue = default (eliminations + high-value kills, attack rule). All styles take a throw that finishes a player.', '')
    foreach ($s in $styleList) { $lines += "JVM options - ${s}: $($jvmFor[$s])  " }
    foreach ($s in $styleList) { $lines += "Runs - ${s}: $($runs[$s] -join ', ')  " }
    $lines | Set-Content $report -Encoding UTF8
}

for ($r = 1; $r -le $Rounds; $r++) {
    foreach ($s in $styleList) {
        "Round ${r}: style $s, $RoundGames games..."
        $before = @(Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object { $_.Name })
        $out = & $runSims -Decks (($opps + $Deck) -join ',') -Games $RoundGames -Parallel $Parallel -JvmArgs ($jvmFor[$s])
        $dir = ($out | Where-Object { $_ -match '^Results folder: ' } | Select-Object -Last 1) -replace '^Results folder: ', ''
        if (-not $dir -or -not (Test-Path $dir)) {
            # Fall back to the run folder this call created.
            $dir = Get-ChildItem (Join-Path $root 'results') -Directory | Where-Object { $_.Name -match '^\d{4}-\d\d-\d\d_\d{6}$' -and $before -notcontains $_.Name } |
                Sort-Object Name | Select-Object -Last 1 | ForEach-Object { $_.FullName }
        }
        $runs[$s] += $dir
        Write-Report "round $r of $Rounds in progress"
    }
}
Write-Report 'finished'
Get-Content $report
"Report: $report"
