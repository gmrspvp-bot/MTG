param([string[]]$RunDirs)
# Looks for questionable Captain America v4 plays in sim logs. Reads logs only.
$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$P = 'Captain America v4'
$removal = 'Path to Exile|Abrade|Galvanic Blast|Stonesplitter Bolt|Wear|Tear'
$counters = 'Mana Drain|Spell Pierce|Syncopate'
$equipment = @{}   # names of his equipment, learned from casts
$boards = @{}; $games = 0; $turn = 0; $phaseCombat = $false
$own = New-Object Collections.ArrayList; $remT = @{}; $ctrT = @{}; $throwT = @{}; $clamp = @{}; $offCap = @{}; $steel = @{}
$capAttacked = $false; $capDiedAtk = New-Object Collections.ArrayList; $capDeaths = 0; $flawless = 0; $flawlessNoAtk = 0
function ParseCreatures($line) { if ($line -match 'creatures \d+[^:]*: (.*?)( \| hand cards|$)') { @($matches[1] -split '; ' | ForEach-Object { ($_ -replace ' \d+/\d+\*?( x\d+)?$', '').Trim() }) } else { @() } }
foreach ($dir in $RunDirs) {
  foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
    $where = "$(Split-Path $dir -Leaf)\$($f.Name)"
    foreach ($line in [IO.File]::ReadLines($f.FullName, [Text.Encoding]::UTF8)) {
      if ($line.StartsWith('Game Result')) { $games++; $boards = @{}; $capAttacked = $false; continue }
      if ($line -match '^Information: BOARD turn (\d+) \(.+?\) \| (Ai\(\d\)-[^|]+?) \| life (-?\d+)') {
        $turn = [int]$matches[1]; $boards[$matches[2]] = ParseCreatures $line; $capAttacked = $false; continue }
      $mine = @(); $theirs = @()
      if ($line.StartsWith('Add To Stack') -or $line.StartsWith('EQUIPAI')) {
        foreach ($k in $boards.Keys) { if ($k.EndsWith($P)) { $mine += $boards[$k] } else { $theirs += $boards[$k] } } }
      if ($line -match "^Add To Stack: Ai\(\d\)-$P cast (.+?)( targeting \[(.+?)( \(\d+\)| - .*?)?\])?$") {
        $card = $matches[1].Trim(); $tgt = if ($matches[3]) { $matches[3].Trim() } else { '' }
        if ($card -match "^($removal)$" -and $tgt) {
          $remT[$tgt] = 1 + [int]$remT[$tgt]
          if (($mine -contains $tgt) -and -not ($theirs -contains $tgt)) { [void]$own.Add("$where turn $turn : $card -> own $tgt") } }
        if ($card -match "^($counters)$") { $ctrT[$tgt] = 1 + [int]$ctrT[$tgt] }
        if ($card -eq 'Flawless Maneuver') { $flawless++ }
        continue }
      if ($line -match "^Add To Stack: Ai\(\d\)-$P activated Captain America, First Avenger targeting \[(.+?)( \(\d+\))?\]") {
        $t = $matches[1]; $k = if ($t -match '^Ai\(\d\)-') { 'a player' } else { "creature: $t" }; $throwT[$k] = 1 + [int]$throwT[$k]; continue }
      if ($line -match "^Add To Stack: Ai\(\d\)-$P activated (.+?) targeting \[(.+?) \(\d+\)\]") {
        $eq = $matches[1].Trim(); $tgt = $matches[2].Trim()
        if ($eq -eq 'Skullclamp') { $clamp[$tgt] = 1 + [int]$clamp[$tgt] }
        elseif ($tgt -ne 'Captain America, First Avenger' -and ($mine -contains 'Captain America, First Avenger') -and $eq -notmatch 'Captain America|Loran|Zirda|Audacious|Inventors|Codsworth') { $offCap["$eq -> $tgt"] = 1 + [int]$offCap["$eq -> $tgt"] }
        continue }
      if ($line -match '^Resolve Stack: Sculpting Steel.*?copy of (.+?)[\.\(\]]') { $steel[$matches[1].Trim()] = 1 + [int]$steel[$matches[1].Trim()]; continue }
      if ($line -match "^Combat: Ai\(\d\)-$P assigned .*Captain America, First Avenger.* to attack") { $capAttacked = $true; continue }
      if ($line -match '^Zone Change: Captain America, First Avenger \(\d+\) was put into (Graveyard|Command|Exile)') {
        $capDeaths++
        if ($capAttacked) { [void]$capDiedAtk.Add("$where turn $turn") }
        continue }
    }
  }
}
"Games: $games"
"`n== Removal aimed at his OWN creatures ($($own.Count))"; $own | Select-Object -First 15
"`n== Removal targets (top 20)"; $remT.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 20 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Counterspell targets (top 25)"; $ctrT.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 25 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Throw targets (top 20)"; $throwT.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 20 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Skullclamp equipped onto"; $clamp.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Equipment put on something else while Captain America was on the battlefield"; $offCap.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 25 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Sculpting Steel copied"; $steel.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Flawless Maneuver casts: $flawless"
"`n== Captain America left the battlefield $capDeaths times; in the same turn as he attacked: $($capDiedAtk.Count)"; $capDiedAtk | Select-Object -First 10
