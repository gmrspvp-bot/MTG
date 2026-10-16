# Second round of upgrades (EDHREC most-played cards that are free in the bulk binder, plus
# replacements for the weakest cards in the overnight games). Originals are not touched.
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude\decks'
$plans = @(
  @{ From = 'Captain America v5'; To = 'Captain America v6'
     Out = 'Galvanic Blast','Pip-Boy 3000','Firion, Wild Rose Warrior','Herald of Eternal Dawn','Paladin''s Arms','Amazing Acrobatics'
     In  = 'Stroke of Midnight','Danitha Capashen, Paragon','In the Trenches','Curiosity','Iroas, God of Victory','Dawn''s Truce'
     Note = 'EDHREC staples Danitha Capashen, In the Trenches, Curiosity; Iroas; Stroke of Midnight; Dawn''s Truce' },
  @{ From = 'Mr House v3'; To = 'Mr House v4'
     Out = 'Illusionist''s Bracers','Securitron Squadron','All That Glitters','Mirkwood Bats'
     In  = 'Circuits Act','Caretaker''s Talent','Blind Obedience','Chiss-Goria, Forge Tyrant'
     Note = 'EDHREC staples Circuits Act and Caretaker''s Talent; Blind Obedience; Chiss-Goria' },
  @{ From = 'Doom Prevails v2'; To = 'Doom Prevails v3'
     Out = 'Kang Dynasty','Progenitor''s Icon','Currency Converter','Stilt-Man, Towering Terror'
     In  = 'Green Goblin, Revenant','Ultimate Green Goblin','Magmakin Artillerist','Mysterio, Master of Illusion'
     Note = 'EDHREC staples: Green Goblins + Magmakin Artillerist discard package, Mysterio' },
  @{ From = 'Deadpool v2'; To = 'Deadpool v3'
     Out = 'Zuko, Conflicted','Guttersnipe','Jaxis, the Troublemaker','Enduring Courage'
     In  = 'Ghost-Spider, Gwen Stacy','Bloatfly Swarm','Fellwar Stone','Mind Stone'
     Note = 'EDHREC staples Fellwar Stone and Mind Stone; Ghost-Spider; Bloatfly Swarm' },
  @{ From = 'Tidus v2'; To = 'Tidus v3'
     Out = 'Farewell','Elemental Bond','Walking Ballista','Rikku, Resourceful Guardian','Lyla, Holographic Assistant'
     In  = 'Kimahri, Valiant Guardian','O''aka, Traveling Merchant','Summon: Yojimbo','Altered Ego','Guiding Hydra'
     Note = 'EDHREC staples Kimahri, O''aka, Summon: Yojimbo, Altered Ego, Guiding Hydra' }
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
            if ($p.Out -contains $name -and -not $removed[$name]) { $removed[$name] = $true; $n--; if ($n -le 0) { continue }; $out.Add("$n $name"); continue }
        }
        $out.Add($l)
    }
    if ($sec -eq 'main') { foreach ($n in $p.In) { $out.Add("1 $n") } }
    $i = $out.IndexOf("Name=$($p.To)")
    $out.Insert($i + 1, "Description=$($p.To) (2026-10-12): $($p.From) + $($p.Note). Out: $($p.Out -join ', '). In: $($p.In -join ', ').")
    $missing = $p.Out | Where-Object { -not $removed[$_] }
    $dest = Join-Path $root "$($p.To).dck"
    if (Test-Path $dest) { throw "$dest already exists" }
    [IO.File]::WriteAllText($dest, ($out -join "`r`n") + "`r`n", (New-Object Text.UTF8Encoding($false)))
    $count = 0; $s = ''; foreach ($l in $out) { if ($l -match '^\[(.+)\]') { $s = $matches[1]; continue }; if ($s -in 'main', 'commander' -and $l -match '^(\d+) ') { $count += [int]$matches[1] } }
    "$($p.To): $count cards" + $(if ($missing) { "  NOT FOUND TO CUT: $($missing -join ', ')" } else { '' })
}
