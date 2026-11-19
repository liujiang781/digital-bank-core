@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Stopping Digital Bank services...
powershell -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1"
echo.
pause >nul
