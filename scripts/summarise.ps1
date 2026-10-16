# Summarises a results folder produced by run-sims.ps1: per-game CSV plus
# win rates per deck.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\summarise.ps1 -RunDir results\<run>

param([Parameter(Mandatory)] [string] $RunDir)

$ErrorActionPreference = 'Stop'
$RunDir = (Resolve-Path $RunDir).Path

$results = @()
$decks = @{}
foreach ($f in Get-ChildItem $RunDir -Filter 'batch*.log') {
    $turn = $null; $losses = @{}
    foreach ($l in Get-Content $f.FullName) {
        if ($l -match '^Ai\(\d+\)-.+ vs ') {
            # Names may carry an AI profile tag, e.g. "Tidus [Realistic]"; winners don't.
            foreach ($m in [regex]::Matches($l, 'Ai\(\d+\)-(.+?)(?= vs |\s+-\s+[\w\s-]+? games? of|$)')) { $decks[($m.Groups[1].Value -replace '\s*\[[^\]]+\]\s*$', '').Trim()] = 1 }
        }
        if ($l -match '^Game Outcome: Turn (\d+)') { $turn = [int]$matches[1] }
        if ($l -match '^Game Outcome: Ai\(\d+\)-(.+) has lost because (.+)$') { $losses[$matches[1].Trim()] = $matches[2].Trim() }
        # Wins: "Game Result: Game N ended in X ms. Ai(k)-Deck has won!"
        # Draws: "Game Result: Game N ended in a Draw! Took X ms."
        if ($l -match '^Game Result: Game (\d+) ended in (\d+) ms\. (.*)$' -or $l -match '^Game Result: Game (\d+) ended in a Draw! Took (\d+) ms') {
            $game = [int]$matches[1]; $ms = [int]$matches[2]; $rest = if ($matches.Count -gt 3) { $matches[3] } else { '' }
            $winner = 'Draw'
            if ($rest -match 'Ai\(\d+\)-(.+) has won') { $winner = $matches[1].Trim() }
            $results += [pscustomobject]@{
                Batch = $f.BaseName; Game = $game; Winner = $winner; Turns = $turn
                Minutes = [math]::Round($ms / 60000, 1)
                LossReasons = ($losses.GetEnumerator() | ForEach-Object { "$($_.Key): $($_.Value)" }) -join '; '
            }
            $turn = $null; $losses = @{}
        }
    }
}
$results | Export-Csv (Join-Path $RunDir 'games.csv') -NoTypeInformation

$total = $results.Count
$names = @($decks.Keys | Sort-Object)
if ($results | Where-Object Winner -eq 'Draw') { $names += 'Draw' }

# 95% range for a win rate (Wilson interval): with this many games, the deck's true
# win rate is very likely inside it. Two decks whose ranges overlap a lot may not
# really differ.
function Get-WinRange([int]$wins, [int]$n) {
    if ($n -le 0) { return '-' }
    $z = 1.96; $p = $wins / $n
    $den = 1 + $z * $z / $n
    $centre = ($p + $z * $z / (2 * $n)) / $den
    $half = $z * [math]::Sqrt($p * (1 - $p) / $n + $z * $z / (4 * $n * $n)) / $den
    $lo = 100.0 * [math]::Max(0.0, $centre - $half)
    $hi = 100.0 * [math]::Min(1.0, $centre + $half)
    '{0:0}-{1:0}%' -f $lo, $hi
}

$summary = foreach ($d in $names) {
    $w = @($results | Where-Object Winner -eq $d)
    [pscustomobject]@{
        Deck         = $d
        Wins         = $w.Count
        WinRate      = if ($total) { '{0:P0}' -f ($w.Count / $total) } else { '-' }
        'Likely range (95%)' = Get-WinRange $w.Count $total
        AvgWinTurn   = if ($w.Count) { [math]::Round(($w | Measure-Object Turns -Average).Average, 1) } else { '-' }
    }
}
$summary = @($summary | Sort-Object Wins -Descending)
$summary | Export-Csv (Join-Path $RunDir 'summary.csv') -NoTypeInformation

$avgTurns = if ($total) { [math]::Round(($results | Measure-Object Turns -Average).Average, 1) } else { 0 }
$avgMin   = if ($total) { [math]::Round(($results | Measure-Object Minutes -Average).Average, 1) } else { 0 }
$text = "Games: $total   Avg game length: $avgTurns turns, $avgMin min`n" + ($summary | Format-Table -AutoSize | Out-String)
$text | Set-Content (Join-Path $RunDir 'summary.txt')
$text
