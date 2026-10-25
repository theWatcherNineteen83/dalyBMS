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

    /** Maximale Frame-Groesse (Host-Frame ~308 Bytes, Puffer fuer mehrere Frames) */
    private static final int MAX_FRAME_SIZE = 400;

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
            // Der Sniffer liefert nur Zellspannungen + Wire Resistance,
            // kein SOC. Gueltig = innerhalb der letzten 30s ein Frame
            // mit mindestens einer Zellspannung empfangen.
            return lastUpdateMs > 0
                    && (System.currentTimeMillis() - lastUpdateMs) < 30000
                    && !cellVoltagesMv.isEmpty();
        }

        public double getTemperatureCelsius() {
            return temperatureRaw > 0 ? temperatureRaw / 10.0 : Double.NaN;
        }

        public double getTotalVoltageV() {
            return totalVoltageMv > 0 ? totalVoltageMv / 1000.0 : Double.NaN;
        }
    }

    private final Map<Integer, JkParallelData> dataMap = new ConcurrentHashMap<>();
    /** Zaehlt die ausgegebenen Hex-Dumps pro Frame-Typ (nur die ersten 3). */
    private final Map<Integer, Integer> frameDumpCount = new ConcurrentHashMap<>();
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
        byte[] ring = new byte[MAX_FRAME_SIZE * 4];
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
                // Kein Header gefunden: Nachlauf (letzte 3 Bytes) behalten,
                // Rest verwerfen.
                if (len > 3) {
                    System.arraycopy(ring, len - 3, ring, 0, 3);
                    return 3;
                }
                return len;
            }

            // Unbekannte Bytes vor dem Header verwerfen (Polling-Frames etc.)
            if (hdrIdx > searchFrom) {
                searchFrom = hdrIdx;
            }

            // Frame-Typ lesen
            int frameType = ring[hdrIdx + 4] & 0xFF;
            if (frameType != TYPE_HOST && frameType != TYPE_SLAVE) {
                searchFrom = hdrIdx + 4;
                continue;
            }

            // Frame-Ende bestimmen: der naechste 55 AA EB 90 Header.
            // WICHTIG: Nur dekodieren, wenn das Frame-Ende schon im Puffer ist,
            // sonst ist der Frame noch unvollstaendig (wird beim naechsten
            // readBytes-Chunk vervollstaendigt).
            int nextHdr = findHeader(ring, hdrIdx + 4, len);
            if (nextHdr < 0) {
                // Frame noch nicht vollstaendig: Bytes behalten und warten.
                // Verschiebe den Frame-Anfang an den Pufferanfang.
                if (hdrIdx > 0) {
                    System.arraycopy(ring, hdrIdx, ring, 0, len - hdrIdx);
                    return len - hdrIdx;
                }
                return len;
            }

            int frameLen = nextHdr - hdrIdx;
            if (frameLen < MIN_FRAME_SIZE) {
                // Zu kurzer Frame (vermutlich Fragment): ueberspringen.
                searchFrom = hdrIdx + 4;
                continue;
            }

            // Vollstaendiger Frame: dekodieren.
            try {
                decodeFrame(ring, hdrIdx, frameLen, frameType);
            } catch (Exception e) {
                logger.fine("Frame-Dekodierung fehlgeschlagen (Typ " + frameType + "): " + e.getMessage());
            }

            searchFrom = nextHdr;
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

        // Diagnose: die ersten 8 Frames pro Typ als Hex-Dump ausgeben,
        // damit die Offsets bestimmt werden koennen.
        Integer frameCount = frameDumpCount.get(frameType);
        int cnt = (frameCount == null) ? 0 : frameCount;
        if (cnt < 3) {
            frameDumpCount.put(frameType, cnt + 1);
            StringBuilder hex = new StringBuilder();
            int dumpEnd = Math.min(offset + len, offset + 256);
            for (int i = offset; i < dumpEnd; i += 16) {
                StringBuilder line = new StringBuilder(String.format("  %04X: ", i - offset));
                for (int j = i; j < i + 16 && j < dumpEnd; j++) {
                    line.append(String.format("%02X ", data[j] & 0xFF));
                }
                hex.append(line.toString().trim()).append("\n");
            }
            logger.info("JK " + typeName + " Frame [" + len + " bytes]:\n" + hex.toString());
        }

        // Zellspannungen: Format haengt vom Frame-Typ ab.
        //   HOST (Typ 02): u16 Big-Endian @ 6..21 (8 Zellen)
        //   SLAVE (Typ 01): u32 Little-Endian @ 6, je 4 Byte pro Zelle
        d.cellVoltagesMv.clear();
        if (frameType == TYPE_HOST) {
            // HOST-Zellspannungen sind u16 Little-Endian: "E2 0C" = 0x0CE2 = 3298 mV
            for (int i = offset + 6; i + 1 < offset + len; i += 2) {
                int mv = u16le(data, i);
                if (mv >= 1500 && mv <= 4500) {
                    d.cellVoltagesMv.add(mv);
                } else if (mv == 0 && !d.cellVoltagesMv.isEmpty()) {
                    break;
                }
            }
        } else {
            // Slave: u32 LE, 4 Byte pro Zelle
            for (int i = offset + 6; i + 3 < offset + len; i += 4) {
                int mv = u32le(data, i);
                if (mv >= 1500 && mv <= 4500) {
                    d.cellVoltagesMv.add(mv);
                } else {
                    break;
                }
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

        // HOST-Frame (Typ 02, ~200 Bytes) enthaelt weitere Felder
        // (empirisch verifiziert gegen Rohdump jk_dump2.txt 25.10.2026, Referenz SOC=57%).
        // Frame-Start = "55" (Header 55 AA EB 90 02 00), Offsets RELATIV dazu:
        //   Gesamtspannung : u16 LE @ 150 (mV)   -> 0x670E = 26.38V
        //   Temperatur 1   : u16 LE @ 162 (0.1C) -> 181 = 18.1C
        //   SOC            : u8     @ 173 (%)    -> 0x39 = 57%
        //   Restkapazitaet : u32 LE @ 174 (mAh)  -> 179606 = 179.6 Ah
        //   Vollkapazitaet : u32 LE @ 178 (mAh)  -> 314000 = 314.0 Ah
        // Diese Offsets gelten nur fuer den HOST-Frame (Typ 02).
        if (frameType == TYPE_HOST) {
            // Gesamtspannung
            if (len > 152) {
                int v = u16le(data, offset + 150);
                if (v > 10000 && v < 60000) d.totalVoltageMv = v;
            }
            // Temperatur (im 308B-Host-Frame: u16 LE @ 162 = 181 = 18.1°C)
            if (len > 164) {
                int t = u16le(data, offset + 162);
                if (t > 0 && t < 1000) d.temperatureRaw = t;
            }
            // Restkapazitaet (liefert den entscheidenden Marker Host vs Slave)
            if (len > 178) {
                long cap = u32le(data, offset + 174);
                if (cap > 10000 && cap < 1000000) d.remainingCapacityMah = cap;
            }
            // SOC: Nur uebernehmen, wenn es der HOST-Frame ist.
            // Host = Restkapazitaet ~179.6 Ah (< 250 Ah), Slave ~274.6 Ah (> 250 Ah).
            // Der Slave wird sowieso per Modbus ausgelesen, daher hier ignorieren.
            if (len > 173) {
                long restCap = d.remainingCapacityMah;
                boolean isHostFrame = restCap < 250000; // < 250 Ah = Host
                if (isHostFrame) {
                    int soc = data[offset + 173] & 0xFF;
                    if (soc > 0 && soc <= 100) d.socPercent = soc;
                }
            }
            // Vollkapazitaet
            if (len > 182) {
                long full = u32le(data, offset + 178);
                if (full > 10000 && full < 1000000) d.fullCapacityMah = full;
            }
        }

        logger.info(String.format("JK %s Frame [%d B] dekodiert: %d Zellen, SOC=%d%%, Spannung=%.2fV, Temp=%.1f C, Rest=%d mAh, Voll=%d mAh",
                typeName, len, d.cellVoltagesMv.size(), d.socPercent,
                d.getTotalVoltageV(), d.getTemperatureCelsius(),
                d.remainingCapacityMah, d.fullCapacityMah));
    }

    // ==================== Hilfsfunktionen ====================

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    /** Little-Endian 16-Bit (fuer HOST-Zellspannungen). */
    private static int u16le(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int off) {
        return ((long) u16(data, off) << 16) | u16(data, off + 2);
    }

    /** Little-Endian 32-Bit (fuer Slave-Zellspannungen). */
    private static int u32le(byte[] data, int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }
}
