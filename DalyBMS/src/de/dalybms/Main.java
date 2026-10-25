package de.dalybms;

import java.util.logging.*;

public class Main {

    private static final Logger logger = Logger.getLogger("DalyBMS");

    /** Software-Version (im Log beim Start sichtbar zur Verifikation des Builds). */
    public static final String VERSION = "1.4.0-jk-sniffer";

    public static void main(String[] args) {
        // ==================== Version ausgeben ====================
        System.out.println("══════════════════════════════════════");
        System.out.println("  Daly BMS Server - Version " + VERSION);
        System.out.println("══════════════════════════════════════");

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
                case "--jk":
                    config.override("jk.enabled", "true");
                    break;
                case "--jk-device":
                    config.override("jk.device", args[++i]);
                    break;
                case "--jk-baudrate":
                    config.override("jk.baudrate", args[++i]);
                    break;
                case "--jk-slave1":
                    config.override("jk.slave_id_1", args[++i]);
                    break;
                case "--jk-slave2":
                    config.override("jk.slave_id_2", args[++i]);
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
            logger.severe("BMS-Verbindung (Daly) fehlgeschlagen: " + e.getMessage());
            logger.severe("Server laeuft trotzdem weiter (JK-BMS kann separat ausgelesen werden).");
            // Daly ist optional: nicht abbrechen, falls JK-BMS aktiv ist
            if (!config.isJkEnabled()) {
                logger.severe("Kein JK-BMS konfiguriert - Abbruch.");
                System.exit(1);
            }
        }

        // ==================== JK-BMS verbinden (optional) ====================
        // 2x JK-BMS an einem zweiten USB/RS485-Bus.
        // Modus A: Parallelprotokoll-Sniffer (passiv, liest Host+Slave Frames)
        // Modus B: Modbus RTU (aktiv, nur fuer Slaves die auf Modbus antworten)
        JkBMS jkBmsHost = null;   // HOST via Parallelprotokoll (Typ 02)
        JkBMS jkBmsSlave = null;  // SLAVE via Parallelprotokoll (Typ 01) oder Modbus
        JkParallelSniffer jkSniffer = null;

        if (config.isJkEnabled()) {
            try {
                if (config.isJkParallelSnifferEnabled()) {
                    // === Parallelprotokoll-Modus ===
                    // Ein JkBMS oeffnet den Port und startet den Sniffer,
                    // beide (Host+Slave) nutzen denselben Sniffer.
                    logger.info("JK-BMS Parallelprotokoll-Sniffer wird gestartet...");

                    // Port oeffnen (fuer Sniffer)
                    JkBMS portOwner = new JkBMS(config.getJkSlaveId1());
                    portOwner.connect(config.getJkDevice());
                    jkSniffer = portOwner.startSniffer();

                    if (jkSniffer != null) {
                        // Host (Typ 02) - antwortet NICHT auf Modbus,
                        // nur Zellspannungen + WireRes ueber den Sniffer.
                        jkBmsHost = new JkBMS(JkParallelSniffer.TYPE_HOST, jkSniffer);
                        jkBmsHost.setSniffer(jkSniffer);
                        logger.info("JK-BMS HOST (Typ 02) via Parallelprotokoll konfiguriert");

                        // Slave (Typ 01) - antwortet auf Modbus (Adresse 1).
                        // Modbus als Primaerquelle (SOC/Spannung/Kapazitaet/Temp/Zellen),
                        // Sniffer als Ergaenzung.
                        jkBmsSlave = new JkBMS(config.getJkSlaveId1());
                        jkBmsSlave.connect(config.getJkDevice());
                        jkBmsSlave.setSniffer(jkSniffer);
                        jkBmsSlave.setParallelFrameType(JkParallelSniffer.TYPE_SLAVE);
                        logger.info("JK-BMS SLAVE (Typ 01) via Modbus + Parallelprotokoll konfiguriert");
                    } else {
                        logger.warning("Sniffer konnte nicht gestartet werden, fallback zu Modbus");
                        portOwner.disconnect();
                        portOwner = null;
                    }
                }

                // Fallback: reiner Modbus-Modus (wenn Sniffer deaktiviert oder fehlgeschlagen)
                if (jkBmsHost == null && jkBmsSlave == null) {
                    logger.info("Verbinde mit JK-BMS 1 (Slave " + config.getJkSlaveId1()
                            + ") an " + config.getJkDevice() + " via Modbus...");
                    jkBmsSlave = new JkBMS(config.getJkSlaveId1());
                    jkBmsSlave.connect(config.getJkDevice());

                    logger.info("Verbinde mit JK-BMS 2 (Slave " + config.getJkSlaveId2()
                            + ") an " + config.getJkDevice() + " via Modbus...");
                    jkBmsHost = new JkBMS(config.getJkSlaveId2(), (JkBMS) jkBmsSlave);
                }
            } catch (Exception e) {
                logger.severe("JK-BMS Verbindung fehlgeschlagen: " + e.getMessage());
                logger.severe("Starte ohne JK-BMS weiter.");
                jkBmsHost = null;
                jkBmsSlave = null;
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

        final JkBMS jkHost = jkBmsHost;
        final JkBMS jkSlave = jkBmsSlave;
        final JkParallelSniffer snifferRef = jkSniffer;
        try {
            BmsRestServer restServer = new BmsRestServer(bms, config.getHttpPort(), config.isReadOnly(), jkHost, jkSlave);
            restServer.start();

            logger.info("Server läuft. Beenden mit Ctrl+C");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Server wird beendet...");
                restServer.stop();
                bms.disconnect();
                if (snifferRef != null) snifferRef.stop();
                if (jkHost != null) jkHost.disconnect();
                if (jkSlave != null) jkSlave.disconnect();
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
        System.out.println("  --jk                JK-BMS aktivieren (2x am zweiten Bus)");
        System.out.println("  --jk-device         JK-BMS Bus (by-path-Pfad empfohlen)");
        System.out.println("  --jk-slave1         Modbus-Adresse JK-BMS 1 (Standard: 1)");
        System.out.println("  --jk-slave2         Modbus-Adresse JK-BMS 2 (Standard: 2)");
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
