# Per-card performance report built from saved Forge simulation logs.
# Reads every results\<run>\batch*.log (or only -RunDirs) and, for each deck,
# reports how often each card was cast/played, the deck's win rate in games
# where it was cast vs. not, damage dealt, and cards that look stuck or idle.
# Plays no games.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\card-stats.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\card-stats.ps1 -RunDirs results\2026-10-09_010823 -MinGames 10

param(
    [string[]] $RunDirs,
    [int] $MinGames = 10   # decks with fewer logged games are skipped
)

$ErrorActionPreference = 'Stop'
$root    = Split-Path -Parent $PSScriptRoot
$deckDir = Join-Path $root 'decks'
# "powershell -File" passes a list as one comma-joined string, so split it here.
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if (-not $RunDirs) { $RunDirs =Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object { $_.FullName } }

# ---------------------------------------------------------------------------
# 1. Parse logs into per-game, per-deck card events.
# ---------------------------------------------------------------------------
$games = New-Object System.Collections.Generic.List[object]

function New-Game { @{ Seats = @{}; Events = @{}; Winner = $null; Turns = 0 } }

function Add-Event($game, $deck, $card, $kind, $amount = 1) {
    if (-not $game.Events.ContainsKey($deck)) { $game.Events[$deck] = @{} }
    $d = $game.Events[$deck]
    if (-not $d.ContainsKey($card)) { $d[$card] = @{ Cast = 0; FirstTurn = $null; Activated = 0; Damage = 0; Died = 0; Discarded = 0 } }
    $d[$card][$kind] += $amount
}

foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $seats = @{}; $game = New-Game; $turn = 0; $cardOwner = @{}; $nameOwner = @{}
        foreach ($l in [IO.File]::ReadLines($f.FullName)) {
            if ($l -match '^Ai\(\d\)-.+ vs ') {
                foreach ($m in [regex]::Matches($l, 'Ai\((\d)\)-(.+?)(?= vs |\s+-\s+[\w\s-]+? games? of|$)')) { $seats[$m.Groups[1].Value] = ($m.Groups[2].Value -replace '\s*\[[^\]]+\]\s*$', '').Trim() }
                continue
            }
            # "Turn:" lines advance twice per real turn; "Game Outcome: Turn N" has the real count.
            if ($l -match '^Turn: Turn (\d+)') { $turn = [math]::Ceiling([int]$matches[1] / 2); continue }
            if ($l -match '^Game Outcome: Turn (\d+)') { $game.Turns = [int]$matches[1]; continue }
            if ($l -match '^Add To Stack: Ai\((\d)\)-.+? cast (.+?)(?: \((\d+)\))?(?: targeting .*)?$') {
                $deck = $seats[$matches[1]]; $card = $matches[2].Trim(); $nameOwner[$card] = $deck
                if ($matches[3]) { $cardOwner[$matches[3]] = $deck }
                Add-Event $game $deck $card 'Cast'
                if (-not $game.Events[$deck][$card].FirstTurn) { $game.Events[$deck][$card].FirstTurn = $turn }
                continue
            }
            if ($l -match '^Land: Ai\((\d)\)-.+? played (.+?) \((\d+)\)') {
                $deck = $seats[$matches[1]]; $card = $matches[2]; $cardOwner[$matches[3]] = $deck; $nameOwner[$card] = $deck
                Add-Event $game $deck $card 'Cast'
                if (-not $game.Events[$deck][$card].FirstTurn) { $game.Events[$deck][$card].FirstTurn = $turn }
                continue
            }
            if ($l -match '^Add To Stack: Ai\((\d)\)-.+? activated (.+?)( \(\d+\))?( targeting.*)?$') {
                $deck = $seats[$matches[1]]; $card = $matches[2].Trim()
                if ($card -notmatch ' Token$') { Add-Event $game $deck $card 'Activated'; if (-not $nameOwner[$card]) { $nameOwner[$card] = $deck } }
                continue
            }
            if ($l -match '^Damage: (.+?) \((\d+)\) deals (\d+)') {
                $deck = $cardOwner[$matches[2]]; if (-not $deck) { $deck = $nameOwner[$matches[1]] }
                if ($deck -and $matches[1] -notmatch ' Token$') { Add-Event $game $deck $matches[1] 'Damage' ([int]$matches[3]) }
                continue
            }
            if ($l -match '^Zone Change: (.+?) \((\d+)\) was put into Graveyard from Battlefield') {
                $deck = $cardOwner[$matches[2]]; if (-not $deck) { $deck = $nameOwner[$matches[1]] }
                if ($deck) { Add-Event $game $deck $matches[1] 'Died' }
                continue
            }
            if ($l -match '^Discard: Ai\((\d)\)-.+? discards (.+?) \(\d+\)') {
                Add-Event $game $seats[$matches[1]] $matches[2] 'Discarded'
                continue
            }
            if ($l -match '^Game Result: Game \d+ ended in \d+ ms\. (.*)$') {
                $rest = $matches[1]
                $game.Winner = if ($rest -match 'Ai\(\d\)-(.+) has won') { $matches[1].Trim() } else { 'Draw' }
                if (-not $game.Turns) { $game.Turns = $turn }
                $game.Seats = $seats.Clone()
                $games.Add($game)
                $game = New-Game; $turn = 0; $cardOwner = @{}; $nameOwner = @{}
            }
        }
    }
}

# ---------------------------------------------------------------------------
# 2. Aggregate per deck and card.
# ---------------------------------------------------------------------------
$outDir = Join-Path $root ("results\card-report-" + (Get-Date -Format 'yyyy-MM-dd_HHmm'))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Double-faced / split cards are logged under the face that was played (e.g. a
# land back face), so map every face name to the name used in the deck lists.
$faceMapFile = Join-Path $root 'results\face-map.json'
$faceMap = @{}
if (Test-Path $faceMapFile) {
    (Get-Content $faceMapFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $faceMap[$_.Name] = $_.Value }
}
$deckNames = @{}
foreach ($d in Get-ChildItem $deckDir -Filter *.dck) {
    foreach ($l in Get-Content $d.FullName -Encoding UTF8) { if ($l -match '^\d+ (.+)$') { $deckNames[$matches[1].Trim()] = 1 } }
}
$unmapped = @($deckNames.Keys | Where-Object { -not $faceMap.ContainsKey("checked:$_") })
if ($unmapped) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $root 'forge-installer-2.0.15\res\cardsfolder\cardsfolder.zip'))
    try {
        foreach ($e in $zip.Entries) {
            if (-not $e.Name.EndsWith('.txt')) { continue }
            $sr = New-Object System.IO.StreamReader($e.Open()); $txt = $sr.ReadToEnd(); $sr.Close()
            $faces = @([regex]::Matches($txt, '(?m)^Name:(.*)$') | ForEach-Object { $_.Groups[1].Value.Trim() })
            if ($faces.Count -lt 2) { continue }
            $full = $faces -join ' // '
            $listName = @($full) + $faces | Where-Object { $deckNames.ContainsKey($_) } | Select-Object -First 1
            if ($listName) { foreach ($f in $faces + $full) { if ($f -ne $listName) { $faceMap[$f] = $listName } } }
        }
    } finally { $zip.Dispose() }
    foreach ($n in $deckNames.Keys) { $faceMap["checked:$n"] = 1 }
    $faceMap | ConvertTo-Json | Set-Content $faceMapFile -Encoding UTF8
}
foreach ($g in $games) {
    foreach ($deck in @($g.Events.Keys)) {
        $ev = $g.Events[$deck]
        foreach ($face in @($ev.Keys)) {
            if (-not $faceMap.ContainsKey($face) -or $face -like 'checked:*') { continue }
            $main = $faceMap[$face]
            if (-not $ev.ContainsKey($main)) { $ev[$main] = $ev[$face] }
            else { foreach ($k in 'Cast','Activated','Damage','Died','Discarded') { $ev[$main][$k] += $ev[$face][$k] }
                   if (-not $ev[$main].FirstTurn) { $ev[$main].FirstTurn = $ev[$face].FirstTurn } }
            $ev.Remove($face)
        }
    }
}

$decks = $games | ForEach-Object { $_.Seats.Values } | Sort-Object -Unique
$report = New-Object System.Text.StringBuilder
[void]$report.AppendLine("Card report from $($games.Count) logged games ($($RunDirs.Count) run folders)")
[void]$report.AppendLine("Cast% = games the card was cast/played. Expected% = rough chance of drawing it by the end of an average game.")
[void]$report.AppendLine("Win%cast / Win%not = deck win rate in games where the card was / wasn't cast. Small samples are noisy.")
[void]$report.AppendLine('')

foreach ($deck in $decks) {
    $dGames = @($games | Where-Object { $_.Seats.Values -contains $deck })
    if ($dGames.Count -lt $MinGames) { continue }
    $wins = @($dGames | Where-Object { $_.Winner -eq $deck }).Count
    $avgRounds = (($dGames | ForEach-Object { $_.Turns }) | Measure-Object -Average).Average / 4
    # Opening hand + roughly one draw per round, out of a 99-card library.
    $expected = [math]::Min(1.0, (7 + $avgRounds) / 99)

    # Deck list (main + commander) so cards that never appear are reported too.
    $listFile = Join-Path $deckDir "$deck.dck"
    $listed = @{}; $commander = $null
    if (Test-Path $listFile) {
        $sec = ''
        foreach ($l in Get-Content $listFile -Encoding UTF8) {
            if ($l -match '^\[(.+)\]') { $sec = $matches[1].ToLower(); continue }
            if ($l -match '^(\d+) (.+)$' -and $sec -in 'main','commander') {
                $listed[$matches[2].Trim()] = [int]$matches[1]
                if ($sec -eq 'commander') { $commander = $matches[2].Trim() }
            }
        }
    }

    $names = @($listed.Keys) + @($dGames | ForEach-Object { if ($_.Events[$deck]) { $_.Events[$deck].Keys } }) | Sort-Object -Unique
    $rows = foreach ($card in $names) {
        $castGames = 0; $castWins = 0; $act = 0; $dmg = 0; $died = 0; $disc = 0; $firstTurns = @(); $casts = 0
        foreach ($g in $dGames) {
            $e = if ($g.Events[$deck]) { $g.Events[$deck][$card] } else { $null }
            if (-not $e) { continue }
            $act += $e.Activated; $dmg += $e.Damage; $died += $e.Died; $disc += $e.Discarded; $casts += $e.Cast
            if ($e.Cast -gt 0) {
                $castGames++; if ($g.Winner -eq $deck) { $castWins++ }
                if ($e.FirstTurn) { $firstTurns += [math]::Ceiling($e.FirstTurn / 4) }
            }
        }
        $notGames = $dGames.Count - $castGames; $notWins = $wins - $castWins
        $isBasic = $card -in 'Plains','Island','Swamp','Mountain','Forest','Wastes'
        $copies = if ($listed.ContainsKey($card)) { $listed[$card] } else { 1 }
        $castPct = $castGames / $dGames.Count
        $exp = if ($card -eq $commander) { 1 } else { [math]::Min(1.0, $expected * $copies) }
        $flag = ''
        if ($card -eq $commander -and $castPct -lt 0.5) { $flag = 'COMMANDER RARELY CAST' }
        if (-not $isBasic -and $card -ne $commander -and $listed.ContainsKey($card)) {
            if ($castGames -eq 0 -and $disc -eq 0) { $flag = 'NEVER CAST' }
            elseif ($castPct -lt $exp * 0.4) { $flag = 'RARELY CAST (stuck in hand?)' }
            elseif ($castGames -ge 5 -and $notGames -ge 5) {
                $diff = ($castWins / $castGames) - ($notWins / $notGames)
                if ($diff -ge 0.15) { $flag = 'STRONG' } elseif ($diff -le -0.15) { $flag = 'WEAK' }
            }
        }
        [pscustomobject]@{
            Card = $card; InList = $listed.ContainsKey($card); Copies = $copies
            CastGames = $castGames; 'Cast%' = [math]::Round(100 * $castPct); 'Expected%' = [math]::Round(100 * $exp)
            'Win%Cast' = if ($castGames) { [math]::Round(100 * $castWins / $castGames) } else { $null }
            'Win%Not'  = if ($notGames) { [math]::Round(100 * $notWins / $notGames) } else { $null }
            AvgRoundCast = if ($firstTurns) { [math]::Round(($firstTurns | Measure-Object -Average).Average, 1) } else { $null }
            Casts = $casts; Activations = $act; Damage = $dmg; Died = $died; Discarded = $disc; Flag = $flag
        }
    }
    $rows = @($rows | Sort-Object @{ e = { -not $_.InList } }, @{ e = { $_.CastGames }; Descending = $true })
    $safe = $deck -replace '[\\/:*?"<>|]', '_'
    $rows | Export-Csv (Join-Path $outDir "$safe.csv") -NoTypeInformation -Encoding UTF8

    [void]$report.AppendLine(('=' * 78))
    [void]$report.AppendLine(("{0}: {1} games, {2} wins ({3:P0}), avg game {4:N1} rounds" -f $deck, $dGames.Count, $wins, ($wins / $dGames.Count), $avgRounds))
    $listedRows = @($rows | Where-Object InList)
    $top = $listedRows | Where-Object { $_.Card -ne $commander } | Sort-Object Damage -Descending | Select-Object -First 5
    [void]$report.AppendLine("  Top damage dealers: " + (($top | Where-Object { $_.Damage -gt 0 } | ForEach-Object { "$($_.Card) ($($_.Damage))" }) -join ', '))
    foreach ($flagName in 'COMMANDER RARELY CAST', 'STRONG', 'WEAK', 'RARELY CAST (stuck in hand?)', 'NEVER CAST') {
        $hit = @($listedRows | Where-Object Flag -eq $flagName)
        if (-not $hit) { continue }
        [void]$report.AppendLine("  ${flagName}:")
        foreach ($r in $hit) {
            [void]$report.AppendLine(("     {0,-38} cast in {1,3}/{2} games ({3}% vs ~{4}% expected)  win% cast/not: {5}/{6}" -f `
                $r.Card, $r.CastGames, $dGames.Count, $r.'Cast%', $r.'Expected%', $r.'Win%Cast', $r.'Win%Not'))
        }
    }
    [void]$report.AppendLine('')
}

$text = $report.ToString()
Set-Content -Path (Join-Path $outDir 'report.txt') -Value $text -Encoding UTF8
$text
"Per-card CSVs and report.txt: $outDir"
