#!/bin/bash
# ============================================================
# Daly BMS - Start Script
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${SCRIPT_DIR}/DalyBMS.jar"

# Standard-Konfiguration (kann über Parameter überschrieben werden)
java -jar "${JAR}" "$@"
