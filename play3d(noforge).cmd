@echo off
rem Play without leaning on Forge's window.
rem
rem Forge still RUNS - it is the rules engine and always will be - but this mode says so on
rem screen every time Forge answers something the 3D table should own, and tallies it in any
rem bug report. Anything still falling through becomes a list instead of "it pulled me to the
rem other window again".
rem
rem   play3d(noforge).cmd            play, and name every hand-off to Forge
rem   play3d(noforge).cmd -CheckOnly report what is missing and stop
rem
rem Same as: play3d -Strict
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\setup.ps1" -NoForgeHelp %*
rem Stay open either way. A window that vanishes the moment it finishes cannot tell you whether
rem it worked, and the failures that matter most - a jar that will not load, a missing JDK - are
rem exactly the ones that used to close the window before anyone could read them.
echo.
if errorlevel 1 (
  echo Setup did not finish. The message above says what is missing.
) else (
  echo Done. You can close this window - the game keeps running without it.
)
echo.
pause
