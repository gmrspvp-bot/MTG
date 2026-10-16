param([string[]]$Cards, [string[]]$Phases = @('MAIN1','MAIN2'), [int]$TimeoutSec = 90, [string]$Battlefield = 'Llanowar Elves;Savannah Lions;Forest;Forest;Island;Island;Plains;Plains;Mountain;Mountain;Swamp;Swamp')
# Asks Forge's AI whether it would cast each card on a fixed board, one java process per
# check, each killed after $TimeoutSec. Run from forge-installer-2.0.15.
$t = (Resolve-Path '..\scripts\tools').Path
$jdk = 'C:\Program Files\BellSoft\LibericaJDK-17\bin'
$out = 'C:\Users\dmara\Claude\scratch\probe-out'
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($c in $Cards) { foreach ($ph in $Phases) {
    $state = Join-Path $out 'state.txt'
    @("[state]", "turn=9", "activeplayer=ai", "activephase=$ph", "humanlife=40", "ailife=40",
      "humanbattlefield=Grizzly Bears;Hill Giant;Forest;Forest;Forest;Forest",
      "aibattlefield=$Battlefield", "aihand=$c") | Set-Content $state -Encoding ASCII
    $log = Join-Path $out 'run.log'
    $p = Start-Process -FilePath "$jdk\java.exe" -ArgumentList "-Dforge.aipatches=catch,counterall,threat,rampfirst -cp `"$t\classes;forge-gui-desktop-2.0.15-jar-with-dependencies.jar`" AiCardTest `"$state`" `"$c`"" `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    try { $p.PriorityClass = 'BelowNormal' } catch { }
    if (-not $p.WaitForExit($TimeoutSec * 1000)) { Stop-Process -Id $p.Id -Force; [void]$p.WaitForExit(10000); $status = 'TIMEOUT' } else { $status = 'ok' }
    $o = Get-Content $log -ErrorAction SilentlyContinue
    $first = $o | Select-String '^\s+canPlay=' | Select-Object -First 1
    $would = $o | Select-String 'AI WOULD PLAY' | Select-Object -First 1
    $card = $o | Select-String '^CARD ' | Select-Object -First 1
    [pscustomobject]@{
        Card = $c; Phase = $ph; Run = $status
        Found = [bool]$card
        Spell = if ($first) { ($first.Line.Trim() -split ' :: ')[0] } else { '-' }
        Would = if ($would) { $w = $would.Line -replace '.*AI WOULD PLAY: ', ''; $w.Substring(0, [math]::Min(45, $w.Length)) } else { '-' }
    }
} }
