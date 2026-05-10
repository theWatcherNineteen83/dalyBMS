#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${SCRIPT_DIR}/dist/DalyBMS.jar"

# Falls aus dist/ gestartet
if [ ! -f "$JAR" ]; then
    JAR="${SCRIPT_DIR}/DalyBMS.jar"
fi

if [ ! -f "$JAR" ]; then
    echo "FEHLER: DalyBMS.jar nicht gefunden!"
    echo "Bitte zuerst ./build.sh ausführen."
    exit 1
fi

# ============================================================
# Standard-Konfiguration
# (Passe diese Werte an dein Setup an)
# ============================================================
DEVICE="/dev/ttyUSB0"
HTTP_PORT="8080"
POLL_INTERVAL="30"
MQTT_BROKER="192.168.20.100"
MQTT_PORT="1883"
MQTT_USER="dalybms"
MQTT_PASSWORD="eveakku"
MQTT_TOPIC="daly_bms"

# ============================================================
# Modus wählen (auskommentieren was nicht gebraucht wird)
# ============================================================

# Option 1: Nur REST Server
# java -jar "${JAR}" \
#     -d ${DEVICE} \
#     --rest \
#     --http-port ${HTTP_PORT} \
#     --poll-interval ${POLL_INTERVAL}

# Option 2: REST + MQTT (empfohlen)
java -jar "${JAR}" \
    -d ${DEVICE} \
    --rest \
    --http-port ${HTTP_PORT} \
    --poll-interval ${POLL_INTERVAL} \
    --mqtt \
    --mqtt-broker ${MQTT_BROKER} \
    --mqtt-port ${MQTT_PORT} \
    --mqtt-user ${MQTT_USER} \
    --mqtt-password ${MQTT_PASSWORD} \
    --mqtt-topic ${MQTT_TOPIC} \
    --verbose

# Option 3: Nur einmal auslesen
# java -jar "${JAR}" -d ${DEVICE}

# Option 4: Einmal auslesen + MQTT
# java -jar "${JAR}" \
#     -d ${DEVICE} \
#     --mqtt \
#     --mqtt-broker ${MQTT_BROKER} \
#     --mqtt-user ${MQTT_USER} \
#     --mqtt-password ${MQTT_PASSWORD}