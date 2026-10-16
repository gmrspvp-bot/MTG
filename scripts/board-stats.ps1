# Board-state report from the "BOARD" snapshot lines that SimRunner writes into the
# game logs (runs made after 2026-10-09). Plays no games.
#
# Per deck:
#   - when attacked: untapped creatures it had, how often it had none, and how often it
#     had some but didn't block
#   - mana left open during opponents' turns, and when an opponent cast a spell
#     (and a mana value 4+ spell)
#   - its own board at the start of its turns (creatures, lands, hand, commander out)
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\board-stats.ps1 -RunDirs results\<run>[,results\<run2>]

param([Parameter(Mandatory)] [string[]] $RunDirs)

$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$s = @{}
function Get-Deck([string]$name) {
    if (-not $s.ContainsKey($name)) {
        $s[$name] = @{ Attacked = 0; UntappedSum = 0; NoBlockers = 0; HadBlockersNoBlock = 0; BlockedAny = 0
                       OppTurns = 0; OppTurnOpenSum = 0; OppTurnOpen2 = 0
                       OppCasts = 0; OppCastOpenSum = 0; BigCasts = 0; BigCastOpen2 = 0
                       OwnTurns = 0; CreatureSum = 0; LandSum = 0; HandSum = 0; CmdrOut = 0 }
    }
    $s[$name]
}
function Strip([string]$p) { $p.Trim() -replace '^Ai\(\d+\)-', '' }

$reAttack = '^Information: BOARD attack \| (.+?) -> (.+?) \| attackers (\d+) \(power (\d+)\) \| defender life (-?\d+) \| untapped creatures (\d+)'
$reCast   = '^Information: BOARD cast \| (.+) \(mv (\d+)\) by (.+?) \| open mana: (.*)$'
$reTurn   = '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| life (-?\d+) \| hand (\d+) \| lands (\d+) \((\d+) untapped\) \| open mana (-?\d+) \| commander (\S*) \| creatures (\d+) \((\d+) untapped\)'
$games = 0
foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $pending = $null   # attack waiting for its blocks
        foreach ($line in [IO.File]::ReadLines($f.FullName)) {
            if ($line.StartsWith('Game Result:')) { $games++; continue }
            if ($pending) {
                if ($line -match '^Phase: .*(First Strike Damage Step|Combat Damage Step|End of Combat Step)') {
                    $d = Get-Deck $pending.Defender
                    if ($pending.Blocks -gt 0) { $d.BlockedAny++ }
                    elseif ($pending.Untapped -gt 0) { $d.HadBlockersNoBlock++ }
                    $pending = $null
                } elseif ($line.StartsWith('Combat: ') -and $line.Contains(' to block ') -and $line.StartsWith("Combat: $($pending.DefenderFull) assigned")) {
                    $pending.Blocks++
                }
            }
            if (-not $line.StartsWith('Information: BOARD')) { continue }
            if ($line -match $reAttack) {
                $defFull = $matches[2].Trim(); $untapped = [int]$matches[6]
                $d = Get-Deck (Strip $defFull)
                $d.Attacked++; $d.UntappedSum += $untapped
                if ($untapped -eq 0) { $d.NoBlockers++ }
                $pending = @{ Defender = (Strip $defFull); DefenderFull = $defFull; Untapped = $untapped; Blocks = 0 }
            } elseif ($line -match $reCast) {
                $mv = [int]$matches[2]; $open = $matches[4]
                foreach ($part in ($open -split ', ')) {
                    if ($part -match '^(.+) (-?\d+)$') {
                        $name = Strip $matches[1]; $m = [int]$matches[2]
                        if ($m -lt 0) { continue }
                        $d = Get-Deck $name
                        $d.OppCasts++; $d.OppCastOpenSum += $m
                        if ($mv -ge 4) { $d.BigCasts++; if ($m -ge 2) { $d.BigCastOpen2++ } }
                    }
                }
            } elseif ($line -match $reTurn) {
                $active = Strip $matches[1]; $name = Strip $matches[2]
                $hand = [int]$matches[4]; $lands = [int]$matches[5]; $open = [int]$matches[7]; $cmdZone = $matches[8]
                $creatures = [int]$matches[9]
                $d = Get-Deck $name
                if ($name -eq $active) {
                    $d.OwnTurns++; $d.CreatureSum += $creatures; $d.LandSum += $lands; $d.HandSum += $hand
                    if ($cmdZone -match 'Battlefield') { $d.CmdrOut++ }
                } elseif ($open -ge 0) {
                    $d.OppTurns++; $d.OppTurnOpenSum += $open
                    if ($open -ge 2) { $d.OppTurnOpen2++ }
                }
            }
        }
    }
}

function Avg($sum, $n) { if ($n) { [math]::Round($sum / $n, 1) } else { '-' } }
function Pc($x, $n) { if ($n) { '{0:0}%' -f (100.0 * $x / $n) } else { '-' } }

"Games: $games  (runs: $($RunDirs -join ', '))"
""
"WHEN ATTACKED"
$s.Keys | Sort-Object | ForEach-Object { $d = $s[$_]; [pscustomobject]@{
    Deck = $_
    'Times attacked' = $d.Attacked
    'Untapped creatures (avg)' = Avg $d.UntappedSum $d.Attacked
    'Had no untapped creature' = Pc $d.NoBlockers $d.Attacked
    'Had some, blocked nothing' = Pc $d.HadBlockersNoBlock $d.Attacked
    'Blocked at least one' = Pc $d.BlockedAny $d.Attacked
} } | Format-Table -AutoSize | Out-String -Width 250
"MANA LEFT OPEN"
$s.Keys | Sort-Object | ForEach-Object { $d = $s[$_]; [pscustomobject]@{
    Deck = $_
    'Open at start of opponents turns (avg)' = Avg $d.OppTurnOpenSum $d.OppTurns
    '...2+ open' = Pc $d.OppTurnOpen2 $d.OppTurns
    'Open when opponent casts (avg)' = Avg $d.OppCastOpenSum $d.OppCasts
    '2+ open when opponent casts MV4+' = Pc $d.BigCastOpen2 $d.BigCasts
} } | Format-Table -AutoSize | Out-String -Width 250
"OWN BOARD AT START OF ITS TURNS"
$s.Keys | Sort-Object | ForEach-Object { $d = $s[$_]; [pscustomobject]@{
    Deck = $_
    'Turns' = $d.OwnTurns
    'Creatures (avg)' = Avg $d.CreatureSum $d.OwnTurns
    'Lands (avg)' = Avg $d.LandSum $d.OwnTurns
    'Hand (avg)' = Avg $d.HandSum $d.OwnTurns
    'Commander on battlefield' = Pc $d.CmdrOut $d.OwnTurns
} } | Format-Table -AutoSize | Out-String -Width 250
