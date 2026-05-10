@echo off
REM ============================================================
REM Daly BMS - Build Script (Windows)
REM ============================================================
REM
REM Verwendete JARs:
REM   - jSerialComm-2.11.4.jar
REM   - json-20251224.jar
REM   - org.eclipse.paho.client.mqttv3-1.2.6-20201211.050620-2.jar
REM
REM ============================================================

setlocal enabledelayedexpansion

set PROJECT_DIR=%~dp0
set SRC_DIR=%PROJECT_DIR%src
set RES_DIR=%PROJECT_DIR%resources
set LIB_DIR=%PROJECT_DIR%lib
set OUT_DIR=%PROJECT_DIR%out
set DIST_DIR=%PROJECT_DIR%dist
set MAIN_CLASS=de.dalybms.Main
set JAR_NAME=DalyBMS.jar

REM ---- JAR Dateinamen ----
set JAR_SERIAL=jSerialComm-2.11.4.jar
set JAR_JSON=json-20251224.jar
set JAR_MQTT=org.eclipse.paho.client.mqttv3-1.2.6-20201211.050620-2.jar

echo.
echo ============================================
echo   Daly BMS - Build (Windows)
echo ============================================
echo.

REM ---- Java pruefen ----
echo [1/7] Java-Version pruefen...
where javac >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo   FEHLER: javac nicht gefunden!
    echo   Bitte JDK installieren und JAVA_HOME / PATH setzen.
    echo.
    echo   Download: https://adoptium.net/
    echo.
    exit /b 1
)
javac -version
java -version 2>&1 | findstr /i "version"
echo.

REM ---- Abhaengigkeiten pruefen ----
echo [2/7] Abhaengigkeiten pruefen...
set MISSING=0

if not exist "%LIB_DIR%\%JAR_SERIAL%" (
    echo   FEHLT: lib\%JAR_SERIAL%
    set MISSING=1
) else (
    echo   OK:    lib\%JAR_SERIAL%
)

if not exist "%LIB_DIR%\%JAR_JSON%" (
    echo   FEHLT: lib\%JAR_JSON%
    set MISSING=1
) else (
    echo   OK:    lib\%JAR_JSON%
)

if not exist "%LIB_DIR%\%JAR_MQTT%" (
    echo   FEHLT: lib\%JAR_MQTT%
    set MISSING=1
) else (
    echo   OK:    lib\%JAR_MQTT%
)

if %MISSING%==1 (
    echo.
    echo   FEHLER: Es fehlen JAR-Dateien im lib\ Ordner!
    echo   Bitte lade die fehlenden JARs herunter und lege sie in lib\ ab.
    echo.
    echo   Downloads:
    echo     https://fazecast.github.io/jSerialComm/
    echo     https://repo1.maven.org/maven2/org/json/json/
    echo     https://repo1.maven.org/maven2/org/eclipse/paho/org.eclipse.paho.client.mqttv3/
    echo.
    exit /b 1
)
echo.

REM ---- Quelldateien pruefen ----
echo [3/7] Quelldateien pruefen...
set FILE_COUNT=0
for /r "%SRC_DIR%" %%f in (*.java) do (
    set /a FILE_COUNT+=1
)
if %FILE_COUNT%==0 (
    echo   FEHLER: Keine .java Dateien in src\ gefunden!
    exit /b 1
)
echo   %FILE_COUNT% Java-Dateien gefunden
echo.

REM ---- Aufraeumen ----
echo [4/7] Alte Build-Artefakte loeschen...
if exist "%OUT_DIR%" (
    rmdir /s /q "%OUT_DIR%"
    echo   out\ geloescht
)
if exist "%DIST_DIR%" (
    rmdir /s /q "%DIST_DIR%"
    echo   dist\ geloescht
)
mkdir "%OUT_DIR%"
mkdir "%DIST_DIR%\lib"
echo.

REM ---- Classpath zusammenbauen ----
set CLASSPATH=%LIB_DIR%\%JAR_SERIAL%;%LIB_DIR%\%JAR_JSON%;%LIB_DIR%\%JAR_MQTT%

echo [5/7] Kompilieren...
echo   Classpath:
echo     %JAR_SERIAL%
echo     %JAR_JSON%
echo     %JAR_MQTT%
echo.

REM ---- Quelldateien sammeln ----
if exist "%OUT_DIR%\sources.txt" del "%OUT_DIR%\sources.txt"
for /r "%SRC_DIR%" %%f in (*.java) do (
    echo %%f>> "%OUT_DIR%\sources.txt"
)

REM ---- Kompilieren ----
javac ^
    -cp "%CLASSPATH%" ^
    -d "%OUT_DIR%" ^
    -source 11 ^
    -target 11 ^
    -encoding UTF-8 ^
    @"%OUT_DIR%\sources.txt"

if %errorlevel% neq 0 (
    echo.
    echo   ============================================
    echo   FEHLER beim Kompilieren!
    echo   ============================================
    echo.
    echo   Pruefe:
    echo   - Sind alle .java Dateien in src\de\dalybms\ vorhanden?
    echo   - Stimmen die JAR-Versionen?
    echo   - Gibt es Syntaxfehler im Code?
    echo.
    del "%OUT_DIR%\sources.txt" 2>nul
    exit /b 1
)
del "%OUT_DIR%\sources.txt" 2>nul
echo   Kompilierung erfolgreich!
echo.

REM ---- Ressourcen kopieren ----
echo [6/7] Ressourcen kopieren...
if exist "%RES_DIR%\openapi.yaml" (
    copy /y "%RES_DIR%\openapi.yaml" "%OUT_DIR%\" >nul
    echo   openapi.yaml kopiert
) else (
    echo   WARNUNG: resources\openapi.yaml nicht gefunden!
    echo   Swagger UI wird ohne OpenAPI-Spec starten.
)
echo.

REM ---- JAR erstellen ----
echo [7/7] JAR erstellen...

REM Manifest mit Classpath erstellen
echo Manifest-Version: 1.0> "%OUT_DIR%\MANIFEST.MF"
echo Main-Class: %MAIN_CLASS%>> "%OUT_DIR%\MANIFEST.MF"
echo Class-Path: lib/%JAR_SERIAL% lib/%JAR_JSON% lib/%JAR_MQTT%>> "%OUT_DIR%\MANIFEST.MF"

REM JAR packen
cd "%OUT_DIR%"
jar cfm "%DIST_DIR%\%JAR_NAME%" "%OUT_DIR%\MANIFEST.MF" .
cd "%PROJECT_DIR%"

if %errorlevel% neq 0 (
    echo   FEHLER beim JAR-Erstellen!
    exit /b 1
)

REM Libs in dist kopieren
copy /y "%LIB_DIR%\%JAR_SERIAL%" "%DIST_DIR%\lib\" >nul
copy /y "%LIB_DIR%\%JAR_JSON%" "%DIST_DIR%\lib\" >nul
copy /y "%LIB_DIR%\%JAR_MQTT%" "%DIST_DIR%\lib\" >nul

REM ---- Windows Start-Script erstellen ----
(
echo @echo off
echo REM ============================================================
echo REM Daly BMS - Start Script ^(Windows^)
echo REM ============================================================
echo.
echo set SCRIPT_DIR=%%~dp0
echo set JAR=%%SCRIPT_DIR%%DalyBMS.jar
echo.
echo java -jar "%%JAR%%" %%*
) > "%DIST_DIR%\run.bat"

REM ---- Linux Start-Script erstellen ----
(
echo #!/bin/bash
echo SCRIPT_DIR="$^(cd "$^(dirname "$0"^)" ^&^& pwd^)"
echo java -jar "${SCRIPT_DIR}/DalyBMS.jar" "$@"
) > "%DIST_DIR%\run.sh"

REM ---- Ergebnis anzeigen ----
echo.
echo ============================================
echo   Build erfolgreich!
echo ============================================
echo.
echo   Erstellte Dateien:
echo.
echo     dist\
echo       DalyBMS.jar
echo       run.bat
echo       run.sh
echo       lib\
echo         %JAR_SERIAL%
echo         %JAR_JSON%
echo         %JAR_MQTT%
echo.
echo ============================================
echo   Starten (Windows):
echo ============================================
echo.
echo   REM Nur auslesen (einmal):
echo   java -jar dist\DalyBMS.jar -d COM3
echo.
echo   REM REST Server:
echo   java -jar dist\DalyBMS.jar -d COM3 --rest --http-port 8080
echo.
echo   REM REST + MQTT:
echo   java -jar dist\DalyBMS.jar -d COM3 --rest --mqtt ^
echo       --mqtt-broker 192.168.20.100 ^
echo       --mqtt-user dalybms --mqtt-password eveakku
echo.
echo ============================================
echo   Starten (Raspberry Pi):
echo ============================================
echo.
echo   REM Kopiere dist\ auf den Pi:
echo   scp -r dist\* orangepi@192.168.20.XXX:~/dalybms/
echo.
echo   REM Auf dem Pi:
echo   java -jar DalyBMS.jar -d /dev/ttyUSB0 --rest --mqtt ^
echo       --mqtt-broker 192.168.20.100 ^
echo       --mqtt-user dalybms --mqtt-password eveakku
echo.
echo ============================================

endlocal