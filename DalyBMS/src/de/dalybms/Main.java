package de.dalybms;

import java.util.logging.*;

public class Main {

    private static final Logger logger = Logger.getLogger("DalyBMS");

    public static void main(String[] args) {
        // ==================== Konfiguration laden ====================
        Config config = new Config();
        String configFile = null;

        // ==================== Argumente parsen (überschreiben Config) ====================
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                    configFile = args[++i];
                    config = new Config(configFile);
                    break;
                case "-d":
                case "--device":
                    config.override("serial.device", args[++i]);
                    break;
                case "--mqtt":
                    config.override("mqtt.enabled", "true");
                    break;
                case "--mqtt-broker":
                    config.override("mqtt.broker", args[++i]);
                    break;
                case "--mqtt-port":
                    config.override("mqtt.port", args[++i]);
                    break;
                case "--mqtt-user":
                    config.override("mqtt.username", args[++i]);
                    break;
                case "--mqtt-password":
                    config.override("mqtt.password", args[++i]);
                    break;
                case "--mqtt-topic":
                    config.override("mqtt.topic", args[++i]);
                    break;
                case "--rest":
                    // REST ist implizit immer aktiv, --rest nur für Kompatibilität
                    break;
                case "--http-port":
                    config.override("http.port", args[++i]);
                    break;
                case "--poll-interval":
                    config.override("http.poll_interval_seconds", args[++i]);
                    break;
                case "--read-write":
                    config.override("readonly", "false");
                    break;
                case "--verbose":
                case "-v":
                    config.override("verbose", "true");
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    return;
            }
        }

        setupLogging(config.isVerbose());

        // Zeige Konfiguration
        config.print();

        // ==================== BMS verbinden ====================
        DalyBMS bms = new DalyBMS(
                config.getSerialRequestRetries(),
                config.getSerialAddress()
        );

        try {
            logger.info("Verbinde mit BMS an " + config.getSerialDevice() + "...");
            bms.connect(config.getSerialDevice());
        } catch (Exception e) {
            logger.severe("BMS-Verbindung fehlgeschlagen: " + e.getMessage());
            System.exit(1);
        }

        // ==================== JK-BMS verbinden (optional) ====================
        // 2x JK-BMS an einem zweiten USB/RS485-Bus (Modbus RTU, eigene Slave-Adressen)
        JkBMS jkBms1 = null;
        JkBMS jkBms2 = null;
        if (config.isJkEnabled()) {
            try {
                logger.info("Verbinde mit JK-BMS 1 (Slave " + config.getJkSlaveId1()
                        + ") an " + config.getJkDevice() + "...");
                jkBms1 = new JkBMS(config.getJkSlaveId1());
                jkBms1.connect(config.getJkDevice());

                logger.info("Verbinde mit JK-BMS 2 (Slave " + config.getJkSlaveId2()
                        + ") an " + config.getJkDevice() + "...");
                jkBms2 = new JkBMS(config.getJkSlaveId2());
                jkBms2.connect(config.getJkDevice());
            } catch (Exception e) {
                logger.severe("JK-BMS Verbindung fehlgeschlagen: " + e.getMessage());
                logger.severe("Starte ohne JK-BMS weiter.");
                // JK nicht blockierend: bei Fehler ohne JK weiterlaufen lassen
                jkBms1 = null;
                jkBms2 = null;
            }
        }

        // ==================== REST Server + MQTT Modus ====================
        if (config.isMqttEnabled()) {
            // Starte MQTT im Hintergrund
            startMqttLoop(bms,
                    config.getMqttBroker(),
                    config.getMqttPort(),
                    config.getMqttUsername(),
                    config.getMqttPassword(),
                    config.getMqttTopic(),
                    config.getPollIntervalSeconds());
        }

        final JkBMS jk1 = jkBms1;
        final JkBMS jk2 = jkBms2;
        try {
            BmsRestServer restServer = new BmsRestServer(bms, config.getHttpPort(), config.isReadOnly(), jk1, jk2);
            restServer.start();

            logger.info("Server läuft. Beenden mit Ctrl+C");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Server wird beendet...");
                restServer.stop();
                bms.disconnect();
                if (jk1 != null) jk1.disconnect();
                if (jk2 != null) jk2.disconnect();
            }));

            Thread.currentThread().join();

        } catch (Exception e) {
            logger.severe("REST Server Fehler: " + e.getMessage());
            e.printStackTrace();
            bms.disconnect();
            System.exit(1);
        }
    }

    /**
     * MQTT im Hintergrund senden (parallel zum REST Server)
     */
    private static void startMqttLoop(DalyBMS bms, String broker, int port,
                                       String user, String password,
                                       String topic, int intervalSeconds) {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

        MqttPublisher mqtt = new MqttPublisher(broker, port, user, password, topic);
        try {
            mqtt.connect();
        } catch (Exception e) {
            logger.severe("MQTT Verbindung fehlgeschlagen: " + e.getMessage());
            return;
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                DalyBMSData data = bms.getAll();
                mqtt.publishAll(data);
            } catch (Exception e) {
                logger.warning("MQTT Fehler: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void printHelp() {
        System.out.println("Daly BMS CLI + REST Server (Java)");
        System.out.println("===================================");
        System.out.println();
        System.out.println("Konfiguration:");
        System.out.println("  --config <pfad>     Alternative config.properties laden");
        System.out.println();
        System.out.println("Optionen (überschreiben config.properties):");
        System.out.println("  -d, --device        Serielles Gerät (Standard: /dev/ttyUSB0)");
        System.out.println("  --http-port         HTTP Port (Standard: 8080)");
        System.out.println("  --poll-interval     Abfrage-Intervall in Sekunden (Standard: 30)");
        System.out.println("  --mqtt              MQTT aktivieren");
        System.out.println("  --mqtt-broker       MQTT Broker (Standard: 192.168.20.100)");
        System.out.println("  --mqtt-port         MQTT Port (Standard: 1883)");
        System.out.println("  --mqtt-user         MQTT Benutzername");
        System.out.println("  --mqtt-password     MQTT Passwort");
        System.out.println("  --mqtt-topic        MQTT Topic (Standard: daly_bms)");
        System.out.println("  --read-write        Schreibzugriff erlauben (MOSFET, SOC)");
        System.out.println("  -v, --verbose       Ausführliche Ausgabe");
        System.out.println("  -h, --help          Diese Hilfe anzeigen");
        System.out.println();
        System.out.println("Beispiele:");
        System.out.println("  # REST Server (Standard):");
        System.out.println("  java -jar DalyBMS.jar");
        System.out.println();
        System.out.println("  # REST + MQTT:");
        System.out.println("  java -jar DalyBMS.jar --mqtt");
        System.out.println();
        System.out.println("  # Mit anderer config-Datei:");
        System.out.println("  java -jar DalyBMS.jar --config /pfad/zu/config.properties");
    }

    private static void setupLogging(boolean verbose) {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("[%s] %s%n", record.getLevel(), record.getMessage());
            }
        });
        if (verbose) {
            handler.setLevel(Level.ALL);
            Logger.getLogger("de.dalybms").setLevel(Level.ALL);
        } else {
            handler.setLevel(Level.INFO);
        }
        rootLogger.addHandler(handler);
    }
}
