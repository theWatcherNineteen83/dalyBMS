package de.dalybms;

import java.util.HashMap;
import java.util.Map;

public class ErrorCodes {

    private static final Map<Integer, Map<Integer, String>> CODES = new HashMap<>();

    static {
        // Byte 0
        Map<Integer, String> byte0 = new HashMap<>();
        byte0.put(0, "Cell voltage is too high. level one alarm");
        byte0.put(1, "Cell voltage is too high. level two alarm");
        byte0.put(2, "Cell voltage is too low. level one alarm");
        byte0.put(3, "Cell voltage is too low. level two alarm");
        byte0.put(4, "Total voltage is too high. level one alarm");
        byte0.put(5, "Total voltage is too high. level two alarm");
        byte0.put(6, "Total voltage is too low. level one alarm");
        byte0.put(7, "Total voltage is too low. level two alarm");
        CODES.put(0, byte0);

        // Byte 1
        Map<Integer, String> byte1 = new HashMap<>();
        byte1.put(0, "Charging temperature too high. level one alarm");
        byte1.put(1, "Charging temperature too high. level two alarm");
        byte1.put(2, "Charging temperature too low. level one alarm");
        byte1.put(3, "Charging temperature too low. level two alarm");
        byte1.put(4, "Discharging temperature too high. level one alarm");
        byte1.put(5, "Discharging temperature too high. level two alarm");
        byte1.put(6, "Discharging temperature too low. level one alarm");
        byte1.put(7, "Discharging temperature too low. level two alarm");
        CODES.put(1, byte1);

        // Byte 2
        Map<Integer, String> byte2 = new HashMap<>();
        byte2.put(0, "Charging overcurrent. level one alarm");
        byte2.put(1, "Charging overcurrent. level two alarm");
        byte2.put(2, "Discharging overcurrent. level one alarm");
        byte2.put(3, "Discharging overcurrent. level two alarm");
        byte2.put(4, "SOC is too high. level one alarm");
        byte2.put(5, "SOC is too high. level two alarm");
        byte2.put(6, "SOC is too low. level one alarm");
        byte2.put(7, "SOC is too low. level two alarm");
        CODES.put(2, byte2);

        // Byte 3
        Map<Integer, String> byte3 = new HashMap<>();
        byte3.put(0, "Excessive differential voltage. level one alarm");
        byte3.put(1, "Excessive differential voltage. level two alarm");
        byte3.put(2, "Excessive temperature difference. level one alarm");
        byte3.put(3, "Excessive temperature difference. level two alarm");
        CODES.put(3, byte3);

        // Byte 4
        Map<Integer, String> byte4 = new HashMap<>();
        byte4.put(0, "Charging MOSFET fault");
        byte4.put(1, "Discharging MOSFET fault");
        byte4.put(2, "Cell voltage sensor fault");
        byte4.put(3, "Temperature sensor fault");
        byte4.put(4, "Charging MOSFET adhesion fault");
        byte4.put(5, "Discharging MOSFET adhesion fault");
        byte4.put(6, "Charging MOSFET breaker fault");
        byte4.put(7, "Discharging MOSFET breaker fault");
        CODES.put(4, byte4);

        // Byte 5
        Map<Integer, String> byte5 = new HashMap<>();
        byte5.put(0, "AFE acquisition chip fault");
        byte5.put(1, "Voltage collecting dropped");
        byte5.put(2, "Cell temperature sensor fault");
        byte5.put(3, "EEPROM fault");
        byte5.put(4, "RTC fault");
        byte5.put(5, "Precharge fault");
        byte5.put(6, "Communication fault");
        byte5.put(7, "Internal communication fault");
        CODES.put(5, byte5);

        // Byte 6
        Map<Integer, String> byte6 = new HashMap<>();
        byte6.put(0, "Current module fault");
        byte6.put(1, "Main pressure detection fault");
        byte6.put(2, "Short circuit protection fault");
        byte6.put(3, "Low voltage forbidden charging fault");
        CODES.put(6, byte6);

        // Byte 7
        Map<Integer, String> byte7 = new HashMap<>();
        byte7.put(0, "GPS disconnected fault");
        byte7.put(1, "Modify parameters not possible");
        CODES.put(7, byte7);
    }

    public static String getError(int byteIndex, int bitIndex) {
        Map<Integer, String> byteErrors = CODES.get(byteIndex);
        if (byteErrors == null) return null;
        return byteErrors.get(bitIndex);
    }
}