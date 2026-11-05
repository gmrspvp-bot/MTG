# Overnight league of every deck, 2026-11-06. Pods alternate between the new rule and the old one,
# so a pod and the deck it holds is on one arm only: forge.ai.leaderdamage on (blank) or off.
& (Join-Path $PSScriptRoot 'league-all.ps1') -Parallel 6 -Full -LastStart '08:00' -Deadline '08:30' `
    -Label 'league-1106' -JvmArgs @('', '-Dforge.ai.leaderdamage=false')
