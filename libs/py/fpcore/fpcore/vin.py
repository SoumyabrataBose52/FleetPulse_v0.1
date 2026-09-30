"""
VIN Validation — §7.1
======================
Validates 17-character VINs per ISO 3779 / NHTSA rules.

Rules:
- 17 characters, charset A–H J–N P R–Z 0–9 (no I, O, Q)
- Position 9 is the check digit (0–9 or X)
- North-American style check digit computed via weighted transliteration

Strictness modes:
  STRICT     → regex + check digit; rejects on any failure
  WARN_ONLY  → returns (True, quality_flag=VIN_WARN) on check-digit mismatch

Complexity: O(17) per call.
"""

import re
from enum import Enum
from typing import NamedTuple

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_VIN_PATTERN = re.compile(r"^[A-HJ-NPR-Z0-9]{17}$")

# Transliteration table: char → numeric value used in check-digit computation
_TRANSLITERATION: dict[str, int] = {
    **{str(d): d for d in range(10)},
    "A": 1, "B": 2, "C": 3, "D": 4, "E": 5, "F": 6, "G": 7, "H": 8,
    "J": 1, "K": 2, "L": 3, "M": 4, "N": 5, "P": 7, "R": 9,
    "S": 2, "T": 3, "U": 4, "V": 5, "W": 6, "X": 7, "Y": 8, "Z": 9,
}

# Positional weights (positions 1–17)
_WEIGHTS = [8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2]

# Known-valid VIN for tests: 1HGCM82633A004352
# sum = 311, 311 mod 11 = 3, check digit (pos 9) = '3' ✓
KNOWN_VALID_VIN = "1HGCM82633A004352"


class VinStrictness(Enum):
    STRICT = "strict"
    WARN_ONLY = "warn_only"


class VinResult(NamedTuple):
    valid: bool
    check_digit_ok: bool
    quality_flag: str | None  # 'VIN_WARN' when check digit bad in WARN_ONLY mode
    error: str | None


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def validate(vin: str, strictness: VinStrictness = VinStrictness.STRICT) -> VinResult:
    """
    Validate a VIN string.

    Args:
        vin: The VIN to validate (case-insensitive; will be uppercased).
        strictness: STRICT rejects bad check digit; WARN_ONLY returns VIN_WARN flag.

    Returns:
        VinResult(valid, check_digit_ok, quality_flag, error)
    """
    vin = vin.upper().strip()

    # Step 1: charset + length check
    if not _VIN_PATTERN.match(vin):
        return VinResult(valid=False, check_digit_ok=False, quality_flag=None,
                         error=f"VIN '{vin}' fails charset/length check (17 chars, no I/O/Q)")

    # Step 2: Check digit computation
    total = sum(_TRANSLITERATION[ch] * w for ch, w in zip(vin, _WEIGHTS))
    remainder = total % 11
    expected = "X" if remainder == 10 else str(remainder)
    check_ok = vin[8] == expected

    if not check_ok:
        if strictness == VinStrictness.STRICT:
            return VinResult(valid=False, check_digit_ok=False, quality_flag=None,
                             error=f"VIN '{vin}' check digit invalid: got '{vin[8]}', expected '{expected}'")
        else:
            return VinResult(valid=True, check_digit_ok=False, quality_flag="VIN_WARN", error=None)

    return VinResult(valid=True, check_digit_ok=True, quality_flag=None, error=None)


def generate_check_digit(vin_without_check: str) -> str:
    """
    Compute the check digit for a 16-char VIN prefix (positions 1–8, 10–17).

    Args:
        vin_without_check: 16 chars; position 9 can be any placeholder.

    Returns:
        The check digit character ('0'–'9' or 'X').
    """
    if len(vin_without_check) == 16:
        # Insert placeholder at position 9 (index 8) for computation
        full = vin_without_check[:8] + "0" + vin_without_check[8:]
    elif len(vin_without_check) == 17:
        full = vin_without_check
    else:
        raise ValueError("Provide 16 chars (without check digit) or 17 chars")

    full = full.upper()
    total = sum(_TRANSLITERATION[ch] * w for ch, w in zip(full, _WEIGHTS))
    remainder = total % 11
    return "X" if remainder == 10 else str(remainder)


def build_vin(prefix_16: str) -> str:
    """
    Build a complete valid VIN from a 16-character prefix by inserting the check digit.

    Args:
        prefix_16: 16 chars (positions 1–8 and 10–17).

    Returns:
        17-char valid VIN.
    """
    prefix_16 = prefix_16.upper()
    if not re.match(r"^[A-HJ-NPR-Z0-9]{16}$", prefix_16):
        raise ValueError(f"Invalid prefix: '{prefix_16}' (16 chars, no I/O/Q)")
    check = generate_check_digit(prefix_16)
    return prefix_16[:8] + check + prefix_16[8:]
