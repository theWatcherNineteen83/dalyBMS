package de.dalybms;

import com.fazecast.jSerialComm.SerialPort;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Raw RS485 Sniffer - zeichnet ALLE Bytes auf dem Bus auf, ohne Interpretation.
 * Zeigt Timestamp, Richtung (TX/RX) und Hex-Daten.
 *
 * Nutzung:
 *   java -cp "out:dist/lib/*" de.dalybms.Rs485RawSniffer /dev/ttyUSB1 [sekunden]
 *
 * Standard: 30 Sekunden sniffen.
 */
public class Rs485RawSniffer {

    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : "/dev/ttyUSB1";
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 30;

        SerialPort port = SerialPort.getCommPort(device);
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setParity(SerialPort.NO_PARITY);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        // Non-blocking read mit kurzem Timeout für kontinuierliches Polling
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

        if (!port.openPort()) {
            System.err.println("Konnte Port nicht öffnen: " + device);
            System.exit(1);
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        System.out.println("=== RS485 Raw Sniffer ===");
        System.out.println("Port: " + device + " @ 115200 8N1");
        System.out.println("Dauer: " + durationSec + " Sekunden");
        System.out.println("Zeichne ALLE Rohdaten auf...");
        System.out.println();

        long startTime = System.currentTimeMillis();
        long endTime = startTime + durationSec * 1000L;
        int totalBytes = 0;
        int frames = 0;

        byte[] buffer = new byte[1024];

        while (System.currentTimeMillis() < endTime) {
            int n = port.readBytes(buffer, buffer.length);
            if (n > 0) {
                frames++;
                totalBytes += n;
                String ts = LocalDateTime.now().format(dtf);
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    int b = buffer[i] & 0xFF;
                    hex.append(String.format("%02X ", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                }
                System.out.printf("[%s] RX %3d Byte: %s |%s|%n",
                        ts, n, hex.toString().trim(), ascii.toString());
            }
        }

        System.out.println();
        System.out.println("=== Sniffer beendet ===");
        System.out.printf("Empfangen: %d Byte in %d Frame(s) über %d Sekunden%n",
                totalBytes, frames, durationSec);

        port.closePort();
    }
}
