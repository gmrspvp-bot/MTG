# What people choose with each card vs what the AI chooses with the same card in the same deck
# (the owner, 2026-11-02: "how the human was playing its cards and what they were choosing").
# Plays no games. Companion to deck-style.ps1, which compares overall play style.
#
# For every cast, activation and trigger with a target, per deck (matched by commander) and card:
#   - when: own turn upkeep or draw / main 1 / combat / main 2 / end step, or an opponent's turn (combat, end
#     step, other), and whether it went on the stack in response to another player's spell;
#   - what: own permanent, an opponent's permanent, a player, a spell on the stack;
#   - which: for an opponent's permanent, was it the life leader's, the lowest-life player's or
#     the biggest board's, a commander, that player's biggest creature; for our own creature
#     (equipment, auras, protection), was it our commander.
# Logs don't record a modal spell's mode or a "you may" answer, so those can't be compared.
#
# Writes scratch\card-choices\<commander>.md for each deck with human games, and index.md with
# the biggest differences. A difference is a lead to judge, not a rule: a few human uses can be
# luck of the draw, and a person's choice can be a misplay.
#
#   powershell -ExecutionPolicy Bypass -File scripts\card-choices.ps1 -RunsFile scratch\humandecks-1102-runs.txt
#   powershell -ExecutionPolicy Bypass -File scripts\card-choices.ps1 -Runs results\2026-11-02_184943 -Deck Angels

param(
    [string[]] $Runs,                     # sim run folders for the AI side
    [string] $RunsFile,                   # or a scratch\*-runs.txt listing them
    [string] $Deck,                       # only this deck (deck name or commander, regex)
    [int] $MinUses = 3,                   # a card needs this many human uses to be compared
    [string] $Out = 'scratch\card-choices'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- deck name -> commander (as deck-style.ps1) ----------------------------------------------------
$cmdOf = @{}
$dckFiles = @(Get-ChildItem 'decks' -Filter '*.dck') + @(Get-ChildItem 'decks\archive' -Filter '*.dck' -ErrorAction SilentlyContinue)
foreach ($f in $dckFiles) {
    $inCmd = $false
    foreach ($ln in [IO.File]::ReadLines($f.FullName)) {
        if ($ln -match '^\[commander\]') { $inCmd = $true; continue }
        if ($ln -match '^\[') { $inCmd = $false; continue }
        if ($inCmd -and $ln -match '^\d+\s+(.+?)(\|.*)?$') {
            $cmdOf[$f.BaseName] = $matches[1].Trim()
            $bare = $f.BaseName -replace '^[^-]+? - ', ''
            if (-not $cmdOf.ContainsKey($bare)) { $cmdOf[$bare] = $matches[1].Trim() }
            break
        }
    }
}
function Get-Key([string] $deckName) { if ($cmdOf.ContainsKey($deckName)) { return $cmdOf[$deckName] } return $deckName }

# --- results: $stats[side][deckKey][card] = counters ------------------------------------------------
$stats = @{ HUMAN = @{}; AI = @{} }
$games = @{ HUMAN = @{}; AI = @{} }
$COUNTERS = 'uses','main1','combat','main2','ownBegin','ownEnd','oppCombat','oppEnd','oppOther','response',
    'tOwn','tOpp','tPlayerSelf','tPlayerOpp','tSpell','tOppChoice','tLeader','tLowest','tBigBoard','tCmdr','tBiggest','tOwnCreature','tOwnCmdr'
function Get-Stat($side, $key, $card) {
    $d = $stats[$side]
    if (-not $d.ContainsKey($key)) { $d[$key] = @{} }
    if (-not $d[$key].ContainsKey($card)) { $h = @{}; foreach ($c in $COUNTERS) { $h[$c] = 0 }; $d[$key][$card] = $h }
    return $d[$key][$card]
}

$cardRe = [regex]'(?<name>[^\[\],]+?) \((?<id>\d+)\)'
$creatRe = [regex]'^(?<name>.+?) (?<p>-?\d+)/(?<t>-?\d+)'

function Read-Log([string] $path, [bool] $humanOnly) {
    $header = @{}                          # name -> @(kind, deckKey)
    $inGame = $false; $lastTurn = 0
    $active = ''; $phase = ''
    $life = @{}; $creat = @{}; $board = @{}   # board[player] = list of @(name, power)
    $ctrl = @{}                            # card name -> controller (last seen)
    $stack = New-Object System.Collections.Generic.List[string]
    $playersOf = @{}

    $side = {
        param($n)
        if ($header.ContainsKey($n)) {
            $h = $header[$n]
            if ($humanOnly -and $h[0] -ne 'HUMAN') { return $null }
            return @($h[0], $h[1])
        }
        if (-not $humanOnly -and $n -match '^Ai\(\d\)-(.+)$') { return @('AI', (Get-Key $matches[1])) }
        return $null
    }

    foreach ($ln in [IO.File]::ReadLines($path, [Text.Encoding]::UTF8)) {
        $c0 = $ln[0]
        if ($c0 -eq 'P') {
            if ($ln.StartsWith('Player: ')) {
                if ($ln -match '^Player: (.+?) \((HUMAN|AI)\) deck=\? \(commander (.+?)\)') { $header[$matches[1]] = @($matches[2], $matches[3]) }
                elseif ($ln -match '^Player: (.+?) \((HUMAN|AI)\) deck=(.+?)\s*$') { $header[$matches[1]] = @($matches[2], (Get-Key $matches[3])) }
            } elseif ($ln.StartsWith('Phase: ')) {
                $stack.Clear()
                if ($ln -match 'Main phase, precombat') { $phase = 'main1' }
                elseif ($ln -match 'Main phase, postcombat') { $phase = 'main2' }
                elseif ($ln -match 'Combat|Attackers|Blockers|Damage') { $phase = 'combat' }
                elseif ($ln -match 'End step|Cleanup') { $phase = 'end' }
                elseif ($ln -match 'Upkeep|Draw step|Untap') { $phase = 'begin' }
                else { $phase = 'other' }
            }
            continue
        }
        if ($c0 -eq 'T' -and $ln.StartsWith('Turn: Turn ')) {
            if ($ln -notmatch '^Turn: Turn (\d+) \((.+)\)\s*$') { continue }
            $t = [int]$matches[1]
            if ($t -eq $lastTurn) { continue }
            if ($t -lt $lastTurn -or -not $inGame) {
                $inGame = $true; $life = @{}; $creat = @{}; $board = @{}; $ctrl = @{}; $playersOf = @{}
            }
            $lastTurn = $t; $active = $matches[2]; $phase = 'other'; $stack.Clear()
            continue
        }
        if (-not $inGame) { continue }
        if ($c0 -eq 'I' -and $ln.StartsWith('Information: BOARD turn ')) {
            if ($ln -notmatch '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| (life (-?\d+)|out)(.*)$') { continue }
            $n = $matches[2]; $rest = $matches[5]
            if ($matches[3] -eq 'out') { $life[$n] = 0; $board[$n] = @(); continue }
            $life[$n] = [int]$matches[4]
            if (-not $playersOf.ContainsKey($n)) {
                $s = & $side $n
                if ($s) { $playersOf[$n] = $s; if (-not $games[$s[0]].ContainsKey($s[1])) { $games[$s[0]][$s[1]] = 0 }; $games[$s[0]][$s[1]]++ }
                else { $playersOf[$n] = $null }
            }
            $list = @()
            if ($rest -match 'creatures (\d+) \([^)]*\):\s*(.*?)\s*\| ') {
                $creat[$n] = [int]$matches[1]
                foreach ($cr in ($matches[2] -split '; ')) {
                    $m = $creatRe.Match($cr)
                    if ($m.Success) { $list += ,@($m.Groups['name'].Value, [int]$m.Groups['p'].Value); $ctrl[$m.Groups['name'].Value] = $n }
                }
            }
            $board[$n] = $list
            continue
        }
        if ($c0 -eq 'L' -and $ln.StartsWith('Life: ')) {
            if ($ln -match '^Life: Life: (.+?) (-?\d+) > (-?\d+)') { $life[$matches[1]] = [int]$matches[3] }
            continue
        }
        if ($c0 -eq 'R' -and $ln.StartsWith('Resolve Stack: ')) {
            if ($stack.Count -gt 0) { $stack.RemoveAt($stack.Count - 1) }
            continue
        }
        if ($c0 -ne 'A' -or -not $ln.StartsWith('Add To Stack: ')) { continue }
        if ($ln -notmatch '^Add To Stack: (.+?) (cast|activated|triggered) (.+?)(?: targeting (.*))?\s*$') { continue }
        $who = $matches[1]; $how = $matches[2]; $card = $matches[3]; $tgt = $matches[4]
        $respondingTo = if ($stack.Count -gt 0) { $stack[$stack.Count - 1] } else { $null }
        $stack.Add($who)
        if ($how -eq 'cast') { $ctrl[$card] = $who }
        $s = & $side $who
        if (-not $s) { continue }
        if ($how -eq 'triggered' -and -not $tgt) { continue }     # untargeted triggers say nothing about a choice
        $st = Get-Stat $s[0] $s[1] $card
        $st.uses++
        if ($who -eq $active) {
            switch ($phase) { 'main1' { $st.main1++ } 'combat' { $st.combat++ } 'main2' { $st.main2++ } 'end' { $st.ownEnd++ } default { $st.ownBegin++ } }
        } else {
            switch ($phase) { 'combat' { $st.oppCombat++ } 'end' { $st.oppEnd++ } default { $st.oppOther++ } }
        }
        if ($how -ne 'triggered' -and $respondingTo -and $respondingTo -ne $who) { $st.response++ }
        if (-not $tgt) { continue }
        # First target only: that's the choice that matters for nearly every card.
        $m = $cardRe.Match($tgt)
        $opps = @($life.Keys | Where-Object { $_ -ne $who -and $life[$_] -gt 0 })
        if ($m.Success) {
            $tn = $m.Groups['name'].Value.Trim()
            $owner = $ctrl[$tn]
            if ($owner -eq $who) {
                $st.tOwn++
                $mine = @($board[$who] | Where-Object { $_[0] -eq $tn })
                if ($mine.Count) { $st.tOwnCreature++; if ($playersOf[$who] -and $tn -eq $s[1]) { $st.tOwnCmdr++ } }
            } elseif ($owner) {
                $st.tOpp++
                if ($opps.Count -ge 2 -and $life.ContainsKey($owner)) {
                    $st.tOppChoice++
                    $maxL = ($opps | ForEach-Object { $life[$_] } | Measure-Object -Maximum).Maximum
                    $minL = ($opps | ForEach-Object { $life[$_] } | Measure-Object -Minimum).Minimum
                    $maxB = ($opps | ForEach-Object { [int]$creat[$_] } | Measure-Object -Maximum).Maximum
                    if ($life[$owner] -eq $maxL) { $st.tLeader++ }
                    if ($life[$owner] -eq $minL) { $st.tLowest++ }
                    if ([int]$creat[$owner] -eq $maxB) { $st.tBigBoard++ }
                }
                $os = $playersOf[$owner]
                $ok = if ($os) { $os[1] } elseif ($header.ContainsKey($owner)) { $header[$owner][1] } elseif ($owner -match '^Ai\(\d\)-(.+)$') { Get-Key $matches[1] } else { $null }
                if ($ok -and $tn -eq $ok) { $st.tCmdr++ }
                $theirs = @($board[$owner])
                if ($theirs.Count) {
                    $mine = @($theirs | Where-Object { $_[0] -eq $tn })
                    $maxP = ($theirs | ForEach-Object { $_[1] } | Measure-Object -Maximum).Maximum
                    if ($mine.Count -and $mine[0][1] -eq $maxP) { $st.tBiggest++ }
                }
            }
        } else {
            $inner = ($tgt -replace '^\[|\]$', '').Trim()
            if ($inner -eq $who) { $st.tPlayerSelf++ }
            elseif ($life.ContainsKey($inner)) {
                $st.tPlayerOpp++
                if ($opps.Count -ge 2) {
                    $st.tOppChoice++
                    if ($life[$inner] -eq ($opps | ForEach-Object { $life[$_] } | Measure-Object -Maximum).Maximum) { $st.tLeader++ }
                    if ($life[$inner] -eq ($opps | ForEach-Object { $life[$_] } | Measure-Object -Minimum).Minimum) { $st.tLowest++ }
                    if ([int]$creat[$inner] -eq ($opps | ForEach-Object { [int]$creat[$_] } | Measure-Object -Maximum).Maximum) { $st.tBigBoard++ }
                }
            }
            elseif ($inner) { $st.tSpell++ }
        }
    }
}

# --- read everything ---------------------------------------------------------------------------------
$humanFiles = @(Get-ChildItem 'scratch\play-logs' -Filter 'game_*.log' | Sort-Object Name)
foreach ($f in $humanFiles) { Read-Log $f.FullName $true }
if ($RunsFile) { foreach ($ln in Get-Content $RunsFile) { if ($ln -match '(\d{4}-\d\d-\d\d_\d{6})') { $Runs += "results\$($matches[1])" } } }
$Runs = @($Runs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ -and (Test-Path $_) } | Sort-Object -Unique)
foreach ($d in $Runs) { foreach ($f in Get-ChildItem $d -Filter 'batch*.log') { Read-Log $f.FullName $false } }
"human logs: {0}; AI run folders: {1}" -f $humanFiles.Count, $Runs.Count

# --- compare -----------------------------------------------------------------------------------------
function Pct($n, $d) { if ($d -gt 0) { return $n / $d } return $null }
function F($v) { if ($null -eq $v) { return '-' } return ('{0:P0}' -f $v) -replace ' ', '' }
# rows: label, counter, denominator counter
$ROWS = @(
    @('Own turn, main 1', 'main1', 'uses'), @('Own turn, combat', 'combat', 'uses'), @('Own turn, main 2', 'main2', 'uses'),
    @('Own turn, upkeep or draw step', 'ownBegin', 'uses'), @('Own turn, end step', 'ownEnd', 'uses'),
    @('Opponent''s turn, combat', 'oppCombat', 'uses'),
    @('Opponent''s turn, end step', 'oppEnd', 'uses'), @('Opponent''s turn, other', 'oppOther', 'uses'),
    @('In response to another player', 'response', 'uses'),
    @('Targets own permanent', 'tOwn', 'uses'), @('Targets an opponent''s permanent', 'tOpp', 'uses'),
    @('Targets a player (opponent)', 'tPlayerOpp', 'uses'), @('Targets a spell', 'tSpell', 'uses'),
    @('  ...at the life leader', 'tLeader', 'tOppChoice'), @('  ...at the lowest life', 'tLowest', 'tOppChoice'),
    @('  ...at the biggest board', 'tBigBoard', 'tOppChoice'), @('  ...a commander', 'tCmdr', 'tOpp'),
    @('  ...their biggest creature', 'tBiggest', 'tOpp'), @('  own creature: our commander', 'tOwnCmdr', 'tOwnCreature')
)
New-Item -ItemType Directory -Force $Out | Out-Null
Get-ChildItem $Out -Filter '*.md' -ErrorAction SilentlyContinue | Remove-Item
$index = New-Object System.Collections.Generic.List[string]
$allDiffs = New-Object System.Collections.Generic.List[object]
foreach ($key in ($stats.HUMAN.Keys | Sort-Object)) {
    if ($Deck -and $key -notmatch $Deck) { continue }
    $hCards = $stats.HUMAN[$key]
    $aCards = if ($stats.AI.ContainsKey($key)) { $stats.AI[$key] } else { @{} }
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# $key - card choices"); $md.Add('')
    $md.Add(("Human games: **{0}**. AI games: **{1}**. Cards with at least {2} human uses (casts, activations or targeted triggers)." -f [int]$games.HUMAN[$key], [int]$games.AI[$key], $MinUses))
    $md.Add('')
    $deckDiffs = New-Object System.Collections.Generic.List[string]
    $sections = New-Object System.Collections.Generic.List[string]
    foreach ($card in ($hCards.Keys | Sort-Object { -$hCards[$_].uses })) {
        $h = $hCards[$card]
        if ($h.uses -lt $MinUses) { continue }
        $a = $aCards[$card]
        $sections.Add(("## {0}" -f $card)); $sections.Add('')
        $sections.Add(("Uses: people {0}, AI {1}." -f $h.uses, $(if ($a) { $a.uses } else { 0 }))); $sections.Add('')
        $sections.Add('| | People | AI |'); $sections.Add('|---|---|---|')
        foreach ($row in $ROWS) {
            $hv = Pct $h[$row[1]] $h[$row[2]]
            $av = if ($a) { Pct $a[$row[1]] $a[$row[2]] } else { $null }
            if ((-not $hv) -and (-not $av)) { continue }
            $flag = ''
            # A difference: 30 points apart, with at least MinUses human cases behind the share.
            if ($null -ne $hv -and $null -ne $av -and [math]::Abs($hv - $av) -ge 0.30 -and $h[$row[2]] -ge $MinUses -and $a[$row[2]] -ge 5) {
                $flag = '**diff**'
                $txt = "{0}: {1} - people {2} ({3} of {4}), AI {5} ({6} of {7})" -f $card, $row[0].Trim(' .'), (F $hv), $h[$row[1]], $h[$row[2]], (F $av), $a[$row[1]], $a[$row[2]]
                $deckDiffs.Add("- $txt")
                $allDiffs.Add([pscustomobject]@{ Deck = $key; Text = $txt; Size = [math]::Abs($hv - $av) * [math]::Sqrt($h[$row[2]]) })
            }
            $sections.Add(("| {0} | {1} | {2} | {3}" -f $row[0], (F $hv), (F $av), $flag))
        }
        $sections.Add('')
    }
    if ($sections.Count -eq 0) { continue }
    $md.Add('## Differences'); $md.Add('')
    if ($deckDiffs.Count) { $md.AddRange($deckDiffs) } else { $md.Add('None at this sample size.') }
    $md.Add('')
    $md.AddRange($sections)
    $safe = $key -replace '[\\/:*?"<>|]', '_'
    [IO.File]::WriteAllLines((Join-Path $Out "$safe.md"), $md, (New-Object Text.UTF8Encoding $false))
    $index.Add(("| [{0}]({1}.md) | {2} | {3} | {4} |" -f $key, $safe, [int]$games.HUMAN[$key], [int]$games.AI[$key], $deckDiffs.Count))
}
$top = @($allDiffs | Sort-Object Size -Descending | Select-Object -First 40)
$im = New-Object System.Collections.Generic.List[string]
$im.Add('# Card choices: people vs the AI'); $im.Add('')
$im.Add(("Made {0} by scripts\card-choices.ps1 from {1} recorded games and {2} sim run folders." -f (Get-Date -Format 'yyyy-MM-dd HH:mm'), $humanFiles.Count, $Runs.Count)); $im.Add('')
$im.Add('| Deck (commander) | Human games | AI games | Differences |'); $im.Add('|---|---|---|---|')
$im.AddRange($index); $im.Add('')
$im.Add('## Biggest differences'); $im.Add('')
foreach ($d in $top) { $im.Add("- **$($d.Deck)** - $($d.Text)") }
[IO.File]::WriteAllLines((Join-Path $Out 'index.md'), $im, (New-Object Text.UTF8Encoding $false))
"Reports in $Out (index.md)"
