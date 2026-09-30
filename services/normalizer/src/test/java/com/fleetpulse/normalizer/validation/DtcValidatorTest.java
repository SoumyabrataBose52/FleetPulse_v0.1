package com.fleetpulse.normalizer.validation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DtcValidatorTest {

    @Test
    void testValidDtcCodes() {
        assertTrue(DtcValidator.isValid("P0301")); // Powertrain
        assertTrue(DtcValidator.isValid("C0040")); // Chassis
        assertTrue(DtcValidator.isValid("B1234")); // Body
        assertTrue(DtcValidator.isValid("U0100")); // Network
        assertTrue(DtcValidator.isValid("p0420")); // Case-insensitive
    }

    @Test
    void testInvalidDtcCodes() {
        assertFalse(DtcValidator.isValid("X1234")); // Illegal system prefix
        assertFalse(DtcValidator.isValid("P030"));  // Too short
        assertFalse(DtcValidator.isValid("P03011")); // Too long
        assertFalse(DtcValidator.isValid("P030Z")); // Non-hex character Z
        assertFalse(DtcValidator.isValid(null));
        assertFalse(DtcValidator.isValid(""));
    }

    @Test
    void testSanitizeAndValidate() {
        List<String> raw = List.of("P0301", "p0420", "INVALID_CODE", "P0301", "U0100");
        List<String> valid = DtcValidator.sanitizeAndValidate(raw);

        assertEquals(3, valid.size());
        assertEquals("P0301", valid.get(0));
        assertEquals("P0420", valid.get(1));
        assertEquals("U0100", valid.get(2));
    }
}
