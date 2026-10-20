# Update this copy from GitHub, then rebuild. Run by "Update.cmd" (double-click).
#
# Works for both ways a friend gets the project:
#   - a git clone (git installed):  fetch + fast-forward pull; settings Forge rewrote are kept;
#   - a downloaded zip (no git):    downloads the latest zip and copies it over this folder,
#                                   keeping your settings, card pictures and the portable JDK.
# Then rebuilds the AI hooks and the 3D table (scripts\setup.ps1 -CheckOnly -Rebuild), which also
# fetches a portable JDK the first time if the PC has none.
#
# Preflight first, so it never half-updates: Forge must be closed, GitHub must be reachable.
#
#   powershell -ExecutionPolicy Bypass -File scripts\update.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\update.ps1 -Check     # only say whether there is an update
#   powershell -ExecutionPolicy Bypass -File scripts\update.ps1 -Zip       # use the zip route even with git

param(
    [switch] $Check,
    [switch] $Zip
)

$ErrorActionPreference = 'Stop'
$root    = Split-Path -Parent $PSScriptRoot
$repo    = 'gmrspvp-bot/MTG'
$branch  = 'main'
$verFile = Join-Path $root 'tools\update-version.txt'

function Ok($t)   { Write-Host "  [ok]   $t" -ForegroundColor Green }
function Work($t) { Write-Host "  [..]   $t" -ForegroundColor Cyan }
function Bad($t)  { Write-Host "  [!]    $t" -ForegroundColor Red }
function Note($t) { Write-Host "         $t" -ForegroundColor DarkGray }

Write-Host ""
Write-Host "MTG 3D table - update" -ForegroundColor Cyan
Write-Host ""

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'      # the progress bar makes downloads several times slower

# ---- Preflight ---------------------------------------------------------------------------------
# 1. Forge (or the 3D table) running from this folder would hold the jars and classes open, and
#    an update under a running game is how a copy ends up half old, half new.
$running = @()
try {
    $running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
} catch { }
if ($running.Count -gt 0) {
    Bad "Forge is still running from this folder. Close Forge (and the 3D table), then run Update again."
    exit 1
}
Ok "Forge is not running"

# 2. GitHub reachable, and what the latest version is.
$latest = $null
try {
    $info = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/commits/$branch" -Headers @{ 'User-Agent' = 'mtg-update' } -TimeoutSec 30
    $latest = $info.sha
    Ok ("GitHub reachable - latest version " + $latest.Substring(0, 8) + " (" + $info.commit.committer.date + ")")
} catch {
    Bad "Could not reach GitHub: $($_.Exception.Message)"
    Note "Check the internet connection and try again."
    exit 1
}

# ---- Which route --------------------------------------------------------------------------------
$git = $null
foreach ($g in @('C:\Program Files\Git\cmd\git.exe', 'C:\Program Files (x86)\Git\cmd\git.exe')) { if (Test-Path $g) { $git = $g; break } }
if (-not $git) { $c = Get-Command git -ErrorAction SilentlyContinue; if ($c) { $git = $c.Source } }
$useGit = (-not $Zip) -and $git -and (Test-Path (Join-Path $root '.git'))

if ($useGit) {
    # ---- Git route ------------------------------------------------------------------------------
    Ok "Git copy - updating with git"
    & $git -C $root fetch -q origin $branch
    if ($LASTEXITCODE -ne 0) { Bad "git fetch failed"; exit 1 }
    $here = (& $git -C $root rev-parse HEAD).Trim()
    $there = (& $git -C $root rev-parse "origin/$branch").Trim()
    if ($here -eq $there) {
        Ok "Already up to date"
        if ($Check) { exit 0 }
    } else {
        $n = (& $git -C $root rev-list --count "HEAD..origin/$branch").Trim()
        Work "$n new change(s) on GitHub"
        if ($Check) { exit 0 }
        # Forge rewrites this one on every start; setup puts our copy back anyway.
        & $git -C $root checkout -q -- 'forge-installer-2.0.15/forge.profile.properties' 2>$null
        # Anything else changed locally (Forge's preferences, mostly) is kept: put aside, then back.
        $dirty = @(& $git -C $root status --porcelain --untracked-files=no)
        $stashed = $false
        if ($dirty.Count -gt 0) {
            & $git -C $root stash push -q -m ("update.cmd " + (Get-Date -Format s))
            if ($LASTEXITCODE -eq 0) { $stashed = $true; Note "Your local changes are put aside for the update." }
        }
        & $git -C $root pull -q --ff-only origin $branch
        if ($LASTEXITCODE -ne 0) {
            Bad "Could not update with a fast-forward pull (this copy has commits of its own)."
            if ($stashed) { & $git -C $root stash pop -q }
            Note "Ask in the group chat, or run Update with -Zip to copy the latest over this folder."
            exit 1
        }
        if ($stashed) {
            & $git -C $root stash pop -q
            if ($LASTEXITCODE -ne 0) {
                # Their change and ours touched the same file: keep GitHub's, leave theirs in the stash.
                & $git -C $root checkout -q -- .
                Note "Some of your local changes clashed with the update; GitHub's version was kept."
                Note "Yours are still saved: 'git stash list' shows them."
            }
        }
        Ok "Updated to $($there.Substring(0, 8))"
    }
} else {
    # ---- Zip route ------------------------------------------------------------------------------
    Ok ("Downloaded copy - updating from the GitHub zip" + $(if ($git -and (Test-Path (Join-Path $root '.git'))) { " (-Zip)" } else { "" }))
    $have = if (Test-Path $verFile) { (Get-Content $verFile -TotalCount 1).Trim() } else { '' }
    if ($have -eq $latest) {
        Ok "Already up to date"
        if ($Check) { exit 0 }
    } else {
        if ($have) { Work "Your copy is $($have.Substring(0, [Math]::Min(8, $have.Length))); updating to $($latest.Substring(0, 8))" }
        else { Work "Version of this copy unknown (first update) - updating to $($latest.Substring(0, 8))" }
        if ($Check) { exit 0 }
        $tmp = Join-Path $env:TEMP ("mtg-update-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        try {
            $zipFile = Join-Path $tmp 'mtg.zip'
            Work "Downloading the latest version (a few hundred MB - Forge is included)..."
            Invoke-WebRequest -Uri "https://github.com/$repo/archive/refs/heads/$branch.zip" -OutFile $zipFile -UseBasicParsing
            Work "Unpacking..."
            Expand-Archive -Path $zipFile -DestinationPath $tmp -Force
            $src = Get-ChildItem -Directory $tmp | Select-Object -First 1
            if (-not $src) { throw "the download did not contain the project" }
            $s = $src.FullName

            Work "Copying the new version over this folder..."
            # Everything, adding and replacing - but never your settings, card pictures, portable
            # JDK, build output or recorded games beyond what GitHub has.
            $keep = @(
                (Join-Path $root 'forge-data\preferences'),
                (Join-Path $root 'forge-data\cache'),
                (Join-Path $root 'tools\jdk'),
                (Join-Path $root 'scripts\tools\classes'),
                (Join-Path $root 'ui3d\classes'),
                (Join-Path $root 'ui3d\natives'),
                (Join-Path $root 'results')
            )
            $opts = @('/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:2', '/W:1', '/XD') + $keep
            # preferences: copied only if this copy has none yet (a first update from an old zip)
            if (-not (Test-Path (Join-Path $root 'forge-data\preferences'))) { $opts = $opts | Where-Object { $_ -ne (Join-Path $root 'forge-data\preferences') } }
            & robocopy $s $root @opts | Out-Null
            if ($LASTEXITCODE -ge 8) { throw "copying failed (robocopy $LASTEXITCODE)" }
            # Source folders are MIRRORED, so a file removed on GitHub is removed here too - a
            # leftover .java would still be compiled and could break the build.
            foreach ($dir in @('scripts\tools', 'ui3d\src')) {
                & robocopy (Join-Path $s $dir) (Join-Path $root $dir) /MIR /NFL /NDL /NJH /NJS /NP /R:2 /W:1 /XD classes /XF *.class *.jar | Out-Null
                if ($LASTEXITCODE -ge 8) { throw "mirroring $dir failed (robocopy $LASTEXITCODE)" }
            }
            New-Item -ItemType Directory -Force -Path (Split-Path $verFile) | Out-Null
            Set-Content -Path $verFile -Value $latest -Encoding ASCII
            Ok "Updated to $($latest.Substring(0, 8))"
        } catch {
            Bad "Update failed: $($_.Exception.Message)"
            Note "Nothing was half-copied into the code folders unless the message says so; run Update again."
            exit 1
        } finally {
            Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
        }
    }
}

# ---- Rebuild -------------------------------------------------------------------------------------
# Forced: files from a zip keep GitHub's dates, which can be older than the last build, so
# "newer source than classes" would miss them. Also fetches a portable JDK if the PC has none.
Write-Host ""
Work "Rebuilding the AI hooks and the 3D table..."
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'setup.ps1') -CheckOnly -Rebuild
if ($LASTEXITCODE -ne 0) { Bad "The rebuild did not finish - the message above says why."; exit 1 }
Write-Host ""
Write-Host "Up to date. Double-click 'Play 3D.cmd' to play." -ForegroundColor Green
exit 0
