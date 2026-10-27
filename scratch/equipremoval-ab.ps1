# A/B of the equipment-aware removal re-aim (-Dforge.ai.equipremoval, 2026-10-27): Eivor pods,
# each pod played twice in a row - off, then on - so both arms see the same opponents.
# Results: scratch\equipremoval-runs.txt (each line's JVM args say which arm).
#
#   powershell -ExecutionPolicy Bypass -File scratch\equipremoval-ab.ps1 -LastStart 18:00 -Deadline 18:40
param([string] $LastStart = '18:00', [string] $Deadline = '18:40', [int] $Parallel = 3, [switch] $Full)

$pods = @(
    'Eivor,Angels,Captain America v7,Mr House v4',
    'Eivor,Doom Prevails v3,Scion,Rin and Seri',
    'Eivor,Claudes Neyali,Deadpool v3,Olivia',
    'Eivor,Eldrazi,Tidus v3,Alec')
$twice = @($pods | ForEach-Object { $_; $_ })
$args = @{ LastStart = $LastStart; Deadline = $Deadline; Parallel = $Parallel; Label = 'equipremoval'
           Pods = $twice; PodJvmArgs = @('', '-Dforge.ai.equipremoval=true') }
if (-not $Full) { $args['Light'] = $true }
& (Join-Path $PSScriptRoot 'run-until.ps1') @args
