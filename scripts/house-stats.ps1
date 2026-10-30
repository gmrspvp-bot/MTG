# Reports how Mr. House's {4},{T} dice ability was paid (Treasure vs other
# mana) and how many dice it rolled, plus Attraction activity.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\house-stats.ps1 -RunDir results\<run>

param([Parameter(Mandatory)] [string] $RunDir)

$ErrorActionPreference = 'Stop'
$logs = Get-ChildItem (Resolve-Path $RunDir) -Filter 'batch*.log' | ForEach-Object { $_.FullName }

$acts = @()
foreach ($f in $logs) {
    $lines = Get-Content $f
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch '^Add To Stack: Ai\(\d\)-(?:.+ - )?Mr House[^ ]*( v\d+)? activated Mr\. House') { continue }
        # Mana payments are logged just before the activation (Treasure sacrifices add Zone Change lines).
        $treasure = 0
        for ($j = $i - 1; $j -ge [math]::Max(0, $i - 20); $j--) {
            if ($lines[$j] -match '^Mana: Treasure') { $treasure++ }
            elseif ($lines[$j] -notmatch '^(Mana|Zone Change): ') { break }
        }
        $robots = 0; $resolved = $false
        for ($k = $i + 1; $k -lt [math]::Min($i + 25, $lines.Count); $k++) {
            if ($lines[$k] -match '^Resolve Stack: Mr\. House, President and CEO \(\d+\) - Roll') { $resolved = $true; continue }
            if ($resolved -and $lines[$k] -match '^Add To Stack: Ai\(\d\)-(?:.+ - )?Mr House[^ ]*( v\d+)? triggered Mr\. House') { $robots++ }
            if ($lines[$k] -match '^(Phase|Add To Stack: Ai\(\d\)-(?!(?:.+ - )?Mr House))') { break }
        }
        $acts += [pscustomobject]@{ Treasure = $treasure; RobotTriggers = $robots }
    }
}

"Mr House dice activations: {0}   paid with Treasure: {1}   Treasure mana spent on it: {2}" -f `
    $acts.Count, @($acts | Where-Object { $_.Treasure -gt 0 }).Count, ($acts | Measure-Object Treasure -Sum).Sum
$acts | Group-Object Treasure | Sort-Object { [int]$_.Name } | ForEach-Object {
    "  {0} Treasure ({1} dice): {2,3} activations, avg Robot triggers {3:N1}" -f $_.Name, ([int]$_.Name + 1), $_.Count, ($_.Group | Measure-Object RobotTriggers -Average).Average
}
"Robots made from all dice rolls: " + @(Select-String -Path $logs -Pattern '^Resolve Stack: Whenever you roll a 4 or higher, create a 3/3').Count
"Attractions opened: " + @(Select-String -Path $logs -Pattern 'open an Attraction\. \[|dies, open an Attraction\. \[').Count +
    "   Attraction visits: " + @(Select-String -Path $logs -Pattern '^Resolve Stack: Visit [^\w\s]').Count
