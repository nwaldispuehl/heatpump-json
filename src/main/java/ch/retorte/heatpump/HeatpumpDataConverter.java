package ch.retorte.heatpump;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ch.retorte.heatpump.HeatpumpDataConverter.Unit.*;
import static java.lang.Integer.parseInt;
import static java.time.format.DateTimeFormatter.ISO_TIME;
import static java.util.Arrays.stream;

@Singleton
public class HeatpumpDataConverter {

    // ---- Statics

    public static final String KEY_TRANSLATION_FILE = "keyTranslation";


    // ---- Fields

    private final Multimap<String, UnitInfo> fields = LinkedListMultimap.create();

    private ResourceBundle key;

    @ConfigProperty(name = "heatpump.language")
    String heatpumpLanguage;


    // ---- Methods

    void onStart(@Observes StartupEvent event) {
        loadTranslation();
        configureFields();
    }

    private void loadTranslation() {
        key = keyTranslationFor(heatpumpLanguage);
    }

    private void configureFields() {
        // Temperature
        addTopic(key.getString("temperature"), "temperature");
        add(key.getString("temperature.flow"), "flow", DEGREE_CELSIUS);
        add(key.getString("temperature.return_flow"), "return_flow", DEGREE_CELSIUS);
        add(key.getString("temperature.return_flow_target"), "return_flow_target", DEGREE_CELSIUS);
        add(key.getString("temperature.hot_gas"), "hot_gas", DEGREE_CELSIUS);
        add(key.getString("temperature.outdoor"), "outdoor", DEGREE_CELSIUS);
        add(key.getString("temperature.outdoor_avg"), "outdoor_avg", DEGREE_CELSIUS);
        add(key.getString("temperature.domestic_hot_water"), "domestic_hot_water", DEGREE_CELSIUS);
        add(key.getString("temperature.domestic_hot_water_target"), "domestic_hot_water_target", DEGREE_CELSIUS);
        add(key.getString("temperature.heat_source_inlet"), "heat_source_inlet", DEGREE_CELSIUS);
        add(key.getString("temperature.heat_source_out"), "heat_source_out", DEGREE_CELSIUS);
        add(key.getString("temperature.flow_max"), "flow_max", DEGREE_CELSIUS);
        add(key.getString("temperature.suction_compressor"), "suction_compressor", DEGREE_CELSIUS);
        add(key.getString("temperature.compressor_heating"), "compressor_heating", DEGREE_CELSIUS, ".*" + DEGREE_CELSIUS.marker());
        add(key.getString("temperature.overheating"), "overheating", KELVIN);

        // Input
        addTopic(key.getString("input"), "input");
        add(key.getString("input.defrost_brine_flow"), "defrost_brine_flow", BOOLEAN);
        add(key.getString("input.supplier_off_time"), "supplier_off_time", BOOLEAN);
        add(key.getString("input.high_pressure_pressostat"), "high_pressure_pressostat", BOOLEAN, key.getString("data.binary.0") + "|" + key.getString("data.binary.1"));
        add(key.getString("input.motor_protection"), "motor_protection", BOOLEAN);
        add(key.getString("input.high_pressure_sensor"), "high_pressure_sensor", BAR, ".*" + BAR.marker());
        add(key.getString("input.low_pressure_sensor"), "low_pressure_sensor", BAR);
        add(key.getString("input.pump_flow"), "pump_flow", LITRES_PER_HOUR);

        // Output
        addTopic(key.getString("output"), "output");
        add(key.getString("output.domestic_hot_water_pump"), "domestic_hot_water_pump", BOOLEAN);
        add(key.getString("output.floor_heating_pump"), "floor_heating_pump", BOOLEAN);
        add(key.getString("output.heating_pump"), "heating_pump", BOOLEAN, key.getString("data.binary.0") + "|" + key.getString("data.binary.1"));
        add(key.getString("output.ventilator_well_brine_pump"), "ventilator_well_brine_pump", BOOLEAN, key.getString("data.binary.0") + "|" + key.getString("data.binary.1"));
        add(key.getString("output.compressor"), "compressor", BOOLEAN);
        add(key.getString("output.circulation_pump"), "circulation_pump", BOOLEAN);
        add(key.getString("output.additional_circulation_pump"), "additional_circulation_pump", BOOLEAN);
        add(key.getString("output.additional_heating_generator_1"), "additional_heating_generator_1", BOOLEAN);
        add(key.getString("output.additional_heating_generator_2"), "additional_heating_generator_2", BOOLEAN);
        add(key.getString("output.compressor_heating"), "compressor_heating", BOOLEAN, key.getString("data.binary.0") + "|" + key.getString("data.binary.1"));
        add(key.getString("output.compressor_speed_target"), "compressor_speed_target", HERTZ);
        add(key.getString("output.compressor_speed"), "compressor_speed", HERTZ);
        add(key.getString("output.ventilator_well_brine_pump_power"), "ventilator_well_brine_pump_power", PERCENT, ".*" + PERCENT.marker());
        add(key.getString("output.heating_pump_power"), "heating_pump_power", PERCENT, ".*" + PERCENT.marker());

        // Timing
        addTopic(key.getString("timing"), "timing");
        add(key.getString("timing.heat_pump_up"), "heat_pump_up", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.additional_heating_1_up"), "additional_heating_1_up", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.additional_heating_2_up"), "additional_heating_2_up", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.net_input_delay"), "net_input_delay", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.off_time_switching_cycle"), "off_time_switching_cycle", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.compressor_down"), "compressor_down", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.heating_control_more"), "heating_control_more", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.heating_control_less"), "heating_control_less", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.thermal_disinfection_up"), "thermal_disinfection_up", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.domestic_hot_water_blockade"), "domestic_hot_water_blockade", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.release_additional_heating"), "release_additional_heating", HOUR_MINUTE_SECONDS);
        add(key.getString("timing.release_cooling"), "release_cooling", HOUR_MINUTE_SECONDS);

        // Operating time
        addTopic(key.getString("operating_time"), "operating_time");
        add(key.getString("operating_time.compressor_operating_hours"), "compressor_operating_hours", HOURS);
        add(key.getString("operating_time.compressor_impulses"), "compressor_impulses", INTEGER);
        add(key.getString("operating_time.compressor_avg_runtime"), "compressor_avg_runtime", HOUR_MINUTE);
        add(key.getString("operating_time.additional_heating_1_operating_hours"), "additional_heating_1_operating_hours", HOURS);
        add(key.getString("operating_time.additional_heating_2_operating_hours"), "additional_heating_2_operating_hours", HOURS);
        add(key.getString("operating_time.heat_pump_operating_hours"), "heat_pump_operating_hours", HOURS);
        add(key.getString("operating_time.heating_operating_hours"), "heating_operating_hours", HOURS);
        add(key.getString("operating_time.dhw_operating_hours"), "dhw_operating_hours", HOURS);

        // Status
        addTopic(key.getString("status"), "status");
        add(key.getString("status.heat_pump_type"), "heat_pump_type", TEXT);
        add(key.getString("status.software_version"), "software_version", TEXT);
        add(key.getString("status.processor_version"), "processor_version", TEXT);
        add(key.getString("status.io_version"), "io_version", HTML);
        add(key.getString("status.interface_version"), "interface_version", HTML);
        add(key.getString("status.inverter_version"), "inverter_version", TEXT);
        add(key.getString("status.bivalence_level"), "bivalence_level", INTEGER);
        add(key.getString("status.mode"), "mode", OPERATING_MODE);
        add(key.getString("status.heating_capacity"), "heating_capacity", KILO_WATTS);

        // Energy monitor
        addTopic(key.getString("monitor"), "monitor");
        // Heat amount
        addTopic(key.getString("monitor.heat_quantity"), "heat_quantity");
        add(key.getString("monitor.heat_quantity.heating"), "heating", KILO_WATT_HOURS);
        add(key.getString("monitor.heat_quantity.domestic_hot_water"), "domestic_hot_water", KILO_WATT_HOURS);
        add(key.getString("monitor.heat_quantity.total"), "total", KILO_WATT_HOURS);
        // Energy used
        addTopic(key.getString("monitor.energy_input"), "energy_input");
        add(key.getString("monitor.energy_input.heating"), "heating", KILO_WATT_HOURS);
        add(key.getString("monitor.energy_input.domestic_hot_water"), "domestic_hot_water", KILO_WATT_HOURS);
        add(key.getString("monitor.energy_input.total"), "total", KILO_WATT_HOURS);
    }

    private void addTopic(String fieldIdentifier, String jsonIdentifier) {
        add(fieldIdentifier, jsonIdentifier, null, null);
    }

    private void add(String fieldIdentifier, String jsonIdentifier, Unit unit) {
        add(fieldIdentifier, jsonIdentifier, unit, null);
    }

    private void add(String fieldIdentifier, String jsonIdentifier, Unit unit, String valuePattern) {
        fields.put(fieldIdentifier, new UnitInfo(jsonIdentifier, unit, valuePattern));
    }

    public UnitInfo getFor(String fieldIdentifier, String fieldValue) {
        Collection<UnitInfo> unitInfos = fields.get(fieldIdentifier);
        if (unitInfos.isEmpty()) {
            return null;
        }
        else if (unitInfos.size() == 1) {
            return unitInfos.iterator().next();
        }
        else {
            // If there is more than one unit registered for the given key we first check if one matches the given value with its regex pattern.
            for (UnitInfo unitInfo : unitInfos) {
                if (matches(unitInfo, fieldValue)) {
                    return unitInfo;
                }
            }

            // If there is no match with the regex we just yield the first unit.
            return unitInfos.iterator().next();
        }
    }

    private boolean matches(UnitInfo unitInfo, String fieldValue) {
        if (unitInfo.valueMatchingRegex() != null) {
            Pattern pattern = Pattern.compile(unitInfo.valueMatchingRegex());
            Matcher matcher = pattern.matcher(fieldValue);
            return matcher.matches();
        }
        return false;
    }

    private ResourceBundle keyTranslationFor(String languageTag) {
        return ResourceBundle.getBundle(KEY_TRANSLATION_FILE, Locale.forLanguageTag(languageTag));
    }

    public ResourceBundle bundle() {
        return key;
    }


    // ---- Inner classes

    public record UnitInfo(String identifier, Unit unit, String valueMatchingRegex) {}

    public enum Unit {

        // ---- Enums

        OPERATING_MODE(true, "mode"),
        TEXT(false, "text"),
        HTML(false, "html"),
        INTEGER(true, "integer"),
        PERCENT(true, "%"),
        DEGREE_CELSIUS(true, "°C"),
        HERTZ(true, "Hz"),
        KELVIN(true, "K"),
        HOUR_MINUTE(true, "s"),
        HOUR_MINUTE_SECONDS(true, "s"),
        HOURS(true, "h"),
        BAR(true, "bar"),
        BOOLEAN(true, "boolean"),
        LITRES_PER_HOUR(true, "l/h"),
        KILO_WATTS(true, "kW"),
        KILO_WATT_HOURS(true, "kWh");


        // ---- Static

        private static final String LITRES_PER_HOUR_ZERO = "---";
        private static final Integer HEATING_MODE_DEFAULT = 0;


        // ---- Fields

        private final boolean numeric;
        private final String marker;


        // ---- Constructor

        Unit(boolean numeric, String marker) {
            this.numeric = numeric;
            this.marker = marker;
        }


        // ---- Methods

        public boolean isNumeric() {
            return numeric;
        }

        public String marker() {
            return marker;
        }

        public void convertWith(ResourceBundle bundle, String rawValue, Consumer<String> textualConsumer, Consumer<Number> numericConsumer) {
            if (isNumeric()) {
                numericConsumer.accept(convertNumeric(bundle, rawValue));
            }
            else {
                textualConsumer.accept(convertTextual(rawValue));
            }
        }

        private String convertTextual(String value) {
            return switch (this) {
                case TEXT -> value;
                case HTML -> convertHtml(value);
                default -> value;
            };
        }
        
        private Number convertNumeric(ResourceBundle bundle, String value) {
            return switch (this) {
                case OPERATING_MODE -> convertOperatingMode(bundle, value);
                case INTEGER -> parseInt(value);
                case PERCENT -> convertPercent(value);
                case DEGREE_CELSIUS -> convertCelsiusTemperature(value);
                case HERTZ -> convertHertz(value);
                case KELVIN -> convertKelvinTemperature(value);
                case HOUR_MINUTE -> convertHourMinute(value);
                case HOUR_MINUTE_SECONDS -> convertHourMinuteSecond(value);
                case HOURS -> convertHours(value);
                case BAR -> convertBarPressure(value);
                case BOOLEAN -> convertBoolean(bundle, value);
                case LITRES_PER_HOUR -> convertLitresPerHourFlow(value);
                case KILO_WATTS -> convertKiloWatts(value);
                case KILO_WATT_HOURS -> convertKiloWattHours(value);
                default -> Double.valueOf(value);
            };
        }


        // ---- Converter methods

        private static Double convertCelsiusTemperature(String celsiusTemperature) {
            return Double.valueOf(celsiusTemperature.replace(DEGREE_CELSIUS.marker(),  "").trim());
        }

        private static Double convertKelvinTemperature(String kelvinTemperature) {
            return Double.valueOf(kelvinTemperature.replace(KELVIN.marker(),  "").trim());
        }

        private static Double convertBarPressure(String barPressure) {
            return Double.valueOf(barPressure.replace(BAR.marker(),  "").trim());
        }

        private static Double convertKiloWatts(String kiloWatts) {
            return Double.valueOf(kiloWatts.replace(KILO_WATTS.marker(),  "").trim());
        }

        private static Double convertKiloWattHours(String kiloWattHours) {
            return Double.valueOf(kiloWattHours.replace(KILO_WATT_HOURS.marker(),  "").trim());
        }

        private static Integer convertHours(String hours) {
            return Integer.valueOf(hours.replace(HOURS.marker(),  ""));
        }

        private static Integer convertHertz(String hertz) {
            return Integer.valueOf(hertz.replace(HERTZ.marker(),  "").trim());
        }

        private static Integer convertPercent(String percent) {
            return Integer.valueOf(percent.replace(PERCENT.marker(),  "").trim());
        }

        private static String convertHtml(String html) {
            return html.replaceAll("\\<.*?>", "").trim();
        }

        private static Integer convertOperatingMode(ResourceBundle bundle, String mode) {
            final String heatingModeListString = bundle.getString("data.mode.list");
            final List<String> heatingModes = stream(heatingModeListString.split(";")).map(String::trim).toList();

            for (int index = 0; index < heatingModes.size(); index++) {
                final String heatingMode = heatingModes.get(index);
                if (heatingMode.equals(mode)) {
                    return index;
                }
            }

            return HEATING_MODE_DEFAULT;
        }

        private static Integer convertBoolean(ResourceBundle bundle, String bool) {
            final String on = bundle.getString("data.binary.1");
            if (bool.equals(on)) {
                return 1;
            }
            else {
                return 0;
            }
        }

        private static Long convertHourMinute(String hourMinute) {
            LocalTime parsedTime = LocalTime.parse(hourMinute, ISO_TIME);
            return parsedTime.toEpochSecond(LocalDate.EPOCH, ZoneOffset.UTC);
        }

        private static Integer convertHourMinuteSecond(String hourMinuteSecond) {
            final String[] parts = hourMinuteSecond.split(":");
            final int hour = parseInt(parts[0]);
            final int minute = parseInt(parts[1]);
            final int second = parseInt(parts[2]);
            return hour * 3600 + minute * 60 + second;
        }

        private static Integer convertLitresPerHourFlow(String litresPerHour) {
            final String result = litresPerHour.replace(LITRES_PER_HOUR.marker(), "").trim();
            if (LITRES_PER_HOUR_ZERO.equals(result)) {
                return 0;
            }
            else {
                return Integer.valueOf(result);
            }
        }

    }

}
