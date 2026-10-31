# What pulled the eye off the 3D table, from the newest play log.
#
# Written for the session where the owner and a friend sat down with both windows on one screen
# and flagged every time Forge's took over (2026-10-31). Run it when you stop playing:
#
#     powershell -File scripts\attention-report.ps1
#     powershell -File scripts\attention-report.ps1 -Log scratch\play-logs\console_....log
#
# The interesting line is UNEXPLAINED: Forge's window came forward and nothing the 3D side did
# asked it to. Before the ATTENTION log went in those were invisible - the only evidence was
# that a session minimised the window thirteen times and only ever recorded three reasons for
# raising it.

param(
    [string] $Log = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $root 'scratch\play-logs'

if (-not $Log) {
    $newest = Get-ChildItem $dir -Filter 'console_*.log' -File -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) { Write-Host "No console log in $dir" -ForegroundColor Yellow; exit 1 }
    $Log = $newest.FullName
}
if (-not (Test-Path $Log)) { Write-Host "No such log: $Log" -ForegroundColor Yellow; exit 1 }

$lines = Get-Content $Log
Write-Host ""
Write-Host "Attention report" -ForegroundColor Cyan
Write-Host "  $Log" -ForegroundColor DarkGray
Write-Host ""

$att = $lines | Where-Object { $_ -match 'UI3D: ATTENTION' }
$min = @($lines | Where-Object { $_ -match "Forge's window minimised" }).Count
$res = @($lines | Where-Object { $_ -match "Forge's window restored" }).Count

if ($att.Count -eq 0) {
    Write-Host "  No ATTENTION lines at all." -ForegroundColor Yellow
    Write-Host "  Either this log predates the watcher, or Forge's window never came forward." -ForegroundColor DarkGray
    Write-Host "  minimised $min   restored $res" -ForegroundColor DarkGray
    Write-Host ""
    exit 0
}

# Split explained from not. The reason is the text between " - " and the first "   prompt=".
$rows = foreach ($l in $att) {
    $why = 'UNEXPLAINED'
    if ($l -match ' - (.+?)\s+prompt="') { $why = $matches[1] }
    $prompt = ''
    if ($l -match 'prompt="(.*?)"') { $prompt = $matches[1] }
    # $inp, not $input: $input is one of PowerShell's own automatic variables and assigning to
    # it inside a pipeline is asking for a bad afternoon.
    $inp = ''
    if ($l -match 'input=(\S+)') { $inp = $matches[1] }
    [pscustomobject]@{ Why = $why; Prompt = $prompt; Input = $inp }
}

$unexplained = @($rows | Where-Object { $_.Why -eq 'UNEXPLAINED' })
Write-Host ("  Forge's window came forward {0} times: {1} explained, {2} UNEXPLAINED" -f `
    $rows.Count, ($rows.Count - $unexplained.Count), $unexplained.Count) -ForegroundColor White
Write-Host ("  (minimised {0}, restored {1})" -f $min, $res) -ForegroundColor DarkGray
Write-Host ""

Write-Host "  By reason" -ForegroundColor Cyan
$rows | Group-Object Why | Sort-Object Count -Descending | ForEach-Object {
    $colour = 'Gray'
    if ($_.Name -eq 'UNEXPLAINED') { $colour = 'Yellow' }
    Write-Host ("    {0,4}  {1}" -f $_.Count, $_.Name) -ForegroundColor $colour
}
Write-Host ""

if ($unexplained.Count -gt 0) {
    Write-Host "  What was on the prompt when it happened for no reason we know" -ForegroundColor Cyan
    Write-Host "  (this is the clue to what asked for the window)" -ForegroundColor DarkGray
    $unexplained | Group-Object Prompt | Sort-Object Count -Descending | Select-Object -First 12 | ForEach-Object {
        $p = $_.Name
        if (-not $p) { $p = '(no prompt)' }
        Write-Host ("    {0,4}  {1}" -f $_.Count, $p) -ForegroundColor Gray
    }
    Write-Host ""
}

# What the PLAYER said, from the "Forge stole it" button. The machine's record is of the window
# moving; this is of attention actually going. A flag with nothing logged beside it is the most
# interesting row in the whole report - something took the eye that nothing here is watching.
$flags = $lines | Where-Object { $_ -match 'UI3D: STOLEN #' }
if ($flags) {
    $blind = @($flags | Where-Object { $_ -match 'nothing logged in the last 10s' })
    Write-Host ("  Flagged by hand: {0}, of which {1} with nothing logged beside them" -f `
        $flags.Count, $blind.Count) -ForegroundColor Cyan
    foreach ($f in $flags) {
        $colour = 'Gray'
        if ($f -match 'nothing logged in the last 10s') { $colour = 'Yellow' }
        Write-Host ("    " + ($f -replace '^UI3D: ', '')) -ForegroundColor $colour
    }
    Write-Host ""
    if ($blind.Count -gt 0) {
        Write-Host "  The yellow ones are the lead: attention went somewhere and Forge's window" -ForegroundColor DarkGray
        Write-Host "  never came forward, so it was a dialog, a sound, or the taskbar." -ForegroundColor DarkGray
        Write-Host ""
    }
}

# Decisions Forge answered, which -Strict prints. These are the ones with a known fix: take the
# call over in Gui3D and it stops happening.
$ans = $lines | Where-Object { $_ -match "UI3D: Forge answered '" } | ForEach-Object {
    if ($_ -match "Forge answered '(.+?)'") { $matches[1] }
}
if ($ans) {
    Write-Host "  Decisions Forge answered (each one is a call Gui3D could take over)" -ForegroundColor Cyan
    $ans | Group-Object | Sort-Object Count -Descending | ForEach-Object {
        Write-Host ("    {0,4}  {1}" -f $_.Count, $_.Name) -ForegroundColor Gray
    }
    Write-Host ""
}
