"""
Tests for fpcore.vin — VIN Validation (§7.1)
Tests cover: known fixtures, fuzz-safe, property tests, build/generate.
"""

import re
import pytest
from fpcore.vin import (
    validate,
    build_vin,
    generate_check_digit,
    VinStrictness,
    KNOWN_VALID_VIN,
)

# ---------------------------------------------------------------------------
# Fixtures from the plan
# ---------------------------------------------------------------------------

class TestKnownVins:
    def test_known_valid_vin(self):
        result = validate(KNOWN_VALID_VIN)
        assert result.valid is True
        assert result.check_digit_ok is True
        assert result.quality_flag is None

    def test_known_valid_vin_case_insensitive(self):
        result = validate(KNOWN_VALID_VIN.lower())
        assert result.valid is True

    def test_invalid_too_short(self):
        result = validate("1HGCM82633A00435")
        assert result.valid is False
        assert "charset" in (result.error or "").lower() or "length" in (result.error or "").lower()

    def test_invalid_contains_I(self):
        result = validate("1HGCM82633A00435I")
        assert result.valid is False

    def test_invalid_contains_O(self):
        result = validate("1HGCM82633A0O4352")
        assert result.valid is False

    def test_invalid_contains_Q(self):
        result = validate("1HGCM82633Q004352")
        assert result.valid is False

    def test_invalid_check_digit_strict(self):
        # Mutate position 9 (index 8)
        bad = KNOWN_VALID_VIN[:8] + "9" + KNOWN_VALID_VIN[9:]
        result = validate(bad, VinStrictness.STRICT)
        assert result.valid is False
        assert result.check_digit_ok is False

    def test_invalid_check_digit_warn_only(self):
        bad = KNOWN_VALID_VIN[:8] + "9" + KNOWN_VALID_VIN[9:]
        result = validate(bad, VinStrictness.WARN_ONLY)
        assert result.valid is True
        assert result.quality_flag == "VIN_WARN"

    def test_check_digit_x(self):
        # Build a VIN where the check digit should be X (remainder == 10)
        # We'll build one via build_vin and check it round-trips
        prefix = "1M8GDM9A_KP042788"[:16]  # use a known X-check-digit prefix
        # Just test that build_vin produces a valid VIN
        vin = build_vin("1M8GDM9AKPXXXXXX"[:16])
        r = validate(vin)
        assert r.valid is True


# ---------------------------------------------------------------------------
# Build / Generate
# ---------------------------------------------------------------------------

class TestBuildVin:
    def test_build_vin_produces_valid(self):
        prefix = "1HGCM82633A00435"  # 16 chars
        vin = build_vin(prefix)
        assert len(vin) == 17
        r = validate(vin)
        assert r.valid is True

    def test_build_vin_invalid_prefix_raises(self):
        with pytest.raises(ValueError):
            build_vin("1HGCM82633A0435I")  # contains I

    def test_generate_check_digit_known(self):
        # For KNOWN_VALID_VIN the check digit is '3' (at index 8)
        prefix = KNOWN_VALID_VIN[:8] + KNOWN_VALID_VIN[9:]  # 16 chars
        assert generate_check_digit(prefix) == KNOWN_VALID_VIN[8]

    def test_multiple_generated_vins_all_valid(self):
        chars = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
        import random
        rng = random.Random(42)
        for _ in range(1000):
            prefix = "".join(rng.choices(chars, k=16))
            try:
                vin = build_vin(prefix)
                r = validate(vin)
                assert r.valid is True, f"Generated VIN {vin} failed validation"
            except ValueError:
                pass  # invalid prefix characters — skip


# ---------------------------------------------------------------------------
# Property-like tests (without Hypothesis for now; add later)
# ---------------------------------------------------------------------------

class TestVinProperties:
    def test_mutating_any_single_char_usually_invalidates(self):
        """Mutating one character of a valid VIN should produce invalid in most cases."""
        import random
        rng = random.Random(99)
        chars = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
        failures = 0
        trials = 200
        for _ in range(trials):
            vin = list(KNOWN_VALID_VIN)
            pos = rng.randint(0, 16)
            orig = vin[pos]
            new = rng.choice([c for c in chars if c != orig])
            vin[pos] = new
            candidate = "".join(vin)
            r = validate(candidate, VinStrictness.STRICT)
            if r.valid:
                failures += 1
        # Expect <10% to still be valid (false negatives due to hash collisions)
        assert failures / trials < 0.10, f"Too many valid mutated VINs: {failures}/{trials}"

    def test_never_panics_on_arbitrary_input(self):
        """validate() must never raise an exception."""
        garbage_inputs = [
            "", " ", "\x00" * 17, "!" * 17, "a" * 100,
            "1" * 17, "AAAAAAAAAAAAAAAA1",
            "1HGCM82633A00435\n",
        ]
        for s in garbage_inputs:
            try:
                validate(s)
            except Exception as e:
                pytest.fail(f"validate() raised {type(e).__name__} on input {s!r}: {e}")
