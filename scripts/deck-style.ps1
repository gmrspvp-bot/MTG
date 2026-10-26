# How people play each deck vs how the AI plays it, measured the same way, with the biggest gaps
# turned into suggestions (the owner, 2026-10-26: "semi automate this so that you create
# suggestions based on games played by other humans"). Plays no games.
#
# Human side: every HUMAN player in scratch\play-logs\game_*.log (joined logs too; they name the
# commander instead of the deck). AI side: sim batch logs from the run folders given, by default
# every folder listed in a scratch\*-runs.txt written in the last 7 days (-RunsDays). Decks are matched by commander, so
# "Captain America v6" played by a person and "Captain America v7" in the sims are one deck.
#
# Writes scratch\deck-style\<commander>.md per deck with human games, plus index.md listing every
# deck, its human game count and how many games are new since the last run. A suggestion is a
# lead for a person to judge, not a rule: one deck's few games can be the draws, and a person's
# play can be a misplay. Approved ones go into the AI (AI-TODO.md).
#
#   powershell -ExecutionPolicy Bypass -File scripts\deck-style.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\deck-style.ps1 -Runs results\2026-10-26_210031,results\... -Deck Angels

param(
    [string[]] $Runs,
    [int] $RunsDays = 7,                  # no -Runs: every folder in scratch\*-runs.txt files from the last N days
    [string] $Deck,                       # only this deck (deck name or commander, regex)
    [int] $MinGames = 3,                  # human games below this get a "too few games" warning
    [string] $Out = 'scratch\deck-style'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- deck name -> commander --------------------------------------------------------------------
$cmdOf = @{}
$dckFiles = @(Get-ChildItem 'decks' -Filter '*.dck') + @(Get-ChildItem 'decks\archive' -Filter '*.dck' -ErrorAction SilentlyContinue)
foreach ($f in $dckFiles) {
    $inCmd = $false
    foreach ($ln in [IO.File]::ReadLines($f.FullName)) {
        if ($ln -match '^\[commander\]') { $inCmd = $true; continue }
        if ($ln -match '^\[') { $inCmd = $false; continue }
        if ($inCmd -and $ln -match '^\d+\s+(.+?)(\|.*)?$') { $cmdOf[$f.BaseName] = $matches[1].Trim(); break }
    }
}
function Get-Key([string] $deckName) {
    if ($cmdOf.ContainsKey($deckName)) { return $cmdOf[$deckName] }
    return $deckName
}

# --- one log file -> per player-game records ------------------------------------------------------
function New-Rec($name, $key, $deckName, $kind, $file) {
    [pscustomobject]@{
        name = $name; key = $key; deck = $deckName; kind = $kind; file = $file
        ownTurns = 0; cmdFirst = $null; cmdCasts = 0; capacity = 0
        spellsOwn = 0; spellsOpp = 0; main1 = 0; main2 = 0
        unspent = 0; unspentN = 0; held = 0; heldN = 0
        couldAttack = 0; attackTurns = 0; attackers = 0; creaturesAtStart = 0; defenders = 0
        atkLeader = 0; atkWeakest = 0; atkMostBoard = 0; atkChoices = 0
        actOwn = 0; actOpp = 0; blockChances = 0; blocks = 0
        won = $false; finished = $false; out = $false
        seen = @{}; cast = @{}; castOpp = @{}; delay = @{}; acts = @{}
    }
}

function Read-Log([string] $path, [bool] $humanOnly) {
    $recs = New-Object System.Collections.Generic.List[object]
    $file = Split-Path $path -Leaf
    $header = @{}                       # name -> @(kind, deck, key)
    $g = $null
    $lastTurn = 0; $active = ''; $prevActive = ''; $phase = ''
    $life = @{}; $creat = @{}
    $turnAtk = @{}; $turnDef = @{}; $blockChance = @{}; $blocked = @{}

    $flushTurn = {
        if ($g -and $g.ContainsKey($active)) {
            $r = $g[$active]
            if ($turnAtk.Count -gt 0) { $r.attackTurns++; $r.defenders += $turnDef.Count }
        }
        foreach ($d in @($blockChance.Keys)) {
            if ($g -and $g.ContainsKey($d)) { $g[$d].blockChances++; if ($blocked.ContainsKey($d)) { $g[$d].blocks++ } }
        }
        $turnAtk.Clear(); $turnDef.Clear(); $blockChance.Clear(); $blocked.Clear()
    }
    $finish = {
        if ($g) { foreach ($r in $g.Values) { if ($r.ownTurns -gt 0) { $recs.Add($r) } } }
    }
    $player = {
        param($n)
        if (-not $g.ContainsKey($n)) {
            if ($header.ContainsKey($n)) {
                $h = $header[$n]
                if ($humanOnly -and $h[0] -ne 'HUMAN') { return $null }
                $g[$n] = New-Rec $n $h[2] $h[1] $h[0] $file
            } elseif (-not $humanOnly -and $n -match '^Ai\(\d\)-(.+)$') {
                $g[$n] = New-Rec $n (Get-Key $matches[1]) $matches[1] 'AI' $file
            } else { return $null }
        }
        return $g[$n]
    }

    foreach ($ln in [IO.File]::ReadLines($path, [Text.Encoding]::UTF8)) {
        if ($ln.StartsWith('Player: ')) {
            if ($ln -match '^Player: (.+?) \((HUMAN|AI)\) deck=\? \(commander (.+?)\)') { $header[$matches[1]] = @($matches[2], $matches[3], $matches[3]) }
            elseif ($ln -match '^Player: (.+?) \((HUMAN|AI)\) deck=(.+?)\s*$') { $header[$matches[1]] = @($matches[2], $matches[3], (Get-Key $matches[3])) }
            continue
        }
        if ($ln.StartsWith('Turn: Turn ')) {
            if ($ln -notmatch '^Turn: Turn (\d+) \((.+)\)\s*$') { continue }
            $t = [int]$matches[1]
            if ($t -eq $lastTurn) { continue }               # recorders repeat the turn-1 line
            if ($t -lt $lastTurn -or -not $g) { & $flushTurn; & $finish; $g = @{}; $life = @{}; $creat = @{} }
            else { & $flushTurn }
            $lastTurn = $t; $prevActive = $active; $active = $matches[2]; $phase = ''
            $r = & $player $active
            if ($r) { $r.ownTurns++ }
            continue
        }
        if (-not $g) { continue }
        if ($ln.StartsWith('Information: BOARD turn ')) {
            if ($ln -notmatch '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| (life (-?\d+)|out)(.*)$') { continue }
            $n = $matches[2]; $rest = $matches[5]
            if ($matches[3] -eq 'out') { $life[$n] = 0; continue }
            $life[$n] = [int]$matches[4]
            if ($rest -match 'creatures (\d+)') { $creat[$n] = [int]$matches[1] }
            $r = & $player $n
            if (-not $r) { continue }
            $open = 0; if ($rest -match 'open mana (\d+)') { $open = [int]$matches[1] }
            # this line comes before the untap step, so judge "could cast it this turn" by the most mana
            # this player has had open so far (or their land count)
            $lands = 0; if ($rest -match 'lands (\d+)') { $lands = [int]$matches[1] }
            $r.capacity = [math]::Max([math]::Max($r.capacity, $open), $lands)
            $hand = $null; if ($rest -match 'hand cards: (.*)$' -and $matches[1] -ne 'hidden') { $hand = $matches[1] }
            if ($n -eq $active) {
                if ($creat[$n] -gt 0) { $r.couldAttack++; $r.creaturesAtStart += $creat[$n] }
            }
            if ($hand) {
                $castable = 0
                foreach ($c in ($hand -split '; ')) {
                    if ($c -notmatch '^(.+)\((land|\d+)\)$') { continue }
                    $cn = $matches[1]; $mv = $matches[2]
                    if ($mv -eq 'land') { continue }
                    # "seen" = in hand at the start of an own turn with the mana to cast it
                    if ($n -eq $active -and [int]$mv -le $r.capacity -and -not $r.seen.ContainsKey($cn)) { $r.seen[$cn] = $r.ownTurns }
                    if ([int]$mv -le $open) { $castable++ }
                }
                if ($n -eq $prevActive -and $n -ne $active) { $r.held += $castable; $r.heldN++ }
            }
            if ($n -eq $prevActive -and $n -ne $active) { $r.unspent += $open; $r.unspentN++ }
            continue
        }
        if ($ln.StartsWith('Phase: ')) {
            if ($ln -match 'Main phase, precombat') { $phase = 'main1' } elseif ($ln -match 'Main phase, postcombat') { $phase = 'main2' } else { $phase = 'other' }
            continue
        }
        if ($ln.StartsWith('Life: ')) {
            if ($ln -match '^Life: Life: (.+?) (-?\d+) > (-?\d+)') { $life[$matches[1]] = [int]$matches[3] }
            continue
        }
        if ($ln.StartsWith('Add To Stack: ')) {
            if ($ln -match '^Add To Stack: (.+?) cast (.+?)(?: targeting \[.*\])?\s*$') {
                $r = & $player $matches[1]
                if (-not $r) { continue }
                $cn = $matches[2]
                $own = ($matches[1] -eq $active)
                if ($own) { $r.spellsOwn++; if ($phase -eq 'main1') { $r.main1++ } elseif ($phase -eq 'main2') { $r.main2++ } }
                else { $r.spellsOpp++; $r.castOpp[$cn] = 1 + [int]$r.castOpp[$cn] }
                if ($cn -eq $r.key) { $r.cmdCasts++; if ($null -eq $r.cmdFirst) { $r.cmdFirst = $r.ownTurns } }
                elseif (-not $r.cast.ContainsKey($cn)) {
                    $r.cast[$cn] = 1
                    if ($r.seen.ContainsKey($cn)) { $r.delay[$cn] = $r.ownTurns - $r.seen[$cn] }
                } else { $r.cast[$cn]++ }
            } elseif ($ln -match '^Add To Stack: (.+?) activated (.+?)(?: targeting \[.*\])?\s*$') {
                $r = & $player $matches[1]
                if (-not $r) { continue }
                if ($matches[1] -eq $active) { $r.actOwn++ } else { $r.actOpp++ }
                $r.acts[$matches[2]] = 1 + [int]$r.acts[$matches[2]]
            }
            continue
        }
        if ($ln.StartsWith('Information: BOARD attack | ')) {
            if ($ln -notmatch '^Information: BOARD attack \| (.+?) -> (.+?) \| attackers (\d+) .*?\| untapped creatures (\d+)') { continue }
            $a = $matches[1]; $d = $matches[2]; $nAtk = [int]$matches[3]; $unt = [int]$matches[4]
            if ($unt -gt 0) { $blockChance[$d] = 1 }
            $r = & $player $a
            if (-not $r) { continue }
            if (-not $turnAtk.ContainsKey($a)) { $turnAtk[$a] = 0 }
            $turnAtk[$a] += $nAtk; $r.attackers += $nAtk
            if (-not $turnDef.ContainsKey($d) -and $life.ContainsKey($d)) {
                $turnDef[$d] = 1
                $opp = @($life.Keys | Where-Object { $_ -ne $a -and $life[$_] -gt 0 })
                if ($opp.Count -ge 2) {
                    $r.atkChoices++
                    $maxL = ($opp | ForEach-Object { $life[$_] } | Measure-Object -Maximum).Maximum
                    $minL = ($opp | ForEach-Object { $life[$_] } | Measure-Object -Minimum).Minimum
                    $maxB = ($opp | ForEach-Object { [int]$creat[$_] } | Measure-Object -Maximum).Maximum
                    if ($life[$d] -eq $maxL) { $r.atkLeader++ }
                    if ($life[$d] -eq $minL) { $r.atkWeakest++ }
                    if ([int]$creat[$d] -eq $maxB) { $r.atkMostBoard++ }
                }
            }
            continue
        }
        if ($ln.StartsWith('Combat: ')) {
            if ($ln -match '^Combat: (.+?) assigned .+ to block ') { $blocked[$matches[1]] = 1 }
            continue
        }
        if ($ln.StartsWith('Game Result: ')) {
            foreach ($r in $g.Values) { $r.finished = ($ln -notmatch 'unfinished') }
            if ($ln -match 'Game Result: (?:Game \d+ ended in \d+ ms\. )?(.+?) has won') { $w = & $player $matches[1]; if ($w) { $w.won = $true } }
            continue
        }
        if ($ln.StartsWith('Result Override: ')) {
            # "Result Override: <name> (<deck>) - ... Count this game as a <deck> win": take the player named first
            if ($ln -match '^Result Override: (.+?) \(' ) { foreach ($r in $g.Values) { $r.won = ($r.name -eq $matches[1]) } }
            continue
        }
    }
    & $flushTurn; & $finish
    return ,$recs
}

# --- read everything -------------------------------------------------------------------------------
$human = New-Object System.Collections.Generic.List[object]
$humanFiles = @(Get-ChildItem 'scratch\play-logs' -Filter 'game_*.log' | Sort-Object Name)
foreach ($f in $humanFiles) { foreach ($r in (Read-Log $f.FullName $true)) { $human.Add($r) } }

if (-not $Runs) {
    $Runs = @()
    foreach ($rf in Get-ChildItem scratch -Filter "*-runs.txt" | Where-Object { $_.LastWriteTime -gt (Get-Date).AddDays(-$RunsDays) }) {
        foreach ($ln in Get-Content $rf.FullName) { if ($ln -match '\| (\d{4}-\d\d-\d\d_\d{6}) \|') { $Runs += "results\$($matches[1])" } }
    }
    $Runs = @($Runs | Sort-Object -Unique)
}
$Runs = @($Runs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ -and (Test-Path $_) })
$ai = New-Object System.Collections.Generic.List[object]
foreach ($d in $Runs) {
    foreach ($f in Get-ChildItem $d -Filter 'batch*.log') { foreach ($r in (Read-Log $f.FullName $false)) { $ai.Add($r) } }
}
"human player-games: {0} from {1} logs; AI player-games: {2} from {3} run folders" -f $human.Count, $humanFiles.Count, $ai.Count, $Runs.Count

# --- measures ------------------------------------------------------------------------------------
function Ratio($num, $den) { if ($den -gt 0) { return $num / $den } else { return $null } }
function Measures($set) {
    $s = @($set)
    $sum = { param($p) ($s | ForEach-Object { $_.$p } | Measure-Object -Sum).Sum }
    $fin = @($s | Where-Object { $_.finished })
    $cmd = @($s | Where-Object { $null -ne $_.cmdFirst } | ForEach-Object { $_.cmdFirst })
    $turns = & $sum 'ownTurns'
    [ordered]@{
        'Win rate (finished games)'             = @((Ratio (@($fin | Where-Object { $_.won }).Count) $fin.Count), 'pct')
        'Commander first cast (own turn)'       = @($(if ($cmd.Count) { ($cmd | Measure-Object -Average).Average } else { $null }), 'num')
        'Commander casts per game'              = @((Ratio (& $sum 'cmdCasts') $s.Count), 'num')
        'Spells per own turn'                   = @((Ratio (& $sum 'spellsOwn') $turns), 'num')
        'Spells cast on opponents'' turns'      = @((Ratio (& $sum 'spellsOpp') ((& $sum 'spellsOwn') + (& $sum 'spellsOpp'))), 'pct')
        'Own-turn spells in main phase 2'       = @((Ratio (& $sum 'main2') ((& $sum 'main1') + (& $sum 'main2'))), 'pct')
        'Mana left unspent after own turn'      = @((Ratio (& $sum 'unspent') (& $sum 'unspentN')), 'num')
        'Affordable spells kept in hand'        = @((Ratio (& $sum 'held') (& $sum 'heldN')), 'num')
        'Attacks on turns with creatures'       = @((Ratio (& $sum 'attackTurns') (& $sum 'couldAttack')), 'pct')
        'Attackers per attacking turn'          = @((Ratio (& $sum 'attackers') (& $sum 'attackTurns')), 'num')
        'Players attacked per attacking turn'   = @((Ratio (& $sum 'defenders') (& $sum 'attackTurns')), 'num')
        'Attacks at the life leader'            = @((Ratio (& $sum 'atkLeader') (& $sum 'atkChoices')), 'pct')
        'Attacks at the lowest life'            = @((Ratio (& $sum 'atkWeakest') (& $sum 'atkChoices')), 'pct')
        'Attacks at the biggest board'          = @((Ratio (& $sum 'atkMostBoard') (& $sum 'atkChoices')), 'pct')
        'Activations per own turn'              = @((Ratio (& $sum 'actOwn') $turns), 'num')
        'Activations on opponents'' turns'      = @((Ratio (& $sum 'actOpp') ((& $sum 'actOwn') + (& $sum 'actOpp'))), 'pct')
        'Blocks when able'                      = @((Ratio (& $sum 'blocks') (& $sum 'blockChances')), 'pct')
    }
}
function Fmt($v, $kind) {
    if ($null -eq $v) { return '-' }
    if ($kind -eq 'pct') { return ('{0:P0}' -f $v) -replace ' ', '' }
    return '{0:N1}' -f $v
}
# A gap worth a suggestion: percentages 15 points apart, numbers 35% apart (and at least 0.5).
function Gap($h, $a, $kind) {
    if ($null -eq $h -or $null -eq $a) { return 0 }
    if ($kind -eq 'pct') { $d = $h - $a; if ([math]::Abs($d) -ge 0.15) { return $d } else { return 0 } }
    $d = $h - $a; $base = [math]::Max([math]::Abs($a), 0.5)
    if ([math]::Abs($d) -ge 0.5 -and [math]::Abs($d) / $base -ge 0.35) { return $d } else { return 0 }
}
$advice = @{
    'Commander first cast (own turn)'     = @('casts its commander later than people do', 'casts its commander earlier than people do')
    'Commander casts per game'            = @('recasts its commander less', 'recasts its commander more')
    'Spells per own turn'                 = @('casts fewer spells a turn', 'casts more spells a turn')
    'Spells cast on opponents'' turns'    = @('rarely casts at instant speed on others'' turns', 'casts on others'' turns more than people do')
    'Own-turn spells in main phase 2'     = @('casts too much before combat', 'holds spells until after combat more than people do')
    'Mana left unspent after own turn'    = @('spends its mana down more than people do (holds up less)', 'leaves more mana unspent')
    'Affordable spells kept in hand'      = @('dumps its hand more than people do', 'sits on affordable spells')
    'Attacks on turns with creatures'     = @('attacks less often', 'attacks more often')
    'Attackers per attacking turn'        = @('attacks with fewer creatures', 'attacks with more creatures')
    'Players attacked per attacking turn' = @('focuses one player more', 'spreads attacks over more players')
    'Attacks at the life leader'          = @('hits the life leader less', 'hits the life leader more')
    'Attacks at the lowest life'          = @('goes after the lowest-life player less', 'goes after the lowest-life player more')
    'Attacks at the biggest board'        = @('attacks the biggest board less', 'attacks the biggest board more')
    'Activations per own turn'            = @('uses activated abilities less on its turn', 'activates more on its turn')
    'Activations on opponents'' turns'    = @('rarely activates on others'' turns', 'activates on others'' turns more')
    'Blocks when able'                    = @('blocks less', 'blocks more')
}

# --- per-card comparison ---------------------------------------------------------------------------
function CardStats($set) {
    $cs = @{}
    foreach ($r in @($set)) {
        foreach ($c in $r.seen.Keys) { if (-not $cs.ContainsKey($c)) { $cs[$c] = @{ seen = 0; cast = 0; castGames = 0; opp = 0; delay = 0; delayN = 0; acts = 0; onField = 0 } }; $cs[$c].seen++ }
        foreach ($c in $r.cast.Keys) {
            if (-not $cs.ContainsKey($c)) { $cs[$c] = @{ seen = 0; cast = 0; castGames = 0; opp = 0; delay = 0; delayN = 0; acts = 0; onField = 0 } }
            if ($r.seen.ContainsKey($c)) { $cs[$c].cast++ }
            $cs[$c].castGames++
            $cs[$c].onField++
            if ($r.castOpp.ContainsKey($c)) { $cs[$c].opp++ }
            if ($r.delay.ContainsKey($c)) { $cs[$c].delay += $r.delay[$c]; $cs[$c].delayN++ }
        }
        foreach ($c in $r.acts.Keys) {
            if (-not $cs.ContainsKey($c)) { $cs[$c] = @{ seen = 0; cast = 0; castGames = 0; opp = 0; delay = 0; delayN = 0; acts = 0; onField = 0 } }
            $cs[$c].acts += $r.acts[$c]
            if (-not $r.cast.ContainsKey($c)) { $cs[$c].onField++ }
        }
    }
    return $cs
}

# --- reports -------------------------------------------------------------------------------------
New-Item -ItemType Directory -Force $Out | Out-Null
$seenFile = Join-Path $Out 'seen-logs.txt'
$seenBefore = @{}
if (Test-Path $seenFile) { foreach ($x in Get-Content $seenFile) { $seenBefore[$x] = 1 } }

$keys = @($human | ForEach-Object { $_.key } | Sort-Object -Unique)
if ($Deck) { $keys = @($keys | Where-Object { $k = $_; $_ -match $Deck -or @($human | Where-Object { $_.key -eq $k -and $_.deck -match $Deck }).Count -gt 0 }) }
$index = @('# Deck styles: people vs the AI', '', "Made $(Get-Date -Format 'yyyy-MM-dd HH:mm') by scripts\deck-style.ps1 from $($humanFiles.Count) recorded games and $($Runs.Count) sim run folders.", '',
           '| Deck (commander) | Human games (finished) | New since last run | AI games | Suggestions |', '|---|---|---|---|---|')

foreach ($k in $keys) {
    $h = @($human | Where-Object { $_.key -eq $k })
    $a = @($ai | Where-Object { $_.key -eq $k })
    $hFin = @($h | Where-Object { $_.finished }).Count
    $new = @($h | Where-Object { -not $seenBefore.ContainsKey($_.file) }).Count
    $decks = ($h | ForEach-Object { $_.deck } | Sort-Object -Unique) -join ', '
    $people = ($h | ForEach-Object { $_.name } | Sort-Object -Unique) -join ', '
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# $k"); $md.Add('')
    $md.Add("Human games: **$($h.Count)** ($hFin finished) as $decks, played by $people. AI games: **$($a.Count)** (" + (($a | ForEach-Object { $_.deck } | Sort-Object -Unique) -join ', ') + ').')
    if ($h.Count -lt $MinGames) { $md.Add(''); $md.Add("> Only $($h.Count) human game(s): treat every line below as a hint, not a pattern.") }
    if ($a.Count -eq 0) { $md.Add(''); $md.Add('> No sim games for this deck in the run folders read, so there is nothing to compare against.') }
    $md.Add('')

    $hm = Measures $h; $am = if ($a.Count) { Measures $a } else { $null }
    $sugg = New-Object System.Collections.Generic.List[object]
    $md.Add('| Measure | People | AI | |'); $md.Add('|---|---|---|---|')
    foreach ($m in $hm.Keys) {
        $hv = $hm[$m][0]; $kind = $hm[$m][1]; $av = if ($am) { $am[$m][0] } else { $null }
        $gap = if ($m -like 'Win rate*') { 0 } else { Gap $hv $av $kind }
        $flag = ''
        if ($gap -ne 0) {
            $flag = '**gap**'
            $txt = $advice[$m][$(if ($gap -gt 0) { 0 } else { 1 })]
            $size = if ($kind -eq 'pct') { [math]::Abs($gap) / 0.15 } else { [math]::Abs($gap) / [math]::Max([math]::Abs($av), 0.5) / 0.35 }
            $sugg.Add([pscustomobject]@{ size = $size; text = "The AI $txt (people $(Fmt $hv $kind), AI $(Fmt $av $kind): $m)." })
        }
        $md.Add("| $m | $(Fmt $hv $kind) | $(Fmt $av $kind) | $flag |")
    }

    # cards: cast when held, how long held, instant speed, activations
    $hc = CardStats $h; $ac = CardStats $a
    $cardLines = New-Object System.Collections.Generic.List[object]
    foreach ($c in $hc.Keys) {
        $x = $hc[$c]; $y = $ac[$c]
        if ($c -eq $k) { continue }
        if ($x.seen -ge 3 -and $y -and $y.seen -ge 5) {
            $hr = $x.cast / $x.seen; $ar = $y.cast / $y.seen
            if ([math]::Abs($hr - $ar) -ge 0.35) {
                $verb = if ($hr -gt $ar) { 'people cast it once they can afford it, the AI often holds it' } else { 'the AI casts it more readily than people do' }
                $cardLines.Add([pscustomobject]@{ size = [math]::Abs($hr - $ar) * [math]::Min($x.seen, 6); text = "**$c**: $verb (cast once affordable: people $($x.cast)/$($x.seen), AI $($y.cast)/$($y.seen))." })
            }
        }
        if ($x.delayN -ge 3 -and $y -and $y.delayN -ge 4) {
            $hd = $x.delay / $x.delayN; $ad = $y.delay / $y.delayN
            if ([math]::Abs($hd - $ad) -ge 2) {
                $verb = if ($hd -lt $ad) { 'people cast it sooner once affordable' } else { 'people wait longer before casting it' }
                $cardLines.Add([pscustomobject]@{ size = [math]::Abs($hd - $ad) * [math]::Min($x.delayN, 6) / 3; text = "**$c**: $verb (own turns from affordable to cast: people $('{0:N1}' -f $hd), AI $('{0:N1}' -f $ad))." })
            }
        }
        if ($x.castGames -ge 3 -and $y -and $y.castGames -ge 4) {
            $ho = $x.opp / $x.castGames; $ao = $y.opp / $y.castGames
            if ([math]::Abs($ho - $ao) -ge 0.4) {
                $verb = if ($ho -gt $ao) { 'people cast it on opponents'' turns' } else { 'the AI casts it on opponents'' turns, people on their own' }
                $cardLines.Add([pscustomobject]@{ size = [math]::Abs($ho - $ao) * [math]::Min($x.castGames, 6); text = "**$c**: $verb (games it was cast on an opponent's turn: people $($x.opp)/$($x.castGames), AI $($y.opp)/$($y.castGames))." })
            }
        }
        if ($x.acts -ge 3 -and $x.onField -ge 3 -and $y -and $y.onField -ge 4) {
            $ha = $x.acts / $x.onField; $aa = $y.acts / $y.onField
            if ($ha -ge 2 * [math]::Max($aa, 0.25) -or $aa -ge 2 * [math]::Max($ha, 0.25)) {
                $verb = if ($ha -gt $aa) { 'people use its ability far more' } else { 'the AI uses its ability far more' }
                $cardLines.Add([pscustomobject]@{ size = [math]::Abs($ha - $aa) * [math]::Min($x.onField, 6) / 2; text = "**$c**: $verb (activations per game it was out: people $('{0:N1}' -f $ha), AI $('{0:N1}' -f $aa))." })
            }
        }
    }

    $md.Add(''); $md.Add('## Suggestions'); $md.Add('')
    if ($sugg.Count -eq 0) { $md.Add('No play-level gap big enough to flag.') }
    foreach ($s in ($sugg | Sort-Object size -Descending)) { $md.Add("- $($s.text)") }
    $md.Add(''); $md.Add('## Cards'); $md.Add('')
    if ($cardLines.Count -eq 0) { $md.Add('No card with enough games on both sides shows a clear difference.') }
    foreach ($s in ($cardLines | Sort-Object size -Descending | Select-Object -First 15)) { $md.Add("- $($s.text)") }
    $md.Add(''); $md.Add('Human games read: ' + (($h | ForEach-Object { $_.file } | Sort-Object -Unique) -join ', '))

    $safe = ($k -replace '[\\/:*?"<>|]', '')
    [IO.File]::WriteAllLines((Join-Path (Resolve-Path $Out) "$safe.md"), $md, (New-Object Text.UTF8Encoding $false))
    $index += "| [$k]($safe.md) | $($h.Count) ($hFin) | $new | $($a.Count) | $($sugg.Count + $cardLines.Count) |"
    "{0,-40} human {1,3} ({2} new)  AI {3,4}  suggestions {4}" -f $k, $h.Count, $new, $a.Count, ($sugg.Count + $cardLines.Count)
}

[IO.File]::WriteAllLines((Join-Path (Resolve-Path $Out) 'index.md'), $index, (New-Object Text.UTF8Encoding $false))
[IO.File]::WriteAllLines((Join-Path (Resolve-Path $Out) 'seen-logs.txt'), @($human | ForEach-Object { $_.file } | Sort-Object -Unique), (New-Object Text.UTF8Encoding $false))
"Reports in $Out (index.md)"
