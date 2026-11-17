@echo off
cd /d "%~dp0"
echo Starting Commons Bell...
python server.py %*
pause
