package de.dalybms;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * JK BMS (PB-Serie) per RS485 / Modbus RTU auslesen.
 *
 * Erbt von DalyBMS, damit BmsRestServer, MqttPublisher und DalyBMSData
 * unverändert weiterverwendet werden können. Nur Lesen, keine Schreibzugriffe.
 *
 * Protokoll laut "JK BMS RS485 Modbus V1.0":
 *   115200 Baud, 8N1, Funktion 03H (Register lesen), Slave-Adresse 1..247
 *   Datenblock ab 0x1200 (nur lesbar), Werte Big-Endian.
 */
public class JkBMS extends DalyBMS {

    private static final Logger logger = Logger.getLogger(JkBMS.class.getName());

    // ---- Anpassbar, falls beim ersten Test Werte unplausibel sind ----
    /** Basisadresse des Echtzeit-Datenblocks */
    private static final int BASE = 0x1200;
    /**
     * false: Adresse = BASE + Offset/2 (Register zu je 2 Byte, Modbus-üblich)
     * true:  Adresse = BASE + Offset    (Adressen in Byte-Schritten, wie in der PDF-Tabelle)
     * Umschaltbar per -Djk.addrBytes=true
     */
    private static final boolean ADDR_IN_BYTES = Boolean.getBoolean("jk.addrBytes");

    private static final int RETRIES = 3;
    private static final int BLOCK_REGS = 64;          // 64 Register = 128 Byte pro Abfrage
    private static final int BLOCK_BYTES = BLOCK_REGS * 2;

    private final int slaveId;
    private SerialPort port;

    /** Letzter Rohdatenpuffer (Offset 0..255), für Diagnose */
    private final byte[] buf = new byte[BLOCK_BYTES * 2];

    private int cellCount = 0;
    private int tempSensorCount = 0;

    public JkBMS(int slaveId) {
        super();
        this.slaveId = slaveId;
    }

    public JkBMS() {
        this(1);
    }

    // ==================== Verbindung ====================

    @Override
    public void connect(String device) throws IOException {
        port = SerialPort.getCommPort(device);
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setParity(SerialPort.NO_PARITY);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 500);

        if (!port.openPort()) {
            throw new IOException("Konnte seriellen Port nicht öffnen: " + device);
        }
        logger.info("JK-BMS verbunden mit " + device + " (115200 8N1, Slave " + slaveId + ")");
    }

    @Override
    public void disconnect() {
        if (port != null && port.isOpen()) {
            port.closePort();
            logger.info("JK-BMS Verbindung getrennt");
        }
    }

    // ==================== Modbus ====================

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

    /** Liest 'count' Register ab Adresse 'reg' und liefert die reinen Datenbytes. */
    private byte[] readRegisters(int reg, int count) throws IOException {
        byte[] req = new byte[8];
        req[0] = (byte) slaveId;
        req[1] = 0x03;
        req[2] = (byte) (reg >> 8);
        req[3] = (byte) reg;
        req[4] = (byte) (count >> 8);
        req[5] = (byte) count;
        int crc = crc16(req, 6);
        req[6] = (byte) crc;          // CRC low zuerst
        req[7] = (byte) (crc >> 8);

        int expected = 5 + 2 * count;
        IOException last = null;

        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            try {
                // alte Reste im Empfangspuffer verwerfen
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
                    // Fehlerframe (5 Byte) nicht bis zum Timeout abwarten
                    if (got >= 5 && (resp[1] & 0x80) != 0) break;
                }

                if (got >= 5 && (resp[1] & 0x80) != 0) {
                    throw new IOException(String.format(
                            "Modbus-Fehlercode 0x%02X vom BMS", resp[2] & 0xFF));
                }
                if (got < expected) {
                    throw new IOException("Antwort unvollständig (" + got + " von "
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

    /** Block A = Offset 0..127 (Zellspannungen, Statistik), Block B = 128..255 (Spannung, Strom, SOC, ...). */
    private void readBlock(boolean second) throws IOException {
        int offset = second ? BLOCK_BYTES : 0;
        byte[] data = readRegisters(regAddress(offset), BLOCK_REGS);
        System.arraycopy(data, 0, buf, offset, BLOCK_BYTES);
    }

    // ==================== Werte aus dem Puffer ====================

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
        double totalVoltage = round(u32(144) / 1000.0, 3);   // mV -> V
        double current = round(s32(152) / 1000.0, 3);        // mA -> A
        double soc = u8(167);                                // %
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
        // Batterie-Sensoren 1..5 (Einheit 0,1 °C), Sensor 0 = MOSFET/Leistungsteil
        int[] offsets = {156, 158, 248, 250, 252};
        for (int i = 0; i < offsets.length; i++) {
            int raw = s16(offsets[i]);
            boolean plausible = raw > -400 && raw < 1500;
            // Sensor 1 und 2 immer zeigen, 3..5 nur wenn plausibel und nicht 0 (nicht bestückt)
            if (plausible && (i < 2 || raw != 0)) {
                temps.put(i + 1, (int) Math.round(raw / 10.0));
            }
        }
        int mos = s16(138);
        if (mos > -400 && mos < 1500) {
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

        // Min/Max Temperatur
        if (!d.temperatures.isEmpty()) {
            int hiS = -1, loS = -1, hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE;
            for (Map.Entry<Integer, Integer> e : d.temperatures.entrySet()) {
                if (e.getValue() > hi) { hi = e.getValue(); hiS = e.getKey(); }
                if (e.getValue() < lo) { lo = e.getValue(); loS = e.getKey(); }
            }
            d.temperatureRange = new DalyBMSData.TemperatureRange(hi, hiS, lo, loS);
        }

        // MOSFET / Status
        boolean charging = u8(192) != 0;
        boolean discharging = u8(193) != 0;
        double current = d.soc.current;
        String mode = current > 0.05 ? "charging" : (current < -0.05 ? "discharging" : "stationary");
        double capacityAh = round(s32(168) / 1000.0, 3);
        d.mosfetStatus = new DalyBMSData.MosfetStatus(mode, charging, discharging, capacityAh);

        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("CHARGE_MOS", charging);
        states.put("DISCHARGE_MOS", discharging);
        states.put("BALANCING", u8(166) != 0);
        states.put("CHARGER_PLUGGED", u8(239) != 0);
        d.status = new DalyBMSData.Status(d.cellVoltages.size(), d.temperatures.size(),
                charging, discharging, states, (int) u32(176));

        return d;
    }

    // ==================== Überschriebene Abfragen ====================

    @Override
    public DalyBMSData getAll() throws IOException {
        readBlock(false);
        readBlock(true);
        return decodeAll();
    }

    /** Schnell: nur Block B (Spannung, Strom, SOC) */
    @Override
    public DalyBMSData.SOC getSOC() throws IOException {
        readBlock(true);
        return decodeSoc();
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
    public Map<Integer, Double> getCellVoltages() throws IOException {
        readBlock(false);
        return decodeCellVoltages();
    }

    @Override
    public Map<Integer, Integer> getTemperatures() throws IOException {
        readBlock(false);   // Mos-Temperatur (Offset 138)
        readBlock(true);    // Batterie-Temperaturen
        return decodeTemperatures();
    }

    @Override
    public List<String> getErrors() throws IOException {
        readBlock(true);
        return decodeErrors();
    }

    @Override
    public int getCellCount() {
        return cellCount;
    }

    @Override
    public int getTemperatureSensorCount() {
        return tempSensorCount;
    }

    // ==================== Nur lesen ====================

    @Override
    public void setDischargeMosfet(boolean on) throws IOException {
        throw new IOException("JK-BMS: Schreiben nicht unterstützt (nur lesen)");
    }

    @Override
    public void setChargeMosfet(boolean on) throws IOException {
        throw new IOException("JK-BMS: Schreiben nicht unterstützt (nur lesen)");
    }

    @Override
    public void setSOC(double value) throws IOException {
        throw new IOException("JK-BMS: Schreiben nicht unterstützt (nur lesen)");
    }

    // ==================== Testprogramm ====================

    private String hex(int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) sb.append(String.format("%02X ", buf[i] & 0xFF));
        return sb.toString().trim();
    }

    /**
     * Schnelltest direkt am BMS:
     *   java -cp ... de.dalybms.JkBMS /dev/ttyUSB0 [slave]
     * Gibt Rohbytes und dekodierte Werte aus, um Adressierung und Byte-Reihenfolge zu prüfen.
     */
    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : "/dev/ttyUSB0";
        int slave = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        JkBMS bms = new JkBMS(slave);
        bms.connect(device);
        try {
            DalyBMSData data = bms.getAll();
            System.out.println("Roh Zellen 1-4        : " + bms.hex(0, 8));
            System.out.println("Roh Gesamtspannung    : " + bms.hex(144, 4));
            System.out.println("Roh Leistung          : " + bms.hex(148, 4));
            System.out.println("Roh Strom             : " + bms.hex(152, 4));
            System.out.println("Roh Balance/SOC       : " + bms.hex(166, 2));
            System.out.println(data);
        } finally {
            bms.disconnect();
        }
    }
}
