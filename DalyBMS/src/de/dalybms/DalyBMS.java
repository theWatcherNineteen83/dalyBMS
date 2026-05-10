package de.dalybms;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Logger;

public class DalyBMS {

    private static final Logger logger = Logger.getLogger(DalyBMS.class.getName());

    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;
    private int address;
    private int requestRetries;

    // Gespeicherter Status (wird von getStatus() gesetzt)
    private int cellCount = 0;
    private int temperatureSensorCount = 0;

    
    /**
     * @param requestRetries Anzahl der Wiederholungen bei fehlgeschlagenen Anfragen
     * @param address        Quelladresse (4 = USB/RS485, 8 = UART/Bluetooth)
     */
    public DalyBMS(int requestRetries, int address) {
        this.requestRetries = requestRetries;
        this.address = address;
    }
    
    public DalyBMS() {
        this(5, 4);  // 5 Retries statt 3
    }

    
    /**
     * Verbindung zum seriellen Gerät herstellen
     *
     * @param device z.B. "/dev/ttyUSB0" oder "COM3"
     */
    public void connectAlt(String device) throws IOException {
        serialPort = SerialPort.getCommPort(device);
        serialPort.setBaudRate(9600);
        serialPort.setNumDataBits(8);
        serialPort.setParity(SerialPort.NO_PARITY);
        serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 500);
        serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

        if (!serialPort.openPort()) {
            throw new IOException("Konnte seriellen Port nicht öffnen: " + device);
        }

        inputStream = serialPort.getInputStream();
        outputStream = serialPort.getOutputStream();

        logger.info("Verbunden mit " + device);

        //kein Polling
        // Status einmal laden, um cellCount und temperatureSensorCount zu kennen
        //getStatus();
    }
    public void connect(String device) throws IOException {
        serialPort = SerialPort.getCommPort(device);
        serialPort.setBaudRate(9600);
        serialPort.setNumDataBits(8);
        serialPort.setParity(SerialPort.NO_PARITY);
        serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

        // Timeout erhöht: 2000ms statt 500ms
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                2000,   // Read Timeout (ms)
                2000    // Write Timeout (ms)
        );

        if (!serialPort.openPort()) {
            throw new IOException("Konnte seriellen Port nicht öffnen: " + device);
        }

        inputStream = serialPort.getInputStream();
        outputStream = serialPort.getOutputStream();

        logger.info("Verbunden mit " + device);
    }
    

    /**
     * Verbindung trennen
     */
    public void disconnect() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            logger.info("Verbindung getrennt");
        }
    }

    // ==================== Checksum ====================

    private byte calcCrc(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF);
        }
        return (byte) (sum & 0xFF);
    }

    // ==================== Nachricht formatieren ====================

    private byte[] formatMessage(int command, byte[] extra) {
        byte[] message = new byte[13];
        message[0] = (byte) 0xA5;
        message[1] = (byte) (address * 16); // 4 -> 0x40, 8 -> 0x80
        message[2] = (byte) command;
        message[3] = 0x08; // Datenlänge

        // Extra-Daten einfügen (falls vorhanden)
        if (extra != null) {
            System.arraycopy(extra, 0, message, 4, Math.min(extra.length, 8));
        }

        // CRC berechnen (über die ersten 12 Bytes)
        byte[] crcData = Arrays.copyOf(message, 12);
        message[12] = calcCrc(crcData);

        logger.fine("TX: " + bytesToHex(message));
        return message;
    }

    private byte[] formatMessage(int command) {
        return formatMessage(command, null);
    }

    // ==================== Lesen ====================
    private List<byte[]> read(int command, byte[] extra, int maxResponses) throws IOException {
        logger.fine(String.format("-- 0x%02X ------------------------", command));

        if (!serialPort.isOpen()) {
            serialPort.openPort();
            sleep(100);
        }

        byte[] message = formatMessage(command, extra);

        // Buffer leeren
        serialPort.flushIOBuffers();
        sleep(50);  // Kurze Pause nach Flush

        // Nachricht senden
        outputStream.write(message);
        outputStream.flush();
        sleep(100);  // Warten bis BMS verarbeitet hat

        List<byte[]> responseDataList = new ArrayList<>();
        String commandHex = String.format("%02x", command);

        for (int i = 0; i < maxResponses; i++) {
            byte[] response = new byte[13];
            int totalRead = 0;
            long startTime = System.currentTimeMillis();

            // 13 Bytes lesen mit eigenem Timeout
            while (totalRead < 13) {
                // Eigener Timeout-Check: max 3 Sekunden pro Frame
                if (System.currentTimeMillis() - startTime > 3000) {
                    logger.fine(i + " Timeout beim Lesen (Frame " + i + ")");
                    break;
                }

                int available = serialPort.bytesAvailable();
                if (available <= 0) {
                    sleep(50);
                    continue;
                }

                int bytesToRead = Math.min(available, 13 - totalRead);
                int bytesRead = inputStream.read(response, totalRead, bytesToRead);
                if (bytesRead <= 0) {
                    sleep(50);
                    continue;
                }
                totalRead += bytesRead;
            }

            if (totalRead < 13) {
                if (totalRead > 0) {
                    logger.fine(i + " Unvollständige Antwort: " + totalRead + " Bytes");
                }
                break;
            }

            logger.fine(i + " RX: " + bytesToHex(response) + " (" + totalRead + " bytes)");

            // CRC prüfen
            byte[] crcData = Arrays.copyOf(response, 12);
            byte expectedCrc = calcCrc(crcData);
            if (expectedCrc != response[12]) {
                logger.warning("CRC Fehler: erwartet " +
                        String.format("%02x", expectedCrc) + " bekommen " +
                        String.format("%02x", response[12]));
                continue;
            }

            // Command prüfen
            String responseCommand = String.format("%02x", response[2] & 0xFF);
            if (!responseCommand.equals(commandHex)) {
                logger.fine("Falscher Befehl in Antwort: " +
                        responseCommand + " != " + commandHex);
                continue;
            }

            // Daten extrahieren (Bytes 4-11)
            byte[] data = Arrays.copyOfRange(response, 4, 12);
            responseDataList.add(data);
        }

        return responseDataList;
    }
    private List<byte[]> readALt(int command, byte[] extra, int maxResponses) throws IOException {
        if (!serialPort.isOpen()) {
            throw new IOException("Serieller Port ist nicht geöffnet");
        }

        byte[] message = formatMessage(command, extra);

        // Buffer leeren
        serialPort.flushIOBuffers();

        // Nachricht senden
        outputStream.write(message);
        outputStream.flush();

        List<byte[]> responseDataList = new ArrayList<>();
        String commandHex = String.format("%02x", command);

        for (int i = 0; i < maxResponses; i++) {
            byte[] response = new byte[13];
            int bytesRead = 0;
            int totalRead = 0;

            // 13 Bytes lesen
            while (totalRead < 13) {
                bytesRead = inputStream.read(response, totalRead, 13 - totalRead);
                if (bytesRead <= 0) {
                    logger.fine(i + " leere Antwort für Befehl " + commandHex);
                    break;
                }
                totalRead += bytesRead;
            }

            if (totalRead < 13) {
                break;
            }

            logger.fine(i + " RX: " + bytesToHex(response));

            // CRC prüfen
            byte[] crcData = Arrays.copyOf(response, 12);
            byte expectedCrc = calcCrc(crcData);
            if (expectedCrc != response[12]) {
                logger.warning("CRC Fehler: erwartet " +
                        String.format("%02x", expectedCrc) + " bekommen " +
                        String.format("%02x", response[12]));
                continue;
            }

            // Command prüfen
            String responseCommand = String.format("%02x", response[2] & 0xFF);
            if (!responseCommand.equals(commandHex)) {
                logger.warning("Falscher Befehl in Antwort: " + responseCommand + " != " + commandHex);
                continue;
            }

            // Daten extrahieren (Bytes 4-11)
            byte[] data = Arrays.copyOfRange(response, 4, 12);
            responseDataList.add(data);
        }

        return responseDataList;
    }

    private byte[] readRequest(int command) throws IOException {
        return readRequest(command, null, 1);
    }

    private byte[] readRequest(int command, byte[] extra, int maxResponses) throws IOException {
        List<byte[]> responseData = null;

        for (int attempt = 0; attempt < requestRetries; attempt++) {
            responseData = read(command, extra, maxResponses);
            if (responseData != null && !responseData.isEmpty()) {
                break;
            }
            logger.fine("Versuch " + (attempt + 1) + " fehlgeschlagen, wiederhole...");
            sleep(500);  // 500ms statt 200ms
        }

        if (responseData == null || responseData.isEmpty()) {
            logger.warning(String.format("Befehl 0x%02X fehlgeschlagen nach %d Versuchen",
                    command, requestRetries));
            return null;
        }

        return responseData.get(0);
    }
    
    private List<byte[]> readRequestList(int command, int maxResponses) throws IOException {
        List<byte[]> responseData = null;

        for (int attempt = 0; attempt < requestRetries; attempt++) {
            responseData = read(command, null, maxResponses);
            if (responseData != null && !responseData.isEmpty()) {
                break;
            }
            logger.fine("Versuch " + (attempt + 1) + " fehlgeschlagen, wiederhole...");
            sleep(200);
        }

        if (responseData == null || responseData.isEmpty()) {
            logger.warning(String.format("Befehl 0x%02X fehlgeschlagen nach %d Versuchen", command, requestRetries));
            return Collections.emptyList();
        }

        return responseData;
    }

    // ==================== BMS Befehle ====================

    /**
     * SOC: Gesamtspannung, Strom, SOC-Prozent
     */
    public DalyBMSData.SOC getSOC() throws IOException {
        byte[] data = readRequest(0x90);
        if (data == null) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        double totalVoltage = buf.getShort() / 10.0;
        buf.getShort(); // immer 0
        double current = (buf.getShort() - 30000) / 10.0;
        double socPercent = buf.getShort() / 10.0;

        return new DalyBMSData.SOC(totalVoltage, current, socPercent);
    }

    /**
     * Zellen mit der höchsten und niedrigsten Spannung
     */
    public DalyBMSData.CellVoltageRange getCellVoltageRange() throws IOException {
        byte[] data = readRequest(0x91);
        if (data == null) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        double highestVoltage = buf.getShort() / 1000.0;
        int highestCell = buf.get() & 0xFF;
        double lowestVoltage = buf.getShort() / 1000.0;
        int lowestCell = buf.get() & 0xFF;

        return new DalyBMSData.CellVoltageRange(highestVoltage, highestCell, lowestVoltage, lowestCell);
    }

    /**
     * Temperaturbereich
     */
    public DalyBMSData.TemperatureRange getTemperatureRange() throws IOException {
        byte[] data = readRequest(0x92);
        if (data == null) return null;

        int highestTemp = (data[0] & 0xFF) - 40;
        int highestSensor = data[1] & 0xFF;
        int lowestTemp = (data[2] & 0xFF) - 40;
        int lowestSensor = data[3] & 0xFF;

        return new DalyBMSData.TemperatureRange(highestTemp, highestSensor, lowestTemp, lowestSensor);
    }

    /**
     * MOSFET Status (Lade/Entlade)
     */
    public DalyBMSData.MosfetStatus getMosfetStatus() throws IOException {
        byte[] data = readRequest(0x93);
        if (data == null) return null;

        String mode;
        int modeValue = data[0] & 0xFF;
        if (modeValue == 0) {
            mode = "stationary";
        } else if (modeValue == 1) {
            mode = "charging";
        } else {
            mode = "discharging";
        }

        boolean chargingMosfet = data[1] != 0;
        boolean dischargingMosfet = data[2] != 0;

        // Kapazität: Bytes 4-7 als Big-Endian int
        ByteBuffer buf = ByteBuffer.wrap(data, 4, 4);
        double capacityAh = buf.getInt() / 1000.0;

        return new DalyBMSData.MosfetStatus(mode, chargingMosfet, dischargingMosfet, capacityAh);
    }

    /**
     * BMS Status (Zellenanzahl, Zyklen, etc.)
     */
    public DalyBMSData.Status getStatus() throws IOException {
        byte[] data = readRequest(0x94);
        if (data == null) return null;

        int cells = data[0] & 0xFF;
        int tempSensors = data[1] & 0xFF;
        boolean chargerRunning = data[2] != 0;
        boolean loadRunning = data[3] != 0;

        // State Bits
        int stateByte = data[4] & 0xFF;
        String[] stateNames = {"DI1", "DI2", "DI3", "DI4", "DO1", "DO2", "DO3", "DO4"};
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            boolean value = ((stateByte >> i) & 1) == 1;
            states.put(stateNames[i], value);
        }

        // Zyklen: Bytes 5-6 als Big-Endian short
        int cycles = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);

        // Werte speichern für spätere Verwendung
        this.cellCount = cells;
        this.temperatureSensorCount = tempSensors;

        return new DalyBMSData.Status(cells, tempSensors, chargerRunning, loadRunning, states, cycles);
    }

    /**
     * Einzelne Zellspannungen
     */
    public Map<Integer, Double> getCellVoltages() throws IOException {
        if (cellCount == 0) {
            logger.warning("getStatus() muss zuerst aufgerufen werden");
            return null;
        }

        int maxResponses;
        if (address == 8) {
            // Bluetooth: BMS sendet immer 16 Frames
            maxResponses = 16;
        } else {
            // USB: nur Frames mit Daten
            maxResponses = (int) Math.ceil(cellCount / 3.0);
        }

        List<byte[]> responseData = readRequestList(0x95, maxResponses);
        if (responseData.isEmpty()) return null;

        Map<Integer, Double> cellVoltages = new LinkedHashMap<>();
        int cellIndex = 1;

        for (byte[] frame : responseData) {
            int frameNumber = frame[0] & 0xFF;
            ByteBuffer buf = ByteBuffer.wrap(frame, 1, 6);

            for (int i = 0; i < 3 && cellIndex <= cellCount; i++) {
                double voltage = buf.getShort() / 1000.0;
                cellVoltages.put(cellIndex, voltage);
                cellIndex++;
            }
        }

        return cellVoltages;
    }

    /**
     * Temperatursensoren
     */
    public Map<Integer, Integer> getTemperatures() throws IOException {
        if (temperatureSensorCount == 0) {
            logger.warning("getStatus() muss zuerst aufgerufen werden");
            return null;
        }

        int maxResponses;
        if (address == 8) {
            maxResponses = 3;
        } else {
            maxResponses = (int) Math.ceil(temperatureSensorCount / 7.0);
        }

        List<byte[]> responseData = readRequestList(0x96, maxResponses);
        if (responseData.isEmpty()) return null;

        Map<Integer, Integer> temperatures = new LinkedHashMap<>();
        int sensorIndex = 1;

        for (byte[] frame : responseData) {
            for (int i = 1; i < 8 && sensorIndex <= temperatureSensorCount; i++) {
                int temp = (frame[i] & 0xFF) - 40;
                temperatures.put(sensorIndex, temp);
                sensorIndex++;
            }
        }

        return temperatures;
    }

    /**
     * Fehlerstatus
     */
    public List<String> getErrors() throws IOException {
        byte[] data = readRequest(0x98);
        if (data == null) return Collections.emptyList();

        // Prüfen ob alle Bytes 0 sind
        boolean allZero = true;
        for (byte b : data) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) return Collections.emptyList();

        List<String> errors = new ArrayList<>();
        for (int byteIndex = 0; byteIndex < data.length; byteIndex++) {
            int b = data[byteIndex] & 0xFF;
            if (b == 0) continue;

            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                if (((b >> bitIndex) & 1) == 1) {
                    String error = ErrorCodes.getError(byteIndex, bitIndex);
                    if (error != null) {
                        errors.add(error);
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Alle Daten auf einmal abfragen
     */
    public DalyBMSData getAll() throws IOException {
        DalyBMSData allData = new DalyBMSData();
        allData.soc = getSOC();
        allData.cellVoltageRange = getCellVoltageRange();
        allData.temperatureRange = getTemperatureRange();
        allData.mosfetStatus = getMosfetStatus();
        allData.status = getStatus();
        allData.cellVoltages = getCellVoltages();
        allData.temperatures = getTemperatures();
        allData.errors = getErrors();
        return allData;
    }

    // ==================== Set-Befehle ====================

    public void setDischargeMosfet(boolean on) throws IOException {
        byte[] extra = new byte[8];
        extra[0] = (byte) (on ? 0x01 : 0x00);
        readRequest(0xD9, extra, 1);
    }

    public void setChargeMosfet(boolean on) throws IOException {
        byte[] extra = new byte[8];
        extra[0] = (byte) (on ? 0x01 : 0x00);
        readRequest(0xDA, extra, 1);
    }

    public void setSOC(double value) throws IOException {
        int v = (int) Math.round(value * 10.0);
        v = Math.max(0, Math.min(1000, v));
        byte[] extra = new byte[8];
        extra[6] = (byte) ((v >> 8) & 0xFF);
        extra[7] = (byte) (v & 0xFF);
        readRequest(0x21, extra, 1);
    }

    // ==================== Hilfsmethoden ====================

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public int getCellCount() {
        return cellCount;
    }

    public int getTemperatureSensorCount() {
        return temperatureSensorCount;
    }
}