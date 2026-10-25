#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# Liegt neben DalyBMS.jar (in dist/) und findet die config.properties
# im selben Verzeichnis oder einer Ebene darueber.
# ============================================================

# Verzeichnis dieses Skripts (= dist/)
HERE="$(cd "$(dirname "$0")" && pwd)"

# JAR liegt immer im selben Verzeichnis wie dieses Skript
JAR="${HERE}/DalyBMS.jar"

if [ ! -f "${JAR}" ]; then
    echo "FEHLER: DalyBMS.jar nicht gefunden unter ${JAR}"
    echo "Bitte zuerst ./build.sh ausfuehren."
    exit 1
fi

# config.properties: neben der JAR, oder im Projektroot darueber
if [ -f "${HERE}/config.properties" ]; then
    CFGDIR="${HERE}"
elif [ -f "$(dirname "${HERE}")/config.properties" ]; then
    CFGDIR="$(dirname "${HERE}")"
else
    CFGDIR="${HERE}"
fi

# Arbeitsverzeichnis auf das config-Verzeichnis setzen,
# damit Config.java die Datei im Arbeitsverzeichnis findet.
cd "${CFGDIR}" || exit 1

echo "Starte DalyBMS aus ${CFGDIR} ..."
echo "Config: ${CFGDIR}/config.properties"
java -jar "${JAR}" "$@"
