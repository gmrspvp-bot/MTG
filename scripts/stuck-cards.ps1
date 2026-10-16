# Cards the AI keeps in hand while it has the mana to cast them. Uses the BOARD turn
# snapshots (with hand contents, runs from 2026-10-10): right after each player's own
# turn, any non-land card in hand whose mana value is no more than the mana that player
# left unspent counts as "held with mana". Colours are ignored, so treat it as a lead,
# not proof; instants (counterspells, tricks) may be held on purpose. Plays no games.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\stuck-cards.ps1 -RunDirs results\<run>[,...] [-Top 30]

param([Parameter(Mandatory)] [string[]] $RunDirs, [int] $Top = 30)

$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
# Unspent mana = untapped lands (Forge's open-mana estimate also counts Treasures, which
# the AI rightly saves, and mana creatures). Lands held for a counterspell still count.
$reTurn = '^Information: BOARD turn \d+ \((.+?)\) \| (.+?) \| life .*\| lands \d+ \((\d+) untapped\) \|.*\| hand cards: ?(.*)$'
$held = @{}; $seen = @{}; $turns = @{}
foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $prevActive = $null; $curActive = $null
        foreach ($line in [IO.File]::ReadLines($f.FullName)) {
            if ($line.StartsWith('Game Result')) { $prevActive = $null; $curActive = $null; continue }
            if ($line -notmatch $reTurn) { continue }
            $active = $matches[1]; $p = $matches[2]; $open = [int]$matches[3]; $hand = $matches[4]
            if ($active -ne $curActive) { $prevActive = $curActive; $curActive = $active }
            if ($p -ne $prevActive -or $open -lt 0) { continue }   # only the player whose turn just ended
            $deck = $p -replace '^Ai\(\d+\)-', ''
            $turns[$deck] = 1 + [int]$turns[$deck]
            foreach ($c in ($hand -split '; ')) {
                if ($c -notmatch '^(.+)\((land|\d+)\)$' -or $matches[2] -eq 'land') { continue }
                $name = $matches[1]; $mv = [int]$matches[2]
                if ($mv -eq 0) { continue }   # X-cost cards show as 0 in hand; can't judge them
                $k = "$deck|$name"
                $seen[$k] = 1 + [int]$seen[$k]
                if ($mv -le $open) { $held[$k] = 1 + [int]$held[$k] }
            }
        }
    }
}
"Cards left in hand at the end of their owner's turn although enough mana was unspent (colours ignored)."
"Held = times that happened; In hand = end-of-turn checks the card was in hand at all."
""
$held.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First $Top | ForEach-Object {
    $deck, $name = $_.Key -split '\|', 2
    [pscustomobject]@{ Deck = $deck; Card = $name; Held = $_.Value; 'In hand' = $seen[$_.Key]; 'Held %' = '{0:0}%' -f (100.0 * $_.Value / $seen[$_.Key]) }
} | Format-Table -AutoSize | Out-String -Width 200
"End-of-turn checks per deck: " + (($turns.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name) $($_.Value)" }) -join ', ')
