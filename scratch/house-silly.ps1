param([string[]]$RunDirs)
# Looks for questionable Mr House v2 plays in sim logs. Reads logs only.
$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$hostile = 'Terminate|Anguished Unmaking|Attempted Murder|Wear|Tear|Wear // Tear|Delete|Path to Exile|Grab the Prize'
$boards = @{}; $life = 40; $mine = @(); $theirs = @(); $games = 0; $turn = 0; $lastZone = @()
$own = New-Object Collections.ArrayList; $exs = New-Object Collections.ArrayList; $lowPay = New-Object Collections.ArrayList
$bomb = @{}; $disp = @{}; $clamp = @{}; $houseAtk = 0; $houseAtkGames = 0; $atkThisGame = $false
$reanim = New-Object Collections.ArrayList; $selfKill = New-Object Collections.ArrayList; $lastSelfLoss = $null
function ParseCreatures($line) { if ($line -match 'creatures \d+[^:]*: (.*?)( \| hand cards|$)') { @($matches[1] -split '; ' | ForEach-Object { ($_ -replace ' \d+/\d+\*?( x\d+)?$', '').Trim() }) } else { @() } }
foreach ($dir in $RunDirs) {
  foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
    $where = "$(Split-Path $dir -Leaf)\$($f.Name)"
    foreach ($line in [IO.File]::ReadLines($f.FullName, [Text.Encoding]::UTF8)) {
      if ($line.StartsWith('Game Result')) { $games++; if ($atkThisGame) { $houseAtkGames++ }; $atkThisGame = $false; $life = 40; $mine = @(); $theirs = @(); $lastSelfLoss = $null; $boards = @{}; continue }
      if ($line -match '^Game Outcome: (Ai\(\d\)-Mr House v2) has lost because life total reached 0') {
        if ($lastSelfLoss) { [void]$selfKill.Add("$where turn $turn : last life loss before death was self-inflicted: $lastSelfLoss") }
        continue }
      if ($line -match '^Information: BOARD turn (\d+) \(.+?\) \| (Ai\(\d\)-[^|]+?) \| life (-?\d+)') {
        $turn = [int]$matches[1]; $who = $matches[2]; $l = [int]$matches[3]
        $boards[$who] = ParseCreatures $line
        if ($who.EndsWith('Mr House v2')) { $life = $l }
        $mine = @(); $theirs = @()
        foreach ($k in $boards.Keys) { if ($k.EndsWith('Mr House v2')) { $mine += $boards[$k] } else { $theirs += $boards[$k] } }
        continue }
      if ($line -match '^Zone Change: (.+?) \(\d+\) was put into Graveyard from Battlefield') { $lastZone = @(@($lastZone + $matches[1]) | Select-Object -Last 3); continue }
      if ($line -match '^Add To Stack: Ai\(\d\)-Mr House v2 (cast|activated) (.+?) targeting \[(.+?) \(\d+\)') {
        $card = $matches[2].Trim(); $tgt = $matches[3].Trim()
        if ($card -match "^($hostile)$" -and ($mine -contains $tgt) -and -not ($theirs -contains $tgt)) { [void]$own.Add("$where turn $turn : $card -> own $tgt") }
        if ($card -eq 'Skullclamp') { $clamp[$tgt] = 1 + [int]$clamp[$tgt] }
        if ($card -eq 'Reanimate') { [void]$reanim.Add("$tgt (life $life)") }
      }
      if ($line -match '^Add To Stack: Ai\(\d\)-Mr House v2 activated Goblin Bombardment') { $n = if ($lastZone.Count) { $lastZone[-1] } else { '?' }; $bomb[$n] = 1 + [int]$bomb[$n] }
      if ($line -match '^Add To Stack: Ai\(\d\)-Mr House v2 cast Deadly Dispute') { $n = if ($lastZone.Count) { $lastZone[-1] } else { '?' }; $disp[$n] = 1 + [int]$disp[$n] }
      if ($line -match '^Resolve Stack: Exsanguinate .* each lose (\d+) life') { [void]$exs.Add([int]$matches[1]) }
      if ($line -match '^Combat: Ai\(\d\)-Mr House v2 assigned (.*) to attack' -and $matches[1] -match 'Mr\. House, President') { $houseAtk++; $atkThisGame = $true }
      if ($line -match '^Resolve Stack: (?<src>At the beginning of your first main phase, \w+ \w+).*Ai\(\d\)-Mr House v2 loses (?<amt>\d+) life' -or $line -match '^Resolve Stack: (?<src>.+?)( \(\d+\))? - .*Ai\(\d\)-Mr House v2 loses (?<amt>\d+) life') {
        $src = $matches['src']; $amt = [int]$matches['amt']
        $lastSelfLoss = "$src (-$amt at ~$life life)"
        if ($life -le 8) { [void]$lowPay.Add("$where turn $turn : $src -$amt at ~$life life") }
        continue }
      if ($line -match 'Damage: .* deals \d+ damage to Ai\(\d\)-Mr House v2' -or $line -match 'Ai\(\d\)-Mr House v2 loses') { if ($line -notmatch 'Resolve Stack') { $lastSelfLoss = $null } }
    }
  }
}
"Games: $games"
"`n== Hostile spells aimed at his OWN creatures ($($own.Count))"; $own | Select-Object -First 15
"`n== Exsanguinate X values ($($exs.Count) casts)"; $exs | Group-Object | Sort-Object { [int]$_.Name } | ForEach-Object { "X=$($_.Name): $($_.Count)" }
"`n== Life paid at 8 life or less ($($lowPay.Count))"; $lowPay | Select-Object -First 15
"`n== Deaths where the last life loss was self-inflicted ($($selfKill.Count))"; $selfKill | Select-Object -First 15
"`n== Goblin Bombardment: what was sacrificed"; $bomb.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Deadly Dispute: what was sacrificed"; $disp.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Skullclamp equipped onto"; $clamp.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15 | ForEach-Object { "$($_.Value)  $($_.Key)" }
"`n== Reanimate targets"; $reanim | Group-Object | Sort-Object Count -Descending | Select-Object -First 15 | ForEach-Object { "$($_.Count)  $($_.Name)" }
"`n== Attacks that include Mr. House himself (0 power): $houseAtk, in $houseAtkGames games"
