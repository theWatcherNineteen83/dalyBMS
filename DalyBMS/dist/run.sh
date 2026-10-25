#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# Wechselt ins Projektroot (wo config.properties liegt),
# damit Config.java die Datei automatisch findet.
# ============================================================

# Absoluter Pfad zum Skript
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Projektroot ermitteln
if [ -f "${SCRIPT_DIR}/config.properties" ]; then
    ROOT="${SCRIPT_DIR}"
elif [ -f "$(dirname "${SCRIPT_DIR}")/config.properties" ]; then
    ROOT="$(dirname "${SCRIPT_DIR}")"
else
    ROOT="${SCRIPT_DIR}"
fi

JAR="${ROOT}/dist/DalyBMS.jar"

if [ ! -f "${JAR}" ]; then
    echo "FEHLER: DalyBMS.jar nicht gefunden unter ${JAR}"
    echo "Bitte zuerst ./build.sh ausfuehren."
    exit 1
fi

cd "${ROOT}" || exit 1

echo "Starte DalyBMS aus ${ROOT} ..."
echo "Config: ${ROOT}/config.properties"
java -jar "${JAR}" "$@"
