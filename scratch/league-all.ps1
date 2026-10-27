# A league of every current deck (decks\*.dck, not the archive), in balanced 4-player pods
# (the owner, 2026-10-26: "add all the other decks as well to the sims").
#
# Rounds: each round some decks sit out (in turn, so over a full cycle every deck sits out once)
# and the rest are shuffled - seeded, so the pods are the same every time - into pods of four.
# With 18 decks a cycle is 9 rounds = 36 pods, each deck in 8 of them. The pods go to
# run-until.ps1, which plays them in order (40 games each) until the deadline. Results:
# scratch\<Label>-runs.txt and the folders it lists; summarise with scratch\league-table.ps1.
#
#   powershell -ExecutionPolicy Bypass -File scratch\league-all.ps1                 # light, until 08:30
#   powershell -ExecutionPolicy Bypass -File scratch\league-all.ps1 -Parallel 6 -Full
param([string] $LastStart = '08:00', [string] $Deadline = '08:30', [int] $Parallel = 3, [switch] $Full,
      [string] $Label = 'league-all', [string[]] $JvmArgs = @(''), [switch] $ListOnly)

$root = Split-Path -Parent $PSScriptRoot
$decks = @(Get-ChildItem (Join-Path $root 'decks') -Filter '*.dck' | Sort-Object Name | ForEach-Object { $_.BaseName })
$n = $decks.Count
# n mod 4 decks sit out each round, so the rest fill pods of four exactly; with none to sit
# out (24 decks), 8 rounds so every deck still plays 8 pods.
$sit = $n % 4
$rounds = if ($sit -gt 0) { [math]::Ceiling($n / $sit) } else { 8 }
$pods = @()
for ($r = 0; $r -lt $rounds; $r++) {
    $out = @(for ($k = 0; $k -lt $sit; $k++) { $decks[($sit * $r + $k) % $n] })
    $rest = @($decks | Where-Object { $out -notcontains $_ })
    $rng = New-Object System.Random (1000 + $r)
    $rest = @($rest | Sort-Object { $rng.Next() })
    for ($i = 0; $i + 3 -lt $rest.Count; $i += 4) { $pods += ($rest[$i..($i + 3)] -join ',') }
}
"{0} decks, {1} rounds, {2} pods" -f $n, $rounds, $pods.Count
if ($ListOnly) { $pods; return }
$args = @{ LastStart = $LastStart; Deadline = $Deadline; Parallel = $Parallel; Label = $Label; Pods = $pods; PodJvmArgs = $JvmArgs }
if (-not $Full) { $args['Light'] = $true }
& (Join-Path $PSScriptRoot 'run-until.ps1') @args
