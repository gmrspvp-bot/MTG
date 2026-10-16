param([string[]]$RunDirs)
# Looks for weird or pointless plays by every deck in sim logs. Reads logs only.
$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$commanders = 'Captain America, First Avenger|Mr\. House, President and CEO|Doctor Doom, King of Latveria|Tidus, Yuna''s Guardian|Deadpool, Trading Card'
function Deck($p) { $p -replace '^Ai\(\d\)-', '' }
function Items($s) { @([regex]::Matches($s, '(.+?) \((\d+)\)(, | and |,? and |$)') | ForEach-Object { [pscustomobject]@{ Name = ($_.Groups[1].Value -replace '^(and |, )', '').Trim(); Id = $_.Groups[2].Value } }) }
function Add($h, $k) { $h[$k] = 1 + [int]$h[$k] }
function ParseCreatures($line) { if ($line -match 'creatures \d+[^:]*: (.*?)( \| hand cards|$)') { @($matches[1] -split '; ' | ForEach-Object { ($_ -replace ' \d+/\d+\*?( x\d+)?$', '').Trim() }) } else { @() } }

$games = 0; $seats = @{}
$chumpAtk = @{}; $chumpAtkCards = @{}; $chumpBlk = @{}; $chumpBlkCards = @{}; $cleanupDiscard = @{}; $cleanupCards = @{}
$x0 = @{}; $ownTarget = @{}; $tokenRemoval = @{}; $cmdrSac = @{}; $churn = @{}; $failed = @{}; $failedWithTargets = @{}; $fizzle = @{}
foreach ($dir in $RunDirs) {
  foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
    $boards = @{}; $life = @{}; $active = ''; $phase = ''; $equips = @{}; $prev = ''
    $atk = @{}; $blk = @{}; $died = @{}; $dmgToPlayer = @{}; $seen = @{}; $x0seen = @{}
    foreach ($line in [IO.File]::ReadLines($f.FullName, [Text.Encoding]::UTF8)) {
      if ($line.StartsWith('Game Result')) {
        $games++; foreach ($p in $seen.Keys) { Add $seats (Deck $p) }
        foreach ($k in $equips.Keys) { if ($equips[$k] -ge 6) { $churn["$k"] = [Math]::Max([int]$churn["$k"], $equips[$k]) } }
        $boards = @{}; $life = @{}; $equips = @{}; $seen = @{}; $x0seen = @{}; continue }
      if ($line -match '^Phase: (Ai\(\d\)-.+?)''s? (.+)$') {
        $active = $matches[1]; $phase = $matches[2]; $seen[$active] = 1
        if ($phase -eq 'Declare Attackers Step') { $atk = @{}; $blk = @{}; $died = @{}; $dmgToPlayer = @{} }
        if ($phase -eq 'End of Combat Step') {
          foreach ($id in $atk.Keys) {
            $a = $atk[$id]; $bs = @($blk.Keys | Where-Object { $blk[$_].Target -eq $id })
            if ($bs.Count -gt 0 -and $died[$id] -and -not $dmgToPlayer[$id] -and -not ($bs | Where-Object { $died[$_] })) {
              Add $chumpAtk (Deck $a.Owner); Add $chumpAtkCards "$(Deck $a.Owner): $($a.Name)" } }
          foreach ($id in $blk.Keys) {
            $b = $blk[$id]; $t = $b.Target
            if ($died[$id] -and $atk.ContainsKey($t) -and -not $died[$t] -and $b.Name -notmatch 'Token') {
              $l = if ($life.ContainsKey($b.Owner)) { $life[$b.Owner] } else { 40 }
              if ($l -ge 20) { Add $chumpBlk (Deck $b.Owner); Add $chumpBlkCards "$(Deck $b.Owner): $($b.Name) (life $l)" } } }
        }
        continue }
      if ($line -match '^Information: BOARD turn \d+ \(.+?\) \| (Ai\(\d\)-[^|]+?) \| life (-?\d+)') { $boards[$matches[1]] = ParseCreatures $line; $life[$matches[1]] = [int]$matches[2]; continue }
      if ($line -match '^Life: Life: (Ai\(\d\)-.+?) (-?\d+) > (-?\d+)') { $life[$matches[1]] = [int]$matches[3] }
      if ($line -match '^Combat: (Ai\(\d\)-.+?) assigned (.+) to attack ') { foreach ($it in Items $matches[2]) { $atk[$it.Id] = [pscustomobject]@{ Name = $it.Name; Owner = $matches[1] } }; continue }
      if ($line -match '^(Combat: )?(Ai\(\d\)-.+?) assigned (.+) to block (.+?) \((\d+)\)\.$') { $o = $matches[2]; $t = $matches[5]; foreach ($it in Items $matches[3]) { $blk[$it.Id] = [pscustomobject]@{ Name = $it.Name; Owner = $o; Target = $t } }; continue }
      if ($line -match '^Damage: .+? \((\d+)\) deals \d+ combat damage to Ai\(\d\)-') { $dmgToPlayer[$matches[1]] = $true; continue }
      if ($line -match '^Zone Change: (.+?) \((\d+)\) was put into (Graveyard|Exile|Command) from Battlefield') {
        $died[$matches[2]] = $true; $prev = $line; continue }
      if ($line -match '^Discard: (Ai\(\d\)-.+?) discards (.+?) \(\d+\)' -and $phase -eq 'Cleanup step' -and $matches[1] -eq $active) { Add $cleanupDiscard (Deck $matches[1]); Add $cleanupCards "$(Deck $matches[1]): $($matches[2])"; continue }
      if ($line -match 'Activator: (Ai\(\d\)-[^,\]]+), SpellAbility: (.+?) \(X=0\)' -or $line -match '^Add To Stack: (Ai\(\d\)-.+?) cast (.+?) \(X=0\)') {
        $k = "$(Deck $matches[1]): $($matches[2] -replace ' - .*$','')"; if (-not $x0seen[$k + $active]) { $x0seen[$k + $active] = 1; Add $x0 $k } }
      if ($line -match '^Add To Stack: (Ai\(\d\)-.+?) (cast|activated) (.+?) targeting \[(.+?) \(\d+\)\]') {
        $p = $matches[1]; $card = $matches[3].Trim(); $tgt = $matches[4].Trim(); $kind = $matches[2]
        $mine = @($boards[$p]); $theirs = @(); foreach ($k in $boards.Keys) { if ($k -ne $p) { $theirs += $boards[$k] } }
        if ($kind -eq 'cast' -and ($mine -contains $tgt) -and -not ($theirs -contains $tgt)) { Add $ownTarget "$(Deck $p): $card -> own $tgt" }
        if ($kind -eq 'cast' -and $tgt -match 'Token$' -and -not ($mine -contains $tgt)) { Add $tokenRemoval "$(Deck $p): $card -> $tgt" }
        if ($kind -eq 'activated') { $k = "$(Deck $p): $card"; $equips[$k] = 1 + [int]$equips[$k] }
      }
      if ($line -match '^Add To Stack: (Ai\(\d\)-.+?) (cast|activated) (.+?)( targeting|$)') {
        $c = $matches[1]; $what = $matches[3]
        if ($prev -match "^Zone Change: ($commanders) \(\d+\) was put into (Graveyard|Command)") { Add $cmdrSac "$(Deck $c): $($matches[1]) gone right before own $what" } }
      if ($line -match '^Add To Stack: (.+?) \(\d+\) - \[Couldn''t add to stack, failed to target\] - (.*)$') { Add $failed $matches[1]; if ($matches[2] -match '\(\d+\)') { Add $failedWithTargets $matches[1] } }
      if ($line -match 'fizzle|countered for having no legal|no legal targets') { Add $fizzle ($line.Substring(0, [Math]::Min(120, $line.Length))) }
      if (-not $line.StartsWith('Information:')) { $prev = $line }
    }
  }
}
function Top($h, $n = 15) { $h.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First $n | ForEach-Object { "  $($_.Value)  $($_.Key)" } }
"Games: $games"; "Seats per deck:"; Top $seats
"`n== Attacks that only lost the attacker (blocked, died, killed no blocker, no damage through), per deck"; Top $chumpAtk; "  -- cards:"; Top $chumpAtkCards 20
"`n== Chump blocks with a non-token at 20+ life (blocker died, attacker survived)"; Top $chumpBlk; "  -- cards:"; Top $chumpBlkCards 20
"`n== Cards discarded to hand size at own cleanup"; Top $cleanupDiscard; "  -- cards:"; Top $cleanupCards 20
"`n== X=0 spells"; Top $x0 20
"`n== Spells cast at own creatures (check: removal = bad, pump/aura = fine)"; Top $ownTarget 30
"`n== Spells aimed at opponents' tokens"; Top $tokenRemoval 25
"`n== Own commander left the battlefield right before own cast/activation (sacrifice?)"; Top $cmdrSac 15
"`n== Same ability activated 6+ times in one game (max per game)"; Top $churn 20
"`n== Triggers that failed to target (total / with a target picked)"; $failed.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "  $($_.Value) / $([int]$failedWithTargets[$_.Key])  $($_.Key)" }
"`n== Fizzles"; Top $fizzle 10
