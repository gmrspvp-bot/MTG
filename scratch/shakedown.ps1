# Short test of each upgraded deck against three current decks. Run folders -> scratch\shakedown-runs.txt
param([int]$Games = 24, [string]$Label = 'shakedown')
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
$list = Join-Path $root "scratch\$Label-runs.txt"
$pods = [ordered]@{
    'Captain America v5' = 'Captain America v5,Doom Prevails,Mr House v2,Deadpool'
    'Mr House v3'        = 'Mr House v3,Doom Prevails,Deadpool,Tidus'
    'Doom Prevails v2'   = 'Doom Prevails v2,Mr House v2,Captain America v4,Deadpool'
    'Deadpool v2'        = 'Deadpool v2,Doom Prevails,Captain America v4,Tidus'
    'Tidus v2'           = 'Tidus v2,Doom Prevails,Mr House v2,Captain America v4'
}
foreach ($deck in $pods.Keys) {
    $before = @(Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object Name)
    & powershell -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\run-sims.ps1') -Decks $pods[$deck] -Games $Games -Parallel 6 | Select-Object -Last 2
    $new = Get-ChildItem (Join-Path $root 'results') -Directory | Where-Object { $before -notcontains $_.Name } | Sort-Object CreationTime | Select-Object -Last 1
    "$deck | $($new.Name)" | Add-Content $list
}
