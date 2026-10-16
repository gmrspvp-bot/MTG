# Combines several run folders (from run-sims.ps1) into overall standings:
# per deck, games played (seated), wins, win rate, and win rate vs the 25%
# expected in an even four-player game.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\overall-standings.ps1 -RunDirs results\a,results\b

param([Parameter(Mandatory)] [string[]] $RunDirs)

$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$played = @{}; $wins = @{}; $total = 0
foreach ($dir in $RunDirs) {
    $games = Import-Csv (Join-Path $dir 'games.csv')
    # Which decks sat at the table in this run (from the batch headers).
    $seated = @{}
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $head = Select-String -Path $f.FullName -Pattern '^Ai\(1\)-.+ vs ' | Select-Object -First 1
        if ($head) { foreach ($m in [regex]::Matches($head.Line, 'Ai\(\d\)-(.+?)(?= vs |\s+-\s+\w+ games? of|$)')) { $seated[$m.Groups[1].Value.Trim()] = 1 } }
    }
    foreach ($g in $games) {
        $total++
        foreach ($d in $seated.Keys) { $played[$d] = 1 + [int]$played[$d] }
        if ($g.Winner -ne 'Draw') { $wins[$g.Winner] = 1 + [int]$wins[$g.Winner] }
    }
}

$rows = foreach ($d in $played.Keys) {
    $w = [int]$wins[$d]; $p = $played[$d]
    [pscustomobject]@{ Deck = $d; Games = $p; Wins = $w; WinRate = '{0:P0}' -f ($w / $p); 'vs 25% even share' = '{0:+0;-0;0} pts' -f [math]::Round(100 * ($w / $p - 0.25)) }
}
"Combined: $total games from $($RunDirs.Count) runs"
$rows | Sort-Object { $_.Wins / $_.Games } -Descending | Format-Table -AutoSize | Out-String
