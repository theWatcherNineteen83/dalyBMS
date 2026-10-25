package de.dalybms;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Passiver Sniffer fuer das JK-BMS-interne Parallelprotokoll (55 AA EB 90).
 *
 * Der JK-Host sendet auf dem RS485-Bus kontinuierlich Frames mit Header 55 AA EB 90.
 * Byte 4 = Frame-Typ:
 *   0x02 = Host-Daten (das Geraet selbst)
 *   0x01 = Slave-Daten (vom verbundenen Slave-BMS)
 *
 * Dieser Sniffer laeuft als Hintergrund-Thread und dekodiert die Frames
 * in Echtzeit. Die Daten koennen dann von JkBMS abgerufen werden.
 *
 * Empirisch verifizierte Offsets (Rohdump 25.10.2026, JK PB2A16S20P, 8S):
 *
 * HOST-Frame (Typ 02), ~296 Bytes:
 *   Zellspannungen : u16 BE @ 6..19 (7 Zellen, mV)
 *   Wire Resistance: u8   @ 63..77 (8 Werte, mOhm)
 *   Gesamtspannung : u16 BE @ 139 (mV) -- verifiziert: 26368 = 26.37V
 *   Restkapazitaet : u32 BE @ 137 (mAh) -- plausibel: 288512 mAh
 *   SOC            : u8   @ 162 (%)     -- verifiziert: 53%
 *   Temperatur     : u16 BE @ 214 (0.1°C) -- verifiziert: 178 = 17.8°C
 *   Vollkapazitaet : u32 BE @ 218 (mAh) -- plausibel: 327877 mAh
 *   Strom          : NICHT im Frame enthalten
 *
 * SLAVE-Frame (Typ 01): Struktur aehnlich, Offsets werden noch verifiziert.
 */
public class JkParallelSniffer {

    private static final Logger logger = Logger.getLogger(JkParallelSniffer.class.getName());

    /** Frame-Header */
    private static final byte[] HEADER = { 0x55, (byte) 0xAA, (byte) 0xEB, (byte) 0x90 };

    /** Typ-Konstanten */
    public static final int TYPE_SLAVE = 0x01;
    public static final int TYPE_HOST  = 0x02;

    /** Maximale Frame-Groesse (konservativ) */
    private static final int MAX_FRAME_SIZE = 512;

    /** Minimale Frame-Groesse fuer gueltige Daten */
    private static final int MIN_FRAME_SIZE = 50;

    // ==================== Dekodierte Daten (thread-safe) ====================

    /** Daten pro Frame-Typ (01 = Slave, 02 = Host) */
    public static class JkParallelData {
        public volatile int frameType;
        public volatile long lastUpdateMs;

        // Zellspannungen (mV)
        public final List<Integer> cellVoltagesMv = Collections.synchronizedList(new ArrayList<>());

        // Gesamtspannung (mV), -1 = unbekannt
        public volatile int totalVoltageMv = -1;

        // SOC (%), -1 = unbekannt
        public volatile int socPercent = -1;

        // Temperatur (0.1°C), -1 = unbekannt
        public volatile int temperatureRaw = -1;

        // Restkapazitaet (mAh), -1 = unbekannt
        public volatile long remainingCapacityMah = -1;

        // Vollkapazitaet (mAh), -1 = unbekannt
        public volatile long fullCapacityMah = -1;

        // Wire Resistance (mOhm)
        public final List<Integer> wireResistance = Collections.synchronizedList(new ArrayList<>());

        public boolean isValid() {
            return lastUpdateMs > 0 && socPercent >= 0;
        }

        public double getTemperatureCelsius() {
            return temperatureRaw > 0 ? temperatureRaw / 10.0 : Double.NaN;
        }

        public double getTotalVoltageV() {
            return totalVoltageMv > 0 ? totalVoltageMv / 1000.0 : Double.NaN;
        }
    }

    private final Map<Integer, JkParallelData> dataMap = new ConcurrentHashMap<>();
    private final SerialPort port;
    private volatile boolean running = false;
    private Thread readerThread;

    /**
     * Erstellt einen Sniffer auf einem bereits geoeffneten SerialPort.
     * Der Port muss bereits konfiguriert sein (115200 8N1).
     */
    public JkParallelSniffer(SerialPort port) {
        this.port = port;
    }

    /** Startet den Hintergrund-Reader. */
    public void start() {
        if (running) return;
        running = true;
        readerThread = new Thread(this::readLoop, "jk-parallel-sniffer");
        readerThread.setDaemon(true);
        readerThread.start();
        logger.info("JK Parallel-Sniffer gestartet");
    }

    /** Stoppt den Hintergrund-Reader. */
    public void stop() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("JK Parallel-Sniffer gestoppt");
    }

    /** Liefert die letzten dekodierten Daten fuer einen Frame-Typ. */
    public JkParallelData getData(int frameType) {
        return dataMap.get(frameType);
    }

    /** Liefert Host-Daten (Typ 02). */
    public JkParallelData getHostData() {
        return getData(TYPE_HOST);
    }

    /** Liefert Slave-Daten (Typ 01). */
    public JkParallelData getSlaveData() {
        return getData(TYPE_SLAVE);
    }

    // ==================== Reader-Thread ====================

    private void readLoop() {
        byte[] buf = new byte[4096];
        // Ringpuffer fuer Frame-Erkennung
        byte[] ring = new byte[MAX_FRAME_SIZE * 2];
        int ringLen = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int available = port.bytesAvailable();
                if (available <= 0) {
                    Thread.sleep(10);
                    continue;
                }

                int toRead = Math.min(available, buf.length);
                int n = port.readBytes(buf, toRead);
                if (n <= 0) {
                    Thread.sleep(10);
                    continue;
                }

                // Bytes in Ringpuffer kopieren
                for (int i = 0; i < n; i++) {
                    ring[ringLen++] = buf[i];
                    if (ringLen >= ring.length) {
                        // Ueberlauf: erste Haelfte verwerfen
                        System.arraycopy(ring, ring.length / 2, ring, 0, ring.length / 2);
                        ringLen = ring.length / 2;
                    }
                }

                // Frames suchen und extrahieren
                ringLen = extractFrames(ring, ringLen);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    logger.warning("JK Sniffer Fehler: " + e.getMessage());
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Sucht 55 AA EB 90-Header im Ringpuffer und extrahiert vollstaendige Frames.
     * Gibt die neue gueltige Laenge des Ringpuffers zurueck.
     */
    private int extractFrames(byte[] ring, int len) {
        int searchFrom = 0;

        while (searchFrom + 4 < len) {
            // Header suchen
            int hdrIdx = findHeader(ring, searchFrom, len);
            if (hdrIdx < 0) {
                // Kein Header gefunden: alles bis auf die letzten 3 Bytes verwerfen
                // (koennte Teil eines Headers sein)
                if (len > 3) {
                    System.arraycopy(ring, len - 3, ring, 0, 3);
                    return 3;
                }
                return len;
            }

            // Frame-Typ lesen
            int frameType = ring[hdrIdx + 4] & 0xFF;
            if (frameType != TYPE_HOST && frameType != TYPE_SLAVE) {
                // Unbekannter Typ: Header ueberspringen
                searchFrom = hdrIdx + 4;
                continue;
            }

            // Frame-Laenge bestimmen: suche naechsten Header oder Ende
            int nextHdr = findHeader(ring, hdrIdx + 4, len);
            int frameEnd = (nextHdr >= 0) ? nextHdr : len;
            int frameLen = frameEnd - hdrIdx;

            if (frameLen < MIN_FRAME_SIZE) {
                // Zu kurz: warten bis mehr Daten kommen
                if (nextHdr < 0) {
                    // Keine weiteren Daten: behalten und warten
                    return len;
                }
                searchFrom = hdrIdx + 4;
                continue;
            }

            // Frame dekodieren
            try {
                decodeFrame(ring, hdrIdx, frameLen, frameType);
            } catch (Exception e) {
                logger.fine("Frame-Dekodierung fehlgeschlagen (Typ " + frameType + "): " + e.getMessage());
            }

            searchFrom = frameEnd;
        }

        // Verbrauchte Bytes entfernen
        if (searchFrom > 0 && searchFrom < len) {
            int remaining = len - searchFrom;
            System.arraycopy(ring, searchFrom, ring, 0, remaining);
            return remaining;
        } else if (searchFrom >= len) {
            return 0;
        }
        return len;
    }

    /** Findet den naechsten 55 AA EB 90 Header ab Position 'from'. */
    private int findHeader(byte[] data, int from, int len) {
        for (int i = from; i + 3 < len; i++) {
            if ((data[i] & 0xFF) == 0x55
                    && (data[i + 1] & 0xFF) == 0xAA
                    && (data[i + 2] & 0xFF) == 0xEB
                    && (data[i + 3] & 0xFF) == 0x90) {
                return i;
            }
        }
        return -1;
    }

    // ==================== Frame-Dekodierung ====================

    private void decodeFrame(byte[] data, int offset, int len, int frameType) {
        JkParallelData d = dataMap.computeIfAbsent(frameType, k -> new JkParallelData());
        d.frameType = frameType;
        d.lastUpdateMs = System.currentTimeMillis();

        String typeName = (frameType == TYPE_HOST) ? "HOST" : "SLAVE";

        // Zellspannungen ab Offset 6 (u16 BE, mV)
        d.cellVoltagesMv.clear();
        for (int i = 6; i + 1 < Math.min(offset + len, 70); i += 2) {
            int relOff = i - offset;
            if (relOff < 6) continue;
            int mv = u16(data, i);
            if (mv >= 2000 && mv <= 4500) {
                d.cellVoltagesMv.add(mv);
            } else if (mv == 0 && !d.cellVoltagesMv.isEmpty()) {
                break;
            }
        }

        // Wire Resistance: nach den Zellen, typischerweise ab ~Offset 63
        d.wireResistance.clear();
        // Suche FF-Marker (CellSta)
        int ffIdx = -1;
        for (int i = offset + 20; i + 3 < offset + len; i++) {
            if ((data[i] & 0xFF) == 0xFF
                    && (data[i + 1] & 0xFF) == 0x00
                    && (data[i + 2] & 0xFF) == 0x00
                    && (data[i + 3] & 0xFF) == 0x00) {
                ffIdx = i;
                break;
            }
        }
        if (ffIdx >= 0 && ffIdx + 12 < offset + len) {
            // Wire Resistance beginnt typischerweise bei ffIdx+8 (nach FF 00 00 00 + Wiederholung Zellspannung)
            // Im Dump: FF 00 00 00 E1 0C 00 00 00 00 48 00 46 ...
            // WireRes startet bei ffIdx+10 (nach der wiederholten Zellspannung)
            int wrStart = ffIdx + 10;
            for (int i = wrStart; i + 1 < offset + len && i < wrStart + 32; i += 2) {
                int val = u16(data, i);
                if (val > 0 && val < 500) {
                    d.wireResistance.add(val);
                } else if (val == 0) {
                    break;
                }
            }
        }

        // Die folgenden Offsets sind empirisch aus dem Host-Dump (Typ 02).
        // Fuer den Slave (Typ 01) muessen sie ggf. angepasst werden.
        // Wir pruefen Plausibilitaet und verwenden nur valide Werte.

        // Gesamtspannung: u16 BE @ relativer Offset 139 (im Frame)
        if (len > 141) {
            int voltageMv = u16(data, offset + 139);
            if (voltageMv > 10000 && voltageMv < 60000) {
                d.totalVoltageMv = voltageMv;
            }
        }

        // Restkapazitaet: u32 BE @ relativer Offset 137
        if (len > 141) {
            long capMah = u32(data, offset + 137);
            if (capMah > 50000 && capMah < 500000) {
                d.remainingCapacityMah = capMah;
            }
        }

        // SOC: u8 @ relativer Offset 162
        if (len > 162) {
            int soc = data[offset + 162] & 0xFF;
            if (soc >= 0 && soc <= 100) {
                d.socPercent = soc;
            }
        }

        // Temperatur: u16 BE @ relativer Offset 214 (0.1°C)
        if (len > 216) {
            int tempRaw = u16(data, offset + 214);
            if (tempRaw > 0 && tempRaw < 1000) {
                d.temperatureRaw = tempRaw;
            }
        }

        // Vollkapazitaet: u32 BE @ relativer Offset 218
        if (len > 222) {
            long fullCap = u32(data, offset + 218);
            if (fullCap > 50000 && fullCap < 500000) {
                d.fullCapacityMah = fullCap;
            }
        }

        logger.fine(String.format("JK %s Frame: %d Zellen, SOC=%d%%, Spannung=%.2fV, Temp=%.1f°C",
                typeName, d.cellVoltagesMv.size(), d.socPercent,
                d.getTotalVoltageV(), d.getTemperatureCelsius()));
    }

    // ==================== Hilfsfunktionen ====================

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static long u32(byte[] data, int off) {
        return ((long) u16(data, off) << 16) | u16(data, off + 2);
    }
}
