package org.example.shared.statutory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Statutory Indian CBIC HSN (Harmonized System of Nomenclature) and
 * SAC (Services Accounting Code) Tariff Registry.
 *
 * Provides real-time resolution of 4-digit chapters, 6-digit subheadings,
 * and 8-digit tariff items to standard statutory GST rates and descriptions.
 */
public final class HsnTariffRegistry {

    public record TariffEntry(
        String code,
        double gstRate,
        String description,
        boolean isChapter
    ) {}

    private static final Map<String, TariffEntry> TARIFF_MAP;

    static {
        Map<String, TariffEntry> map = new HashMap<>();

        // Chapter 73: Articles of iron or steel (Key industrial domain for Jasvi)
        map.put("7304", new TariffEntry("7304", 18.0, "Tubes, pipes and hollow profiles, seamless, of iron or steel", false));
        map.put("7306", new TariffEntry("7306", 18.0, "Tubes, pipes and hollow profiles, welded or riveted, of iron or steel", false));
        map.put("730630", new TariffEntry("730630", 18.0, "Welded pipes of circular cross-section, of iron or non-alloy steel", false));
        map.put("73063000", new TariffEntry("73063000", 18.0, "Other welded tubes, pipes and hollow profiles of iron/steel", false));
        map.put("7307", new TariffEntry("7307", 18.0, "Tube or pipe fittings (couplings, elbows, sleeves), of iron or steel", false));
        map.put("73072100", new TariffEntry("73072100", 18.0, "Flanges of stainless steel", false));
        map.put("73079990", new TariffEntry("73079990", 18.0, "Other pipe fittings of iron or steel", false));
        map.put("7308", new TariffEntry("7308", 18.0, "Structures and parts of structures of iron or steel", false));
        map.put("7318", new TariffEntry("7318", 18.0, "Screws, bolts, nuts, coach-screws, screw hooks, rivets, washers", false));
        map.put("73181500", new TariffEntry("73181500", 18.0, "Other screws and bolts, whether or not with their nuts or washers", false));
        map.put("73181600", new TariffEntry("73181600", 18.0, "Nuts of iron or steel", false));
        map.put("7326", new TariffEntry("7326", 18.0, "Other articles of iron or steel, forged or stamped", false));

        // Chapter 84: Machinery, mechanical appliances, parts thereof
        map.put("8413", new TariffEntry("8413", 18.0, "Pumps for liquids, whether or not fitted with measuring device", false));
        map.put("8414", new TariffEntry("8414", 18.0, "Air or vacuum pumps, air or other gas compressors and fans", false));
        map.put("8421", new TariffEntry("8421", 18.0, "Centrifuges, filtering or purifying machinery for liquids or gases", false));
        map.put("8428", new TariffEntry("8428", 18.0, "Other lifting, handling, loading or unloading machinery", false));
        map.put("8466", new TariffEntry("8466", 18.0, "Parts and accessories suitable for machine-tools", false));
        map.put("8471", new TariffEntry("8471", 18.0, "Automatic data processing machines & units (Computers, servers)", false));
        map.put("84713010", new TariffEntry("84713010", 18.0, "Personal computers / laptops / portable data processors", false));
        map.put("8479", new TariffEntry("8479", 18.0, "Machines and mechanical appliances having individual functions", false));
        map.put("84799090", new TariffEntry("84799090", 18.0, "Parts of machinery and mechanical appliances having individual functions", false));
        map.put("8481", new TariffEntry("8481", 18.0, "Taps, cocks, valves for pipes, boiler shells, tanks, vats", false));
        map.put("84818030", new TariffEntry("84818030", 18.0, "Industrial valves (gate, globe, check, ball, butterfly)", false));
        map.put("8482", new TariffEntry("8482", 18.0, "Ball or roller bearings and parts thereof", false));
        map.put("8483", new TariffEntry("8483", 18.0, "Transmission shafts, cranks, bearing housings, plain shaft bearings, gears", false));

        // Chapter 85: Electrical machinery and equipment
        map.put("8501", new TariffEntry("8501", 18.0, "Electric motors and generators (excluding generating sets)", false));
        map.put("8504", new TariffEntry("8504", 18.0, "Electrical transformers, static converters (rectifiers, inverters)", false));
        map.put("8517", new TariffEntry("8517", 18.0, "Telephone sets, smartphones, transmission/reception apparatus", false));
        map.put("8536", new TariffEntry("8536", 18.0, "Electrical apparatus for switching or protecting electrical circuits (<= 1000V)", false));
        map.put("8537", new TariffEntry("8537", 18.0, "Boards, panels, consoles, desks for electric control or distribution", false));
        map.put("8544", new TariffEntry("8544", 18.0, "Insulated wire, cable and other electric conductors", false));

        // Chapter 40: Rubber articles
        map.put("4009", new TariffEntry("4009", 18.0, "Tubes, pipes and hoses, of vulcanised rubber, with or without fittings", false));
        map.put("4016", new TariffEntry("4016", 18.0, "Other articles of vulcanised rubber (gaskets, washers, seals)", false));

        // Chapter 39: Plastics articles
        map.put("3917", new TariffEntry("3917", 18.0, "Tubes, pipes and hoses, and fittings thereof, of plastics", false));
        map.put("3926", new TariffEntry("3926", 18.0, "Other articles of plastics and articles of other materials", false));

        // Chapter 72: Iron and steel raw materials
        map.put("7208", new TariffEntry("7208", 18.0, "Flat-rolled products of iron or non-alloy steel, hot-rolled", false));
        map.put("7214", new TariffEntry("7214", 18.0, "Other bars and rods of iron or non-alloy steel, forged or hot-drawn", false));
        map.put("7216", new TariffEntry("7216", 18.0, "Angles, shapes and sections of iron or non-alloy steel", false));

        // Chapter 90: Measuring and precision instruments
        map.put("9026", new TariffEntry("9026", 18.0, "Instruments for measuring or checking liquid/gas flow, level, pressure", false));
        map.put("9031", new TariffEntry("9031", 90.0, "Measuring or checking instruments, appliances and machines", false));
        map.put("9032", new TariffEntry("9032", 18.0, "Automatic regulating or controlling instruments and apparatus (thermostats)", false));

        // Chapter 87: Motor vehicles, tractors, parts
        map.put("8708", new TariffEntry("8708", 18.0, "Parts and accessories of motor vehicles", false));

        // Services (SAC - Chapter 99)
        map.put("9954", new TariffEntry("9954", 18.0, "Construction services (General construction and civil engineering)", false));
        map.put("9965", new TariffEntry("9965", 5.0, "Goods transport services (GTA by road)", false));
        map.put("9967", new TariffEntry("9967", 18.0, "Supporting and auxiliary transport services (cargo handling, storage)", false));
        map.put("9971", new TariffEntry("9971", 18.0, "Financial and related services (banking, loan processing)", false));
        map.put("9983", new TariffEntry("9983", 18.0, "Other professional, technical and business services (IT, engineering)", false));
        map.put("9987", new TariffEntry("9987", 18.0, "Maintenance, repair and installation services (machinery & equipment)", false));
        map.put("9988", new TariffEntry("9988", 12.0, "Manufacturing services on physical inputs owned by others (Job work)", false));

        // 2-Digit Chapter Defaults
        map.put("72", new TariffEntry("72", 18.0, "Iron and steel (Chapter 72)", true));
        map.put("73", new TariffEntry("73", 18.0, "Articles of iron or steel (Chapter 73)", true));
        map.put("74", new TariffEntry("74", 18.0, "Copper and articles thereof (Chapter 74)", true));
        map.put("76", new TariffEntry("76", 18.0, "Aluminium and articles thereof (Chapter 76)", true));
        map.put("82", new TariffEntry("82", 18.0, "Tools, implements, cutlery of base metal (Chapter 82)", true));
        map.put("84", new TariffEntry("84", 18.0, "Nuclear reactors, boilers, machinery and appliances (Chapter 84)", true));
        map.put("85", new TariffEntry("85", 18.0, "Electrical machinery, equipment and parts (Chapter 85)", true));
        map.put("87", new TariffEntry("87", 28.0, "Vehicles and parts thereof (Chapter 87)", true));
        map.put("90", new TariffEntry("90", 18.0, "Optical, photographic, checking, precision instruments (Chapter 90)", true));
        map.put("39", new TariffEntry("39", 18.0, "Plastics and articles thereof (Chapter 39)", true));
        map.put("40", new TariffEntry("40", 18.0, "Rubber and articles thereof (Chapter 40)", true));

        TARIFF_MAP = Collections.unmodifiableMap(map);
    }

    private HsnTariffRegistry() {}

    /**
     * Resolves an HSN/SAC code against the CBIC statutory tariff schedule.
     * Searches in order: 8-digit exact -> 6-digit subheading -> 4-digit heading -> 2-digit chapter.
     */
    public static TariffEntry lookup(String input) {
        if (input == null) return null;
        String clean = input.trim().replaceAll("[^0-9]", "");
        if (clean.length() < 2) return null;

        // Try exact 8-digit or full length match
        if (clean.length() >= 8) {
            TariffEntry exact = TARIFF_MAP.get(clean.substring(0, 8));
            if (exact != null) return exact;
        }

        // Try 6-digit match
        if (clean.length() >= 6) {
            TariffEntry sub = TARIFF_MAP.get(clean.substring(0, 6));
            if (sub != null) return sub;
        }

        // Try 4-digit heading match
        if (clean.length() >= 4) {
            TariffEntry head = TARIFF_MAP.get(clean.substring(0, 4));
            if (head != null) return head;
        }

        // Try 2-digit chapter match
        TariffEntry chap = TARIFF_MAP.get(clean.substring(0, 2));
        if (chap != null) return chap;

        // If numeric format is valid (4, 6, or 8 digits) but not in explicit registry,
        // provide fallback standard industrial tariff rate (18.0%)
        if (clean.length() == 4 || clean.length() == 6 || clean.length() == 8) {
            return new TariffEntry(clean, 18.0, "Commodity / Service (Chapter " + clean.substring(0, 2) + ")", false);
        }

        return null;
    }

    /**
     * Checks if the given string adheres to canonical Indian HSN/SAC format (4, 6, or 8 digits).
     */
    public static boolean isValidHsnFormat(String input) {
        if (input == null) return false;
        String clean = input.trim().replaceAll("[^0-9]", "");
        return clean.length() == 4 || clean.length() == 6 || clean.length() == 8;
    }
}
