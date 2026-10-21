<#
.SYNOPSIS
    Rank every card in our decks by how well regarded it is, for the 3D table to mark.

.DESCRIPTION
    Scryfall carries EDHREC's rank for each card - roughly "how many Commander decks play this",
    where 1 is the most played card in the format. It is the closest thing to an opinion that can
    be looked up rather than argued about, so it is what the table uses to decide which cards on
    the battlefield are worth drawing attention to.

    Writes ui3d\data\card-ranks.txt: one "name=rank" a line, lowest rank first. The 3D table
    loads it at startup (Notable.java) and gives a card an ember glow when it is highly ranked,
    when it is simply large, or when it has a pile of keywords.

    The file IS in git - it is small, it changes only when the decks change, and it means the 3D
    table works on a fresh clone without anyone having to fetch anything.

.EXAMPLE
    .\scripts\card-ranks.ps1
    Rank every card in every deck in decks\.

.EXAMPLE
    .\scripts\card-ranks.ps1 -Deck Alec
    Just one deck (added to what is already in the file).
#>
[CmdletBinding()]
param(
    [string[]] $Deck,
    # Cards at or above this rank number are not worth writing down - the file would be every
    # card we own. 2000 keeps the ones a Commander player would recognise.
    [int] $Keep = 2000
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'ui3d\data\card-ranks.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null

# What we already know, so a second run only asks about what it has not seen.
$known = @{}
if (Test-Path $out) {
    foreach ($line in Get-Content $out -Encoding UTF8) {
        if ($line -match '^\s*#') { continue }
        $bits = $line -split '=', 2
        if ($bits.Count -eq 2) { $known[$bits[0]] = [int]$bits[1] }
    }
    Write-Host ("Already ranked: {0}" -f $known.Count)
}

# ---- the cards we play -------------------------------------------------------------------------
$deckDir = Join-Path $root 'decks'
$files = if ($Deck) {
    $Deck | ForEach-Object {
        $f = Join-Path $deckDir ("{0}.dck" -f $_)
        if (-not (Test-Path $f)) { throw "No such deck: $f" }
        Get-Item $f
    }
} else {
    Get-ChildItem $deckDir -Filter *.dck -File
}

$wanted = [ordered]@{}
foreach ($f in $files) {
    foreach ($line in Get-Content $f.FullName -Encoding UTF8) {
        if ($line -match '^\s*\[') { continue }
        if ($line -match '^\s*(\d+)\s+(.+?)\s*$') {
            $name = ($Matches[2] -split '\|')[0].Trim()
            if ($name -and -not $wanted.Contains($name)) { $wanted[$name] = $true }
        }
    }
}
Write-Host ("Decks: {0}, distinct cards: {1}" -f $files.Count, $wanted.Count)

$ask = @($wanted.Keys | Where-Object { -not $known.ContainsKey($_) })
if ($ask.Count -eq 0) {
    Write-Host 'Nothing new to look up.' -ForegroundColor Green
} else {
    Write-Host ("Looking up {0} card(s) on Scryfall..." -f $ask.Count)
}

# ---- Scryfall, politely -------------------------------------------------------------------------
# Same throttle as card-images.ps1, and for the same reason: one delay in front of every call,
# whatever happened to the one before it, and a real wait when told to back off.
$headers = @{ 'User-Agent' = 'MTG3D-table/1.0 (local play group card ranks)'; 'Accept' = '*/*' }
$script:lastCall = (Get-Date).AddSeconds(-10)

function Get-Card([string] $name) {
    for ($try = 1; $try -le 4; $try++) {
        $since = ((Get-Date) - $script:lastCall).TotalMilliseconds
        $wait = if ($since -ge 150) { 0 } else { [int](150 - $since) }
        if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
        $script:lastCall = Get-Date
        try {
            $url = 'https://api.scryfall.com/cards/named?exact=' + [uri]::EscapeDataString($name)
            return (Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
        } catch {
            $code = $null
            try { $code = [int]$_.Exception.Response.StatusCode } catch { }
            if ($code -eq 429) { Write-Host '  rate-limited; waiting 75s' -ForegroundColor Yellow; Start-Sleep -Seconds 75; continue }
            if ($code -eq 404) { return $null }
            if ($try -eq 4) { return $null }
            Start-Sleep -Seconds 3
        }
    }
    return $null
}

$i = 0; $found = 0
foreach ($name in $ask) {
    $i++
    Write-Progress -Activity 'Ranking cards' -Status $name -PercentComplete ($i * 100 / $ask.Count)
    # NOT $card: case-insensitive variables mean that would assign into a parameter if one
    # existed, which is how card-images.ps1 silently returned nothing for every card.
    $info = Get-Card $name
    if ($null -eq $info) { continue }
    if ($null -ne $info.edhrec_rank) {
        $known[$name] = [int]$info.edhrec_rank
        $found++
    }
}
Write-Progress -Activity 'Ranking cards' -Completed

# ---- write it out ---------------------------------------------------------------------------------
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('# EDHREC rank for the cards in decks\, lowest (most played) first.')
$lines.Add('# Written by scripts\card-ranks.ps1; read by ui3d Notable.java. Do not hand-edit.')
$kept = 0
foreach ($e in ($known.GetEnumerator() | Sort-Object Value)) {
    if ($e.Value -le 0 -or $e.Value -gt $Keep) { continue }
    $lines.Add(('{0}={1}' -f $e.Key, $e.Value))
    $kept++
}
Set-Content -Path $out -Value $lines -Encoding UTF8
Write-Host ("Looked up {0}, wrote {1} ranked card(s) to {2}" -f $found, $kept, $out) -ForegroundColor Green

# A look at what the table will be lighting up.
Write-Host ''
Write-Host 'The best-regarded cards we play:' -ForegroundColor Cyan
$shown = 0
foreach ($e in ($known.GetEnumerator() | Sort-Object Value)) {
    if ($e.Value -le 0) { continue }
    Write-Host ('  {0,6}  {1}' -f $e.Value, $e.Key)
    if (++$shown -ge 25) { break }
}
