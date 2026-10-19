$end = (Get-Date).Date.AddDays(1).AddHours(8)
while ((Get-Date) -lt $end) {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'SimLauncher sim' } | ForEach-Object { try { $h = Get-Process -Id $_.ProcessId; if ($h.PriorityClass -ne 'Idle') { $h.PriorityClass = 'Idle' }; if ([int64]$h.ProcessorAffinity -ne 15) { $h.ProcessorAffinity = [IntPtr]0x0F } } catch {} }
  if (-not (Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match 'run-until\.ps1' })) { break }
  Start-Sleep 20
}
