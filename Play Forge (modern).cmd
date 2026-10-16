@echo off
rem Double-click to play Forge's modern (mobile/Adventure-style) interface against the AI, with
rem this project's AI improvements, card fixes and game recording. See scripts\play.ps1 -Modern.
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0scripts\play.ps1" -Modern
