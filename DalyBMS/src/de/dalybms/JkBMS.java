package de.dalybms;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * JK BMS (PB-Serie) per RS485 auslesen.
 *
 * Kombiniert zwei Datenquellen:
 * 1. Modbus RTU (FC 03H) fuer Slave-Daten (wenn der Slave auf Modbus antwortet)
 * 2. Passiver Sniffer des JK-internen Parallelprotokolls (55 AA EB 90) fuer
 *    Host- UND Slave-Daten (wenn Modbus nicht verfuegbar ist oder als Fallback)
 *
 * Der Sniffer laeuft als Hintergrund-Thread und liest kontinuierlich die
 * 55 AA EB 90-Frames vom Bus. Typ 02 = Host, Typ 01 = Slave.
 *
 * Empirisch verifizierte Offsets (Rohdump 25.10.2026, JK PB2A16S20P, 8S):
 *
 * === Modbus-RTU (Slave, Adresse 1) ===
 *   Zellspannungen : u16 @ 0x00..0x3E in mV
 *   SOC            : u8  @ 0xE7 (231) in %
 *   Restkapazitaet : u32 @ 0xE8 (232) in mAh
 *   Vollkapazitaet : u32 @ 0xEC (236) in mAh
 *   Charger Plugged: u8  @ 0xEE (238)
 *   TempMax        : u16 @ 0xDC (220) in 0.1°C
 *   TempMin        : u16 @ 0xDE (222) in 0.1°C
 *   MOS-Temp       : u16 @ 0xC5 (197) in 0.1°C
 *
 * === Parallelprotokoll 55 AA EB 90 (Host + Slave) ===
 *   Zellspannungen : u16 BE ab Offset 6 (mV)
 *   Wire Resistance: u8 ab ~Offset 63 (mOhm)
 *   Gesamtspannung : u16 BE @ 139 (mV)
 *   Restkapazitaet : u32 BE @ 137 (mAh)
 *   SOC            : u8   @ 162 (%)
 *   Temperatur     : u16 BE @ 214 (0.1°C)
 *   Vollkapazitaet : u32 BE @ 218 (mAh)
 *   Strom          : NICHT im Frame enthalten
 */
public class JkBMS extends DalyBMS {

    private static final Logger logger = Logger.getLogger(JkBMS.class.getName());

    // ---- Modbus-Konfiguration ----
    private static final int BASE = 0x1200;
    private static final boolean ADDR_IN_BYTES = Boolean.getBoolean("jk.addrBytes");
    private static final int RETRIES = 3;
    private static final int BLOCK_REGS = 64;
    private static final int BLOCK_BYTES = BLOCK_REGS * 2;

    private final int slaveId;
    private SerialPort port;
    private final Object busLock;
    private final boolean ownsPort;

    /** Letzter Rohdatenpuffer fuer Modbus (Offset 0..255) */
    private final byte[] buf = new byte[BLOCK_BYTES * 2];

    private int cellCount = 0;
    private int tempSensorCount = 0;

    /** Parallelprotokoll-Sniffer (optional, wird geteilt zwischen Host+Slave am selben Bus) */
    private JkParallelSniffer sniffer;
    /** Welcher Frame-Typ diesem JkBMS zugeordnet ist (01=Slave, 02=Host) */
    private final int parallelFrameType;
    /** Ob dieser JkBMS auch Modbus nutzen soll (nur Slave) */
    private final boolean useModbus;

    // ==================== Konstruktoren ====================

    /**
     * Standard-Konstruktor: Modbus-only (fuer Slave am Bus).
     */
    public JkBMS(int slaveId) {
        super();
        this.slaveId = slaveId;
        this.busLock = new Object();
        this.ownsPort = true;
        this.parallelFrameType = JkParallelSniffer.TYPE_SLAVE;
        this.useModbus = true;
    }

    /**
     * Shared-Bus-Konstruktor: nutzt den Port eines anderen JkBMS.
     */
    public JkBMS(int slaveId, JkBMS busOwner) {
        super();
        this.slaveId = slaveId;
        this.port = busOwner.port;
        this.busLock = busOwner.busLock;
        this.ownsPort = false;
        this.parallelFrameType = JkParallelSniffer.TYPE_SLAVE;
        this.useModbus = true;
    }

    /**
     * Parallelprotokoll-Konstruktor: nutzt den Sniffer statt Modbus.
     * @param frameType 0x02 = Host, 0x01 = Slave
     */
    public JkBMS(int frameType, JkParallelSniffer sniffer) {
        super();
        this.slaveId = (frameType == JkParallelSniffer.TYPE_HOST) ? 0 : 1;
        this.busLock = new Object();
        this.ownsPort = false;
        this.sniffer = sniffer;
        this.parallelFrameType = frameType;
        this.useModbus = false;
    }

    public JkBMS() {
        this(1);
    }

    // ==================== Verbindung ====================

    @Override
    public void connect(String device) throws IOException {
        if (!useModbus) {
            // Nur Sniffer-Modus: kein Port noetig
            logger.info("JK-BMS (" + getLabel() + ") nutzt Parallelprotokoll-Sniffer");
            return;
        }
        if (!ownsPort && port != null && port.isOpen()) {
            logger.info("JK-BMS (Slave " + slaveId + ") nutzt geteilten Bus " + port.getSystemPortName());
            return;
        }
        port = SerialPort.getCommPort(device);
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setParity(SerialPort.NO_PARITY);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 500);

        if (!port.openPort()) {
            throw new IOException("Konnte seriellen Port nicht oeffnen: " + device);
        }
        logger.info("JK-BMS verbunden mit " + device + " (115200 8N1, Slave " + slaveId + ")");
    }

    /**
     * Startet den Parallelprotokoll-Sniffer auf demselben Port.
     * Muss nach connect() aufgerufen werden, wenn Sniffer-Daten gewuenscht sind.
     */
    public JkParallelSniffer startSniffer() {
        if (sniffer != null) {
            sniffer.start();
            return sniffer;
        }
        if (port == null || !port.isOpen()) {
            logger.warning("Sniffer kann nicht gestartet werden: Port nicht offen");
            return null;
        }
        sniffer = new JkParallelSniffer(port);
        sniffer.start();
        return sniffer;
    }

    /** Setzt einen externen Sniffer (z.B. von einem anderen JkBMS erstellt). */
    public void setSniffer(JkParallelSniffer sniffer) {
        this.sniffer = sniffer;
    }

    @Override
    public void disconnect() {
        if (sniffer != null) {
            sniffer.stop();
        }
        if (ownsPort && port != null && port.isOpen()) {
            port.closePort();
            logger.info("JK-BMS Verbindung getrennt");
        }
    }

    private String getLabel() {
        return (parallelFrameType == JkParallelSniffer.TYPE_HOST) ? "HOST" : "SLAVE";
    }

    // ==================== Daten abrufen ====================

    @Override
    public DalyBMSData getAll() throws IOException {
        // Prioritaet: Sniffer-Daten (passiv, immer aktuell)
        if (sniffer != null) {
            JkParallelSniffer.JkParallelData pData = sniffer.getData(parallelFrameType);
            if (pData != null && pData.isValid()) {
                return decodeFromSniffer(pData);
            }
        }

        // Fallback: Modbus (nur fuer Slave)
        if (useModbus) {
            readBlock(false);
            readBlock(true);
            return decodeAll();
        }

        throw new IOException("Keine Daten verfuegbar (" + getLabel() + ")");
    }

    @Override
    public DalyBMSData.SOC getSOC() throws IOException {
        if (sniffer != null) {
            JkParallelSniffer.JkParallelData pData = sniffer.getData(parallelFrameType);
            if (pData != null && pData.socPercent >= 0) {
                double voltage = pData.getTotalVoltageV();
                double soc = pData.socPercent;
                return new DalyBMSData.SOC(
                        Double.isNaN(voltage) ? 0 : voltage,
                        0,  // Strom nicht im Parallelprotokoll
                        soc
                );
            }
        }
        if (useModbus) {
            readBlock(true);
            return decodeSoc();
        }
        throw new IOException("Kein SOC verfuegbar (" + getLabel() + ")");
    }

    @Override
    public Map<Integer, Double> getCellVoltages() throws IOException {
        if (sniffer != null) {
            JkParallelSniffer.JkParallelData pData = sniffer.getData(parallelFrameType);
            if (pData != null && !pData.cellVoltagesMv.isEmpty()) {
                Map<Integer, Double> cells = new LinkedHashMap<>();
                for (int i = 0; i < pData.cellVoltagesMv.size(); i++) {
                    cells.put(i + 1, pData.cellVoltagesMv.get(i) / 1000.0);
                }
                cellCount = cells.size();
                return cells;
            }
        }
        if (useModbus) {
            readBlock(false);
            return decodeCellVoltages();
        }
        throw new IOException("Keine Zellspannungen verfuegbar (" + getLabel() + ")");
    }

    @Override
    public Map<Integer, Integer> getTemperatures() throws IOException {
        if (sniffer != null) {
            JkParallelSniffer.JkParallelData pData = sniffer.getData(parallelFrameType);
            if (pData != null && pData.temperatureRaw > 0) {
                Map<Integer, Integer> temps = new LinkedHashMap<>();
                temps.put(1, (int) Math.round(pData.getTemperatureCelsius()));
                tempSensorCount = 1;
                return temps;
            }
        }
        if (useModbus) {
            readBlock(false);
            readBlock(true);
            return decodeTemperatures();
        }
        throw new IOException("Keine Temperaturen verfuegbar (" + getLabel() + ")");
    }

    @Override
    public List<String> getErrors() throws IOException {
        if (useModbus) {
            readBlock(true);
            return decodeErrors();
        }
        return Collections.emptyList();
    }

    @Override
    public DalyBMSData.CellVoltageRange getCellVoltageRange() throws IOException {
        return getAll().cellVoltageRange;
    }

    @Override
    public DalyBMSData.TemperatureRange getTemperatureRange() throws IOException {
        return getAll().temperatureRange;
    }

    @Override
    public DalyBMSData.MosfetStatus getMosfetStatus() throws IOException {
        return getAll().mosfetStatus;
    }

    @Override
    public DalyBMSData.Status getStatus() throws IOException {
        return getAll().status;
    }

    @Override
    public int getCellCount() { return cellCount; }

    @Override
    public int getTemperatureSensorCount() { return tempSensorCount; }

    // ==================== Nur lesen ====================

    @Override
    public void setDischargeMosfet(boolean on) throws IOException {
        throw new IOException("JK-BMS: Schreiben nicht unterstuetzt (nur lesen)");
    }

    @Override
    public void setChargeMosfet(boolean on) throws IOException {
        throw new IOException("JK-BMS: Schreiben nicht unterstuetzt (nur lesen)");
    }

    @Override
    public void setSOC(double value) throws IOException {
        throw new IOException("JK-BMS: Schreiben nicht unterstuetzt (nur lesen)");
    }

    // ==================== Sniffer-Daten dekodieren ====================

    private DalyBMSData decodeFromSniffer(JkParallelSniffer.JkParallelData pData) {
        DalyBMSData d = new DalyBMSData();

        // SOC
        double voltage = pData.getTotalVoltageV();
        d.soc = new DalyBMSData.SOC(
                Double.isNaN(voltage) ? 0 : voltage,
                0,  // Strom nicht im Parallelprotokoll
                pData.socPercent
        );

        // Zellspannungen
        d.cellVoltages = new LinkedHashMap<>();
        for (int i = 0; i < pData.cellVoltagesMv.size(); i++) {
            d.cellVoltages.put(i + 1, pData.cellVoltagesMv.get(i) / 1000.0);
        }
        cellCount = d.cellVoltages.size();

        // Min/Max Zellspannung
        if (!d.cellVoltages.isEmpty()) {
            int hiCell = -1, loCell = -1;
            double hi = -1, lo = Double.MAX_VALUE;
            for (Map.Entry<Integer, Double> e : d.cellVoltages.entrySet()) {
                if (e.getValue() > hi) { hi = e.getValue(); hiCell = e.getKey(); }
                if (e.getValue() < lo) { lo = e.getValue(); loCell = e.getKey(); }
            }
            d.cellVoltageRange = new DalyBMSData.CellVoltageRange(hi, hiCell, lo, loCell);
        }

        // Temperaturen
        d.temperatures = new LinkedHashMap<>();
        if (pData.temperatureRaw > 0) {
            d.temperatures.put(1, (int) Math.round(pData.getTemperatureCelsius()));
        }
        tempSensorCount = d.temperatures.size();

        if (!d.temperatures.isEmpty()) {
            int hiS = -1, loS = -1, hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE;
            for (Map.Entry<Integer, Integer> e : d.temperatures.entrySet()) {
                if (e.getValue() > hi) { hi = e.getValue(); hiS = e.getKey(); }
                if (e.getValue() < lo) { lo = e.getValue(); loS = e.getKey(); }
            }
            d.temperatureRange = new DalyBMSData.TemperatureRange(hi, hiS, lo, loS);
        }

        // Kapazitaet
        double capacityAh = pData.remainingCapacityMah > 0
                ? Math.round(pData.remainingCapacityMah / 1000.0 * 1000.0) / 1000.0
                : 0;
        d.mosfetStatus = new DalyBMSData.MosfetStatus("unknown", false, false, capacityAh);

        // Status
        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("SOURCE", true);
        states.put("PARALLEL_PROTOCOL", true);
        d.status = new DalyBMSData.Status(cellCount, tempSensorCount,
                false, false, states, 0);

        d.errors = Collections.emptyList();

        return d;
    }

    // ==================== Modbus-Methoden (fuer Slave-Fallback) ====================

    private static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    private int regAddress(int offset) {
        return BASE + (ADDR_IN_BYTES ? offset : offset / 2);
    }

    private byte[] readRegisters(int reg, int count) throws IOException {
        synchronized (busLock) {
            return readRegistersLocked(reg, count);
        }
    }

    private byte[] readRegistersLocked(int reg, int count) throws IOException {
        byte[] req = new byte[8];
        req[0] = (byte) slaveId;
        req[1] = 0x03;
        req[2] = (byte) (reg >> 8);
        req[3] = (byte) reg;
        req[4] = (byte) (count >> 8);
        req[5] = (byte) count;
        int crc = crc16(req, 6);
        req[6] = (byte) crc;
        req[7] = (byte) (crc >> 8);

        int expected = 5 + 2 * count;
        IOException last = null;

        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            try {
                while (port.bytesAvailable() > 0) {
                    byte[] trash = new byte[port.bytesAvailable()];
                    port.readBytes(trash, trash.length);
                }

                port.writeBytes(req, req.length);

                byte[] resp = new byte[expected];
                int got = 0;
                long deadline = System.currentTimeMillis() + 1500;
                while (got < expected && System.currentTimeMillis() < deadline) {
                    int n = port.readBytes(resp, expected - got, got);
                    if (n > 0) got += n;
                    if (got >= 5 && (resp[1] & 0x80) != 0) break;
                }

                if (got >= 5 && (resp[1] & 0x80) != 0) {
                    throw new IOException(String.format(
                            "Modbus-Fehlercode 0x%02X vom BMS", resp[2] & 0xFF));
                }
                if (got < expected) {
                    throw new IOException("Antwort unvollstaendig (" + got + " von "
                            + expected + " Byte)");
                }
                if ((resp[0] & 0xFF) != slaveId || resp[1] != 0x03
                        || (resp[2] & 0xFF) != 2 * count) {
                    throw new IOException("Unerwarteter Frame-Header");
                }
                int calc = crc16(resp, expected - 2);
                int recv = (resp[expected - 2] & 0xFF) | ((resp[expected - 1] & 0xFF) << 8);
                if (calc != recv) {
                    throw new IOException(String.format(
                            "CRC-Fehler (berechnet %04X, empfangen %04X)", calc, recv));
                }

                return Arrays.copyOfRange(resp, 3, 3 + 2 * count);

            } catch (IOException e) {
                last = e;
                logger.fine("JK Versuch " + attempt + "/" + RETRIES + ": " + e.getMessage());
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("JK-BMS Abfrage fehlgeschlagen: "
                + (last != null ? last.getMessage() : "unbekannt"));
    }

    private void readBlock(boolean second) throws IOException {
        int offset = second ? BLOCK_BYTES : 0;
        byte[] data = readRegisters(regAddress(offset), BLOCK_REGS);
        System.arraycopy(data, 0, buf, offset, BLOCK_BYTES);
    }

    // ==================== Werte aus dem Modbus-Puffer ====================

    private int u8(int off)  { return buf[off] & 0xFF; }
    private int u16(int off) { return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF); }
    private int s16(int off) { return (short) u16(off); }
    private long u32(int off) {
        return ((long) u16(off) << 16) | u16(off + 2);
    }
    private int s32(int off) { return (int) u32(off); }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private DalyBMSData.SOC decodeSoc() {
        double totalVoltage = 0;
        double current = 0;
        double soc = u8(231);     // 0xE7 - verifiziert
        return new DalyBMSData.SOC(totalVoltage, current, soc);
    }

    private Map<Integer, Double> decodeCellVoltages() {
        Map<Integer, Double> cells = new LinkedHashMap<>();
        for (int i = 0; i < 32; i++) {
            int mv = u16(i * 2);
            if (mv > 0) {
                cells.put(i + 1, round(mv / 1000.0, 3));
            }
        }
        cellCount = cells.size();
        return cells;
    }

    private Map<Integer, Integer> decodeTemperatures() {
        Map<Integer, Integer> temps = new LinkedHashMap<>();
        int maxRaw = u16(220);   // 0xDC
        int minRaw = u16(222);   // 0xDE
        if (maxRaw > 0 && maxRaw < 1500) {
            temps.put(1, (int) Math.round(maxRaw / 10.0));
        }
        if (minRaw > 0 && minRaw < 1500) {
            temps.put(2, (int) Math.round(minRaw / 10.0));
        }
        int mos = u16(197);      // 0xC5
        if (mos > 0 && mos < 1500) {
            temps.put(0, (int) Math.round(mos / 10.0));
        }
        tempSensorCount = temps.size();
        return temps;
    }

    private static final String[] ALARMS = {
            "Balance wire resistance too high",
            "MOSFET over temperature",
            "Cell count does not match setting",
            "Current sensor fault",
            "Cell overvoltage protection",
            "Battery overvoltage protection",
            "Charge overcurrent protection",
            "Charge short circuit protection",
            "Charge over temperature protection",
            "Charge under temperature protection",
            "Internal communication fault",
            "Cell undervoltage protection",
            "Battery undervoltage protection",
            "Discharge overcurrent protection",
            "Discharge short circuit protection",
            "Discharge over temperature protection",
            "Charge MOSFET fault",
            "Discharge MOSFET fault",
            "GPS disconnected",
            "Please change the authorization password",
            "Discharge on failed",
            "Battery over temperature alarm"
    };

    private List<String> decodeErrors() {
        long bits = u32(160);
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < ALARMS.length; i++) {
            if ((bits & (1L << i)) != 0) {
                errors.add(ALARMS[i]);
            }
        }
        return errors;
    }

    private DalyBMSData decodeAll() {
        DalyBMSData d = new DalyBMSData();

        d.soc = decodeSoc();
        d.cellVoltages = decodeCellVoltages();
        d.temperatures = decodeTemperatures();
        d.errors = decodeErrors();

        if (!d.cellVoltages.isEmpty()) {
            int hiCell = -1, loCell = -1;
            double hi = -1, lo = Double.MAX_VALUE;
            for (Map.Entry<Integer, Double> e : d.cellVoltages.entrySet()) {
                if (e.getValue() > hi) { hi = e.getValue(); hiCell = e.getKey(); }
                if (e.getValue() < lo) { lo = e.getValue(); loCell = e.getKey(); }
            }
            d.cellVoltageRange = new DalyBMSData.CellVoltageRange(hi, hiCell, lo, loCell);
        }

        if (!d.temperatures.isEmpty()) {
            int hiS = -1, loS = -1, hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE;
            for (Map.Entry<Integer, Integer> e : d.temperatures.entrySet()) {
                if (e.getValue() > hi) { hi = e.getValue(); hiS = e.getKey(); }
                if (e.getValue() < lo) { lo = e.getValue(); loS = e.getKey(); }
            }
            d.temperatureRange = new DalyBMSData.TemperatureRange(hi, hiS, lo, loS);
        }

        boolean charging = u8(192) != 0;
        boolean discharging = u8(193) != 0;
        double current = d.soc.current;
        String mode = current > 0.05 ? "charging" : (current < -0.05 ? "discharging" : "stationary");
        double capacityAh = round(u32(232) / 1000.0, 3);
        d.mosfetStatus = new DalyBMSData.MosfetStatus(mode, charging, discharging, capacityAh);

        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("CHARGE_MOS", charging);
        states.put("DISCHARGE_MOS", discharging);
        states.put("BALANCING", u8(166) != 0);
        states.put("CHARGER_PLUGGED", u8(238) != 0);
        d.status = new DalyBMSData.Status(d.cellVoltages.size(), d.temperatures.size(),
                charging, discharging, states, (int) u32(176));

        return d;
    }

    // ==================== Testprogramm ====================

    private String hex(int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) sb.append(String.format("%02X ", buf[i] & 0xFF));
        return sb.toString().trim();
    }

    private void dump() {
        for (int row = 0; row < buf.length; row += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder asc = new StringBuilder();
            for (int i = row; i < row + 16 && i < buf.length; i++) {
                int b = buf[i] & 0xFF;
                hex.append(String.format("%02X ", b));
                asc.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            System.out.println(String.format("%04X  %-48s  %s", row, hex, asc));
        }
    }

    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : "/dev/ttyUSB0";
        int slave = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        JkBMS bms = new JkBMS(slave);
        bms.connect(device);
        try {
            DalyBMSData data = bms.getAll();
            System.out.println("===== Voller Hex-Dump (256 Byte) =====");
            bms.dump();
            System.out.println("======================================");
            System.out.println(data);
        } finally {
            bms.disconnect();
        }
    }
}
