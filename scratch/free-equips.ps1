param([string[]]$RunDirs, [string]$Deck = 'Captain America v4')
# How Captain America's deck uses its free / cheap equip sources.
$P = [regex]::Escape($Deck)
$eqNames = 'Adaptive Omnitool','Auxiliary Boosters','Basilisk Collar','Brotherhood Regalia','Champion''s Helm','Colossus Hammer','Commander''s Plate','Conqueror''s Flail','Darksteel Plate','Deathrender','Gold Pan','Hookblade','Mithril Coat','Mjölnir, Storm Hammer','Monk''s Fist','Nazgûl Battle-Mace','Paladin''s Arms','Skullclamp','Sword of Feast and Famine','Sword of Light and Shadow','Sword of War and Peace','Web-Shooters','Whispersilk Cloak','Excalibur, Sword of Eden','Sword'
$sources = 'Sigarda''s Aid','Codsworth, Handy Helper','Sokka and Suki','Weapons Vendor','Puresteel Paladin','Mithril Coat','Hookblade','Mjölnir, Storm Hammer','Firion, Wild Rose Warrior','Bureau Headmaster','Zirda, the Dawnwaker','Captain America, First Avenger'
$st = @{}; foreach ($s in $sources) { $st[$s] = @{ CastGames = 0; Uses = 0; OnCaptain = 0; UseGames = 0 } }
$equips = 0; $equipsOnCap = 0; $equipsWithPuresteel = 0; $games = 0; $puresteelTurnsNoEquip = 0
foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $cast = @{}; $used = @{}; $pureIn = $false
        foreach ($line in [IO.File]::ReadLines($f.FullName)) {
            if ($line.StartsWith('Game Result')) {
                $games++
                foreach ($s in $sources) { if ($cast[$s]) { $st[$s].CastGames++ }; if ($used[$s]) { $st[$s].UseGames++ } }
                $cast = @{}; $used = @{}; $pureIn = $false; continue
            }
            if ($line -match "^Add To Stack: Ai\(\d\)-$P cast (.+?)( targeting|$)") { $cast[$matches[1].Trim()] = $true; continue }
            if ($line -match '^Resolve Stack: Puresteel Paladin') { $pureIn = $true; continue }
            if ($line -match '^Zone Change: Puresteel Paladin \(\d+\) was put into .* from Battlefield') { $pureIn = $false; continue }
            if ($line -match "^Add To Stack: Ai\(\d\)-$P (activated|triggered) (.+?)( targeting \[(.*)\])?$") {
                $kind = $matches[1]; $src = $matches[2].Trim(); $tgt = "$($matches[4])"
                $onCap = $tgt -match 'Captain America, First Avenger'
                # Equip activations: "activated <Equipment> targeting [creature]"
                if ($kind -eq 'activated' -and $eqNames -contains $src -and $tgt) {
                    $equips++; if ($onCap) { $equipsOnCap++ }; if ($pureIn) { $equipsWithPuresteel++ }
                }
                if ($st.ContainsKey($src) -and ($src -ne 'Puresteel Paladin')) {
                    if ($src -eq 'Captain America, First Avenger' -and $kind -ne 'triggered') { continue }   # throws are activations; Catch is the trigger
                    if ($src -eq 'Codsworth, Handy Helper' -and $kind -ne 'activated') { continue }
                    $st[$src].Uses++; $used[$src] = $true
                    if ($onCap -or ($src -eq 'Captain America, First Avenger')) { $st[$src].OnCaptain++ }
                }
            }
        }
    }
}
"Games: $games"
""
"Free / cheap equip sources:"
foreach ($s in $sources) {
    $x = $st[$s]
    $castTxt = if ($s -eq 'Captain America, First Avenger') { 'commander' } else { '{0,3} games cast' -f $x.CastGames }
    "  {0,-32} {1} | uses {2,4} in {3,3} games | onto Captain America {4}" -f $s, $castTxt, $x.Uses, $x.UseGames, $x.OnCaptain
}
""
"Equip activations (paid equip ability): $equips total, $equipsOnCap onto Captain America, $equipsWithPuresteel while Puresteel Paladin was on the battlefield"
