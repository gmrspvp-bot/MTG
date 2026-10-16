param([string[]]$Files)
# Turn-by-turn summary of the HUMAN player's decisions in recorded desktop games
# (scratch\play-logs\game_*.log from PlayHook). Reads logs only.
$Files = @($Files | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($f in $Files) {
    $L = [IO.File]::ReadAllLines($f, [Text.Encoding]::UTF8)
    $human = ($L | Where-Object { $_ -match '^Player: (.+?) \(HUMAN\)' } | Select-Object -First 1) -replace '^Player: (.+?) \(HUMAN\).*', '$1'
    $decks = @{}; foreach ($p in ($L | Where-Object { $_ -match '^Player: ' })) { if ($p -match '^Player: (.+?) \((AI|HUMAN)\) deck=(.+)$') { $decks[$matches[1]] = $matches[3] } }
    "################ $(Split-Path $f -Leaf)"
    "players: " + (($decks.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', ')
    ($L | Where-Object { $_ -match '^Game Outcome: (Turn \d+|.*has (won|lost).*)$|^Game Result' }) | ForEach-Object { "  $_" }
    $H = [regex]::Escape($human)
    ($L | Where-Object { $_ -match "^Mulligan: $H" }) | ForEach-Object { "  $_" }
    $turn = 0; $active = ''; $own = 0
    foreach ($l in $L) {
        if ($l -match '^Turn: Turn (\d+) \((.+?)\)') { $turn = [int]$matches[1]; $active = $matches[2]; if ($active -eq $human) { $own++ } }
        if ($active -eq $human) {
            if ($l -match "^Information: BOARD turn \d+ \($H\) \| $H \| life (-?\d+) \| hand (\d+) \| lands (\d+) \((\d+) untapped\).*?creatures \d+[^:]*: (.*?) \| hand cards: (.*)$") {
                "  [my turn $own / T$turn] life $($matches[1]) lands $($matches[3]) | board: $($matches[5]) | hand: $($matches[6])" }
            if ($l -match "^Land: $H played (.+?) \(") { "      land $($matches[1])" }
            if ($l -match "^Add To Stack: $H (cast|activated) (.+?)( targeting \[(.+)\])?$") { "      $($matches[1]) $($matches[2])" + $(if ($matches[4]) { " -> " + ($matches[4] -replace ' \(\d+\)', '') } else { '' }) }
            if ($l -match "^Combat: $H assigned (.+) to attack (.+)\.$") { "      ATTACK " + ($matches[1] -replace ' \(\d+\)', '') + " -> $($matches[2])" }
            if ($l -match "^Damage: (.+?) \(\d+\) deals (\d+) combat damage to (.+?)\.?$" -and $l -notmatch "to $H") { "        hit: $($matches[1]) -> $($matches[3]) for $($matches[2])" }
        } else {
            if ($l -match "^Combat: (.+?) assigned (.+) to attack $H\.$") { "      (T$turn $($matches[1]) attacks me with " + ($matches[2] -replace ' \(\d+\)', '') + ")" }
            if ($l -match "^(Combat: )?$H assigned (.+) to block (.+)\.$") { "      (T$turn I BLOCK: " + ($matches[2] -replace ' \(\d+\)', '') + " blocks " + ($matches[3] -replace ' \(\d+\)', '') + ")" }
            if ($l -match "^Add To Stack: $H (cast|activated) (.+?)( targeting \[(.+)\])?$") { "      (T$turn on $active's turn: $($matches[1]) $($matches[2])" + $(if ($matches[4]) { " -> " + ($matches[4] -replace ' \(\d+\)', '') } else { '' }) + ")" }
        }
    }
}
