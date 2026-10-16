# Short test of each second-round deck against three current decks. Run folders -> scratch\<Label>-runs.txt
param([int]$Games = 24, [string]$Label = 'r2shake1')
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
$list = Join-Path $root "scratch\$Label-runs.txt"
$pods = [ordered]@{
    'Captain America v6' = 'Captain America v6,Doom Prevails,Mr House v2,Deadpool'
    'Mr House v4'        = 'Mr House v4,Doom Prevails,Deadpool,Tidus'
    'Doom Prevails v3'   = 'Doom Prevails v3,Mr House v2,Captain America v4,Deadpool'
    'Deadpool v3'        = 'Deadpool v3,Doom Prevails,Captain America v4,Tidus'
    'Tidus v3'           = 'Tidus v3,Doom Prevails,Mr House v2,Captain America v4'
}
foreach ($deck in $pods.Keys) {
    $before = @(Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object Name)
    & powershell -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\run-sims.ps1') -Decks $pods[$deck] -Games $Games -Parallel 6 | Select-Object -Last 2
    $new = Get-ChildItem (Join-Path $root 'results') -Directory | Where-Object { $before -notcontains $_.Name } | Sort-Object CreationTime | Select-Object -Last 1
    "$deck | $($new.Name)" | Add-Content $list
}
