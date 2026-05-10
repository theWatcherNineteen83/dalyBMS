#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${SCRIPT_DIR}/dist/DalyBMS.jar"
CONFIG="${SCRIPT_DIR}/config.properties"

# Falls aus dist/ gestartet (config.properties liegt im Projektroot)
if [ ! -f "${CONFIG}" ]; then
    CONFIG="$(dirname "${SCRIPT_DIR}")/config.properties"
fi

if [ ! -f "$JAR" ]; then
    echo "FEHLER: DalyBMS.jar nicht gefunden!"
    echo "Bitte zuerst ./build.sh ausführen."
    exit 1
fi

# ============================================================
# Konfiguration aus config.properties (falls vorhanden)
# ============================================================
if [ -f "${CONFIG}" ]; then
    echo "Lade Konfiguration: ${CONFIG}"
    # Shell-kompatibel sourcen (nur KEY=VALUE Zeilen)
    while IFS='=' read -r key value; do
        # Kommentare und Leerzeilen überspringen
        case "$key" in
            ''|\#*) continue ;;
        esac
        # Nur gültige Shell-Variablen exportieren
        key=$(echo "$key" | tr '.' '_' | tr -d ' ')
        value=$(echo "$value" | sed 's/[[:space:]]*#.*//' | xargs)
        export "$key=$value"
    done < "${CONFIG}"
fi

# ============================================================
# Konfiguration starten
# ============================================================
if [ -f "${CONFIG}" ] && [ "${mqtt_enabled}" = "true" ]; then
    # Option 1: REST + MQTT (laut config.properties)
    java -jar "${JAR}" \
        --rest \
        --mqtt \
        --verbose
else
    # Option 2: Nur REST Server (Standard)
    java -jar "${JAR}" \
        --rest \
        --verbose
fi
