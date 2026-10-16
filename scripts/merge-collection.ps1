# Combines every ManaBox CSV in manabox\bulk-binder into one collection file,
# manabox\collection-all.csv. Exports without a "Binder Name" column get the
# file name (e.g. red.csv -> "red") as their binder.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\merge-collection.ps1

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$inDir  = Join-Path $root 'manabox\bulk-binder'
$outCsv = Join-Path $root 'manabox\collection-all.csv'

$columns = 'Binder Name','Name','Set code','Set name','Collector number','Foil','Rarity','Quantity','ManaBox ID','Scryfall ID','Condition','Language','Proxy','Source File'
$rows = @()
foreach ($f in Get-ChildItem $inDir -Filter *.csv) {
    $defaultBinder = [IO.Path]::GetFileNameWithoutExtension($f.Name)
    foreach ($r in Import-Csv $f.FullName) {
        $binder = if ($r.PSObject.Properties.Name -contains 'Binder Name' -and $r.'Binder Name') { $r.'Binder Name' } else { $defaultBinder }
        $o = [ordered]@{}
        foreach ($c in $columns) { $o[$c] = $r.$c }
        $o['Binder Name'] = $binder
        $o['Source File'] = $f.Name
        $rows += [pscustomobject]$o
    }
}
$rows | Export-Csv $outCsv -NoTypeInformation -Encoding UTF8

"Wrote manabox\collection-all.csv"
"Rows: {0}   Cards: {1}   Unique names: {2}" -f $rows.Count, ($rows | Measure-Object Quantity -Sum).Sum, @($rows | Select-Object -ExpandProperty Name -Unique).Count
"By binder:"
$rows | Group-Object 'Binder Name' | Sort-Object Name | ForEach-Object { "  {0,-10} {1,5} cards" -f $_.Name, ($_.Group | Measure-Object Quantity -Sum).Sum }
