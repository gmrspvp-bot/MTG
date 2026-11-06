# A/B of kill on sight, 2026-11-06. Each pod runs on then off, so Alexios's win rate compares like for like.
$a = 'Alec - Alexios,Derand - Captain America v7,Dominic - Angels,Dominic - Frodo'
$b = 'Alec - Alexios,Alec - Kotori,Other - Tidus v3,Dominic - Haytham Kenway'
$c = 'Alec - Alexios,Derand - Dr Who,Dominic - Sigurd,Other - Goreclaw'
& (Join-Path $PSScriptRoot 'run-until.ps1') -LastStart '23:00' -Deadline '23:45' -Parallel 4 -Light -Label 'kos-1106' `
    -Pods @($a, $a, $b, $b, $c, $c) -PodJvmArgs @('-Dforge.ai.killonsight=true', '')
