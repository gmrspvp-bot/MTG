# Realism check: how the simulated games compare with typical real Commander games.
# Uses the BOARD snapshot lines (runs made with SimRunner) plus Forge's normal log.
# Plays no games.
#
#   Pace:      rounds per game (winner's turns), round the first player is knocked out
#   Endings:   how players lost (life, commander damage, poison, ...), draws
#   Mulligans: share of opening hands mulliganed (below 7 cards kept)
#   Combat:    how often an attacked player with untapped creatures blocked, and the
#              share of attacks that met no untapped creature at all
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\realism-stats.ps1 -RunDirs results\<run>[,results\<run2>]

param([Parameter(Mandatory)] [string[]] $RunDirs)

$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$reTurn = '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| (life|out)'
$reAtk  = '^Information: BOARD attack \| .+? -> (.+?) \| .* untapped creatures (\d+)'
$games = 0; $draws = 0
$rounds = New-Object System.Collections.Generic.List[int]
$firstOut = New-Object System.Collections.Generic.List[int]
$lossReasons = @{}
$hands = 0; $mulled = 0
$attacks = 0; $atkNoBlockers = 0; $atkWithBlockers = 0; $atkBlocked = 0

foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $own = @{}; $out = @{}; $firstOutRound = $null; $pending = $null
        foreach ($line in [IO.File]::ReadLines($f.FullName)) {
            if ($line -match $reTurn) {
                $active = $matches[1]; $p = $matches[2]
                if ($matches[3] -eq 'out') {
                    if (-not $out.ContainsKey($p)) {
                        $out[$p] = 1
                        if ($null -eq $firstOutRound) { $firstOutRound = ($own.Values | Measure-Object -Maximum).Maximum }
                    }
                } elseif ($p -eq $active) { $own[$p] = 1 + [int]$own[$p] }
                continue
            }
            if ($pending) {
                if ($line -match '^Phase: .*(First Strike Damage Step|Combat Damage Step|End of Combat Step)') {
                    if ($pending.Blocks -gt 0) { $atkBlocked++ }
                    $pending = $null
                } elseif ($line.StartsWith("Combat: $($pending.Def) assigned") -and $line.Contains(' to block ')) { $pending.Blocks++ }
            }
            if ($line -match $reAtk) {
                $attacks++
                $def = $matches[1].Trim(); $u = [int]$matches[2]
                if ($u -eq 0) { $atkNoBlockers++ } else { $atkWithBlockers++; $pending = @{ Def = $def; Blocks = 0 } }
                continue
            }
            if ($line -match '^Mulligan: .+ has kept a hand of (\d+) cards') { $hands++; if ([int]$matches[1] -lt 7) { $mulled++ } ; continue }
            if ($line -match '^Game Outcome: Ai\(\d+\)-.+ has lost (?:because |due to )?(.+)$') {
                $r = $matches[1] -replace '\d+', 'N'
                $lossReasons[$r] = 1 + [int]$lossReasons[$r]; continue
            }
            if ($line.StartsWith('Game Result:')) {
                $games++
                if ($line -match 'Draw') { $draws++ }
                elseif ($own.Count) {
                    $rounds.Add(($own.Values | Measure-Object -Maximum).Maximum)
                    # Forge drops eliminated players from the snapshots, so a loser's last
                    # turn number is the round they went out in.
                    $winner = if ($line -match '(Ai\(\d+\)-.+) has won') { $matches[1].Trim() } else { '' }
                    $losers = @($own.Keys | Where-Object { $_ -ne $winner })
                    if ($losers.Count) { $firstOutRound = ($losers | ForEach-Object { $own[$_] } | Measure-Object -Minimum).Minimum }
                }
                if ($null -ne $firstOutRound) { $firstOut.Add($firstOutRound) }
                $own = @{}; $out = @{}; $firstOutRound = $null; $pending = $null
            }
        }
    }
}

function Stat($list) {
    if (-not $list.Count) { return '-' }
    $sorted = @($list | Sort-Object)
    '{0:0.0} (middle half {1}-{2})' -f ($list | Measure-Object -Average).Average, $sorted[[int]($sorted.Count * 0.25)], $sorted[[int]([math]::Min($sorted.Count - 1, $sorted.Count * 0.75))]
}
function Pc($x, $n) { if ($n) { '{0:0}%' -f (100.0 * $x / $n) } else { '-' } }

"Games: $games (draws $draws)   runs: $($RunDirs | ForEach-Object { Split-Path $_ -Leaf })"
""
"PACE"
"  Rounds per game (winner's turns):   $(Stat $rounds)"
"  First player knocked out in round:  $(Stat $firstOut)"
""
"HOW PLAYERS LOST"
$totalLoss = ($lossReasons.Values | Measure-Object -Sum).Sum
$lossReasons.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "  {0,-5} {1}" -f (Pc $_.Value $totalLoss), $_.Key }
""
"MULLIGANS"
"  Opening hands kept below 7 cards:   $(Pc $mulled $hands) of $hands"
""
"COMBAT"
"  Attacks into a player with no untapped creature:      $(Pc $atkNoBlockers $attacks) of $attacks"
"  Attacked player had untapped creatures and blocked:   $(Pc $atkBlocked $atkWithBlockers) of $atkWithBlockers"
