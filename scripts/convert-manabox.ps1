# Converts ManaBox deck exports (.txt) into Forge Commander decks (.dck)
# and checks every card against Forge's card database.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\convert-manabox.ps1

$ErrorActionPreference = 'Stop'
$root     = Split-Path -Parent $PSScriptRoot
$inDir    = Join-Path $root 'manabox\decks'
$outDir   = Join-Path $root 'decks'
$cardsZip = Join-Path $root 'forge-installer-2.0.15\res\cardsfolder\cardsfolder.zip'

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Load Forge card names (plus AI-unplayable flags) from the card database.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$forgeCards = @{}   # lower-case name -> @{ Name; AiWeak }
$zip = [System.IO.Compression.ZipFile]::OpenRead($cardsZip)
try {
    foreach ($e in $zip.Entries) {
        if (-not $e.Name.EndsWith('.txt')) { continue }
        $sr = New-Object System.IO.StreamReader($e.Open())
        $txt = $sr.ReadToEnd(); $sr.Close()
        if ($txt -notmatch '(?m)^Name:(.*)$') { continue }
        $name = $matches[1].Trim()
        $aiWeak = $txt -match '(?m)^AI:RemoveDeck:All'
        $forgeCards[$name.ToLower()] = @{ Name = $name; AiWeak = $aiWeak }
        # Split / adventure / MDFC cards: also index the "A // B" full name.
        $faces = [regex]::Matches($txt, '(?m)^Name:(.*)$') | ForEach-Object { $_.Groups[1].Value.Trim() }
        if ($faces.Count -gt 1) {
            $full = ($faces -join ' // ')
            $forgeCards[$full.ToLower()] = @{ Name = $full; AiWeak = $aiWeak }
        }
    }
} finally { $zip.Dispose() }

function Resolve-ForgeName([string]$name) {
    $key = $name.ToLower()
    if ($forgeCards.ContainsKey($key)) { return $forgeCards[$key] }
    # Fall back to the front face for double-faced cards.
    $front = ($name -split ' // ')[0].Trim().ToLower()
    if ($forgeCards.ContainsKey($front)) { return $forgeCards[$front] }
    return $null
}

foreach ($file in Get-ChildItem $inDir -Filter *.txt) {
    $section = 'main'
    $commander = @(); $main = @(); $attractions = @()
    $total = 0; $problems = @()

    foreach ($line in Get-Content -LiteralPath $file.FullName -Encoding UTF8) {
        $l = $line.Trim()
        if ($l -eq '') { continue }
        if ($l -match '^//\s*COMMANDER') { $section = 'commander'; continue }
        # Attraction deck: added by hand, since ManaBox doesn't export one.
        if ($l -match '^//\s*ATTRACTIONS') { $section = 'attractions'; continue }
        if ($l -match '^//') { $section = 'main'; continue }
        # ManaBox line: 1 Card Name (SET) 123 *F*
        if ($l -notmatch '^(\d+)\s+(.+?)(\s+\([A-Za-z0-9]+\)\s+\S+)?(\s+\*F\*)?$') { $problems += "UNPARSED: $l"; continue }
        $qty = [int]$matches[1]; $name = $matches[2].Trim()
        if ($section -ne 'attractions') { $total += $qty }
        $card = Resolve-ForgeName $name
        if (-not $card) { $problems += "NOT IN FORGE: $name"; continue }
        if ($card.AiWeak) { $problems += "AI-WEAK (Forge AI may misplay): $($card.Name)" }
        $entry = "$qty $($card.Name)"
        if ($section -eq 'commander') { $commander += $entry; $section = 'main' }
        elseif ($section -eq 'attractions') { $attractions += $entry }
        else { $main += $entry }
    }

    $deckName = [IO.Path]::GetFileNameWithoutExtension($file.Name)
    $deckName = (Get-Culture).TextInfo.ToTitleCase($deckName)
    $out = @('[metadata]', "Name=$deckName", '[commander]') + $commander + @('[main]') + $main
    if ($attractions) { $out += @('[Attractions]') + $attractions }
    $outPath = Join-Path $outDir "$deckName.dck"
    [IO.File]::WriteAllLines($outPath, $out, (New-Object System.Text.UTF8Encoding($false)))

    $attNote = if ($attractions) { " + $($attractions.Count) attractions" } else { '' }
    "{0,-18} {1,3} cards{2} -> decks\{3}.dck" -f $deckName, $total, $attNote, $deckName
    foreach ($p in $problems) { "    $p" }
}
