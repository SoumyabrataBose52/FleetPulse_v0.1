package com.fleetpulse.normalizer.validation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VinValidatorTest {

    @Test
    void testValidVin() {
        assertEquals(VinValidator.Status.VALID, VinValidator.validate("1HGCM82633A004352"));
        assertEquals(VinValidator.Status.VALID, VinValidator.validate("5YJCR2F836A100001"));
    }

    @Test
    void testCheckDigitMismatchReturnsWarn() {
        // Change check digit at index 8 from '3' to '9'
        assertEquals(VinValidator.Status.CHECK_DIGIT_WARN, VinValidator.validate("1HGCR2F89HA000001"));
    }

    @Test
    void testInvalidLength() {
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate("1HGCR2F83HA000"));
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate("1HGCR2F83HA0000019999"));
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate(""));
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate(null));
    }

    @Test
    void testForbiddenCharacters() {
        // I, O, Q are strictly forbidden in 17-char VINs
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate("1HGCR2F83HA00000I"));
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate("1HGCR2F83HA00000O"));
        assertEquals(VinValidator.Status.INVALID_FORMAT, VinValidator.validate("1HGCR2F83HA00000Q"));
    }
}
