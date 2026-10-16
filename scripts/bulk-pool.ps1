# Builds a table of the bulk collection (manabox\collection-all.csv) joined with Forge
# card data: colour identity, mana value, types, AI-unfriendly flag, a role guess
# (removal / ramp / draw / wipe / protection / creature) and the export price.
# Writes results\bulk-pool.csv. Plays no games.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\bulk-pool.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem

$col = Import-Csv (Join-Path $root 'manabox\collection-all.csv')
# Prices live only in the raw ManaBox exports (collection-all.csv drops them).
$price = @{}
foreach ($f in Get-ChildItem (Join-Path $root 'manabox\bulk-binder') -Filter *.csv) {
    foreach ($r in Import-Csv $f.FullName) {
        $n = ($r.Name -split ' // ')[0].Trim(); $p = 0.0
        if ([double]::TryParse($r.'Purchase price', [System.Globalization.NumberStyles]::Any, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$p) -and $p -gt [double]$price[$n]) { $price[$n] = $p }
    }
}
$owned = @{}
foreach ($c in $col) {
    $n = ($c.Name -split ' // ')[0].Trim()
    if (-not $owned.ContainsKey($n)) { $owned[$n] = [pscustomobject]@{ Qty = 0; Price = 0.0; Rarity = $c.Rarity; Binder = $c.'Binder Name' } }
    $owned[$n].Qty += [int]$c.Quantity
    if ($price.ContainsKey($n)) { $owned[$n].Price = [double]$price[$n] }
}

$rows = New-Object System.Collections.Generic.List[object]
$zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $root 'forge-installer-2.0.15\res\cardsfolder\cardsfolder.zip'))
try {
    foreach ($e in $zip.Entries) {
        if (-not $e.Name.EndsWith('.txt')) { continue }
        $sr = New-Object System.IO.StreamReader($e.Open()); $txt = $sr.ReadToEnd(); $sr.Close()
        if ($txt -notmatch '(?m)^Name:(.*)$') { continue }
        $name = $matches[1].Trim()
        if (-not $owned.ContainsKey($name)) { continue }
        $front = ($txt -split '(?m)^ALTERNATE')[0]
        $cost = if ($front -match '(?m)^ManaCost:(.*)$') { $matches[1].Trim() } else { '' }
        $types = if ($front -match '(?m)^Types:(.*)$') { $matches[1].Trim() } else { '' }
        $pt = if ($front -match '(?m)^PT:(.*)$') { $matches[1].Trim() } else { '' }
        $oracle = ((($front -split "`n") | Where-Object { $_ -match '^Oracle:' }) -join ' ') -replace '^Oracle:', ''
        # Colour identity: coloured symbols in cost and rules text of every face.
        $ident = @{}
        foreach ($m in [regex]::Matches($txt, '(?m)^ManaCost:(.*)$')) { foreach ($ch in 'W','U','B','R','G') { if ($m.Groups[1].Value -match "\b$ch\b|$ch/|/$ch") { $ident[$ch] = 1 } } }
        foreach ($m in [regex]::Matches($txt, '\{([WUBRG])(?:/[WUBRGP])?\}')) { $ident[$m.Groups[1].Value] = 1 }
        foreach ($m in [regex]::Matches($txt, '\{[WUBRG2]/([WUBRG])\}')) { $ident[$m.Groups[1].Value] = 1 }
        $identity = (('W','U','B','R','G') | Where-Object { $ident.ContainsKey($_) }) -join ''
        $cmc = 0
        foreach ($p in ($cost -split ' ')) { if ($p -match '^\d+$') { $cmc += [int]$p } elseif ($p -match '^[WUBRGC]' -or $p -match '/') { $cmc += 1 } }
        $o = $oracle.ToLower()
        $role = @()
        if ($types -match '\bLand\b') { $role += 'land' }
        if ($o -match 'destroy target|exile target (creature|artifact|enchantment|nonland|permanent)|deals \d+ damage to target creature|deals x damage to target|target creature gets -\d|fights? (another )?target|return target (creature|nonland permanent) to its owner') { $role += 'removal' }
        if ($o -match 'destroy all|exile all|deals \d+ damage to each creature|all creatures get -') { $role += 'wipe' }
        if ($o -match 'add \{|add one mana|search your library for (a|up to \w+) basic land|treasure token') { $role += 'ramp' }
        if ($o -match 'draw (a|two|three|\w+) cards?') { $role += 'draw' }
        if ($o -match 'hexproof|indestructible|shroud|protection from') { $role += 'protection' }
        if ($types -match 'Creature') { $role += 'creature' }
        $rows.Add([pscustomobject]@{
            Name = $name; Qty = $owned[$name].Qty; Binder = $owned[$name].Binder; Rarity = $owned[$name].Rarity; Price = $owned[$name].Price
            Identity = $identity; CMC = $cmc; Types = $types; PT = $pt
            Legendary = ($types -match 'Legendary' -and $types -match 'Creature')
            AIWeak = [bool]($txt -match '(?m)^AI:RemoveDeck:All')
            Roles = ($role -join ' '); Text = ($oracle -replace '\\n', ' | ')
        })
    }
} finally { $zip.Dispose() }

$out = Join-Path $root 'results\bulk-pool.csv'
$rows | Export-Csv $out -NoTypeInformation -Encoding UTF8
"Bulk cards matched to Forge: $($rows.Count) (of $($owned.Count) unique names). Saved $out"
