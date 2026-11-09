@echo off
rem Creates the relay setup QR (see setup-qr.py). Double-click to run.
py -3 "%~dp0setup-qr.py" %*
echo.
pause
