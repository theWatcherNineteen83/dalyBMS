package de.dalybms;

import java.util.*;

/**
 * Offline-Test fuer die JK-Parallelprotokoll-Dekodierung.
 *
 * Dekodiert die bekannten Host-Frames (aus den Rohdumps vom 25.10.2026)
 * deterministisch und vergleicht gegen die Referenzwerte aus der JK-App.
 *
 * Referenzwerte (JK-App):
 *   HOST : SOC=57%, Spannung=26.38V, RestKap=179.6 Ah, VollKap=314 Ah, Temp=18.4 C
 *   SLAVE: SOC=87%, RestKap=274.6 Ah (wird per Modbus ausgelesen, hier ignoriert)
 *
 * Ausfuehren (nach Kompilierung):
 *   java -cp out de.dalybms.JkParallelDecodeTest
 */
public class JkParallelDecodeTest {

    private static byte[] hex(String s) {
        s = s.replaceAll("\\s+", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static int u16le(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] d, int off) {
        return (d[off] & 0xFF)
                | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16)
                | ((d[off + 3] & 0xFF) << 24);
    }

    static class Result {
        int cells;
        int soc = -1;
        int voltageMv = -1;
        int tempRaw = -1;
        long restMah = -1;
        long fullMah = -1;
    }

    /** Spiegel der HOST-Dekodier-Logik aus JkParallelSniffer.decodeFrame. */
    private static Result decodeHost(byte[] data, int offset, int len) {
        Result r = new Result();

        List<Integer> cells = new ArrayList<>();
        for (int i = offset + 6; i + 1 < offset + len; i += 2) {
            int mv = u16le(data, i);
            if (mv >= 1500 && mv <= 4500) {
                cells.add(mv);
            } else if (mv == 0 && !cells.isEmpty()) {
                break;
            }
        }
        r.cells = cells.size();

        if (len > 152) {
            int v = u16le(data, offset + 150);
            if (v > 10000 && v < 60000) r.voltageMv = v;
        }
        if (len > 164) {
            int t = u16le(data, offset + 162);
            if (t > 0 && t < 1000) r.tempRaw = t;
        }
        if (len > 178) {
            long cap = u32le(data, offset + 174);
            if (cap > 10000 && cap < 1000000) r.restMah = cap;
        }
        if (len > 182) {
            long full = u32le(data, offset + 178);
            if (full > 10000 && full < 1000000) r.fullMah = full;
        }
        if (len > 173) {
            if (r.restMah < 250000) {
                int soc = data[offset + 173] & 0xFF;
                if (soc > 0 && soc <= 100) r.soc = soc;
            }
        }
        return r;
    }

    private static final String HOST_308 =
        "55 AA EB 90 02 00 E2 0C E2 0C E2 0C E2 0C E2 0C " +
        "E2 0C E2 0C E2 0C 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 FF 00 00 00 E2 0C 00 00 00 00 " +
        "48 00 46 00 4A 00 47 00 4A 00 47 00 47 00 45 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "C0 00 00 00 00 00 0E 67 00 00 00 00 00 00 00 00 " +
        "00 00 B5 00 B7 00 00 00 00 00 00 00 00 39 93 BD " +
        "02 00 90 CA 04 00 01 00 00 00 B6 2C 08 00 64 00 " +
        "00 00 A8 43 1A 00 01 01 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 FF 00 01 00 00 00 01 00 00 00 " +
        "01 00 C5 13 3F 40 00 00 00 00 4E 0A 00 00 00 01 " +
        "01 01 00 06 00 00 D3 CE 5C 00 00 00 00 00 C0 00";

    private static final String SLAVE_319 =
        "55 AA EB 90 02 00 EA 0C E9 0C E9 0C E8 0C E9 0C " +
        "E9 0C E8 0C E9 0C 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 FF 00 00 00 E9 0C 02 00 01 03 " +
        "4E 00 4B 00 44 00 41 00 45 00 43 00 46 00 42 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "C9 00 00 00 00 00 46 67 00 00 00 00 00 00 00 00 " +
        "00 00 BC 00 BA 00 00 00 00 00 00 00 00 57 DA 30 " +
        "04 00 90 CA 04 00 00 00 00 00 1C 55 04 00 64 00 " +
        "00 00 CD 9A 13 00 01 01 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 FF 00 01 00 00 00 01 00 00 00 " +
        "01 00 42 48 3F 40 00 00 00 00 53 0A 00 00 00 01 " +
        "01 01 00 06 01 00 D4 CE 5C 00 00 00 00 00 C9 00";

    public static void main(String[] args) {
        int passed = 0, failed = 0;
        System.out.println("=== JK Parallelprotokoll Offline-Test ===\n");

        byte[] host = hex(HOST_308);
        Result r1 = decodeHost(host, 0, host.length);
        System.out.println("[Test 1] 308B Host-Frame (" + host.length + " Bytes)");
        System.out.println("  Zellen: " + r1.cells + " (erwartet 8)");
        System.out.println("  SOC: " + r1.soc + " (erwartet 57)");
        System.out.println("  Spannung: " + (r1.voltageMv > 0 ? r1.voltageMv / 1000.0 + "V" : "NaN") + " (erwartet 26.38V)");
        System.out.println("  Temp: " + (r1.tempRaw > 0 ? r1.tempRaw / 10.0 + "C" : "NaN") + " (erwartet ~18.1C)");
        System.out.println("  RestKap: " + r1.restMah + " mAh (erwartet 179603)");
        System.out.println("  VollKap: " + r1.fullMah + " mAh (erwartet 314000)");
        boolean t1 = r1.cells == 8 && r1.soc == 57
                && Math.abs(r1.voltageMv - 26382) < 50
                && Math.abs(r1.tempRaw - 181) < 5
                && Math.abs(r1.restMah - 179603) < 500
                && Math.abs(r1.fullMah - 314000) < 500;
        System.out.println("  => " + (t1 ? "PASS" : "FAIL"));
        if (t1) passed++; else failed++;

        byte[] slave = hex(SLAVE_319);
        Result r2 = decodeHost(slave, 0, slave.length);
        System.out.println("\n[Test 2] 319B Slave-Frame (" + slave.length + " Bytes)");
        System.out.println("  SOC: " + r2.soc + " (erwartet -1 = ignoriert, RestKap > 250 Ah)");
        System.out.println("  RestKap: " + r2.restMah + " mAh (erwartet 274650)");
        boolean t2 = r2.soc == -1 && Math.abs(r2.restMah - 274650) < 500;
        System.out.println("  => " + (t2 ? "PASS" : "FAIL"));
        if (t2) passed++; else failed++;

        System.out.println("\n=== Ergebnis: " + passed + " pass, " + failed + " fail ===");
        if (failed > 0) System.exit(1);
    }
}
