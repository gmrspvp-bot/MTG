@echo off
rem Double-click to play Forge yourself against AI opponents that use this project's
rem AI patches and card fixes. See scripts\play.ps1.
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0scripts\play.ps1"
