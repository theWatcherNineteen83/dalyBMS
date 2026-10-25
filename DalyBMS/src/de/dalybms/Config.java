package de.dalybms;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Lädt die Konfiguration aus config.properties.
 * Kommandozeilen-Argumente haben Vorrang.
 */
public class Config {

    private static final Logger logger = Logger.getLogger(Config.class.getName());
    private static final String DEFAULT_FILE = "config.properties";

    private final Properties props;

    public Config() {
        this(null);
    }

    /**
     * @param configPath Pfad zur Properties-Datei (null = Autodetect)
     */
    public Config(String configPath) {
        props = new Properties();
        // Defaults setzen
        setDefaults();
        // Aus Datei laden
        loadFromFile(configPath);
    }

    private void setDefaults() {
        props.setProperty("serial.device", "/dev/ttyUSB0");
        props.setProperty("serial.baudrate", "9600");
        props.setProperty("serial.databits", "8");
        props.setProperty("serial.parity", "NONE");
        props.setProperty("serial.stopbits", "1");
        props.setProperty("serial.read_timeout_ms", "2000");
        props.setProperty("serial.write_timeout_ms", "2000");
        props.setProperty("serial.request_retries", "5");
        props.setProperty("serial.address", "4");
        // --- JK-BMS (Modbus RTU, optional; 2x an einem zweiten USB/RS485-Bus) ---
        props.setProperty("jk.enabled", "false");
        props.setProperty("jk.device", "/dev/ttyUSB1");
        props.setProperty("jk.baudrate", "115200");
        props.setProperty("jk.slave_id_1", "1");
        props.setProperty("jk.slave_id_2", "2");
        props.setProperty("jk.parallel_sniffer", "true");
        props.setProperty("http.port", "8080");
        props.setProperty("http.poll_interval_seconds", "30");
        props.setProperty("mqtt.enabled", "false");
        props.setProperty("mqtt.broker", "192.168.20.100");
        props.setProperty("mqtt.port", "1883");
        props.setProperty("mqtt.username", "dalybms");
        props.setProperty("mqtt.password", "eveakku");
        props.setProperty("mqtt.topic", "daly_bms");
        props.setProperty("readonly", "true");
        props.setProperty("verbose", "false");
    }

    private void loadFromFile(String configPath) {
        Path effectivePath = findConfigFile(configPath);

        if (effectivePath == null) {
            logger.fine("Keine config.properties gefunden, verwende Defaults");
            return;
        }

        try (InputStream in = new FileInputStream(effectivePath.toFile())) {
            props.load(in);
            logger.info("Konfiguration geladen: " + effectivePath);
        } catch (IOException e) {
            logger.warning("Fehler beim Laden der Konfiguration: " + e.getMessage());
        }
    }

    private Path findConfigFile(String configPath) {
        // 1. Explizit übergebener Pfad
        if (configPath != null) {
            Path p = Paths.get(configPath);
            if (Files.exists(p)) return p;
        }

        // 2. Im aktuellen Arbeitsverzeichnis
        Path p = Paths.get(DEFAULT_FILE);
        if (Files.exists(p)) return p;

        // 3. Neben der JAR
        String jarDir = getJarDirectory();
        if (jarDir != null) {
            p = Paths.get(jarDir, DEFAULT_FILE);
            if (Files.exists(p)) return p;
        }

        // 4. ../config.properties (für dist/ Struktur)
        if (jarDir != null) {
            p = Paths.get(jarDir).getParent().resolve(DEFAULT_FILE);
            if (Files.exists(p)) return p;
        }

        return null;
    }

    private String getJarDirectory() {
        try {
            String path = Config.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            Path p = Paths.get(path);
            if (Files.isDirectory(p)) {
                return p.toString();
            }
            return p.getParent() != null ? p.getParent().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Überschreibt einen Wert (für Kommandozeilen-Argumente).
     */
    public void override(String key, String value) {
        props.setProperty(key, value);
    }

    // ─── Getter ─────────────────────────────────────────

    public String getSerialDevice() {
        return props.getProperty("serial.device");
    }

    public int getSerialBaudrate() {
        return Integer.parseInt(props.getProperty("serial.baudrate"));
    }

    public int getSerialDatabits() {
        return Integer.parseInt(props.getProperty("serial.databits"));
    }

    public String getSerialParity() {
        return props.getProperty("serial.parity");
    }

    public int getSerialStopbits() {
        return Integer.parseInt(props.getProperty("serial.stopbits"));
    }

    public int getSerialReadTimeoutMs() {
        return Integer.parseInt(props.getProperty("serial.read_timeout_ms"));
    }

    public int getSerialWriteTimeoutMs() {
        return Integer.parseInt(props.getProperty("serial.write_timeout_ms"));
    }

    public int getSerialRequestRetries() {
        return Integer.parseInt(props.getProperty("serial.request_retries"));
    }

    public int getSerialAddress() {
        return Integer.parseInt(props.getProperty("serial.address"));
    }

    public boolean isJkEnabled() {
        return Boolean.parseBoolean(props.getProperty("jk.enabled"));
    }

    public String getJkDevice() {
        return props.getProperty("jk.device");
    }

    public int getJkBaudrate() {
        return Integer.parseInt(props.getProperty("jk.baudrate"));
    }

    public int getJkSlaveId1() {
        return Integer.parseInt(props.getProperty("jk.slave_id_1"));
    }

    public int getJkSlaveId2() {
        return Integer.parseInt(props.getProperty("jk.slave_id_2"));
    }

    public boolean isJkParallelSnifferEnabled() {
        return Boolean.parseBoolean(props.getProperty("jk.parallel_sniffer"));
    }

    public int getHttpPort() {
        return Integer.parseInt(props.getProperty("http.port"));
    }

    public int getPollIntervalSeconds() {
        return Integer.parseInt(props.getProperty("http.poll_interval_seconds"));
    }

    public boolean isMqttEnabled() {
        return Boolean.parseBoolean(props.getProperty("mqtt.enabled"));
    }

    public String getMqttBroker() {
        return props.getProperty("mqtt.broker");
    }

    public int getMqttPort() {
        return Integer.parseInt(props.getProperty("mqtt.port"));
    }

    public String getMqttUsername() {
        return props.getProperty("mqtt.username");
    }

    public String getMqttPassword() {
        return props.getProperty("mqtt.password");
    }

    public String getMqttTopic() {
        return props.getProperty("mqtt.topic");
    }

    public boolean isReadOnly() {
        return Boolean.parseBoolean(props.getProperty("readonly"));
    }

    public boolean isVerbose() {
        return Boolean.parseBoolean(props.getProperty("verbose"));
    }

    /**
     * Gibt die gesamte Properties-Map zurück (für direkten Zugriff).
     */
    public Properties getProperties() {
        return props;
    }

    /**
     * Zeigt die aktive Konfiguration an.
     */
    public void print() {
        System.out.println("══════════════════════════════════════");
        System.out.println("  Daly BMS – Aktive Konfiguration");
        System.out.println("══════════════════════════════════════");
        System.out.printf("  Serial Device:        %s%n", getSerialDevice());
        System.out.printf("  Baudrate:             %d%n", getSerialBaudrate());
        System.out.printf("  HTTP Port:            %d%n", getHttpPort());
        System.out.printf("  Poll Interval:        %ds%n", getPollIntervalSeconds());
        System.out.printf("  MQTT Enabled:         %s%n", isMqttEnabled());
        System.out.printf("  MQTT Broker:          %s%n", getMqttBroker());
        System.out.printf("  MQTT Port:            %d%n", getMqttPort());
        System.out.printf("  MQTT Topic:           %s%n", getMqttTopic());
        System.out.printf("  Read-Only:            %s%n", isReadOnly() ? "JA (--read-write zum Entsperren)" : "NEIN (Schreibzugriff aktiv)");
        System.out.printf("  Verbose:              %s%n", isVerbose());
        if (isJkEnabled()) {
            System.out.printf("  JK-BMS:               aktiv (%s @ %d Baud, Slaves %d+%d)%n",
                    getJkDevice(), getJkBaudrate(), getJkSlaveId1(), getJkSlaveId2());
        } else {
            System.out.println("  JK-BMS:               deaktiviert");
        }
        System.out.println("══════════════════════════════════════");
    }
}
