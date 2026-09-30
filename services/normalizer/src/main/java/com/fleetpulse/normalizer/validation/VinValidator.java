package com.fleetpulse.normalizer.validation;

import java.util.Map;

/**
 * §7.1 VIN Validation and NHTSA/ISO 3779 Check Digit Algorithm.
 *
 * Enforces:
 * 1. Exactly 17 characters.
 * 2. No illegal letters (I, O, Q).
 * 3. Modulo-11 weighted check digit verification at position 9 (index 8).
 */
public class VinValidator {

    public enum Status {
        VALID,
        CHECK_DIGIT_WARN,
        INVALID_FORMAT
    }

    private static final Map<Character, Integer> CHAR_VALUES = Map.ofEntries(
        Map.entry('A', 1), Map.entry('B', 2), Map.entry('C', 3), Map.entry('D', 4),
        Map.entry('E', 5), Map.entry('F', 6), Map.entry('G', 7), Map.entry('H', 8),
        Map.entry('J', 1), Map.entry('K', 2), Map.entry('L', 3), Map.entry('M', 4),
        Map.entry('N', 5), Map.entry('P', 7), Map.entry('R', 9), Map.entry('S', 2),
        Map.entry('T', 3), Map.entry('U', 4), Map.entry('V', 5), Map.entry('W', 6),
        Map.entry('X', 7), Map.entry('Y', 8), Map.entry('Z', 9),
        Map.entry('0', 0), Map.entry('1', 1), Map.entry('2', 2), Map.entry('3', 3),
        Map.entry('4', 4), Map.entry('5', 5), Map.entry('6', 6), Map.entry('7', 7),
        Map.entry('8', 8), Map.entry('9', 9)
    );

    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};

    public static Status validate(String vin) {
        if (vin == null) {
            return Status.INVALID_FORMAT;
        }

        String trimmed = vin.trim().toUpperCase();
        if (trimmed.length() != 17) {
            return Status.INVALID_FORMAT;
        }

        // Check for forbidden characters: I, O, Q
        if (trimmed.contains("I") || trimmed.contains("O") || trimmed.contains("Q")) {
            return Status.INVALID_FORMAT;
        }

        // Verify all characters are in lookup table
        for (int i = 0; i < 17; i++) {
            if (!CHAR_VALUES.containsKey(trimmed.charAt(i))) {
                return Status.INVALID_FORMAT;
            }
        }

        // Modulo 11 check digit calculation
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = trimmed.charAt(i);
            sum += CHAR_VALUES.get(c) * WEIGHTS[i];
        }

        int remainder = sum % 11;
        char expectedCheckChar = (remainder == 10) ? 'X' : (char) ('0' + remainder);
        char actualCheckChar = trimmed.charAt(8);

        if (expectedCheckChar == actualCheckChar) {
            return Status.VALID;
        }

        return Status.CHECK_DIGIT_WARN;
    }
}
