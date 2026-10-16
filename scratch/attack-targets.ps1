param([string]$RunDir)
# For each attack, compare the defender with the other possible targets using the
# latest turn-start snapshot: was it the weakest board, the lowest life, or the
# strongest (most creatures / biggest threat)?
$reTurn = '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| life (-?\d+) \| hand (\d+) \| lands (\d+) \((\d+) untapped\) \| open mana (-?\d+) \| commander (\S*) \| creatures (\d+)'
$reAtk = '^Information: BOARD attack \| (.+?) -> (.+?) \|'
$n = 0; $weakest = 0; $lowLife = 0; $strongest = 0; $cmdOut = 0; $cmdOutChoices = 0
foreach ($f in Get-ChildItem $RunDir -Filter 'batch*.log') {
    $snap = @{}; $seenAtk = @{}
    foreach ($line in [IO.File]::ReadLines($f.FullName)) {
        if ($line -match $reTurn) {
            $snap[$matches[2]] = @{ Life = [int]$matches[3]; Creatures = [int]$matches[9]; Lands = [int]$matches[5]; Cmd = $matches[8] }
            $seenAtk = @{}
        } elseif ($line.StartsWith('Game Result')) { $snap = @{} }
        elseif ($line -match $reAtk) {
            $att = $matches[1]; $def = $matches[2]
            if ($seenAtk.ContainsKey($att)) { continue }   # count each attacking player's combat once (first defender)
            $seenAtk[$att] = 1
            $opts = @($snap.Keys | Where-Object { $_ -ne $att -and $snap[$_].Life -gt 0 })
            if ($opts.Count -lt 2 -or -not $snap.ContainsKey($def)) { continue }
            $n++
            $score = { param($k) $snap[$k].Creatures * 3 + $snap[$k].Lands }
            $minBoard = ($opts | ForEach-Object { & $score $_ } | Measure-Object -Minimum).Minimum
            $maxBoard = ($opts | ForEach-Object { & $score $_ } | Measure-Object -Maximum).Maximum
            $minLife = ($opts | ForEach-Object { $snap[$_].Life } | Measure-Object -Minimum).Minimum
            if ((& $score $def) -eq $minBoard) { $weakest++ }
            if ((& $score $def) -eq $maxBoard) { $strongest++ }
            if ($snap[$def].Life -eq $minLife) { $lowLife++ }
        }
    }
}
"attacks with 2+ possible targets: $n"
"defender had the weakest board:   {0:0}%" -f (100.0 * $weakest / $n)
"defender had the strongest board: {0:0}%" -f (100.0 * $strongest / $n)
"defender had the lowest life:     {0:0}%" -f (100.0 * $lowLife / $n)
"(random choice among 2-3 targets would give roughly 33-50% for each)"
