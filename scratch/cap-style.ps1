param([string[]]$Files, [string]$Player)
# Captain America play-style numbers from game logs (desktop recordings or sim batch logs).
# -Player: name as in the log ("Sam" for the user, "Captain America v6" matches Ai(n)-Captain America v6).
$Files = @($Files | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$P = [regex]::Escape($Player)
$games = 0; $castTurns = @(); $capTurns = 0; $capAttacks = 0; $atkPower = @(); $throwsFace = 0; $throwsCreature = 0; $recasts = 0; $wins = 0
foreach ($f in $Files) {
    $own = 0; $active = ''; $capOut = $false; $castThisGame = 0; $me = $null
    foreach ($l in [IO.File]::ReadLines($f, [Text.Encoding]::UTF8)) {
        if ($l -match '^Turn: Turn \d+ \((.+)\)\s*$') {
            $active = $matches[1]
            if ($active -match "(^|-)$P$") { $me = $active; $own++; if ($capOut) { $capTurns++ } }
            continue }
        if ($l -match "^Add To Stack: (.*$P) cast Captain America, First Avenger") { $castThisGame++; if ($castThisGame -eq 1) { $castTurns += $own } else { $recasts++ }; continue }
        if ($l -match '^Resolve Stack: Captain America, First Avenger - Creature') { $capOut = $true; continue }
        if ($l -match '^Zone Change: Captain America, First Avenger \(\d+\) was put into') { $capOut = $false; continue }
        if ($me -and $active -eq $me -and $l -match "^Combat: .*$P assigned (.*)Captain America, First Avenger \(\d+\)") { $capAttacks++; continue }
        if ($l -match "^Add To Stack: (.*$P) activated Captain America, First Avenger targeting \[(.+)\]") { if ($matches[2] -match '^(Ai\(\d\)-|[A-Z][a-z]+(, [A-Z]|$))' -and $matches[2] -notmatch '\(\d+\)') { $throwsFace++ } else { $throwsCreature++ } ; continue }
        if ($l -match '^Game Result') { $games++; if ($l -match "$P has won") { $wins++ }; $own = 0; $capOut = $false; $castThisGame = 0; $me = $null }
        if ($l -match "^Game Outcome: (.*$P) has won") { }
    }
}
"games: $games  wins: $wins"
"first cast on own turn: avg {0:N1}  ({1})" -f ($castTurns | Measure-Object -Average).Average, ($castTurns -join ',')
"own turns with Cap on battlefield: $capTurns, Cap attacked on $capAttacks of them ({0:P0})" -f ($capAttacks / [math]::Max(1, $capTurns))
"recasts after removal: $recasts ({0:N2} per game)" -f ($recasts / [math]::Max(1, $games))
"throws at players: $throwsFace, at creatures: $throwsCreature ({0:N2} per game)" -f (($throwsFace + $throwsCreature) / [math]::Max(1, $games))
