# 🔋 Daly BMS Monitor

Java-basierter BMS-Monitor für Daly Batterie-Management-Systeme. Liest das BMS über serielle Schnittstelle (RS485/UART) aus und stellt die Daten als REST-API mit Dashboard und Swagger-UI bereit. Optional mit MQTT-Publishing.

## Features

- **📊 Web-Dashboard** — Live-Ansicht aller BMS-Daten (SOC, Spannungen, Temperaturen, MOSFET-Status, Fehler)
- **🔌 REST-API** — 11 GET-Endpunkte + 3 PUT-Endpunkte (MOSFET schalten, SOC setzen)
- **📡 MQTT** — Automatisches Publishing aller Daten (Home-Assistant-kompatibel)
- **📖 Swagger-UI** — Interaktive API-Dokumentation unter `/swagger/`
- **⚙️ Konfigurationsdatei** — Alle Einstellungen in `config.properties`, CLI-Argumente überschreiben
- **🔄 On-Demand-Polling** — BMS wird nur bei API-Requests abgefragt, kein Dauer-Polling
- **🔒 Read-Only Modus** — Schreibzugriffe (MOSFET, SOC) standardmäßig gesperrt, mit `--read-write` freischaltbar

## Voraussetzungen

- **Java 11+** (getestet mit OpenJDK 21)
- **Daly BMS** mit seriellem Anschluss (USB-RS485 oder UART-Bluetooth)
- **JK-BMS** (PB-Serie, optional) über RS485/Modbus RTU an einem zweiten USB-Adapter (2x möglich, Slave-Adressen 1..247)
- **MQTT Broker** (optional, z.B. Mosquitto, Home Assistant)

## Schnellstart

```bash
# 1. config.properties anpassen (vor allem serial.device)
nano config.properties

# 2. Bauen
./build.sh

# 3. Starten (Read-Only, kein Schreibzugriff):
java -jar dist/DalyBMS.jar

# Mit Schreibzugriff:
java -jar dist/DalyBMS.jar --read-write

# Dashboard: http://localhost:8080/
# Swagger:   http://localhost:8080/swagger/
```

Mit MQTT:
```bash
java -jar dist/DalyBMS.jar --mqtt
```

## Konfiguration

Alle Einstellungen in `config.properties`:

```properties
# --- Serielle Schnittstelle ---
serial.device=/dev/ttyUSB0          # Serielles Gerät
serial.baudrate=9600                # Baudrate (Standard: 9600)
serial.request_retries=5            # Wiederholungen bei Fehler
serial.address=4                    # 4 = USB/RS485, 8 = UART/Bluetooth

# --- HTTP / REST Server ---
http.port=8080                      # HTTP Port
http.poll_interval_seconds=30       # Poll-Intervall für MQTT

# --- MQTT ---
mqtt.enabled=false                  # MQTT aktivieren (true/false)
mqtt.broker=192.168.20.100          # MQTT Broker Adresse
mqtt.port=1883                      # MQTT Port
mqtt.username=dalybms               # MQTT Benutzername
mqtt.password=eveakku               # MQTT Passwort
mqtt.topic=daly_bms                 # MQTT Basis-Topic

# --- Logging ---
verbose=false                       # Detaillierte Ausgabe
```

CLI-Argumente überschreiben die Konfigurationsdatei:

```bash
java -jar dist/DalyBMS.jar --help
```

## API-Endpunkte

### GET

| Endpoint | Beschreibung |
|---|---|
| `/` | Dashboard (HTML) |
| `/api/all` | Alle BMS-Daten (JSON) |
| `/api/soc` | SOC: Spannung, Strom, Ladezustand |
| `/api/cell-voltages` | Einzelne Zellspannungen |
| `/api/cell-voltage-range` | Min/Max Zellspannung |
| `/api/temperatures` | Temperatursensoren |
| `/api/temperature-range` | Min/Max Temperatur |
| `/api/mosfet` | MOSFET-Status (Lade/Entlade) |
| `/api/status` | BMS-Status (Zellen, Zyklen, Ladegerät/Last) |
| `/api/errors` | Fehlerliste |
| `/api/health` | Health-Check (kein BMS-Zugriff) |
| `/api/jk/1` | JK-BMS 1 (alle Daten, getrennte Quelle) |
| `/api/jk/2` | JK-BMS 2 (alle Daten, getrennte Quelle) |
| `/api/jk/all` | beide JK-BMS zusammen (getrennt) |
| `/swagger/` | Swagger-UI |
| `/swagger/openapi.yaml` | OpenAPI-Spezifikation |

### PUT (Steuerung)

| Endpoint | Body | Beschreibung |
|---|---|---|
| `/api/set/discharge-mosfet` | `{"on": true/false}` | Entlade-MOSFET schalten |
| `/api/set/charge-mosfet` | `{"on": true/false}` | Lade-MOSFET schalten |
| `/api/set/soc` | `{"value": 50.0}` | SOC manuell setzen |

## MQTT Topics

Wenn MQTT aktiviert ist, werden folgende Topics publiziert (Prefix: `daly_bms/`):

| Topic | Typ | Beispiel |
|---|---|---|
| `daly_bms/all` | JSON | Alle Daten als Objekt |
| `daly_bms/soc` | JSON | SOC-Daten |
| `daly_bms/soc/total_voltage` | Float | 48.5 |
| `daly_bms/soc/soc_percent` | Float | 85.2 |
| `daly_bms/cell_voltages/1` | Float | 3.312 |
| `daly_bms/temperatures/1` | Integer | 25 |
| `daly_bms/mosfet_status/charging_mosfet` | Bool | true |
| `daly_bms/errors` | JSON-Array | Fehlermeldungen |
| `daly_bms/has_errors` | Bool | true/false |

Alle Topics werden mit `retained=true` und `QoS=1` gesendet.

## Home Assistant Integration

```yaml
mqtt:
  sensor:
    - name: "BMS SOC"
      state_topic: "daly_bms/soc/soc_percent"
      unit_of_measurement: "%"
      device_class: battery

    - name: "BMS Total Voltage"
      state_topic: "daly_bms/soc/total_voltage"
      unit_of_measurement: "V"
      device_class: voltage

    - name: "BMS Current"
      state_topic: "daly_bms/soc/current"
      unit_of_measurement: "A"
      device_class: current
```

## Projektstruktur

```
DalyBMS/
├── config.properties          # Konfiguration ← hier editieren!
├── build.sh                   # Build-Script
├── run.sh                     # Start-Script
├── src/de/dalybms/
│   ├── Main.java              # Einstiegspunkt
│   ├── Config.java            # Konfigurations-Loader
│   ├── DalyBMS.java           # Serielle BMS-Kommunikation
│   ├── DalyBMSData.java       # Datenmodell + JSON-Serialisierung
│   ├── BmsRestServer.java     # HTTP REST Server + Dashboard
│   ├── MqttPublisher.java     # MQTT-Client
│   ├── SwaggerHandler.java    # Swagger-UI + OpenAPI-YAML
│   └── ErrorCodes.java        # Daly-Fehlercode-Tabelle
├── resources/
│   └── openapi.yaml           # OpenAPI-Spezifikation
├── lib/                       # Abhängigkeiten (JARs)
├── out/                       # Kompilierte Klassen
└── dist/                      # Fertiges JAR + Startscript
```

## Hardware

- **Daly BMS** (Smart BMS mit UART/RS485) — getestet mit 16S LiFePO4
- **USB-RS485-Adapter** oder **ESP32-Bluetooth-Bridge** (address=8)
- Baudrate: 9600 (Standard bei Daly)

## Fehlerbehebung

| Problem | Lösung |
|---|---|
| `Serieller Port nicht öffnen` | `serial.device` in config.properties prüfen, Rechte: `sudo usermod -a -G dialout $USER` |
| `Keine Daten vom BMS` | Baudrate prüfen (9600), Adapter-Kabel prüfen |
| `MQTT Verbindung fehlgeschlagen` | Broker-IP/Port prüfen, `mqtt.enabled=true` gesetzt? |
| `CRC-Fehler` | Kabelverbindung prüfen, evtl. `serial.request_retries` erhöhen |

## Lizenz

MIT License — siehe [LICENSE](LICENSE)
