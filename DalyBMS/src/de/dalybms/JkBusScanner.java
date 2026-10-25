package de.dalybms;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Scannt einen RS485-Modbus-Bus nach aktiven JK-BMS-Geräten.
 * Sendet an jede Adresse 1-247 eine minimale Modbus-RTU-Anfrage (Funktion 03H, 1 Register)
 * und meldet jede Antwort mit Adresse + Rohdaten.
 *
 * Nutzung:
 *   java -cp "dist/DalyBMS.jar:dist/lib/*" de.dalybms.JkBusScanner /dev/ttyUSB1
 */
public class JkBusScanner {

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

    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : "/dev/ttyUSB1";

        SerialPort port = SerialPort.getCommPort(device);
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setParity(SerialPort.NO_PARITY);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 200);

        if (!port.openPort()) {
            System.err.println("Konnte Port nicht öffnen: " + device);
            System.exit(1);
        }

        System.out.println("=== JK-BMS Bus-Scanner ===");
        System.out.println("Port: " + device + " @ 115200 8N1");
        System.out.println("Scanne Adressen 0-247...");
        System.out.println();

        int found = 0;

        for (int addr = 0; addr <= 247; addr++) {
            // Modbus RTU: Funktion 03H, Register 0x0000, Anzahl 1
            byte[] req = new byte[8];
            req[0] = (byte) addr;
            req[1] = 0x03;
 //           req[2] = 0x00;
            req[2] = 0x12;   // neu: liest Register 0x1200 (Zellspannung 0)
            req[3] = 0x00;
            req[4] = 0x00;
            req[5] = 0x01;
            int crc = crc16(req, 6);
            req[6] = (byte) crc;
            req[7] = (byte) (crc >> 8);

            // Puffer leeren
            while (port.bytesAvailable() > 0) {
                byte[] trash = new byte[port.bytesAvailable()];
                port.readBytes(trash, trash.length);
            }

            port.writeBytes(req, req.length);

            // Warten auf Antwort (max 300ms)
            byte[] resp = new byte[256];
            int got = 0;
            long deadline = System.currentTimeMillis() + 300;
            while (got < 7 && System.currentTimeMillis() < deadline) {
                int n = port.readBytes(resp, Math.min(resp.length - got, 256 - got), got);
                if (n > 0) got += n;
            }

            if (got >= 7 && (resp[0] & 0xFF) == addr && resp[1] == 0x03) {
                int byteCount = resp[2] & 0xFF;
                int expectedLen = 5 + byteCount;
                if (got >= expectedLen) {
                    found++;
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < got; i++) {
                        hex.append(String.format("%02X ", resp[i] & 0xFF));
                    }
                    System.out.printf("  ✅ Adresse %3d (0x%02X): %d Byte Antwort -> %s%n",
                            addr, addr, got, hex.toString().trim());

                    // Bei gültiger Antwort: versuche auch Block A (64 Register ab 0x1200)
                    // um zu prüfen ob es ein JK-BMS ist
                    byte[] req2 = new byte[8];
                    req2[0] = (byte) addr;
                    req2[1] = 0x03;
                    req2[2] = 0x12;
                    req2[3] = 0x00;
                    req2[4] = 0x00;
                    req2[5] = 0x04;  // 4 Register = 8 Byte (Zellspannungen 0-3)
                    int crc2 = crc16(req2, 6);
                    req2[6] = (byte) crc2;
                    req2[7] = (byte) (crc2 >> 8);

                    while (port.bytesAvailable() > 0) {
                        byte[] trash = new byte[port.bytesAvailable()];
                        port.readBytes(trash, trash.length);
                    }
                    port.writeBytes(req2, req2.length);

                    byte[] resp2 = new byte[256];
                    int got2 = 0;
                    deadline = System.currentTimeMillis() + 500;
                    while (got2 < 13 && System.currentTimeMillis() < deadline) {
                        int n = port.readBytes(resp2, Math.min(resp2.length - got2, 256 - got2), got2);
                        if (n > 0) got2 += n;
                    }

                    if (got2 >= 13 && (resp2[0] & 0xFF) == addr && resp2[1] == 0x03) {
                        int bc = resp2[2] & 0xFF;
                        System.out.printf("     Block A (0x1200): %d Byte Daten", bc);
                        if (bc >= 8) {
                            int cell0 = ((resp2[3] & 0xFF) << 8) | (resp2[4] & 0xFF);
                            int cell1 = ((resp2[5] & 0xFF) << 8) | (resp2[6] & 0xFF);
                            int cell2 = ((resp2[7] & 0xFF) << 8) | (resp2[8] & 0xFF);
                            int cell3 = ((resp2[9] & 0xFF) << 8) | (resp2[10] & 0xFF);
                            System.out.printf(" | Zellen: %d/%d/%d/%d mV", cell0, cell1, cell2, cell3);
                            if (cell0 > 2000 && cell0 < 4500) {
                                System.out.print(" ← plausible Zellspannung!");
                            }
                        }
                        System.out.println();
                    } else {
                        System.out.println("     Block A (0x1200): keine Antwort");
                    }
                }
            }

            // Kurze Pause zwischen Anfragen
            Thread.sleep(20);
        }

        System.out.println();
        System.out.println("Scan beendet. Gefunden: " + found + " Gerät(e)");

        port.closePort();
    }
}
