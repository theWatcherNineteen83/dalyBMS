package de.dalybms;

import org.eclipse.paho.client.mqttv3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.logging.Logger;

public class MqttPublisher {

    private static final Logger logger = Logger.getLogger(MqttPublisher.class.getName());

    private final String broker;
    private final int port;
    private final String username;
    private final String password;
    private final String topic;
    private MqttClient client;

    public MqttPublisher(String broker, int port,
                         String username, String password, String topic) {
        this.broker = broker;
        this.port = port;
        this.username = username;
        this.password = password;
        this.topic = topic;
    }

    /**
     * MQTT Verbindung herstellen
     */
    public void connect() throws MqttException {
        String serverUri = "tcp://" + broker + ":" + port;
        String clientId = "daly-bms-java-" + System.currentTimeMillis();

        client = new MqttClient(serverUri, clientId);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);

        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }

        client.connect(options);
        logger.info("MQTT verbunden mit " + serverUri);
    }

    /**
     * MQTT Verbindung trennen
     */
    public void disconnect() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
            client.close();
            logger.info("MQTT getrennt");
        }
    }

    /**
     * Prüft ob MQTT verbunden ist
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * Stellt Verbindung wieder her falls getrennt
     */
    public void reconnectIfNeeded() throws MqttException {
        if (client == null || !client.isConnected()) {
            logger.info("MQTT Reconnect...");
            connect();
        }
    }

    // ==================== Publish Methoden ====================

    /**
     * Alle BMS-Daten als einzelne MQTT Topics publishen
     */
    public void publishAll(DalyBMSData data) throws MqttException {
        if (data == null) return;
        reconnectIfNeeded();

        // Komplettes JSON als ein Topic
        publish(topic + "/all", data.toJson().toString());

        // SOC
        publishSoc(data.soc);

        // Cell Voltage Range
        if (data.cellVoltageRange != null) {
            publish(topic + "/cell_voltage_range/highest_voltage",
                    String.valueOf(data.cellVoltageRange.highestVoltage));
            publish(topic + "/cell_voltage_range/highest_cell",
                    String.valueOf(data.cellVoltageRange.highestCell));
            publish(topic + "/cell_voltage_range/lowest_voltage",
                    String.valueOf(data.cellVoltageRange.lowestVoltage));
            publish(topic + "/cell_voltage_range/lowest_cell",
                    String.valueOf(data.cellVoltageRange.lowestCell));
        }

        // Temperature Range
        if (data.temperatureRange != null) {
            publish(topic + "/temperature_range/highest_temperature",
                    String.valueOf(data.temperatureRange.highestTemperature));
            publish(topic + "/temperature_range/highest_sensor",
                    String.valueOf(data.temperatureRange.highestSensor));
            publish(topic + "/temperature_range/lowest_temperature",
                    String.valueOf(data.temperatureRange.lowestTemperature));
            publish(topic + "/temperature_range/lowest_sensor",
                    String.valueOf(data.temperatureRange.lowestSensor));
        }

        // MOSFET Status
        if (data.mosfetStatus != null) {
            publish(topic + "/mosfet_status/mode",
                    data.mosfetStatus.mode);
            publish(topic + "/mosfet_status/charging_mosfet",
                    String.valueOf(data.mosfetStatus.chargingMosfet));
            publish(topic + "/mosfet_status/discharging_mosfet",
                    String.valueOf(data.mosfetStatus.dischargingMosfet));
            publish(topic + "/mosfet_status/capacity_ah",
                    String.valueOf(data.mosfetStatus.capacityAh));
        }

        // Status
        if (data.status != null) {
            publish(topic + "/status/cells",
                    String.valueOf(data.status.cells));
            publish(topic + "/status/temperature_sensors",
                    String.valueOf(data.status.temperatureSensors));
            publish(topic + "/status/charger_running",
                    String.valueOf(data.status.chargerRunning));
            publish(topic + "/status/load_running",
                    String.valueOf(data.status.loadRunning));
            publish(topic + "/status/cycles",
                    String.valueOf(data.status.cycles));
        }

        // Cell Voltages
        if (data.cellVoltages != null) {
            for (Map.Entry<Integer, Double> entry : data.cellVoltages.entrySet()) {
                publish(topic + "/cell_voltages/" + entry.getKey(),
                        String.valueOf(entry.getValue()));
            }
        }

        // Temperatures
        if (data.temperatures != null) {
            for (Map.Entry<Integer, Integer> entry : data.temperatures.entrySet()) {
                publish(topic + "/temperatures/" + entry.getKey(),
                        String.valueOf(entry.getValue()));
            }
        }

        // Errors
        if (data.errors != null && !data.errors.isEmpty()) {
            publish(topic + "/errors",
                    new JSONArray(data.errors).toString());
            publish(topic + "/has_errors", "true");
        } else {
            publish(topic + "/errors", "[]");
            publish(topic + "/has_errors", "false");
        }

        logger.fine("Alle Daten per MQTT gesendet");
    }

    /**
     * Nur SOC-Daten publishen (schnell, für einzelne SOC-Abfragen)
     */
    public void publishSoc(DalyBMSData.SOC soc) throws MqttException {
        if (soc == null) return;
        reconnectIfNeeded();

        publish(topic + "/soc/total_voltage",
                String.valueOf(soc.totalVoltage));
        publish(topic + "/soc/current",
                String.valueOf(soc.current));
        publish(topic + "/soc/soc_percent",
                String.valueOf(soc.socPercent));

        // SOC auch als JSON
        publish(topic + "/soc", soc.toJson().toString());

        logger.fine("SOC per MQTT gesendet");
    }

    /**
     * Nur Zellspannungen publishen
     */
    public void publishCellVoltages(Map<Integer, Double> cellVoltages)
            throws MqttException {
        if (cellVoltages == null) return;
        reconnectIfNeeded();

        for (Map.Entry<Integer, Double> entry : cellVoltages.entrySet()) {
            publish(topic + "/cell_voltages/" + entry.getKey(),
                    String.valueOf(entry.getValue()));
        }

        // Auch als JSON
        JSONObject json = new JSONObject();
        for (Map.Entry<Integer, Double> entry : cellVoltages.entrySet()) {
            json.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        publish(topic + "/cell_voltages", json.toString());

        logger.fine("Zellspannungen per MQTT gesendet");
    }

    /**
     * Nur Temperaturen publishen
     */
    public void publishTemperatures(Map<Integer, Integer> temperatures)
            throws MqttException {
        if (temperatures == null) return;
        reconnectIfNeeded();

        for (Map.Entry<Integer, Integer> entry : temperatures.entrySet()) {
            publish(topic + "/temperatures/" + entry.getKey(),
                    String.valueOf(entry.getValue()));
        }

        logger.fine("Temperaturen per MQTT gesendet");
    }

    // ==================== Basis Publish ====================

    /**
     * Einzelne MQTT Nachricht senden
     *
     * @param topic   MQTT Topic
     * @param payload Nachrichteninhalt
     */
    private void publish(String topic, String payload) throws MqttException {
        if (client == null || !client.isConnected()) {
            logger.warning("MQTT nicht verbunden, Nachricht verworfen: " + topic);
            return;
        }

        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);
        message.setRetained(true);
        client.publish(topic, message);

        logger.finest("MQTT: " + topic + " = " + payload);
    }
}