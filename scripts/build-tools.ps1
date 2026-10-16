# Builds the project's Java tools from source: scripts\tools\*.java -> scripts\tools\classes,
# and the Java agent scripts\tools\evalcache-agent.jar (EvalCacheAgent + agent-manifest.txt).
# Neither output is in git, so run this after cloning and after pulling changes to any
# scripts\tools\*.java file.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-tools.ps1
#
# Forge must not be running for the jar to be replaced (Windows locks it). If it is, the classes
# are still rebuilt; scripts\tools\classes holds EvalCacheAgent*.class too, and the classpath
# copy wins over the jar's, so new agent hooks still install.

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$jdk   = 'C:\Program Files\BellSoft\LibericaJDK-17\bin'
$forge = Join-Path $root 'forge-installer-2.0.15\forge-gui-desktop-2.0.15-jar-with-dependencies.jar'
$tools = Join-Path $PSScriptRoot 'tools'
$out   = Join-Path $tools 'classes'

if (-not (Test-Path "$jdk\javac.exe")) { throw "JDK 17 not found at $jdk (install BellSoft Liberica JDK 17 or edit `$jdk)." }
if (-not (Test-Path $forge)) { throw "Forge 2.0.15 not found at $forge (see CLAUDE.md, Setup)." }
New-Item -ItemType Directory -Force $out | Out-Null

# All sources in one javac call (they reference each other). The agent uses the JDK's internal
# copy of ASM, hence --add-exports.
$src = (Get-ChildItem (Join-Path $tools '*.java')).FullName
& "$jdk\javac.exe" -nowarn -encoding UTF-8 --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED `
    -cp "$forge;$out" -d $out $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Write-Host "Compiled $($src.Count) sources into $out"

# Agent jar: only the EvalCacheAgent classes, with Premain-Class from agent-manifest.txt.
$stage = Join-Path $root 'scratch\agent-build'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $out 'EvalCacheAgent*.class') $stage
Push-Location $stage
try {
    & "$jdk\jar.exe" cfm (Join-Path $tools 'evalcache-agent.jar') (Join-Path $tools 'agent-manifest.txt') EvalCacheAgent*.class
    if ($LASTEXITCODE -eq 0) { Write-Host "Built $tools\evalcache-agent.jar" }
    else { Write-Warning "Could not write evalcache-agent.jar (Forge running?). The classes copy in $out is current." }
} finally { Pop-Location }
