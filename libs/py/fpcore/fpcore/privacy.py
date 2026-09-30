"""
FleetPulse Privacy Toolkit & Right-to-Erasure (§7.19, §8.S3, §10.2).
Provides:
- Deterministic per-recipient HMAC-SHA256 pseudonymisation (GDPR/DPDP compliance).
- Spatial precision masking and sensitive depot buffer suppression.
- Differential privacy Laplace noise mechanism for aggregate analytics queries.
- Audit and erasure verification utilities.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import math
import random
from typing import List, Optional, Tuple

from fpcore.geo import haversine_m


def pseudonymize_pid(vehicle_pid: str, recipient_period_key: str) -> str:
    """
    Produces unlinkable, privacy-safe pseudonyms per recipient and period (§7.19):
    pid_share = base32(HMAC_SHA256(key_recipient_period, vehicle_pid))[:16].
    Guarantees no correlation across different recipients or reporting periods.
    """
    if not vehicle_pid or not recipient_period_key:
        raise ValueError("vehicle_pid and recipient_period_key must be non-empty")

    key_bytes = recipient_period_key.encode("utf-8")
    msg_bytes = vehicle_pid.encode("utf-8")
    digest = hmac.new(key_bytes, msg_bytes, hashlib.sha256).digest()
    b32 = base64.b32encode(digest).decode("utf-8").rstrip("=")
    return b32[:16].upper()


def mask_location(lat: float, lon: float, decimal_places: int = 3) -> Tuple[float, float]:
    """
    Reduces spatial resolution of GPS coordinates for public or third-party sharing.
    3 decimal places ≈ ~110m resolution (neighborhood level).
    2 decimal places ≈ ~1.1km resolution (district level).
    """
    factor = 10 ** decimal_places
    masked_lat = round(lat * factor) / factor
    masked_lon = round(lon * factor) / factor
    return (masked_lat, masked_lon)


def is_within_privacy_buffer(
    lat: float,
    lon: float,
    buffer_zones: List[Tuple[float, float, float]]  # (center_lat, center_lon, radius_m)
) -> bool:
    """
    Checks if a coordinate falls within any restricted privacy buffer zone (§7.19)
    such as an employee home address or restricted depot facility.
    """
    for center_lat, center_lon, radius_m in buffer_zones:
        dist_m = haversine_m(lat, lon, center_lat, center_lon)
        if dist_m <= radius_m:
            return True
    return False


def add_laplace_noise(
    value: float,
    sensitivity: float,
    epsilon: float,
    rng: Optional[random.Random] = None
) -> float:
    """
    Adds Differential Privacy Laplace noise (§7.19):
    Laplace(0, sensitivity / epsilon).
    Enables provably private aggregate analytics queries.
    """
    if epsilon <= 0.0:
        raise ValueError("Epsilon must be strictly positive")
    if sensitivity < 0.0:
        raise ValueError("Sensitivity must be non-negative")

    scale = sensitivity / epsilon
    r = rng if rng is not None else random.Random()
    u = r.uniform(-0.5, 0.5)
    # Inverse CDF of zero-mean Laplace distribution
    noise = -scale * math.copysign(1.0, u) * math.log(1.0 - 2.0 * abs(u))
    return value + noise


def verify_erasure_evidence(
    records: List[dict],
    target_pid: str
) -> bool:
    """
    Verifies that a vehicle's trace has been completely scrubbed from storage sinks (§8.S3).
    Returns True if target_pid is completely absent.
    """
    for r in records:
        if r.get("vehicle_pid") == target_pid:
            return False
        # Also check for embedded PII
        if str(r).find(target_pid) != -1:
            return False
    return True
