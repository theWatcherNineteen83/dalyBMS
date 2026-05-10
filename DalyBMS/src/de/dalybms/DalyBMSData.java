package de.dalybms;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DalyBMSData {

    public SOC soc;
    public CellVoltageRange cellVoltageRange;
    public TemperatureRange temperatureRange;
    public MosfetStatus mosfetStatus;
    public Status status;
    public Map<Integer, Double> cellVoltages;
    public Map<Integer, Integer> temperatures;
    public List<String> errors;

    // ==================== SOC ====================

    public static class SOC {
        public double totalVoltage;
        public double current;
        public double socPercent;

        public SOC(double totalVoltage, double current, double socPercent) {
            this.totalVoltage = totalVoltage;
            this.current = current;
            this.socPercent = socPercent;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("total_voltage", totalVoltage);
            json.put("current", current);
            json.put("soc_percent", socPercent);
            return json;
        }
    }

    // ==================== Cell Voltage Range ====================

    public static class CellVoltageRange {
        public double highestVoltage;
        public int highestCell;
        public double lowestVoltage;
        public int lowestCell;

        public CellVoltageRange(double highestVoltage, int highestCell,
                                double lowestVoltage, int lowestCell) {
            this.highestVoltage = highestVoltage;
            this.highestCell = highestCell;
            this.lowestVoltage = lowestVoltage;
            this.lowestCell = lowestCell;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("highest_voltage", highestVoltage);
            json.put("highest_cell", highestCell);
            json.put("lowest_voltage", lowestVoltage);
            json.put("lowest_cell", lowestCell);
            return json;
        }
    }

    // ==================== Temperature Range ====================

    public static class TemperatureRange {
        public int highestTemperature;
        public int highestSensor;
        public int lowestTemperature;
        public int lowestSensor;

        public TemperatureRange(int highestTemperature, int highestSensor,
                                int lowestTemperature, int lowestSensor) {
            this.highestTemperature = highestTemperature;
            this.highestSensor = highestSensor;
            this.lowestTemperature = lowestTemperature;
            this.lowestSensor = lowestSensor;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("highest_temperature", highestTemperature);
            json.put("highest_sensor", highestSensor);
            json.put("lowest_temperature", lowestTemperature);
            json.put("lowest_sensor", lowestSensor);
            return json;
        }
    }

    // ==================== MOSFET Status ====================

    public static class MosfetStatus {
        public String mode;
        public boolean chargingMosfet;
        public boolean dischargingMosfet;
        public double capacityAh;

        public MosfetStatus(String mode, boolean chargingMosfet,
                            boolean dischargingMosfet, double capacityAh) {
            this.mode = mode;
            this.chargingMosfet = chargingMosfet;
            this.dischargingMosfet = dischargingMosfet;
            this.capacityAh = capacityAh;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("mode", mode);
            json.put("charging_mosfet", chargingMosfet);
            json.put("discharging_mosfet", dischargingMosfet);
            json.put("capacity_ah", capacityAh);
            return json;
        }
    }

    // ==================== Status ====================

    public static class Status {
        public int cells;
        public int temperatureSensors;
        public boolean chargerRunning;
        public boolean loadRunning;
        public Map<String, Boolean> states;
        public int cycles;

        public Status(int cells, int temperatureSensors, boolean chargerRunning,
                      boolean loadRunning, Map<String, Boolean> states, int cycles) {
            this.cells = cells;
            this.temperatureSensors = temperatureSensors;
            this.chargerRunning = chargerRunning;
            this.loadRunning = loadRunning;
            this.states = states;
            this.cycles = cycles;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("cells", cells);
            json.put("temperature_sensors", temperatureSensors);
            json.put("charger_running", chargerRunning);
            json.put("load_running", loadRunning);
            json.put("states", new JSONObject(states));
            json.put("cycles", cycles);
            return json;
        }
    }

    // ==================== Gesamt-JSON ====================

    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        if (soc != null) json.put("soc", soc.toJson());
        if (cellVoltageRange != null) json.put("cell_voltage_range", cellVoltageRange.toJson());
        if (temperatureRange != null) json.put("temperature_range", temperatureRange.toJson());
        if (mosfetStatus != null) json.put("mosfet_status", mosfetStatus.toJson());
        if (status != null) json.put("status", status.toJson());

        if (cellVoltages != null) {
            JSONObject cv = new JSONObject();
            for (Map.Entry<Integer, Double> entry : cellVoltages.entrySet()) {
                cv.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            json.put("cell_voltages", cv);
        }

        if (temperatures != null) {
            JSONObject temps = new JSONObject();
            for (Map.Entry<Integer, Integer> entry : temperatures.entrySet()) {
                temps.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            json.put("temperatures", temps);
        }

        json.put("errors", errors != null ? errors : Collections.emptyList());

        return json;
    }

    @Override
    public String toString() {
        return toJson().toString(2);
    }
}