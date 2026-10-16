param([string[]]$RunDirs)
# Round-by-round picture of where game time goes.
$reTurn = '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| life (-?\d+) \| hand (\d+) \| lands (\d+) \((\d+) untapped\) \| open mana (-?\d+) \| commander (\S*) \| creatures (\d+) \((\d+) untapped\): ?(.*)$'
$reAtk = '^Information: BOARD attack \| (.+?) -> (.+?) \| attackers (\d+) \(power (\d+)\)'
$reCDmg = '^Damage: .+ deals (\d+) combat damage to (Ai\(\d+\)-.+?)\.$'
$reNDmg = '^Damage: .+ deals (\d+) damage to (Ai\(\d+\)-.+?)\.$'
$reLife = '^Life: Life: (Ai\(\d+\)-.+?) (-?\d+) > (-?\d+)'
$Rounds = @{}
function Row($rn) { if (-not $Rounds.ContainsKey($rn)) { $Rounds[$rn] = @{ Snap=0; Life=0; Power=0; Creatures=0; CDmg=0; NDmg=0; Gain=0; Loss=0; Turns=0; AttackTurns=0; AtkPower=0; BoardPowerOnAtkTurns=0; Games=@{} } }; $Rounds[$rn] }
function BoardPower([string]$list) {
    $sum = 0
    foreach ($item in ($list -split '; ')) {
        if ($item -match ' (-?\d+)/(-?\d+)\*?(\[[^\]]*\])?( x(\d+))?$') { $n = if ($matches[5]) { [int]$matches[5] } else { 1 }; $sum += [math]::Max(0, [int]$matches[1]) * $n }
    }
    $sum
}
$g = 0
foreach ($dir in $RunDirs) { foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
    $own = @{}; $round = 0; $myPower = @{}; $attackedThisTurn = @{}
    foreach ($line in [IO.File]::ReadLines($f.FullName)) {
        if ($line.StartsWith('Game Result')) { $g++; $own = @{}; $round = 0; continue }
        if ($line -match $reTurn) {
            $active = $matches[1]; $p = $matches[2]; $life = [int]$matches[3]; $list = $matches[11]
            if ($p -eq $active) {
                $own[$p] = 1 + [int]$own[$p]; if ($own[$p] -gt $round) { $round = $own[$p] }
                $row = Row $round; $row.Turns++; $myPower[$p] = BoardPower $list
                $attackedThisTurn[$p] = $false
            }
            if ($round -gt 0) { $row = Row $round; $row.Snap++; $row.Life += $life; $row.Power += (BoardPower $list); $row.Creatures += [int]$matches[9]; $row.Games["$g"] = 1 }
            continue
        }
        if ($round -eq 0) { continue }
        $row = Row $round
        if ($line -match $reAtk) {
            $a = $matches[1]
            if (-not $attackedThisTurn[$a]) { $attackedThisTurn[$a] = $true; $row.AttackTurns++; $row.BoardPowerOnAtkTurns += [int]$myPower[$a] }
            $row.AtkPower += [int]$matches[4]
        } elseif ($line -match $reCDmg) { $row.CDmg += [int]$matches[1] }
        elseif ($line -match $reNDmg) { $row.NDmg += [int]$matches[1] }
        elseif ($line -match $reLife) { $d = [int]$matches[3] - [int]$matches[2]; if ($d -gt 0) { $row.Gain += $d } else { $row.Loss += -$d } }
    }
} }
"Games: $g"
"Per round (all players): avg life of players still in, creature power on board per player, damage dealt to players per game, life gained per game, share of turns with an attack, attack power as % of board power on those turns"
$Rounds.Keys | Sort-Object { [int]$_ } | Where-Object { [int]$_ -le 20 } | ForEach-Object {
    $x = $Rounds[$_]; $ng = [math]::Max(1, $x.Games.Count)
    [pscustomobject]@{
        Round = $_; Games = $x.Games.Count
        'Players in' = [math]::Round([math]::Sqrt($x.Snap / $ng), 1)
        'Avg life' = [math]::Round($x.Life / [math]::Max(1,$x.Snap), 0)
        'Power/player' = [math]::Round($x.Power / [math]::Max(1,$x.Snap), 1)
        'Combat dmg/game' = [math]::Round($x.CDmg / $ng, 1)
        'Other dmg/game' = [math]::Round($x.NDmg / $ng, 1)
        'Life lost/game' = [math]::Round($x.Loss / $ng, 1)
        'Life gained/game' = [math]::Round($x.Gain / $ng, 1)
        'Turns w/ attack' = '{0:0}%' -f (100.0 * $x.AttackTurns / [math]::Max(1,$x.Turns))
        'Attack % of power' = '{0:0}%' -f (100.0 * $x.AtkPower / [math]::Max(1,$x.BoardPowerOnAtkTurns))
    }
} | Format-Table -AutoSize | Out-String -Width 250
