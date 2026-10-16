# Overnight test of the human-style Captain America (2026-10-13): 40-game pods in rotation
# until the deadline. No batch starts after $LastStart; anything still running at $Deadline is
# stopped. Run folders -> scratch\overnight-cap-runs.txt
param([string]$LastStart = '07:40', [string]$Deadline = '08:00')
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
$list = Join-Path $root 'scratch\overnight-cap-runs.txt'
$today = Get-Date -Format 'yyyy-MM-dd'
$lastStartAt = [datetime]"$today $LastStart"; $deadlineAt = [datetime]"$today $Deadline"
if ($deadlineAt -lt (Get-Date)) { $lastStartAt = $lastStartAt.AddDays(1); $deadlineAt = $deadlineAt.AddDays(1) }
$pods = @(
    'Captain America v6,Doom Prevails v3,Mr House v4,Tidus v3',
    'Captain America v6,Doom Prevails v3,Deadpool v3,Mr House v4',
    'Captain America v6,Deadpool v3,Tidus v3,Doom Prevails v3'
)
$i = 0
while ((Get-Date) -lt $lastStartAt) {
    $pod = $pods[$i % $pods.Count]; $i++
    $before = @(Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object Name)
    $job = Start-Job -ScriptBlock { param($s, $d) & powershell -ExecutionPolicy Bypass -File $s -Decks $d -Games 40 -Parallel 6 } -ArgumentList (Join-Path $root 'scripts\run-sims.ps1'), $pod
    while ($job.State -eq 'Running' -and (Get-Date) -lt $deadlineAt) { Start-Sleep -Seconds 20 }
    if ($job.State -eq 'Running') {
        # Deadline: stop the batch and its Forge processes.
        Stop-Job $job
        Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -and $_.CommandLine -match 'SimLauncher sim') -or ($_.Name -eq 'powershell.exe' -and $_.CommandLine -match 'run-sims\.ps1') } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    Remove-Job $job -Force
    $new = Get-ChildItem (Join-Path $root 'results') -Directory | Where-Object { $before -notcontains $_.Name } | Sort-Object CreationTime | Select-Object -Last 1
    $done = if ($new) { Get-Content (Join-Path $new.FullName 'progress.txt') -ErrorAction SilentlyContinue } else { '' }
    "$(Get-Date -Format 'HH:mm') | $pod | $($new.Name) | $done" | Add-Content $list
    if ((Get-Date) -ge $deadlineAt) { break }
}
