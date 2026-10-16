# Rotating 40-game pods until a deadline (no batch starts after $LastStart; anything still running
# at $Deadline is stopped). -Light: limits this script and everything it starts to 4 CPU threads
# (children inherit the affinity) at idle priority. Run folders -> scratch\<Label>-runs.txt
param([string]$LastStart = '16:40', [string]$Deadline = '17:00', [int]$Parallel = 3, [switch]$Light, [string]$Label = 'afternoon',
      [string[]]$Pods = @(
        'Captain America v6,Mr House v4,Doom Prevails v3,Tidus v3',
        'Deadpool v3,Mr House v4,Doom Prevails v3,Tidus v3',
        'Deadpool v3,Captain America v6,Doom Prevails v3,Tidus v3',
        'Deadpool v3,Captain America v6,Mr House v4,Tidus v3',
        'Deadpool v3,Captain America v6,Mr House v4,Doom Prevails v3'),
      # Optional JVM options per pod (same rotation as -Pods), e.g. for A/B tests of a -D switch.
      [string[]]$PodJvmArgs = @())
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\dmara\Claude'
if ($Light) { $me = Get-Process -Id $PID; $me.ProcessorAffinity = [IntPtr]0x0F; $me.PriorityClass = 'Idle' }
$list = Join-Path $root "scratch\$Label-runs.txt"
$today = Get-Date -Format 'yyyy-MM-dd'
$lastStartAt = [datetime]"$today $LastStart"; $deadlineAt = [datetime]"$today $Deadline"
# Deadline already past today = tomorrow morning (an overnight run).
if ($deadlineAt -lt (Get-Date)) { $lastStartAt = $lastStartAt.AddDays(1); $deadlineAt = $deadlineAt.AddDays(1) }
$i = 0
while ((Get-Date) -lt $lastStartAt) {
    $pod = $Pods[$i % $Pods.Count]
    $jvm = if ($PodJvmArgs.Count) { $PodJvmArgs[$i % $PodJvmArgs.Count] } else { '' }
    $i++
    $before = @(Get-ChildItem (Join-Path $root 'results') -Directory | ForEach-Object Name)
    $simArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'scripts\run-sims.ps1'),
            '-Decks', "`"$pod`"", '-Games', '40', '-Parallel', "$Parallel")
    if ($jvm) { $simArgs += @('-JvmArgs', "`"$jvm`"") }
    $p = Start-Process powershell -ArgumentList $simArgs -PassThru -WindowStyle Hidden
    if ($Light) { try { $p.PriorityClass = 'Idle' } catch {} }
    while (-not $p.HasExited -and (Get-Date) -lt $deadlineAt) {
        Start-Sleep -Seconds 20
        if ($Light) {   # run-sims sets its java processes to BelowNormal; keep them at Idle
            # Sim processes only - never the user's own Forge window.
            Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'SimLauncher sim' } |
                ForEach-Object { try { $j = Get-Process -Id $_.ProcessId; if ($j.PriorityClass -ne 'Idle') { $j.PriorityClass = 'Idle' } } catch {} }
        }
    }
    if (-not $p.HasExited) {
        Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -and $_.CommandLine -match 'SimLauncher sim') -or ($_.Name -eq 'powershell.exe' -and $_.CommandLine -match 'run-sims\.ps1') } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    $new = Get-ChildItem (Join-Path $root 'results') -Directory | Where-Object { $before -notcontains $_.Name } | Sort-Object CreationTime | Select-Object -Last 1
    $done = if ($new) { Get-Content (Join-Path $new.FullName 'progress.txt') -ErrorAction SilentlyContinue } else { '' }
    "$(Get-Date -Format 'HH:mm') | $pod | $($new.Name) | $done | $jvm" | Add-Content $list
    if ((Get-Date) -ge $deadlineAt) { break }
}
