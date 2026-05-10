@echo off
REM ============================================================
REM Daly BMS - Start Script (Windows)
REM ============================================================

set SCRIPT_DIR=%~dp0
set JAR=%SCRIPT_DIR%DalyBMS.jar

java -jar "%JAR%" %*
