param([string]$RunDir)
$decks = @{}
function Add($n, $k, $v = 1) {
    if (-not $decks.ContainsKey($n)) { $decks[$n] = @{ AttacksMade=0; AttacksReceived=0; Blocks=0; NoBlock=0; CombatDmgTaken=0; CmdrAttacks=0; CmdrBlocks=0 } }
    $decks[$n][$k] += $v
}
$cmdrPat = 'Captain America, First Avenger|Doctor Doom, King|Tidus, Yuna|Mr\. House, President'
$reAtk = '^Combat: Ai\(\d\)-(.+?) assigned (.+?) \(\d+\) to attack Ai\(\d\)-(.+?)\.$'
$reBlk = '^Combat: Ai\(\d\)-(.+?) assigned (.+?) \(\d+\) to block'
$reNo  = '^Combat: Ai\(\d\)-(.+?) didn''t block'
$reDmg = '^Damage: .+ deals (\d+) combat damage to Ai\(\d\)-(.+?)\.$'
foreach ($f in Get-ChildItem $RunDir -Filter 'batch*.log') {
    foreach ($line in [IO.File]::ReadLines($f.FullName)) {
        if ($line -match $reAtk) {
            $a = $matches[1]; $c2 = $matches[2]; $t = $matches[3]
            Add $a 'AttacksMade'; Add $t 'AttacksReceived'
            if ($c2 -match $cmdrPat) { Add $a 'CmdrAttacks' }
        } elseif ($line -match $reBlk) {
            $a = $matches[1]; $c2 = $matches[2]
            Add $a 'Blocks'
            if ($c2 -match $cmdrPat) { Add $a 'CmdrBlocks' }
        } elseif ($line -match $reNo) {
            Add $matches[1] 'NoBlock'
        } elseif ($line -match $reDmg) {
            $n = [int]$matches[1]; Add $matches[2] 'CombatDmgTaken' $n
        }
    }
}
$games = @(Select-String -Path "$RunDir\batch*.log" -Pattern '^Game Result:').Count
$decks.Keys | Sort-Object | ForEach-Object {
    $d = $decks[$_]
    [pscustomobject]@{
        Deck = $_
        'Attackers/game' = [math]::Round($d.AttacksMade / $games, 1)
        'Attacked by/game' = [math]::Round($d.AttacksReceived / $games, 1)
        'Blocks/game' = [math]::Round($d.Blocks / $games, 1)
        'Unblocked/game' = [math]::Round($d.NoBlock / $games, 1)
        'Block %' = [math]::Round(100.0 * $d.Blocks / [math]::Max(1, $d.Blocks + $d.NoBlock))
        'Combat dmg taken/game' = [math]::Round($d.CombatDmgTaken / $games, 1)
        'Cmdr attacks/game' = [math]::Round($d.CmdrAttacks / $games, 1)
        'Cmdr blocks/game' = [math]::Round($d.CmdrBlocks / $games, 2)
    }
} | Format-Table -AutoSize | Out-String -Width 250
"games: $games"
