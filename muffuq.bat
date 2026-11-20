@echo off
rem Double-click entry point: runs the PowerShell toolbox without touching the system execution policy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\muffuq.ps1" %*
if errorlevel 1 pause
