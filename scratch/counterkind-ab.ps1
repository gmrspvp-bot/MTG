# A/B of counterspells judged by kind (-Dforge.ai.counterkind=true), 2026-11-13. Every pod has two or
# more counterspell decks; a and b have Dom's Ardenn (equipment, Super State). Each pod runs on then off.
# Read with scratch\counterkind-ab.pl (wins per deck plus COUNTERAI lines per arm).
param([double]$Hours = 6)
$a = 'Dominic - Adrix and Nev,Dominic - Ardenn,Derand - Dr Who,Alec - Kotori'
$b = 'Derand - Captain America v7,Dominic - Ardenn,James - Zimone,James - Satya'
$c = 'Dominic - Scion,Dominic - Haytham Kenway,Other - Vivi,Kieran - Kratos and Atreus'
$end = (Get-Date).AddHours($Hours)
& (Join-Path $PSScriptRoot 'run-until.ps1') -LastStart $end.AddMinutes(-40).ToString('HH:mm') -Deadline $end.ToString('HH:mm') `
    -Parallel 4 -Light -Label 'counterkind' -Pods @($a, $a, $b, $b, $c, $c) -PodJvmArgs @('-Dforge.ai.counterkind=true', '')
