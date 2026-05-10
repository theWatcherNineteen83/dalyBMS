#/bin/bash
SCRIPT_DIR="$^(cd "$(dirname "$0")" ^&^& pwd^)"
java -jar "${SCRIPT_DIR}/DalyBMS.jar" "$@"
