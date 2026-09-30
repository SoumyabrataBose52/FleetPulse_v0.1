package com.fleetpulse.normalizer.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * §7.2 Diagnostic Trouble Code (DTC) Validator.
 *
 * Validates against SAE J2012 / ISO 15031-6 standard:
 * - 5 characters.
 * - 1st char: P (Powertrain), C (Chassis), B (Body), U (Network/UART).
 * - 2nd char: 0-3.
 * - 3rd-5th chars: Hexadecimal [0-9A-F].
 */
public class DtcValidator {

    private static final Pattern DTC_PATTERN = Pattern.compile("^[PCBU][0-3][0-9A-F]{3}$");

    public static List<String> sanitizeAndValidate(List<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            return List.of();
        }

        Set<String> validCodes = new LinkedHashSet<>();
        for (String code : rawCodes) {
            if (code == null) continue;
            String trimmed = code.trim().toUpperCase();
            if (DTC_PATTERN.matcher(trimmed).matches()) {
                validCodes.add(trimmed);
            }
        }

        return new ArrayList<>(validCodes);
    }

    public static boolean isValid(String code) {
        if (code == null) return false;
        return DTC_PATTERN.matcher(code.trim().toUpperCase()).matches();
    }
}
