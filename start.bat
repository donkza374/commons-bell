@echo off
cd /d "%~dp0"
echo Starting NI Politics Tracker...
python server.py %*
pause
