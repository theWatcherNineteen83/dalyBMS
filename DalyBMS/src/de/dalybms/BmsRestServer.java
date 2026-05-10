package de.dalybms;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class BmsRestServer {

    private static final Logger logger = Logger.getLogger(BmsRestServer.class.getName());

    private final DalyBMS bms;
    private final int httpPort;
    private HttpServer server;

    // MQTT (optional)
    private MqttPublisher mqtt;
    private boolean mqttEnabled = false;

    // Schützt vor versehentlichem Schreiben (Default: true)
    private final boolean readOnly;

    // Gecachte BMS-Daten (thread-safe)
    private volatile DalyBMSData cachedData;
    private volatile String lastUpdate = "never";
    private volatile String lastError = null;

    // Lock für serielle Kommunikation
    private final Object bmsLock = new Object();

    /**
     * Konstruktor ohne MQTT
     */
    public BmsRestServer(DalyBMS bms, int httpPort, boolean readOnly) {
        this.bms = bms;
        this.httpPort = httpPort;
        this.readOnly = readOnly;
    }

    /**
     * Konstruktor mit MQTT – sendet Daten bei jedem GET automatisch mit
     */
    public BmsRestServer(DalyBMS bms, int httpPort,
                         String mqttBroker, int mqttPort,
                         String mqttUser, String mqttPassword,
                         String mqttTopic, boolean readOnly) {
        this.bms = bms;
        this.httpPort = httpPort;
        this.readOnly = readOnly;
        this.mqtt = new MqttPublisher(mqttBroker, mqttPort,
                mqttUser, mqttPassword, mqttTopic);
        this.mqttEnabled = true;
    }

    /**
     * Server starten
     */
    public void start() throws IOException {
        // MQTT verbinden (falls konfiguriert)
        if (mqttEnabled) {
            try {
                mqtt.connect();
                logger.info("MQTT verbunden – Daten werden bei jedem GET mitgesendet");
            } catch (Exception e) {
                logger.warning("MQTT Verbindung fehlgeschlagen: " + e.getMessage());
                logger.warning("REST Server läuft trotzdem weiter ohne MQTT");
                mqttEnabled = false;
            }
        }

        // HTTP Server starten
        server = HttpServer.create(new InetSocketAddress(httpPort), 0);

        // ==================== Dashboard ====================
        server.createContext("/", new RootHandler());

        // ==================== REST GET Endpunkte ====================
        server.createContext("/api/all", new AllHandler());
        server.createContext("/api/soc", new SocHandler());
        server.createContext("/api/cell-voltages", new CellVoltagesHandler());
        server.createContext("/api/cell-voltage-range", new CellVoltageRangeHandler());
        server.createContext("/api/temperatures", new TemperaturesHandler());
        server.createContext("/api/temperature-range", new TemperatureRangeHandler());
        server.createContext("/api/mosfet", new MosfetHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/errors", new ErrorsHandler());
        server.createContext("/api/health", new HealthHandler());

        // ==================== REST PUT Endpunkte (Steuerung) ====================
        server.createContext("/api/set/discharge-mosfet", new SetDischargeMosfetHandler());
        server.createContext("/api/set/charge-mosfet", new SetChargeMosfetHandler());
        server.createContext("/api/set/soc", new SetSocHandler());

        // ==================== Swagger UI ====================
        server.createContext("/swagger", new SwaggerHandler(httpPort));

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        logger.info("==============================================");
        logger.info("REST Server gestartet auf Port " + httpPort);
        logger.info("Modus: On-Demand (BMS wird nur bei Request abgefragt)");
        logger.info("MQTT: " + (mqttEnabled ? "aktiv (sendet bei jedem GET)" : "deaktiviert"));
        logger.info("Schreibzugriff: " + (readOnly ? "READ-ONLY (--read-write zum Aktivieren)" : "aktiviert"));
        logger.info("==============================================");
        logger.info("Dashboard:        http://localhost:" + httpPort + "/");
        logger.info("Swagger UI:       http://localhost:" + httpPort + "/swagger/");
        logger.info("OpenAPI YAML:     http://localhost:" + httpPort + "/swagger/openapi.yaml");
        logger.info("----------------------------------------------");
        logger.info("GET Endpunkte:");
        logger.info("  /api/all                 - Alle Daten");
        logger.info("  /api/soc                 - SOC (Spannung, Strom, %)");
        logger.info("  /api/cell-voltages       - Einzelne Zellspannungen");
        logger.info("  /api/cell-voltage-range  - Min/Max Zellspannung");
        logger.info("  /api/temperatures        - Temperatursensoren");
        logger.info("  /api/temperature-range   - Min/Max Temperatur");
        logger.info("  /api/mosfet              - MOSFET Status");
        logger.info("  /api/status              - BMS Status");
        logger.info("  /api/errors              - Fehlermeldungen");
        logger.info("  /api/health              - Health Check");
        logger.info("----------------------------------------------");
        logger.info("PUT Endpunkte:");
        logger.info("  /api/set/discharge-mosfet  - Entlade-MOSFET schalten");
        logger.info("  /api/set/charge-mosfet     - Lade-MOSFET schalten");
        logger.info("  /api/set/soc               - SOC manuell setzen");
        logger.info("==============================================");
    }

    /**
     * Server stoppen
     */
    public void stop() {
        if (server != null) {
            server.stop(2);
            logger.info("HTTP Server gestoppt");
        }
        if (mqttEnabled && mqtt != null) {
            try {
                mqtt.disconnect();
                logger.info("MQTT getrennt");
            } catch (Exception e) {
                logger.warning("MQTT Trennung fehlgeschlagen: " + e.getMessage());
            }
        }
    }

    // ==================== MQTT Hintergrund-Senden ====================

    /**
     * Sendet alle Daten per MQTT im Hintergrund (blockiert nicht den HTTP Response)
     */
    private void publishToMqtt(DalyBMSData data) {
        if (!mqttEnabled || data == null) return;

        new Thread(() -> {
            try {
                mqtt.publishAll(data);
                logger.fine("MQTT Daten gesendet (Nebenprodukt von GET)");
            } catch (Exception e) {
                logger.warning("MQTT Senden fehlgeschlagen: " + e.getMessage());
                // Versuche MQTT neu zu verbinden
                try {
                    mqtt.disconnect();
                    mqtt.connect();
                    logger.info("MQTT neu verbunden");
                } catch (Exception reconnectError) {
                    logger.warning("MQTT Reconnect fehlgeschlagen: "
                            + reconnectError.getMessage());
                }
            }
        }, "mqtt-publish").start();
    }

    /**
     * Sendet nur SOC per MQTT im Hintergrund
     */
    private void publishSocToMqtt(DalyBMSData.SOC soc) {
        if (!mqttEnabled || soc == null) return;

        new Thread(() -> {
            try {
                mqtt.publishSoc(soc);
                logger.fine("MQTT SOC gesendet");
            } catch (Exception e) {
                logger.warning("MQTT SOC senden fehlgeschlagen: " + e.getMessage());
            }
        }, "mqtt-soc").start();
    }

    // ==================== BMS Daten abfragen (On-Demand) ====================

    /**
     * Fragt alle Daten vom BMS ab. Thread-safe.
     * Bei Fehler werden gecachte Daten zurückgegeben.
     */
    private DalyBMSData fetchAllData() {
        synchronized (bmsLock) {
            try {
                logger.info("BMS wird abgefragt...");
                long start = System.currentTimeMillis();

                DalyBMSData newData = bms.getAll();
                long duration = System.currentTimeMillis() - start;

                // Prüfen ob wirklich Daten kamen
                if (newData != null && newData.soc != null) {
                    cachedData = newData;
                    lastUpdate = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    lastError = null;
                    logger.info("BMS-Daten gelesen in " + duration + " ms");

                    // MQTT als Nebenprodukt senden
                    publishToMqtt(cachedData);
                } else {
                    logger.warning("BMS hat keine vollständigen Daten geliefert ("
                            + duration + " ms)");
                    if (cachedData != null) {
                        lastError = "Letzte Abfrage unvollständig, zeige gecachte Daten vom "
                                + lastUpdate;
                    } else {
                        lastError = "Keine Daten vom BMS erhalten";
                    }
                }

                return cachedData;
            } catch (Exception e) {
                lastError = e.getMessage();
                logger.warning("Fehler beim BMS-Auslesen: " + e.getMessage());

                if (cachedData != null) {
                    logger.info("Verwende gecachte Daten vom " + lastUpdate);
                    return cachedData;
                }
                return null;
            }
        }
    }

    /**
     * Fragt nur SOC vom BMS ab (schneller als getAll).
     */
    private DalyBMSData.SOC fetchSOC() {
        synchronized (bmsLock) {
            try {
                logger.fine("BMS SOC wird abgefragt...");
                DalyBMSData.SOC soc = bms.getSOC();

                if (soc != null) {
                    lastUpdate = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    lastError = null;
                    if (cachedData != null) {
                        cachedData.soc = soc;
                    }

                    // SOC per MQTT senden
                    publishSocToMqtt(soc);

                    return soc;
                } else {
                    logger.warning("SOC Abfrage lieferte keine Daten");
                    return cachedData != null ? cachedData.soc : null;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                logger.warning("Fehler beim SOC-Auslesen: " + e.getMessage());
                return cachedData != null ? cachedData.soc : null;
            }
        }
    }

    /**
     * Fragt nur Zellspannungen vom BMS ab.
     */
    private Map<Integer, Double> fetchCellVoltages() {
        synchronized (bmsLock) {
            try {
                if (bms.getCellCount() == 0) {
                    bms.getStatus();
                }
                Map<Integer, Double> voltages = bms.getCellVoltages();
                if (voltages != null) {
                    lastUpdate = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    lastError = null;
                    if (cachedData != null) {
                        cachedData.cellVoltages = voltages;
                    }
                }
                return voltages != null ? voltages :
                        (cachedData != null ? cachedData.cellVoltages : null);
            } catch (Exception e) {
                lastError = e.getMessage();
                logger.warning("Fehler beim Zellspannungen-Auslesen: " + e.getMessage());
                return cachedData != null ? cachedData.cellVoltages : null;
            }
        }
    }

    /**
     * Fragt nur Temperaturen vom BMS ab.
     */
    private Map<Integer, Integer> fetchTemperatures() {
        synchronized (bmsLock) {
            try {
                if (bms.getTemperatureSensorCount() == 0) {
                    bms.getStatus();
                }
                Map<Integer, Integer> temps = bms.getTemperatures();
                if (temps != null) {
                    lastUpdate = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    lastError = null;
                    if (cachedData != null) {
                        cachedData.temperatures = temps;
                    }
                }
                return temps != null ? temps :
                        (cachedData != null ? cachedData.temperatures : null);
            } catch (Exception e) {
                lastError = e.getMessage();
                logger.warning("Fehler beim Temperatur-Auslesen: " + e.getMessage());
                return cachedData != null ? cachedData.temperatures : null;
            }
        }
    }

    // ==================== HTTP Hilfsmethoden ====================

    private void sendJsonResponse(HttpExchange exchange, int statusCode,
                                  String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                "GET, PUT, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type");
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        OutputStream os = exchange.getResponseBody();
        os.write(response);
        os.close();
    }

    private void sendError(HttpExchange exchange, int statusCode,
                           String message) throws IOException {
        JSONObject error = new JSONObject();
        error.put("error", message);
        error.put("status", statusCode);
        sendJsonResponse(exchange, statusCode, error.toString(2));
    }

    private boolean checkGetMethod(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                    "GET, PUT, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                    "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return false;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Nur GET erlaubt");
            return false;
        }
        return true;
    }

    private boolean checkPutMethod(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                    "GET, PUT, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                    "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return false;
        }
        if (!"PUT".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Nur PUT erlaubt");
            return false;
        }
        if (readOnly) {
            sendError(exchange, 403, "Schreibzugriff deaktiviert (READ-ONLY Modus). "
                    + "Zum Aktivieren: --read-write");
            return false;
        }
        return true;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    // ====================================================================
    // GET Handler - BMS Daten (On-Demand)
    // ====================================================================

    /**
     * GET / - Dashboard (HTML) - fragt BMS live ab
     */
    class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // BMS live abfragen
            DalyBMSData data = fetchAllData();

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><title>Daly BMS</title>");
            html.append("<meta charset='utf-8'>");
            html.append("<style>");
            html.append("body { font-family: monospace; background: #1a1a2e; ");
            html.append("color: #e0e0e0; padding: 20px; max-width: 900px; margin: 0 auto; }");
            html.append("h1 { color: #00d4ff; border-bottom: 2px solid #00d4ff; ");
            html.append("padding-bottom: 10px; }");
            html.append("h2 { color: #00ff88; margin-top: 25px; }");
            html.append("table { border-collapse: collapse; margin: 10px 0; width: 100%; }");
            html.append("td, th { border: 1px solid #333; padding: 8px 15px; text-align: left; }");
            html.append("th { background: #16213e; color: #00d4ff; width: 250px; }");
            html.append("td { background: #0f3460; }");
            html.append(".value { color: #00ff88; font-weight: bold; font-size: 1.1em; }");
            html.append(".error { color: #ff4444; }");
            html.append("a { color: #00d4ff; }");
            html.append(".nav { display: flex; gap: 10px; margin: 15px 0; flex-wrap: wrap; }");
            html.append(".nav a { padding: 8px 15px; border: 1px solid #00d4ff; ");
            html.append("border-radius: 4px; text-decoration: none; font-size: 0.9em; }");
            html.append(".nav a:hover { background: #00d4ff; color: #1a1a2e; }");
            html.append(".big-value { font-size: 2em; color: #00ff88; }");
            html.append(".update-info { color: #888; font-size: 0.85em; margin: 5px 0 20px; }");
            html.append(".warning-info { color: #ffaa00; font-size: 0.85em; }");
            html.append(".refresh-btn { background: #00d4ff; color: #1a1a2e; border: none; ");
            html.append("padding: 10px 20px; font-family: monospace; font-size: 1em; ");
            html.append("cursor: pointer; border-radius: 4px; margin: 10px 0; }");
            html.append(".refresh-btn:hover { background: #00ff88; }");
            html.append("</style></head><body>");

            // Header
            html.append("<h1>&#9889; Daly BMS Monitor</h1>");
            html.append("<div class='update-info'>Abgefragt: ").append(lastUpdate);
            if (lastError != null) {
                html.append(" <span class='warning-info'>&#9888; ")
                        .append(lastError).append("</span>");
            }
            html.append("</div>");

            // Refresh Button
            html.append("<button class='refresh-btn' onclick='location.reload()'>")
                    .append("&#128260; Daten neu laden</button>");

            // Navigation
            html.append("<div class='nav'>");
            html.append("<a href='/swagger/'>&#128218; Swagger UI</a>");
            html.append("<a href='/api/all'>&#128196; JSON (All)</a>");
            html.append("<a href='/swagger/openapi.yaml'>&#128196; OpenAPI YAML</a>");
            html.append("</div>");

            if (data != null) {
                // SOC - Große Anzeige
                if (data.soc != null) {
                    html.append("<h2>&#128267; State of Charge</h2>");
                    html.append("<div class='big-value'>")
                            .append(data.soc.socPercent).append(" %</div>");
                    html.append("<table>");
                    html.append("<tr><th>Gesamtspannung</th><td class='value'>")
                            .append(data.soc.totalVoltage).append(" V</td></tr>");
                    html.append("<tr><th>Strom</th><td class='value'>")
                            .append(data.soc.current).append(" A</td></tr>");
                    html.append("<tr><th>Ladezustand</th><td class='value'>")
                            .append(data.soc.socPercent).append(" %</td></tr>");
                    html.append("</table>");
                }

                // MOSFET Status
                if (data.mosfetStatus != null) {
                    html.append("<h2>&#128268; MOSFET Status</h2>");
                    html.append("<table>");
                    html.append("<tr><th>Modus</th><td class='value'>")
                            .append(data.mosfetStatus.mode).append("</td></tr>");
                    html.append("<tr><th>Lade-MOSFET</th><td class='value'>")
                            .append(data.mosfetStatus.chargingMosfet
                                    ? "&#9989; Ein" : "&#10060; Aus")
                            .append("</td></tr>");
                    html.append("<tr><th>Entlade-MOSFET</th><td class='value'>")
                            .append(data.mosfetStatus.dischargingMosfet
                                    ? "&#9989; Ein" : "&#10060; Aus")
                            .append("</td></tr>");
                    html.append("<tr><th>Restkapazität</th><td class='value'>")
                            .append(data.mosfetStatus.capacityAh)
                            .append(" Ah</td></tr>");
                    html.append("</table>");
                }

                // Zellspannungen
                if (data.cellVoltages != null && !data.cellVoltages.isEmpty()) {
                    html.append("<h2>&#128267; Zellspannungen</h2>");
                    html.append("<table>");
                    html.append("<tr><th>Zelle</th><th>Spannung</th></tr>");
                    for (Map.Entry<Integer, Double> entry :
                            data.cellVoltages.entrySet()) {
                        String color = "";
                        if (data.cellVoltageRange != null) {
                            if (entry.getValue()
                                    == data.cellVoltageRange.highestVoltage) {
                                color = " style='color:#00ff88'";
                            } else if (entry.getValue()
                                    == data.cellVoltageRange.lowestVoltage) {
                                color = " style='color:#ffaa00'";
                            }
                        }
                        html.append("<tr><td>Zelle ").append(entry.getKey())
                                .append("</td><td class='value'")
                                .append(color).append(">")
                                .append(entry.getValue())
                                .append(" V</td></tr>");
                    }
                    html.append("</table>");

                    // Voltage Range Info
                    if (data.cellVoltageRange != null) {
                        double diff = data.cellVoltageRange.highestVoltage
                                - data.cellVoltageRange.lowestVoltage;
                        html.append("<p>Differenz: <span class='value'>")
                                .append(String.format("%.3f", diff))
                                .append(" V</span> (Zelle ")
                                .append(data.cellVoltageRange.lowestCell)
                                .append(" - Zelle ")
                                .append(data.cellVoltageRange.highestCell)
                                .append(")</p>");
                    }
                }

                // Temperaturen
                if (data.temperatures != null && !data.temperatures.isEmpty()) {
                    html.append("<h2>&#127777; Temperaturen</h2>");
                    html.append("<table>");
                    html.append("<tr><th>Sensor</th><th>Temperatur</th></tr>");
                    for (Map.Entry<Integer, Integer> entry :
                            data.temperatures.entrySet()) {
                        html.append("<tr><td>Sensor ").append(entry.getKey())
                                .append("</td><td class='value'>")
                                .append(entry.getValue())
                                .append(" °C</td></tr>");
                    }
                    html.append("</table>");
                }

                // Status
                if (data.status != null) {
                    html.append("<h2>&#128295; BMS Status</h2>");
                    html.append("<table>");
                    html.append("<tr><th>Zellen</th><td class='value'>")
                            .append(data.status.cells).append("</td></tr>");
                    html.append("<tr><th>Temperatursensoren</th><td class='value'>")
                            .append(data.status.temperatureSensors).append("</td></tr>");
                    html.append("<tr><th>Ladegerät</th><td class='value'>")
                            .append(data.status.chargerRunning
                                    ? "&#9989; Aktiv" : "&#10060; Inaktiv")
                            .append("</td></tr>");
                    html.append("<tr><th>Last</th><td class='value'>")
                            .append(data.status.loadRunning
                                    ? "&#9989; Aktiv" : "&#10060; Inaktiv")
                            .append("</td></tr>");
                    html.append("<tr><th>Zyklen</th><td class='value'>")
                            .append(data.status.cycles).append("</td></tr>");
                    html.append("</table>");
                }

                // Fehler
                if (data.errors != null && !data.errors.isEmpty()) {
                    html.append("<h2 class='error'>&#9888; Fehler</h2>");
                    html.append("<table>");
                    for (String error : data.errors) {
                        html.append("<tr><td class='error'>&#9888; ")
                                .append(error).append("</td></tr>");
                    }
                    html.append("</table>");
                } else {
                    html.append("<h2>&#9888; Fehler</h2>");
                    html.append("<p style='color:#00ff88'>&#9989; Keine Fehler</p>");
                }

            } else {
                html.append("<h2 class='error'>BMS-Daten nicht verfügbar</h2>");
                if (lastError != null) {
                    html.append("<p class='error'>").append(lastError).append("</p>");
                }
                html.append("<p>Bitte versuche es erneut:</p>");
                html.append("<button class='refresh-btn' onclick='location.reload()'>")
                        .append("&#128260; Erneut versuchen</button>");
            }

            // MQTT Status Anzeige
            html.append("<h2>&#128225; Verbindungen</h2>");
            html.append("<table>");
            html.append("<tr><th>MQTT</th><td class='value'>")
                    .append(mqttEnabled
                            ? "&#9989; Aktiv (sendet bei jedem Abruf)"
                            : "&#10060; Deaktiviert")
                    .append("</td></tr>");
            html.append("</table>");

            html.append("</body></html>");

            exchange.getResponseHeaders().set("Content-Type",
                    "text/html; charset=utf-8");
            byte[] response = html.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    /**
     * GET /api/all - Alle BMS Daten (live vom BMS)
     */
    class AllHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData data = fetchAllData();
            if (data == null) {
                sendError(exchange, 503,
                        "BMS-Daten nicht verfügbar: " + lastError);
                return;
            }

            JSONObject json = data.toJson();
            json.put("last_update", lastUpdate);
            if (lastError != null) {
                json.put("warning", lastError);
            }
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/soc - Nur SOC (schnelle Abfrage)
     */
    class SocHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData.SOC soc = fetchSOC();
            if (soc == null) {
                sendError(exchange, 503,
                        "SOC-Daten nicht verfügbar: " + lastError);
                return;
            }

            JSONObject json = soc.toJson();
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/cell-voltages - Zellspannungen (live vom BMS)
     */
    class CellVoltagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            Map<Integer, Double> voltages = fetchCellVoltages();
            if (voltages == null) {
                sendError(exchange, 503,
                        "Zellspannungs-Daten nicht verfügbar: " + lastError);
                return;
            }

            JSONObject json = new JSONObject();
            for (Map.Entry<Integer, Double> entry : voltages.entrySet()) {
                json.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/cell-voltage-range - Min/Max Zellspannung (live)
     */
    class CellVoltageRangeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData data = fetchAllData();
            if (data == null || data.cellVoltageRange == null) {
                sendError(exchange, 503, "Daten nicht verfügbar");
                return;
            }

            JSONObject json = data.cellVoltageRange.toJson();
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/temperatures - Temperaturen (live vom BMS)
     */
    class TemperaturesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            Map<Integer, Integer> temps = fetchTemperatures();
            if (temps == null) {
                sendError(exchange, 503,
                        "Temperatur-Daten nicht verfügbar: " + lastError);
                return;
            }

            JSONObject json = new JSONObject();
            for (Map.Entry<Integer, Integer> entry : temps.entrySet()) {
                json.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/temperature-range - Min/Max Temperatur (live)
     */
    class TemperatureRangeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData data = fetchAllData();
            if (data == null || data.temperatureRange == null) {
                sendError(exchange, 503, "Daten nicht verfügbar");
                return;
            }

            JSONObject json = data.temperatureRange.toJson();
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/mosfet - MOSFET Status (live)
     */
    class MosfetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData data = fetchAllData();
            if (data == null || data.mosfetStatus == null) {
                sendError(exchange, 503, "MOSFET-Daten nicht verfügbar");
                return;
            }

            JSONObject json = data.mosfetStatus.toJson();
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/status - BMS Status (live)
     */
    class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData data = fetchAllData();
            if (data == null || data.status == null) {
                sendError(exchange, 503, "Status-Daten nicht verfügbar");
                return;
            }

            JSONObject json = data.status.toJson();
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/errors - BMS Fehler (live)
     */
    class ErrorsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            DalyBMSData data = fetchAllData();
            if (data == null) {
                sendError(exchange, 503, "Daten nicht verfügbar");
                return;
            }

            JSONObject json = new JSONObject();
            json.put("errors", data.errors != null
                    ? new JSONArray(data.errors)
                    : new JSONArray());
            json.put("has_errors", data.errors != null
                    && !data.errors.isEmpty());
            json.put("last_update", lastUpdate);
            sendJsonResponse(exchange, 200, json.toString(2));
        }
    }

    /**
     * GET /api/health - Health Check (kein BMS-Zugriff nötig)
     */
    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkGetMethod(exchange)) return;

            JSONObject json = new JSONObject();
            json.put("status", lastError == null ? "ok" : "error");
            json.put("last_update", lastUpdate);
            json.put("mode", "on-demand");
            json.put("mqtt_enabled", mqttEnabled);
            json.put("bms_connected", lastError == null);
            json.put("cell_count", bms.getCellCount());
            json.put("temperature_sensor_count", bms.getTemperatureSensorCount());
            json.put("has_cached_data", cachedData != null);
            if (lastError != null) {
                json.put("last_error", lastError);
            }
            int code = lastError == null || cachedData != null ? 200 : 503;
            sendJsonResponse(exchange, code, json.toString(2));
        }
    }

    // ====================================================================
    // PUT Handler - Steuerung
    // ====================================================================

    /**
     * PUT /api/set/discharge-mosfet
     * Body: {"on": true} oder {"on": false}
     */
    class SetDischargeMosfetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkPutMethod(exchange)) return;

            String body = readRequestBody(exchange);
            try {
                JSONObject json = new JSONObject(body);
                if (!json.has("on")) {
                    sendError(exchange, 400,
                            "Feld 'on' fehlt. Erwartet: {\"on\": true/false}");
                    return;
                }

                boolean on = json.getBoolean("on");

                synchronized (bmsLock) {
                    bms.setDischargeMosfet(on);
                }

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message",
                        "Discharge MOSFET set to " + (on ? "on" : "off"));
                response.put("discharge_mosfet", on);
                response.put("timestamp", LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                sendJsonResponse(exchange, 200, response.toString(2));

                logger.info("Discharge MOSFET set to " + (on ? "ON" : "OFF"));

            } catch (org.json.JSONException e) {
                sendError(exchange, 400,
                        "Ungültiges JSON: " + e.getMessage()
                                + ". Erwartet: {\"on\": true/false}");
            } catch (Exception e) {
                sendError(exchange, 500,
                        "BMS-Kommunikationsfehler: " + e.getMessage());
                logger.warning("SetDischargeMosfet Fehler: " + e.getMessage());
            }
        }
    }

    /**
     * PUT /api/set/charge-mosfet
     * Body: {"on": true} oder {"on": false}
     */
    class SetChargeMosfetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkPutMethod(exchange)) return;

            String body = readRequestBody(exchange);
            try {
                JSONObject json = new JSONObject(body);
                if (!json.has("on")) {
                    sendError(exchange, 400,
                            "Feld 'on' fehlt. Erwartet: {\"on\": true/false}");
                    return;
                }

                boolean on = json.getBoolean("on");

                synchronized (bmsLock) {
                    bms.setChargeMosfet(on);
                }

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message",
                        "Charge MOSFET set to " + (on ? "on" : "off"));
                response.put("charge_mosfet", on);
                response.put("timestamp", LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                sendJsonResponse(exchange, 200, response.toString(2));

                logger.info("Charge MOSFET set to " + (on ? "ON" : "OFF"));

            } catch (org.json.JSONException e) {
                sendError(exchange, 400,
                        "Ungültiges JSON: " + e.getMessage()
                                + ". Erwartet: {\"on\": true/false}");
            } catch (Exception e) {
                sendError(exchange, 500,
                        "BMS-Kommunikationsfehler: " + e.getMessage());
                logger.warning("SetChargeMosfet Fehler: " + e.getMessage());
            }
        }
    }

    /**
     * PUT /api/set/soc
     * Body: {"value": 50.0}
     */
    class SetSocHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkPutMethod(exchange)) return;

            String body = readRequestBody(exchange);
            try {
                JSONObject json = new JSONObject(body);
                if (!json.has("value")) {
                    sendError(exchange, 400,
                            "Feld 'value' fehlt. Erwartet: {\"value\": 50.0}");
                    return;
                }

                double value = json.getDouble("value");

                if (value < 0.0 || value > 100.0) {
                    sendError(exchange, 400,
                            "Wert muss zwischen 0.0 und 100.0 liegen. "
                                    + "Erhalten: " + value);
                    return;
                }

                synchronized (bmsLock) {
                    bms.setSOC(value);
                }

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "SOC set to " + value + "%");
                response.put("soc_value", value);
                response.put("timestamp", LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                sendJsonResponse(exchange, 200, response.toString(2));

                logger.info("SOC set to " + value + "%");

            } catch (org.json.JSONException e) {
                sendError(exchange, 400,
                        "Ungültiges JSON: " + e.getMessage()
                                + ". Erwartet: {\"value\": 50.0}");
            } catch (Exception e) {
                sendError(exchange, 500,
                        "BMS-Kommunikationsfehler: " + e.getMessage());
                logger.warning("SetSOC Fehler: " + e.getMessage());
            }
        }
    }
}