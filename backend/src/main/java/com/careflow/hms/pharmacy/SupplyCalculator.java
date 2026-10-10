package com.careflow.hms.pharmacy;

import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests a supply quantity for one supply cycle from the prescription's dose, frequency and the
 * catalogue strength. Pure arithmetic with a visible basis; the pharmacist always confirms the quantity.
 * Returns null whenever the dose cannot be converted safely (e.g. "per sliding scale", PRN orders).
 */
final class SupplyCalculator {

    private static final Pattern AMOUNT = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)");

    record Suggestion(Integer quantity, String basis) {
    }

    private SupplyCalculator() {
    }

    static Integer dosesPerDay(MedicationOrder.Frequency f) {
        return switch (f) {
            case OD, HS, WEEKLY, ONCE, STAT -> 1;
            case BD, Q12H -> 2;
            case TDS, Q8H -> 3;
            case QID, Q6H -> 4;
            case Q4H -> 6;
            case PRN -> null;
        };
    }

    static Suggestion suggest(MedicationOrder o, Medication m) {
        Integer perDay = dosesPerDay(o.getFrequency());
        if (perDay == null) {
            return new Suggestion(null, "PRN order: supply quantity at the pharmacist's discretion.");
        }
        BigDecimal dose;
        try {
            dose = new BigDecimal(o.getDose().trim());
        } catch (NumberFormatException e) {
            return new Suggestion(null, "Dose \"" + o.getDose() + "\" cannot be converted to dispense units.");
        }
        Matcher sm = AMOUNT.matcher(m.getStrength());
        if (!sm.find() || dose.signum() <= 0) {
            return new Suggestion(null, "Strength \"" + m.getStrength() + "\" cannot be compared with the dose.");
        }
        BigDecimal strength = new BigDecimal(sm.group(1));
        BigDecimal doseInStrengthUnit = convert(dose, o.getDoseUnit(), sm.group(2));
        if (doseInStrengthUnit == null || strength.signum() <= 0) {
            return new Suggestion(null, "Dose unit " + o.getDoseUnit() + " differs from strength " + m.getStrength() + ".");
        }
        int perDose = doseInStrengthUnit.divide(strength, 4, RoundingMode.HALF_UP).setScale(0, RoundingMode.CEILING).intValue();
        if (perDose < 1 || perDose > 50) {
            return new Suggestion(null, "Calculated units per dose (" + perDose + ") is outside the expected range.");
        }
        boolean single = o.getFrequency() == MedicationOrder.Frequency.ONCE || o.getFrequency() == MedicationOrder.Frequency.STAT;
        int qty = single ? perDose : perDose * perDay;
        String basis = o.getDose() + " " + o.getDoseUnit() + " ÷ " + m.getStrength() + " = " + perDose + " " + m.getDispenseUnit()
                + " per dose" + (single ? " (single dose)" : " × " + perDay + " dose" + (perDay == 1 ? "" : "s")
                + "/day (" + o.getFrequency() + ")");
        return new Suggestion(qty, basis);
    }

    private static BigDecimal convert(BigDecimal value, String fromUnit, String toUnit) {
        String from = norm(fromUnit);
        String to = norm(toUnit);
        if (from.equals(to)) {
            return value;
        }
        int f = massFactor(from);
        int t = massFactor(to);
        if (f > 0 && t > 0) {
            return value.multiply(BigDecimal.valueOf(f)).divide(BigDecimal.valueOf(t), 6, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static String norm(String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "ml", "millilitre", "milliliter" -> "ml";
            case "iu", "units", "unit", "u" -> "iu";
            case "mcg", "µg", "ug" -> "mcg";
            default -> u;
        };
    }

    /** Mass in micrograms per unit, or 0 when not a mass unit. */
    private static int massFactor(String unit) {
        return switch (unit) {
            case "g" -> 1_000_000;
            case "mg" -> 1_000;
            case "mcg" -> 1;
            default -> 0;
        };
    }
}
