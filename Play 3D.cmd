@echo off
rem One click: check what is needed, build whatever is missing, and start the 3D table.
rem Everything it needs is in this repo - Forge included - so a fresh clone just works.
rem Safe to run every time; each step is skipped when it is already done.
rem
rem   Play 3D.cmd            play
rem   Play 3D.cmd -Strict    also say on screen whenever Forge answers something the 3D
rem                          table should own, and tally it in any bug report
rem   Play 3D.cmd -NoUi3d    Forge's own window only
rem   Play 3D.cmd -CheckOnly report what is missing and stop
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\setup.ps1" %*
if errorlevel 1 (
  echo.
  echo Setup did not finish. The message above says what is missing.
  pause
)
