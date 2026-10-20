@echo off
rem Double-click to update this copy from GitHub, then rebuild. Close Forge first.
rem Works for a git clone and for a copy downloaded as a zip; your settings are kept.
rem
rem   Update.cmd          update and rebuild
rem   Update.cmd -Check   only say whether there is an update
rem   Update.cmd -Zip     update from the GitHub zip even in a git clone
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\update.ps1" %*
echo.
pause
