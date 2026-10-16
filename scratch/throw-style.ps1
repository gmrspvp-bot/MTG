param([string[]]$Files, [string]$Player = '')
# Captain America throw style from game logs (desktop recordings or sim batch logs), to compare
# the AI with the user. -Player: name in the log ("Captain America v6" matches Ai(1)-Captain America v6).
# Empty -Player = each file's "(HUMAN)" player from its header (desktop recordings).
$Files = @($Files | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$s = [ordered]@{ games=0; capTurns=0; capAttacks=0; castTurns=@(); throws=0; multi=0; face=0; creatureTargets=0; cmdrTargets=0;
                 deathtouch=0; kills=0; main1=0; main2=0; oppTurn=0; combat=0; dmg=@() }
foreach ($f in $Files) {
    $P = $Player
    if (-not $P) { foreach ($h in (Get-Content $f -TotalCount 6)) { if ($h -match '^Player: (.+) \(HUMAN\)') { $P = $matches[1] } } }
    if (-not $P) { continue }
    $E = [regex]::Escape($P)
    $own = 0; $active = ''; $phase = ''; $capOut = $false; $cast = 0; $inThrow = $false; $sawGame = $false
    foreach ($l in [IO.File]::ReadLines($f, [Text.Encoding]::UTF8)) {
        if ($l -match '^Turn: Turn \d+ \((.+)\)\s*$') { $active = $matches[1]; if ($active -match "(^|-)$E$") { $own++; if ($capOut) { $s.capTurns++ } }; continue }
        if ($l -match '^Phase: (.+)''s (.+)$') { $phase = $matches[2]; continue }
        if ($l -match "^Add To Stack: (.*$E) cast Captain America, First Avenger") { $cast++; if ($cast -eq 1) { $s.castTurns += $own }; continue }
        if ($l -match '^Resolve Stack: Captain America, First Avenger - Creature') { $capOut = $true; continue }
        if ($l -match '^Zone Change: Captain America, First Avenger \(\d+\) was put into') { $capOut = $false }
        if ($active -match "(^|-)$E$" -and $l -match "^Combat: .*$E assigned (.*)Captain America, First Avenger \(\d+\)") { $s.capAttacks++; continue }
        if ($l -match "^Add To Stack: (.*$E) activated Captain America, First Avenger targeting \[(.+)\]\s*$") {
            $t = $matches[2]; $s.throws++; $inThrow = $true; $sum = 0; $hitCards = 0; $hitPlayers = 0; $cmdr = $false
            $mine = $active -match "(^|-)$E$"
            if (-not $mine) { $s.oppTurn++ } elseif ($phase -match 'precombat') { $s.main1++ } elseif ($phase -match 'postcombat') { $s.main2++ } else { $s.combat++ }
            continue }
        if ($inThrow) {
            # Damage lines name each target: cards carry an id at the end, players don't.
            if ($l -match '^Damage: Captain America, First Avenger \(\d+\) deals (\d+) .*? to (.+?)\.?\s*$') {
                $sum += [int]$matches[1]; $who = $matches[2]
                if ($who -match ' \(\d+\)$') { $hitCards++; if ($who -match '^(Doctor Doom|Mr\. House|Tidus, Yuna|Deadpool|Kang)') { $cmdr = $true } } else { $hitPlayers++ }
                if ($l -match 'Deathtouch') { $dtThis = $true }; continue }
            if ($l -match '^Zone Change: .+ was put into Graveyard from Battlefield') { $s.kills++; continue }
            if ($l -match '^Resolve Stack: Captain America, First Avenger \(\d+\) - Captain America') {
                $s.dmg += $sum; if ($dtThis) { $s.deathtouch++ }; $dtThis = $false
                if ($hitCards + $hitPlayers -ge 2) { $s.multi++ }
                if ($hitPlayers -gt 0 -and $hitCards -eq 0) { $s.face++ }
                $s.creatureTargets += $hitCards; if ($cmdr) { $s.cmdrTargets++ }
                continue }
            if ($l -match '^(Add To Stack|Phase|Turn):') { $inThrow = $false; $dtThis = $false }
        }
        if ($l -match '^Game Result') { $s.games++; $own = 0; $capOut = $false; $cast = 0; $inThrow = $false }
    }
}
$g = [math]::Max(1, $s.games); $t = [math]::Max(1, $s.throws)
[pscustomobject]@{
    Games = $s.games
    CastTurn = [math]::Round((($s.castTurns | Measure-Object -Average).Average), 1)
    AttackPct = [math]::Round(100 * $s.capAttacks / [math]::Max(1, $s.capTurns))
    ThrowsPerGame = [math]::Round($s.throws / $g, 2)
    MultiTargetPct = [math]::Round(100 * $s.multi / $t)
    FaceOnlyPct = [math]::Round(100 * $s.face / $t)
    CreaturesPerThrow = [math]::Round($s.creatureTargets / $t, 2)
    CmdrHitPct = [math]::Round(100 * $s.cmdrTargets / $t)
    DeathtouchPct = [math]::Round(100 * $s.deathtouch / $t)
    KillsPerThrow = [math]::Round($s.kills / $t, 2)
    AvgDamage = [math]::Round((($s.dmg | Measure-Object -Average).Average), 1)
    Main1Pct = [math]::Round(100 * $s.main1 / $t)
    Main2Pct = [math]::Round(100 * $s.main2 / $t)
    OwnOtherPct = [math]::Round(100 * $s.combat / $t)   # own turn outside the main phases (combat, end step)
    OppTurnPct = [math]::Round(100 * $s.oppTurn / $t)
}
