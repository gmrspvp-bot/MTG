# Bundle THIS machine's Forge/3D logs into one file to send to whoever is debugging.
#
#     powershell -ExecutionPolicy Bypass -File scripts\collect-logs.ps1
#
# Why this exists: scratch\play-logs is gitignored (.gitignore:35), so nothing from anyone
# else's machine ever reaches the repo. Every network and performance problem this week has
# been diagnosed from one side only - the owner's - while the player actually suffering it
# ("9GB on the host", "his PC going crazy", four disconnects from one address) had the only
# logs that would have said why. One command, one file, send it.
#
# It also takes a machine snapshot, because "it was using like 9gb" needs a number next to how
# much that machine has, what the JVM was told it could use, and what else was running.
#
# Nothing here is uploaded anywhere. It writes a zip and tells you where it is.

param(
    [string] $Out = '',        # where to write the zip; default is the Desktop
    [int]    $Keep = 4,        # how many of each kind of log to take, newest first
    [int]    $MaxLines = 40000 # long logs are trimmed to the first 2000 and the last of these
)

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$logs  = Join-Path $root 'scratch\play-logs'
$bugs  = Join-Path $root 'scratch\bug-reports'
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$name  = "MTG-logs_$($env:COMPUTERNAME)_$stamp"
if (-not $Out) { $Out = [Environment]::GetFolderPath('Desktop') }
$stage = Join-Path $env:TEMP $name
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Write-Host ""
Write-Host "Collecting logs from $($env:COMPUTERNAME)" -ForegroundColor Cyan

# ---- the machine, and what the JVM was allowed --------------------------------------------
$sys = Join-Path $stage 'machine.txt'
$lines = @()
$lines += "machine   : $($env:COMPUTERNAME)   user $($env:USERNAME)"
$lines += "collected : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
try {
    $os = Get-CimInstance Win32_OperatingSystem
    $cs = Get-CimInstance Win32_ComputerSystem
    $lines += "os        : $($os.Caption) $($os.Version)"
    $lines += "cpu       : $((Get-CimInstance Win32_Processor | Select-Object -First 1).Name)"
    $lines += ("ram       : {0:N1} GB total, {1:N1} GB free at collection" -f `
        ($cs.TotalPhysicalMemory/1GB), ($os.FreePhysicalMemory/1MB))
} catch { $lines += "os/cpu/ram: unavailable ($_)" }
try {
    $gpu = Get-CimInstance Win32_VideoController | Select-Object -First 1
    $lines += "gpu       : $($gpu.Name)  driver $($gpu.DriverVersion)"
} catch { }
$lines += ""
$lines += "--- java processes running right now (this is where the 9GB question gets answered) ---"
try {
    $javas = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'"
    if (-not $javas) { $lines += "(none running - run this WHILE the game is up for the useful numbers)" }
    foreach ($j in $javas) {
        $p = Get-Process -Id $j.ProcessId -ErrorAction SilentlyContinue
        $ws = if ($p) { "{0:N2} GB working set, {1:N2} GB private" -f ($p.WorkingSet64/1GB), ($p.PrivateMemorySize64/1GB) } else { '?' }
        $lines += ""
        $lines += "pid $($j.ProcessId)  $ws"
        $lines += "  $($j.CommandLine)"
    }
} catch { $lines += "process list unavailable ($_)" }
$lines | Set-Content -Path $sys -Encoding utf8

# ---- the logs themselves --------------------------------------------------------------------
function Copy-Trimmed($file, $dest) {
    # $file.FullName, not $file: a FileInfo turns into its NAME in a string context, so
    # Get-Content went looking in the working directory and found nothing.
    $path = $file.FullName
    # ReadLines, counted: Measure-Object -Line over -ReadCount 0 sees ONE array object and
    # answers 1 for every file, which it duly did.
    $n = 0
    foreach ($ignored in [System.IO.File]::ReadLines($path)) { $n++ }
    if ($n -le $MaxLines) { Copy-Item $path $dest; return "$($file.Name) ($n lines)" }
    # Head and tail: the start says how it was launched, the end says how it went wrong.
    $head = Get-Content $path -TotalCount 2000
    $tail = Get-Content $path -Tail $MaxLines
    $head + @("", "... [$($n - 2000 - $MaxLines) lines cut from the middle by collect-logs.ps1] ...", "") + $tail |
        Set-Content -Path $dest -Encoding utf8
    return "$($file.Name) ($n lines, trimmed)"
}

$took = @()
foreach ($pat in @('console_*.log', 'console_*.log.err', 'game_*.log', 'attention-flags_*.log')) {
    $files = Get-ChildItem $logs -Filter $pat -File -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First $Keep
    foreach ($f in $files) { $took += Copy-Trimmed $f (Join-Path $stage $f.Name) }
}
if (Test-Path $bugs) {
    $b = Get-ChildItem $bugs -Filter '*.md' -File | Sort-Object LastWriteTime -Descending | Select-Object -First $Keep
    foreach ($f in $b) { Copy-Item $f.FullName (Join-Path $stage $f.Name); $took += $f.Name }
}

# ---- which build this machine is actually on, which has been the open question --------------
try {
    Push-Location $root
    @(
        "commit : $(git rev-parse --short HEAD 2>$null)",
        "date   : $(git log -1 --format=%cd --date=iso 2>$null)",
        "subject: $(git log -1 --format=%s 2>$null)",
        "",
        "--- uncommitted changes ---",
        (git status --short 2>$null)
    ) | Set-Content (Join-Path $stage 'build.txt') -Encoding utf8
    Pop-Location
} catch { }

$zip = Join-Path $Out "$name.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip
Remove-Item $stage -Recurse -Force

foreach ($t in $took) { Write-Host "  $t" -ForegroundColor DarkGray }
Write-Host ""
Write-Host "  Send this one file:" -ForegroundColor Green
Write-Host "    $zip" -ForegroundColor White
Write-Host ("    {0:N1} MB" -f ((Get-Item $zip).Length/1MB)) -ForegroundColor DarkGray
Write-Host ""
