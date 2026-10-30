# Audits Mr House dice triggers. Uses Vexing Puzzlebox's "whenever you roll one or
# more dice" trigger (which logs every die result) to find rolls of 4+ made while
# Mr. House was on the battlefield, and checks that a Mr House Robot trigger
# followed. Reports missed triggers grouped by what made the roll.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\dice-audit.ps1 -RunDirs results\a,results\b

param([Parameter(Mandatory)] [string[]] $RunDirs)

$ErrorActionPreference = 'Stop'
$RunDirs = @($RunDirs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$checked = 0; $expected = 0; $got = 0; $missBySource = @{}; $examples = @()
foreach ($dir in $RunDirs) {
    foreach ($f in Get-ChildItem $dir -Filter 'batch*.log') {
        $lines = [IO.File]::ReadAllLines($f.FullName)
        $seat = $null; $houseIn = 0; $lastRollSource = '?'
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $l = $lines[$i]
            if ($l -match '^Ai\(1\)-.+ vs ') {
                $seat = $null; $houseIn = 0
                foreach ($m in [regex]::Matches($l, 'Ai\((\d)\)-(.+?)(?= vs |\s+-\s+\w+ games? of|$)')) { if ($m.Groups[2].Value.Trim() -like '*Mr House*') { $seat = $m.Groups[1].Value } }
                continue
            }
            if (-not $seat) { continue }
            if ($l -match '^Game Result') { $houseIn = 0; continue }
            # Track Mr. House entering / leaving the battlefield.
            if ($l -match '^Resolve Stack: Mr\. House, President and CEO( \(\d+\))? - Creature' -or
                $l -match 'Mr\. House, President and CEO \(\d+\) .*(onto|to) the battlefield') { $houseIn = 1 }
            if ($l -match '^Zone Change: Mr\. House, President and CEO \(\d+\) was put into \w+ from Battlefield') { $houseIn = 0 }
            # Remember what last made a roll (for grouping misses).
            if ($l -match '^(?:Resolve Stack|Mana): (.+?) \(\d+\)\s*-\s*.*\b[Rr]oll') { $lastRollSource = $matches[1] }
            elseif ($l -match '^Resolve Stack: (Whenever .+? attacks|When .+? enters|Visit)\b.*[Rr]oll') { $lastRollSource = ($matches[1] -replace '^Whenever |^When ', '') }
            # Puzzlebox trigger lists every die from one roll event.
            if ($l -match "^Resolve Stack: Whenever you roll one or more dice, put a number of charge counters on Vexing Puzzlebox.*\[Player: Ai\($seat\)-[^,]+, Result: \[([^\]]*)\]") {
                if (-not $houseIn) { continue }
                $dice = @($matches[1] -split ',\s*' | Where-Object { $_ } | ForEach-Object { [int]$_ })
                $need = @($dice | Where-Object { $_ -ge 4 }).Count
                if ($need -eq 0) { continue }
                $checked++
                # Mr House triggers for the same roll resolve near the Puzzlebox trigger.
                $have = 0
                for ($k = [math]::Max(0, $i - 15); $k -lt [math]::Min($lines.Count, $i + 15); $k++) {
                    if ($lines[$k] -match "^Add To Stack: Ai\($seat\)-.+? triggered Mr\. House") { $have++ }
                }
                $have = [math]::Min($have, $need)
                $expected += $need; $got += $have
                if ($have -lt $need) {
                    $missBySource[$lastRollSource] = ($need - $have) + [int]$missBySource[$lastRollSource]
                    if ($examples.Count -lt 4) { $examples += "$([IO.Path]::GetFileName($dir))\$($f.Name):$($i + 1)  dice [$($dice -join ', ')] from $lastRollSource - $have of $need Mr House triggers" }
                }
            }
        }
    }
}
"Rolls with a 4+ checked (Mr House on battlefield, Puzzlebox out): $checked"
"Mr House Robot triggers expected: $expected   seen: $got   missing: $($expected - $got)"
if ($missBySource.Count) {
    "Missing triggers by roll source:"
    $missBySource.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { "  {0,4}  {1}" -f $_.Value, $_.Key }
    "Examples:"; $examples | ForEach-Object { "  $_" }
}
