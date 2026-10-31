@echo off
rem One click: the 3D table AND Forge's own window, side by side, with every grab of attention
rem logged. This is the one to use for a session spent working out what keeps pulling the eye
rem off the 3D table (the owner and a friend, 2026-10-31).
rem
rem It is a separate file from "Play 3D.cmd" on purpose: it turns OFF the auto-minimise of
rem Forge's window, which is right for this and wrong for ordinary play, and ordinary play is
rem what the other owner's double-click has to keep doing.
rem
rem What it changes, both only for this run:
rem   hideforge=false  Forge's window stays up instead of being minimised 1.5s into the game.
rem                    Without this the table and the two of you would be fighting over the
rem                    same window all session, and half of what got flagged would be us.
rem   strict=true      every decision Forge answers is named on screen and in the console, so
rem                    a flag is "assignGenericAmount" rather than a time of day.
rem
rem The attention log needs no switch and is always on now: ForgeWindow watches its own frame
rem and prints a line every time it comes forward, saying why - or UNEXPLAINED, with the prompt
rem that was showing, when nothing we know about caused it. Those are the ones worth having.
rem
rem Afterwards:  powershell -File scripts\attention-report.ps1
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\setup.ps1" -BothWindows %*
echo.
if errorlevel 1 (
  echo Setup did not finish. The message above says what is missing.
) else (
  echo Done. You can close this window - the game keeps running without it.
  echo.
  echo When you have finished playing:  powershell -File scripts\attention-report.ps1
)
echo.
pause
