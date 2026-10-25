#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${SCRIPT_DIR}/DalyBMS.jar"

# Alle Einstellungen kommen aus config.properties.
# Kommandozeilen-Parameter überschreiben die Config.
java -jar "${JAR}" "$@"
