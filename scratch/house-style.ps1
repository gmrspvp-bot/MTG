# How a Mr House player plays: the owner's recorded games vs the AI's sims, measured the same way.
#
#   powershell -ExecutionPolicy Bypass -File scratch\house-style.ps1 -Files <logs> -Player <regex>
#
# Player: a regex for the player's name in the log ("Captain" in recorded games, "Mr House v4" in
# sim batch logs, where names are like "Ai(2)-Mr House v4"). Per game, then averaged:
#   - which of their own turns Mr. House is first cast;
#   - dice-rolling activations (Deck of Many Things, Vexing Puzzlebox, Jan Jansen, Mr. House's
#     own ability, Component Pouch, Treasure Chest), on their own turn vs on opponents' turns;
#   - Mr. House triggers (robots made);
#   - attacks: how many combats, how many attackers, how many different players hit per combat;
#   - blocks made with Robot tokens;
#   - won or not.

param(
    [string[]] $Files,
    [string] $Player = 'Captain'
)

$dice = 'The Deck of Many Things|Vexing Puzzlebox|Jan Jansen, Chaos Crafter|Mr\. House, President and CEO|Component Pouch|Treasure Chest'
$games = @()

foreach ($f in $Files) {
    $lines = Get-Content $f
    $g = $null
    foreach ($l in $lines) {
        if ($l -match '^Turn: Turn 1 \(') {
            if ($g) { $games += $g }
            $g = [ordered]@{ file = (Split-Path $f -Leaf); turn = 0; own = 0; ownTurn = $false; firstHouse = $null; diceOwn = 0; diceOpp = 0;
                             houseTrig = 0; combats = 0; attackers = 0; spread = 0; robotBlocks = 0; won = $false; seen = $false; curDef = @{}; inCombat = $false }
        }
        if (-not $g) { continue }
        if ($l -match '^Turn: Turn (\d+) \((.+)\)$') {
            if ($g.inCombat) { $g.spread += $g.curDef.Count; $g.inCombat = $false; $g.curDef = @{} }
            $g.turn = [int]$matches[1]
            $g.ownTurn = $matches[2] -match $Player
            if ($g.ownTurn) { $g.own++; $g.seen = $true }
            continue
        }
        if ($l -match "^Add To Stack: (\S*$Player\S*) cast Mr\. House" -and -not $g.firstHouse) { $g.firstHouse = $g.own }
        if ($l -match "^Add To Stack: (\S*$Player\S*) activated ($dice)") { if ($g.ownTurn) { $g.diceOwn++ } else { $g.diceOpp++ } }
        if ($l -match "^Add To Stack: (\S*$Player\S*) triggered Mr\. House") { $g.houseTrig++ }
        # Attacks: "Combat: P assigned A, B to attack X." then more lines "P assigned C to attack Y." in the same combat.
        if ($l -match "^(Combat: )?(\S*$Player\S*) assigned (.+) to attack (.+)\.$") {
            $who = $matches[3]; $def = $matches[4]      # before the next -match overwrites $matches
            if ($l.StartsWith('Combat: ')) {
                if ($g.inCombat) { $g.spread += $g.curDef.Count }
                $g.combats++; $g.inCombat = $true; $g.curDef = @{}
            }
            $g.attackers += ($who -split ', | and ').Count
            $g.curDef[$def] = 1
        } elseif ($g.inCombat -and $l -match '^(Damage|Phase|Turn|Information: BOARD turn)') {
            $g.spread += $g.curDef.Count; $g.inCombat = $false; $g.curDef = @{}
        }
        if ($l -match "^(Combat: )?(\S*$Player\S*) assigned (.*Robot Token.*) to block ") { $g.robotBlocks += ([regex]::Matches($matches[3], 'Robot Token')).Count }
        if ($l -match "(Game Outcome|Game Result): (\S*$Player\S*) has won") { $g.won = $true }
    }
    if ($g) { $games += $g }
}

$games = @($games | Where-Object { $_.seen })
if ($games.Count -eq 0) { "no games found for '$Player'"; exit }
function Avg($xs) { $v = @($xs | Where-Object { $_ -ne $null }); if ($v.Count -eq 0) { return 'n/a' }; [math]::Round(($v | Measure-Object -Average).Average, 1) }
$n = $games.Count
"games: $n   won: $(@($games | Where-Object { $_.won }).Count)"
"Mr. House first cast on own turn:      " + (Avg ($games | ForEach-Object { $_.firstHouse }))
"dice activations per game, own turn:   " + (Avg ($games | ForEach-Object { $_.diceOwn }))
"dice activations per game, opp turns:  " + (Avg ($games | ForEach-Object { $_.diceOpp }))
"Mr. House triggers per game:           " + (Avg ($games | ForEach-Object { $_.houseTrig }))
"combats per game:                      " + (Avg ($games | ForEach-Object { $_.combats }))
"attackers per combat:                  " + (Avg ($games | Where-Object { $_.combats -gt 0 } | ForEach-Object { $_.attackers / $_.combats }))
"players attacked per combat:           " + (Avg ($games | Where-Object { $_.combats -gt 0 } | ForEach-Object { $_.spread / $_.combats }))
"robot blocks per game:                 " + (Avg ($games | ForEach-Object { $_.robotBlocks }))
"own turns per game:                    " + (Avg ($games | ForEach-Object { $_.own }))
