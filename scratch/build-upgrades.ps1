# Builds upgraded copies of the five decks (bulk-binder cards). Originals are not touched.
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude\decks'
$plans = @(
  @{ From = 'Captain America v4'; To = 'Captain America v5'
     Out = 'Layla Hassan','Stonesplitter Bolt','Abrade','Hookblade','Gold Pan','Ponder','Spider-Woman, Stunning Savior'
     In  = 'Sword of Forge and Frontier','Steelshaper''s Gift','Swords to Plowshares','Lightning Bolt','Force of Negation','Spectacular Spider-Man','Battlefield Improvisation'
     Note = 'better removal (Swords to Plowshares, Lightning Bolt, Force of Negation) and equipment support' },
  @{ From = 'Mr House v2'; To = 'Mr House v3'
     Out = 'Revel in Riches','Talisman of Conviction','Wand of Wonder','Contraband Livestock','Black Market Connections','Vihaan, Goldwaker','Deadly Dispute'
     In  = 'Marionette Master','Weftstalker Ardent','Pitiless Plunderer','Infernal Grasp','The Soul Stone','Impact Tremors','Big Score'
     Note = 'artifact/token payoffs and Infernal Grasp' },
  @{ From = 'Doom Prevails'; To = 'Doom Prevails v2'
     Out = 'Kindred Dominance','Black Market Connections','Titan of Littjara','Helmut Zemo, Mastermind','Titania, Proud Pummeler'
     In  = 'Doctor Octopus, Master Planner','Electro, Assaulting Battery','Psychic Frog','Mister Negative','Black Cat, Cunning Thief'
     Note = 'more Villains plus Doctor Octopus (Villains +2/+2, refills the hand)' },
  @{ From = 'Deadpool'; To = 'Deadpool v2'
     Out = 'Dualcaster Mage','Surtr, Fiery Jötun','Mirror Gallery','Blood Seeker','Malakir Rebirth','Harsh Mentor'
     In  = 'Brash Taunter','Bloodghast','Infernal Grasp','Carnage, Crimson Chaos','Black Cat, Cunning Thief','Zuko, Conflicted'
     Note = 'Brash Taunter (with Blasphemous Act), recursion and removal' },
  @{ From = 'Tidus'; To = 'Tidus v2'
     Out = 'Sin, Unending Cataclysm','Gatta and Luzzu','Collective Effort','Nesting Grounds','Forge of Heroes','Skycloud Expanse','Plains'
     In  = 'Court of Garenbrig','Yuna, Grand Summoner','Sylvan Caryatid','The Earth King','Lyla, Holographic Assistant','Elemental Bond','Forest'
     Note = '40 -> 37 lands (one Plains -> Forest), mana fixing and +1/+1 counter cards' }
)
foreach ($p in $plans) {
    $lines = [IO.File]::ReadAllLines((Join-Path $root "$($p.From).dck"), [Text.Encoding]::UTF8)
    $out = New-Object System.Collections.Generic.List[string]
    $sec = ''; $removed = @{}
    foreach ($l in $lines) {
        if ($l -match '^\[(.+)\]') {
            if ($sec -eq 'main') { foreach ($n in $p.In) { $out.Add("1 $n") } }
            $sec = $matches[1]; $out.Add($l); continue
        }
        if ($sec -eq 'metadata' -and $l -match '^Name=') { $out.Add("Name=$($p.To)"); continue }
        if ($sec -eq 'metadata' -and $l -match '^Description=') { continue }
        if ($sec -eq 'main' -and $l -match '^(\d+) (.+?)$') {
            $n = [int]$matches[1]; $name = $matches[2].Trim()
            if ($p.Out -contains $name -and -not $removed[$name]) { $removed[$name] = $true; $n-- ; if ($n -le 0) { continue }; $out.Add("$n $name"); continue }
        }
        $out.Add($l)
    }
    if ($sec -eq 'main') { foreach ($n in $p.In) { $out.Add("1 $n") } }
    $i = $out.IndexOf("Name=$($p.To)")
    $out.Insert($i + 1, "Description=$($p.To) (2026-10-11): $($p.From) upgraded from the bulk binder - $($p.Note). Out: $($p.Out -join ', '). In: $($p.In -join ', ').")
    $missing = $p.Out | Where-Object { -not $removed[$_] }
    $dest = Join-Path $root "$($p.To).dck"
    if (Test-Path $dest) { throw "$dest already exists" }
    [IO.File]::WriteAllText($dest, ($out -join "`r`n") + "`r`n", (New-Object Text.UTF8Encoding($false)))
    $count = 0; $s = ''; foreach ($l in $out) { if ($l -match '^\[(.+)\]') { $s = $matches[1]; continue }; if ($s -in 'main', 'commander' -and $l -match '^(\d+) ') { $count += [int]$matches[1] } }
    "$($p.To): $count cards" + $(if ($missing) { "  NOT FOUND TO CUT: $($missing -join ', ')" } else { '' })
}
