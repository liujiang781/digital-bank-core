@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Starting Digital Bank services...
powershell -ExecutionPolicy Bypass -File "%~dp0start-all.ps1"
echo.
echo Press any key to close this window (services keep running)...
pause >nul
