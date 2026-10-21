# Overnight A/B for the "dice" patch's newest Mr House changes (2026-10-22):
#   - DiceHold: lands kept back for instant-speed dice abilities, let go at every end step;
#   - the Vexing Puzzlebox end-step tap.
# A = defaults (both on), B = both off (-Dforge.dice.hold=false -Dforge.dice.puzzlebox=false).
# Pod: Mr House v4, Doom Prevails v3, Rin and Seri (the friends' deck that won both 4-human
# games on 2026-10-21), Olivia. 160 games each, 6 at a time. Output: scratch\puzzlebox-A.txt /
# -B.txt and the results folders they name. Compare with
# scratch\house-style.ps1 -Player "Mr House v4".
param([int] $Games = 160)
$root = Split-Path -Parent $PSScriptRoot
$decks = 'Mr House v4,Doom Prevails v3,Rin and Seri,Olivia'
& (Join-Path $root 'scripts\run-sims.ps1') -Decks $decks -Games $Games -Parallel 6 *> (Join-Path $PSScriptRoot 'puzzlebox-A.txt')
& (Join-Path $root 'scripts\run-sims.ps1') -Decks $decks -Games $Games -Parallel 6 -JvmArgs '-Dforge.dice.hold=false -Dforge.dice.puzzlebox=false' *> (Join-Path $PSScriptRoot 'puzzlebox-B.txt')
'done' | Out-File (Join-Path $PSScriptRoot 'puzzlebox-done.txt')
