#!/bin/bash

# ============================================================
# Daly BMS - Build Script
# ============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="${PROJECT_DIR}/src"
RES_DIR="${PROJECT_DIR}/resources"
LIB_DIR="${PROJECT_DIR}/lib"
OUT_DIR="${PROJECT_DIR}/out"
DIST_DIR="${PROJECT_DIR}/dist"
MAIN_CLASS="de.dalybms.Main"
JAR_NAME="DalyBMS.jar"

echo "============================================"
echo "  Daly BMS - Build"
echo "============================================"
echo ""

# ---- Java-Version prüfen ----
echo "[1/7] Java-Version prüfen..."
if ! command -v javac &> /dev/null; then
    echo "FEHLER: javac nicht gefunden!"
    echo "Installiere JDK: sudo apt install default-jdk"
    exit 1
fi
javac -version
java -version 2>&1 | head -1
echo ""

# ---- Abhängigkeiten prüfen ----
echo "[2/7] Abhängigkeiten prüfen..."
MISSING=0
for jar in jSerialComm-2.11.4.jar org.eclipse.paho.client.mqttv3-1.2.6-20201211.050620-2.jar json-20251224.jar; do
    if [ ! -f "${LIB_DIR}/${jar}" ]; then
        echo "  FEHLT: lib/${jar}"
        MISSING=1
    else
        echo "  OK:    lib/${jar}"
    fi
done

if [ $MISSING -eq 1 ]; then
    echo ""
    echo "Fehlende JARs herunterladen? (j/n)"
    read -r answer
    if [ "$answer" = "j" ] || [ "$answer" = "J" ]; then
        mkdir -p "${LIB_DIR}"
        echo "Lade jSerialComm..."
        wget -q -O "${LIB_DIR}/jSerialComm-2.11.4.jar" \
            "https://repo1.maven.org/maven2/com/fazecast/jSerialComm/2.11.4/jSerialComm-2.11.4.jar"
        echo "Lade Paho MQTT..."
        wget -q -O "${LIB_DIR}/org.eclipse.paho.client.mqttv3-1.2.6-20201211.050620-2.jar" \
            "https://repo1.maven.org/maven2/org/eclipse/paho/org.eclipse.paho.client.mqttv3/1.2.5/org.eclipse.paho.client.mqttv3-1.2.5.jar"
        echo "Lade JSON..."
        wget -q -O "${LIB_DIR}/json-20251224.jar" \
            "https://repo1.maven.org/maven2/org/json/json/20251224/json-20251224.jar"
        echo "Alle Abhängigkeiten heruntergeladen!"
    else
        echo "Bitte lade die JARs manuell herunter."
        exit 1
    fi
fi
echo ""

# ---- Alte Build-Artefakte löschen ----
echo "[3/7] Alte Build-Artefakte löschen..."
rm -rf "${OUT_DIR}"
rm -rf "${DIST_DIR}"
mkdir -p "${OUT_DIR}"
mkdir -p "${DIST_DIR}/lib"
echo ""

# ---- Classpath zusammenbauen ----
CLASSPATH=""
for jar in "${LIB_DIR}"/*.jar; do
    if [ -n "$CLASSPATH" ]; then
        CLASSPATH="${CLASSPATH}:"
    fi
    CLASSPATH="${CLASSPATH}${jar}"
done
echo "[4/7] Classpath: ${CLASSPATH}"
echo ""

# ---- Kompilieren ----
echo "[5/7] Kompilieren..."
JAVA_FILES=$(find "${SRC_DIR}" -name "*.java")
FILE_COUNT=$(echo "${JAVA_FILES}" | wc -l)
echo "  ${FILE_COUNT} Java-Dateien gefunden"

javac \
    -cp "${CLASSPATH}" \
    -d "${OUT_DIR}" \
    -source 11 \
    -target 11 \
    -encoding UTF-8 \
    ${JAVA_FILES}

echo "  Kompilierung erfolgreich!"
echo ""

# ---- Ressourcen kopieren ----
echo "[6/7] Ressourcen kopieren..."
if [ -d "${RES_DIR}" ]; then
    cp -r "${RES_DIR}"/* "${OUT_DIR}/"
    echo "  openapi.yaml kopiert"
fi
echo ""

# ---- JAR erstellen ----
echo "[7/7] JAR erstellen..."

# Manifest erstellen
MANIFEST_FILE="${OUT_DIR}/MANIFEST.MF"
cat > "${MANIFEST_FILE}" << EOF
Manifest-Version: 1.0
Main-Class: ${MAIN_CLASS}
Class-Path: lib/jSerialComm-2.11.4.jar lib/org.eclipse.paho.client.mqttv3-1.2.6-20201211.050620-2.jar lib/json-20251224.jar
EOF

cd "${OUT_DIR}"
jar cfm "${DIST_DIR}/${JAR_NAME}" "${MANIFEST_FILE}" .
cd "${PROJECT_DIR}"

# Libs in dist kopieren
cp "${LIB_DIR}"/*.jar "${DIST_DIR}/lib/"

# config.properties nach dist kopieren (falls vorhanden)
if [ -f "${PROJECT_DIR}/config.properties" ]; then
    cp "${PROJECT_DIR}/config.properties" "${DIST_DIR}/config.properties"
fi

# Start-Script in dist kopieren
cat > "${DIST_DIR}/run.sh" << 'RUNEOF'
#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# Wechselt ins Projektroot (wo config.properties liegt),
# damit Config.java die Datei automatisch findet.
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Projektroot ermitteln (config.properties liegt im Projektroot)
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
RUNEOF
chmod +x "${DIST_DIR}/run.sh"

# Größe anzeigen
JAR_SIZE=$(du -sh "${DIST_DIR}/${JAR_NAME}" | cut -f1)
TOTAL_SIZE=$(du -sh "${DIST_DIR}" | cut -f1)

echo ""
echo "============================================"
echo "  Build erfolgreich!"
echo "============================================"
echo "  JAR:   dist/${JAR_NAME} (${JAR_SIZE})"
echo "  Gesamt: dist/ (${TOTAL_SIZE})"
echo ""
echo "  Inhalt von dist/:"
find "${DIST_DIR}" -type f | sort | while read f; do
    echo "    ${f#${PROJECT_DIR}/}"
done
echo ""
echo "============================================"
echo "  Starten:"
echo "============================================"
echo ""
echo "  # Nur auslesen (einmal):"
echo "  java -jar dist/DalyBMS.jar -d /dev/ttyUSB0"
echo ""
echo "  # REST Server:"
echo "  java -jar dist/DalyBMS.jar -d /dev/ttyUSB0 --rest"
echo ""
echo "  # REST + MQTT:"
echo "  java -jar dist/DalyBMS.jar -d /dev/ttyUSB0 --rest --mqtt \\"
echo "      --mqtt-broker 192.168.20.100 \\"
echo "      --mqtt-user dalybms --mqtt-password eveakku"
echo ""
echo "  # Oder via Script:"
echo "  dist/run.sh -d /dev/ttyUSB0 --rest"
echo ""
echo "============================================"