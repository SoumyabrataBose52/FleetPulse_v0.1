"""
DTC / OBD-II Parsing — §7.2
============================
Parses and normalises Diagnostic Trouble Codes from various OEM formats.

Format: [P|C|B|U][0-3][0-9A-F]{3}
  P = Powertrain, C = Chassis, B = Body, U = Network/Communication
  Second digit: 0-1 = SAE generic, 2-3 = manufacturer-specific

Input formats handled:
  - OEM A: JSON array  ["P0301", "P0420"]
  - OEM B: semicolon-joined string  "P0301;P0420"
  - OEM C/D/E: array or single string

Complexity: O(n * 5) where n = number of DTC strings per event.
"""

import re
from dataclasses import dataclass

_DTC_PATTERN = re.compile(r"^[PCBU][0-3][0-9A-F]{3}$", re.IGNORECASE)

# Severity mapping by DTC prefix + known codes
# Full catalog is loaded from dtc_catalog table in Postgres at runtime;
# this is a fast in-memory fallback for the normalizer hot path.
_SEVERITY_HINTS: dict[str, str] = {
    "P02": "HIGH",    # Fuel/air mixture faults
    "P03": "HIGH",    # Ignition system / misfire
    "P04": "MEDIUM",  # Emissions control
    "P05": "MEDIUM",  # Speed/idle control
    "P02": "HIGH",
    "P0217": "CRITICAL",  # Coolant overtemperature
    "P0562": "HIGH",      # Battery voltage low
    "P0301": "HIGH",      # Cylinder 1 misfire
    "P0302": "HIGH",
    "P0303": "HIGH",
    "P0304": "HIGH",
    "P0420": "LOW",       # Catalyst below threshold (often nuisance)
}


@dataclass(frozen=True)
class ParsedDTC:
    code: str       # Normalised upper-case, e.g. "P0301"
    system: str     # "POWERTRAIN", "CHASSIS", "BODY", "NETWORK"
    generic: bool   # True if digit 2 is 0 or 1 (SAE generic)
    severity: str   # CRITICAL / HIGH / MEDIUM / LOW / INFO


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def parse(raw: str | list[str]) -> list[ParsedDTC]:
    """
    Parse DTC codes from various OEM raw formats into normalised ParsedDTC records.

    Handles:
    - Single string: "P0301"
    - Semicolon-delimited: "P0301;P0420"
    - JSON-style list passed as Python list: ["P0301", "P0420"]
    - Mixed case: normalised to upper

    Invalid codes are silently dropped (logged by caller).
    Duplicates are removed (order-preserving dedup).

    Args:
        raw: Raw DTC data from any OEM format.

    Returns:
        Deduplicated list of ParsedDTC, or empty list if none valid.
    """
    if isinstance(raw, str):
        tokens = [t.strip() for t in raw.split(";") if t.strip()]
    elif isinstance(raw, list):
        tokens = [str(t).strip() for t in raw if t]
    else:
        return []

    seen: set[str] = set()
    result: list[ParsedDTC] = []
    for token in tokens:
        code = token.upper()
        if code in seen:
            continue
        if not _DTC_PATTERN.match(code):
            continue  # invalid code — caller logs the drop
        seen.add(code)
        result.append(_build(code))
    return result


def is_valid(code: str) -> bool:
    """Return True if the code matches the DTC regex."""
    return bool(_DTC_PATTERN.match(code.upper()))


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

_SYSTEM_MAP = {"P": "POWERTRAIN", "C": "CHASSIS", "B": "BODY", "U": "NETWORK"}


def _build(code: str) -> ParsedDTC:
    system = _SYSTEM_MAP.get(code[0], "UNKNOWN")
    generic = code[1] in ("0", "1")
    severity = _severity(code)
    return ParsedDTC(code=code, system=system, generic=generic, severity=severity)


def _severity(code: str) -> str:
    # Exact match first
    if code in _SEVERITY_HINTS:
        return _SEVERITY_HINTS[code]
    # Prefix match (first 3 chars)
    prefix = code[:3]
    if prefix in _SEVERITY_HINTS:
        return _SEVERITY_HINTS[prefix]
    return "MEDIUM"
