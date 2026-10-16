# Two generations of pods A-E (each pod leaves one deck out), 40 games each.
# Run folders are appended to scratch\generations-runs.txt as they finish.
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
$list = Join-Path $root 'scratch\generations-runs.txt'
$pods = [ordered]@{
    'A (no Deadpool)'  = 'Captain America v4,Mr House v2,Doom Prevails,Tidus'
    'B (no Captain)'   = 'Deadpool,Mr House v2,Doom Prevails,Tidus'
    'C (no Mr House)'  = 'Deadpool,Captain America v4,Doom Prevails,Tidus'
    'D (no Doom)'      = 'Deadpool,Captain America v4,Mr House v2,Tidus'
    'E (no Tidus)'     = 'Deadpool,Captain America v4,Mr House v2,Doom Prevails'
}
foreach ($gen in 1, 2) {
    foreach ($pod in $pods.Keys) {
        $before = @(Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object Name)
        & powershell -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\run-sims.ps1') -Decks $pods[$pod] -Games 40 -Parallel 6 | Select-Object -Last 3
        $new = Get-ChildItem (Join-Path $root 'results') -Directory | Where-Object { $before -notcontains $_.Name } | Sort-Object CreationTime | Select-Object -Last 1
        "gen $gen | pod $pod | $($new.Name)" | Add-Content $list
        "gen $gen pod $pod done: $($new.Name)"
    }
}
