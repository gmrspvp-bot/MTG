param([string[]]$RunDirs, [string]$Deck = 'Captain America v4')
# Function audit: for every card in the deck, what its script can do (cast / activated
# abilities / triggers / land) versus what the logs show it did. Flags cards whose
# abilities never happen. Run from anywhere; reads Forge's card scripts + our overrides.
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
$deckFile = Join-Path $root "decks\$Deck.dck"
$names = @{}
$sec = ''
foreach ($l in [IO.File]::ReadAllLines($deckFile, [Text.Encoding]::UTF8)) {
    if ($l -match '^\[(.+)\]') { $sec = $matches[1]; continue }
    if ($sec -in 'main', 'commander' -and $l -match '^(\d+) (.+)$') { $names[$matches[2].Trim()] = [int]$matches[1] }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$scripts = @{}
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $root 'forge-installer-2.0.15\res\cardsfolder\cardsfolder.zip'))
foreach ($e in $zip.Entries) {
    if (-not $e.Name.EndsWith('.txt')) { continue }
    $sr = New-Object IO.StreamReader($e.Open(), [Text.Encoding]::UTF8); $t = $sr.ReadToEnd(); $sr.Close()
    if ($t -match '(?m)^Name:(.*)$' -and $names.ContainsKey($matches[1].Trim())) { $scripts[$matches[1].Trim()] = $t }
}
$zip.Dispose()
foreach ($f in Get-ChildItem (Join-Path $root 'forge-data\custom\cards')) {
    $t = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
    if ($t -match '(?m)^Name:(.*)$' -and $names.ContainsKey($matches[1].Trim())) { $scripts[$matches[1].Trim()] = $t }
}

$P = [regex]::Escape($Deck)
$cast = @{}; $castGames = @{}; $act = @{}; $trig = @{}; $land = @{}; $games = 0
foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $seen = @{}
        foreach ($line in [IO.File]::ReadLines($f.FullName, [Text.Encoding]::UTF8)) {
            if ($line.StartsWith('Game Result')) { $games++; foreach ($k in $seen.Keys) { $castGames[$k] = 1 + [int]$castGames[$k] }; $seen = @{}; continue }
            if ($line -match "^Add To Stack: Ai\(\d\)-$P cast (.+?)( targeting|$)") { $n = $matches[1].Trim(); $cast[$n] = 1 + [int]$cast[$n]; $seen[$n] = 1; continue }
            if ($line -match "^Add To Stack: Ai\(\d\)-$P activated (.+?)( targeting|$)") { $n = $matches[1].Trim(); $act[$n] = 1 + [int]$act[$n]; continue }
            if ($line -match "^Add To Stack: Ai\(\d\)-$P triggered (.+?)( targeting|$)") { $n = $matches[1].Trim(); $trig[$n] = 1 + [int]$trig[$n]; continue }
            if ($line -match "^Land: Ai\(\d\)-$P played (.+?) \(\d+\)") { $n = $matches[1].Trim(); $land[$n] = 1 + [int]$land[$n]; $seen[$n] = 1; continue }
        }
    }
}
"Games: $games   Deck: $Deck"
$rows = foreach ($n in ($names.Keys | Sort-Object)) {
    $t = $scripts[$n]
    if (-not $t) { [pscustomobject]@{ Card = $n; Type = '?'; Cast = 0; InGames = 0; ActAbilities = 0; Activated = 0; Triggers = 0; Triggered = 0; Flag = 'NO SCRIPT FOUND' }; continue }
    $front = ($t -split '(?m)^ALTERNATE')[0]
    $types = if ($front -match '(?m)^Types:(.*)$') { $matches[1].Trim() } else { '' }
    $isLand = $types -match '\bLand\b'
    # Non-mana activated abilities (mana abilities don't use the stack, so they never log).
    $abs = @([regex]::Matches($front, '(?m)^A:AB\$ (\w+).*$') | Where-Object { $_.Groups[1].Value -ne 'Mana' })
    $hasEquip = $front -match '(?m)^K:Equip'
    $trs = @([regex]::Matches($front, '(?m)^T:Mode\$')).Count
    $c = [int]$cast[$n] + [int]$land[$n]
    $a = [int]$act[$n]; $tr = [int]$trig[$n]
    $flag = @()
    if ($n -ne 'Captain America, First Avenger' -and -not $isLand -and $c -eq 0) { $flag += 'NEVER CAST' }
    if ($isLand -and [int]$land[$n] -eq 0 -and $types -notmatch 'Basic') { $flag += 'never played' }
    if ($c -gt 0 -and ($abs.Count -gt 0 -or $hasEquip) -and $a -eq 0) { $flag += 'abilities never activated' }
    if ($c -gt 0 -and $trs -gt 0 -and $tr -eq 0) { $flag += 'never triggered' }
    [pscustomobject]@{
        Card = $n; Type = ($types -replace 'Legendary ', '' -split ' ')[0]
        Cast = $c; InGames = [int]$castGames[$n]
        ActAbilities = $abs.Count + [int]$hasEquip; Activated = $a
        Triggers = $trs; Triggered = $tr
        Flag = ($flag -join '; ')
    }
}
$rows | Sort-Object @{ e = { $_.Flag -eq '' } }, Card | Format-Table -AutoSize | Out-String -Width 220
