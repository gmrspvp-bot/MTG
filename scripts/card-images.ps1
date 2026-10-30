<#
.SYNOPSIS
    Which cards have a picture on the 3D table, and fetch the ones that do not.

.DESCRIPTION
    The 3D table draws card pictures out of the cache Forge fills, and Forge only fills it with
    cards it has had a reason to download. On this machine that was 546 pictures against several
    thousand cards in the decks we play, which is why so much of the board came up as a drawn
    stand-in rather than the real card.

    This checks a deck, a list of decks, or single cards by name, against the cache the same way
    CardArt does - the exact name, then the front face of a split or double-faced card, then a
    loose match that ignores punctuation - and says what is missing. With -Download it fetches the
    missing ones from Scryfall.

    Pictures land in forge-data\cache\pics\cards\MTG3D\, which is Forge's cache folder but not a
    real set code, so Forge's own window carries on using its own files and the 3D table picks
    these up on its next rescan (within a few seconds, while a game is running).

    The cache is NOT in git - it is several hundred megabytes - so each machine runs this once.

.EXAMPLE
    .\scripts\card-images.ps1
    Check every commander deck and list what has no picture.

.EXAMPLE
    .\scripts\card-images.ps1 -Deck "Alec - Athreos", "Claude - Neyali" -Download
    Fetch the missing pictures for two decks.

.EXAMPLE
    .\scripts\card-images.ps1 -Card "Mr. House, President and CEO" -Download
    Check (and fetch) one card by name.
#>
[CmdletBinding()]
param(
    # Deck names (no .dck), as many as you like. Default: every commander deck.
    [string[]] $Deck,
    # Card names, checked on their own without reading any deck.
    [string[]] $Card,
    # Fetch the missing pictures from Scryfall instead of only listing them.
    [switch] $Download,
    # Say something about every card, not only the misses.
    [switch] $Verbose_
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pics = Join-Path $root 'forge-data\cache\pics\cards'
$dest = Join-Path $pics 'MTG3D'

# ---- the cache, keyed the way CardArt keys it ------------------------------------------------
function Get-BaseName([string] $fileName) {
    $n = $fileName
    if ($n -notmatch '\.(jpg|jpeg|png)$') { return $null }
    $n = $n.Substring(0, $n.LastIndexOf('.'))
    foreach ($m in @('.fullborder', '.full', '.border')) {
        if ($n.ToLower().EndsWith($m)) { $n = $n.Substring(0, $n.Length - $m.Length); break }
    }
    return $n.Trim()
}

function Get-LooseKey([string] $name) {
    if ($null -eq $name) { return '' }
    return ($name.ToLower() -replace 'æ', 'ae' -replace '[^a-z0-9]', '')
}

$exact = @{}
$loose = @{}
function Add-Picture([string] $name, [string] $path) {
    if ([string]::IsNullOrWhiteSpace($name)) { return }
    $k = $name.ToLower().Trim()
    if (-not $exact.ContainsKey($k)) { $exact[$k] = $path }
    $l = Get-LooseKey $k
    if ($l -and -not $loose.ContainsKey($l)) { $loose[$l] = $path }
}

if (Test-Path $pics) {
    foreach ($f in Get-ChildItem $pics -Recurse -File) {
        $base = Get-BaseName $f.Name
        if (-not $base) { continue }
        Add-Picture $base $f.FullName
        $plain = $base -replace '\d+$', ''
        if ($plain -and $plain -ne $base) { Add-Picture $plain $f.FullName }
        $slash = $base.IndexOf('//')
        if ($slash -gt 0) {
            Add-Picture $base.Substring(0, $slash).Trim() $f.FullName
            Add-Picture $base.Substring($slash + 2).Trim() $f.FullName
        }
    }
}
Write-Host ("Cache: {0} pictures, {1} names" -f (Get-ChildItem $pics -Recurse -File -ErrorAction SilentlyContinue).Count, $exact.Count)

function Find-Picture([string] $name) {
    $k = $name.ToLower().Trim()
    if ($exact.ContainsKey($k)) { return $exact[$k] }
    $slash = $k.IndexOf('//')
    if ($slash -gt 0) {
        $front = $k.Substring(0, $slash).Trim()
        if ($exact.ContainsKey($front)) { return $exact[$front] }
    }
    $l = Get-LooseKey $k
    if ($l -and $loose.ContainsKey($l)) { return $loose[$l] }
    return $null
}

# ---- what to check ---------------------------------------------------------------------------
$wanted = [ordered]@{}        # card name -> the decks it came from
function Want([string] $name, [string] $from) {
    $name = $name.Trim()
    if (-not $name) { return }
    if (-not $wanted.Contains($name)) { $wanted[$name] = New-Object System.Collections.Generic.List[string] }
    if ($from -and -not $wanted[$name].Contains($from)) { $wanted[$name].Add($from) }
}

if ($Card) {
    foreach ($c in $Card) { Want $c '' }
} else {
    $deckDir = Join-Path $root 'forge-data\decks\commander'
    $files = if ($Deck) {
        $Deck | ForEach-Object {
            $f = Join-Path $deckDir ("{0}.dck" -f $_)
            if (-not (Test-Path $f)) { throw "No such deck: $f" }
            Get-Item $f
        }
    } else {
        Get-ChildItem $deckDir -Filter *.dck -File
    }
    foreach ($f in $files) {
        $name = $f.BaseName
        # UTF-8 explicitly: read as the system codepage, "Bartolome del Presidio" came back with
        # its accented letter mangled and no amount of loose matching would find the card.
        foreach ($line in Get-Content $f.FullName -Encoding UTF8) {
            # "1 Arcane Signet" or "1 Arcane Signet|CMR"; section headers start with [
            if ($line -match '^\s*(\d+)\s+(.+?)\s*$' -and $line -notmatch '^\s*\[') {
                Want (($Matches[2] -split '\|')[0]) $name
            }
        }
    }
    Write-Host ("Decks:  {0}" -f ($files.Count))
}

# ---- the check ---------------------------------------------------------------------------------
$missing = New-Object System.Collections.Generic.List[string]
foreach ($name in $wanted.Keys) {
    if (Find-Picture $name) {
        if ($Verbose_) { Write-Host ("  ok      {0}" -f $name) -ForegroundColor DarkGray }
    } else {
        $missing.Add($name)
    }
}
Write-Host ("Cards:  {0} wanted, {1} with a picture, {2} without" -f $wanted.Count, ($wanted.Count - $missing.Count), $missing.Count)

if ($missing.Count -eq 0) { Write-Host 'Nothing missing.' -ForegroundColor Green; return }

if (-not $Download) {
    foreach ($m in $missing) {
        $from = $wanted[$m] -join ', '
        if ($from) { Write-Host ("  missing {0}   ({1})" -f $m, $from) }
        else { Write-Host ("  missing {0}" -f $m) }
    }
    Write-Host ''
    Write-Host 'Run again with -Download to fetch these from Scryfall.' -ForegroundColor Yellow
    return
}

# ---- fetching -----------------------------------------------------------------------------------
# Scryfall asks for a real user agent and 50-100ms between calls, and says plainly that ignoring
# a 429 earns a network block. Every call goes through Get-Url, which keeps 150ms between calls
# whatever happened to the last one - the first version only slept on the success path, so 533
# failures went out back to back and the whole run was rate-limited (2026-10-21) - and backs off
# for a minute when told to.
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$headers = @{ 'User-Agent' = 'MTG3D-table/1.0 (local play group image cache)'; 'Accept' = '*/*' }
$got = 0; $failed = New-Object System.Collections.Generic.List[string]
$script:lastCall = (Get-Date).AddSeconds(-10)

function Get-Url([string] $url, [string] $outFile) {
    for ($try = 1; $try -le 4; $try++) {
        $since = ((Get-Date) - $script:lastCall).TotalMilliseconds
        $wait = if ($since -ge 150) { 0 } else { [int](150 - $since) }
        if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
        $script:lastCall = Get-Date
        try {
            if ($outFile) {
                Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 60 -OutFile $outFile
                return $true
            }
            return (Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 30).Content
        } catch {
            $code = $null
            try { $code = [int]$_.Exception.Response.StatusCode } catch { }
            if ($code -eq 429) {
                Write-Host ("  rate-limited; waiting 75s (attempt {0})" -f $try) -ForegroundColor Yellow
                Start-Sleep -Seconds 75
                continue
            }
            if ($code -eq 404) { throw "not found on Scryfall" }
            if ($try -eq 4) { throw }
            Start-Sleep -Seconds 3
        }
    }
    throw "gave up after four attempts"
}

function Get-SafeFileName([string] $name) {
    $out = $name
    foreach ($ch in [System.IO.Path]::GetInvalidFileNameChars()) { $out = $out.Replace([string]$ch, '') }
    return $out.Trim().TrimEnd('.')
}

$i = 0
foreach ($name in $missing) {
    $i++
    Write-Progress -Activity 'Fetching card pictures' -Status $name -PercentComplete ($i * 100 / $missing.Count)
    try {
        $url = 'https://api.scryfall.com/cards/named?exact=' + [uri]::EscapeDataString($name)
        # NOT $card: PowerShell does not care about case, so that assigns into the [string[]]
        # $Card parameter, which quietly turns the answer into an array of strings - and every
        # single card then came back "no picture on Scryfall" (2026-10-21).
        $info = (Get-Url $url $null) | ConvertFrom-Json
        $img = $null
        if ($info.image_uris -and $info.image_uris.normal) { $img = $info.image_uris.normal }
        elseif ($info.card_faces -and $info.card_faces[0].image_uris) { $img = $info.card_faces[0].image_uris.normal }
        if (-not $img) { $failed.Add("$name (the card has no picture on Scryfall)"); continue }
        $file = Join-Path $dest ((Get-SafeFileName $name) + '.fullborder.jpg')
        Get-Url $img $file | Out-Null
        $got++
    } catch {
        $failed.Add("$name ($($_.Exception.Message))")
    }
}
Write-Progress -Activity 'Fetching card pictures' -Completed

Write-Host ''
Write-Host ("Fetched {0} picture(s) into {1}" -f $got, $dest) -ForegroundColor Green
if ($failed.Count -gt 0) {
    Write-Host ("{0} could not be fetched:" -f $failed.Count) -ForegroundColor Yellow
    foreach ($f in $failed) { Write-Host ("  {0}" -f $f) }
}
