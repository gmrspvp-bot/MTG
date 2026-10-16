# Reports Captain America's Throw usage in a results folder from run-sims.ps1.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\throw-stats.ps1 -RunDir results\<run>

param([Parameter(Mandatory)] [string] $RunDir)

$ErrorActionPreference = 'Stop'
$logs = Get-ChildItem (Resolve-Path $RunDir) -Filter 'batch*.log' | ForEach-Object { $_.FullName }

$throws = @()
foreach ($f in $logs) {
    $lines = Get-Content $f
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch '^Add To Stack: Ai\(\d\)-(Captain America[^ ]*(?: v\d+)?) activated Captain America, First Avenger targeting \[(.*)\]') { continue }
        $targets = $matches[2]
        $phase = ''
        for ($j = $i; $j -ge 0; $j--) {
            if ($lines[$j] -match "^Phase: Ai\(\d\)-(.+?)'s (.+)$") {
                $phase = $(if ($matches[1] -like 'Captain America*') { 'own ' } else { 'opp ' }) + $matches[2]
                break
            }
        }
        $dmg = 0; $killed = 0; $deathtouch = $false
        for ($m = $i + 1; $m -lt [math]::Min($i + 12, $lines.Count); $m++) {
            if ($lines[$m] -match '^(Add To Stack|Phase):') { break }
            if ($lines[$m] -match '^Damage: Captain America, First Avenger \(\d+\) deals (\d+) damage( \(Deathtouch\))?') {
                $dmg += [int]$matches[1]; if ($matches[2]) { $deathtouch = $true }
            }
            if ($lines[$m] -match 'was put into Graveyard from Battlefield') { $killed++ }
        }
        $throws += [pscustomobject]@{
            Log = [IO.Path]::GetFileName($f); Phase = $phase; Targets = $targets
            Damage = $dmg; Killed = $killed; Deathtouch = $deathtouch; AtPlayer = ($targets -match 'Ai\(\d\)-')
        }
    }
}

$throws | Export-Csv (Join-Path $RunDir 'throws.csv') -NoTypeInformation
"Throws: {0}   Damage: {1}   Permanents killed: {2}   Aimed at players: {3}   With deathtouch: {4}" -f `
    $throws.Count, ($throws | Measure-Object Damage -Sum).Sum, ($throws | Measure-Object Killed -Sum).Sum,
    @($throws | Where-Object AtPlayer).Count, @($throws | Where-Object Deathtouch).Count
"By phase:"
$throws | Group-Object Phase | Sort-Object Count -Descending | ForEach-Object { "  {0,4}x {1}" -f $_.Count, $_.Name }
