# Round-2 decks vs the best previous version, same pods as the first prop games.
# Reports: results\compare_*.md; index in scratch\prop-games2.txt
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
$list = Join-Path $root 'scratch\prop-games2.txt'
$pairs = @(
    @{ A = 'Captain America v4'; B = 'Captain America v6'; Opp = 'Doom Prevails,Mr House v2,Deadpool' },
    @{ A = 'Mr House v3';        B = 'Mr House v4';        Opp = 'Doom Prevails,Deadpool,Tidus' },
    @{ A = 'Doom Prevails v2';   B = 'Doom Prevails v3';   Opp = 'Mr House v2,Captain America v4,Deadpool' },
    @{ A = 'Deadpool v2';        B = 'Deadpool v3';        Opp = 'Doom Prevails,Captain America v4,Tidus' },
    @{ A = 'Tidus';              B = 'Tidus v3';           Opp = 'Doom Prevails,Mr House v2,Captain America v4' }
)
foreach ($p in $pairs) {
    $before = @(Get-ChildItem (Join-Path $root 'results') -Filter 'compare_*.md' | ForEach-Object Name)
    & powershell -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\compare.ps1') -A $p.A -B $p.B -Opponents $p.Opp `
        -RoundGames 40 -MinGames 80 -MaxGames 160 -Parallel 6 | Select-Object -Last 6
    $rep = Get-ChildItem (Join-Path $root 'results') -Filter 'compare_*.md' | Where-Object { $before -notcontains $_.Name } | Select-Object -Last 1
    "$($p.A) vs $($p.B) | $($rep.Name) | finished $(Get-Date -Format 'HH:mm')" | Add-Content $list
}
