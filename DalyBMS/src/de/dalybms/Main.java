package de.dalybms;

import java.util.logging.*;

public class Main {

    private static final Logger logger = Logger.getLogger("DalyBMS");

    public static void main(String[] args) {
        // ==================== Konfiguration ====================
        String serialDevice = "/dev/ttyUSB0";
        String mqttBroker = "192.168.20.100";
        int mqttPort = 1883;
        String mqttUser = "dalybms";
        String mqttPassword = "eveakku";
        String mqttTopic = "daly_bms";
        boolean useMqtt = false;
        boolean useRest = false;
        int httpPort = 8080;
        int pollInterval = 30;  // Sekunden
        boolean verbose = false;

        // ==================== Argumente parsen ====================
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-d":
                case "--device":
                    serialDevice = args[++i];
                    break;
                case "--mqtt":
                    useMqtt = true;
                    break;
                case "--mqtt-broker":
                    mqttBroker = args[++i];
                    break;
                case "--mqtt-port":
                    mqttPort = Integer.parseInt(args[++i]);
                    break;
                case "--mqtt-user":
                    mqttUser = args[++i];
                    break;
                case "--mqtt-password":
                    mqttPassword = args[++i];
                    break;
                case "--mqtt-topic":
                    mqttTopic = args[++i];
                    break;
                case "--rest":
                    useRest = true;
                    break;
                case "--http-port":
                    httpPort = Integer.parseInt(args[++i]);
                    break;
                case "--poll-interval":
                    pollInterval = Integer.parseInt(args[++i]);
                    break;
                case "--verbose":
                case "-v":
                    verbose = true;
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    return;
            }
        }

        setupLogging(verbose);

        // ==================== BMS verbinden ====================
        DalyBMS bms = new DalyBMS();
        try {
            logger.info("Verbinde mit BMS an " + serialDevice + "...");
            bms.connect(serialDevice);
        } catch (Exception e) {
            logger.severe("BMS-Verbindung fehlgeschlagen: " + e.getMessage());
            System.exit(1);
        }

     // ==================== REST Server Modus ====================
        if (useRest) {
            try {
                BmsRestServer restServer = new BmsRestServer(bms, httpPort);
                restServer.start();

                logger.info("Server läuft. Beenden mit Ctrl+C");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    logger.info("Server wird beendet...");
                    restServer.stop();
                    bms.disconnect();
                }));

                Thread.currentThread().join();

            } catch (Exception e) {
                logger.severe("REST Server Fehler: " + e.getMessage());
                e.printStackTrace();
                bms.disconnect();
                System.exit(1);
            }
        }
        // ==================== Einmal-Modus (wie vorher) ====================
        else {
            try {
                DalyBMSData data = bms.getAll();
                System.out.println(data.toString());

                if (useMqtt) {
                    MqttPublisher mqtt = new MqttPublisher(
                            mqttBroker, mqttPort, mqttUser, mqttPassword, mqttTopic);
                    mqtt.connect();
                    mqtt.publishAll(data);
                    mqtt.disconnect();
                }
            } catch (Exception e) {
                logger.severe("Fehler: " + e.getMessage());
                e.printStackTrace();
            } finally {
                bms.disconnect();
            }
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
        System.out.println("Modi:");
        System.out.println("  (Standard)     Einmal auslesen und beenden");
        System.out.println("  --rest         REST Server starten (läuft dauerhaft)");
        System.out.println("  --mqtt         Daten per MQTT senden");
        System.out.println("  --rest --mqtt  Beides gleichzeitig");
        System.out.println();
        System.out.println("Optionen:");
        System.out.println("  -d, --device        Serielles Gerät (Standard: /dev/ttyUSB0)");
        System.out.println("  --http-port         HTTP Port (Standard: 8080)");
        System.out.println("  --poll-interval     Abfrage-Intervall in Sekunden (Standard: 30)");
        System.out.println("  --mqtt-broker       MQTT Broker (Standard: 192.168.20.100)");
        System.out.println("  --mqtt-port         MQTT Port (Standard: 1883)");
        System.out.println("  --mqtt-user         MQTT Benutzername");
        System.out.println("  --mqtt-password     MQTT Passwort");
        System.out.println("  --mqtt-topic        MQTT Topic (Standard: daly_bms)");
        System.out.println("  -v, --verbose       Ausführliche Ausgabe");
        System.out.println("  -h, --help          Diese Hilfe anzeigen");
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