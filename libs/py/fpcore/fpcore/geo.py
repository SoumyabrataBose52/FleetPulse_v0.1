"""
Geo — Haversine, GPS Outlier Filter, Geohash — §7.3 & §7.4
=============================================================

Haversine distance (§7.3):
  d = 2R·asin(√(sin²(Δφ/2) + cosφ₁·cosφ₂·sin²(Δλ/2)))
  R = 6371.0088 km

GPS Outlier Filter (§7.3):
  Computes implied speed from consecutive positions; flags GPS_SUSPECT when:
    implied_speed > max(250 km/h, 3 × reported_speed)
  Escape hatch: if ≥ 3 consecutive suspect points agree within 100 m → accept relocation.
  Never derives speed from GPS deltas when reported speed ≈ 0 (jitter filter).

Geohash (§7.4):
  Base-32 encoding: '0123456789bcdefghjkmnpqrstuvwxyz'
  Interleaves longitude (even bits) and latitude (odd bits).
  Functions: encode, decode, neighbors, cover_bbox.

All functions are pure (no I/O, no side effects).
Complexity: O(p) per encode/decode, O(1) per haversine, O(1) per GPS filter step.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterator

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

EARTH_RADIUS_KM = 6371.0088
MAX_SAFE_SPEED_KMH = 250.0
OUTLIER_SPEED_MULTIPLIER = 3.0
RELOCATION_CONFIRM_POINTS = 3
RELOCATION_RADIUS_M = 100.0

_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
_BASE32_MAP = {ch: i for i, ch in enumerate(_BASE32)}

# ---------------------------------------------------------------------------
# Haversine
# ---------------------------------------------------------------------------


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Return the great-circle distance in kilometres between two points.

    Args:
        lat1, lon1: First point in decimal degrees.
        lat2, lon2: Second point in decimal degrees.
    """
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)

    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Return the great-circle distance in metres."""
    return haversine_km(lat1, lon1, lat2, lon2) * 1000.0


# ---------------------------------------------------------------------------
# GPS Outlier Filter
# ---------------------------------------------------------------------------


@dataclass
class GpsPoint:
    lat: float
    lon: float
    ts_s: float           # Unix timestamp in seconds
    speed_kmh: float | None = None  # Sensor-reported speed (None if unavailable)


@dataclass
class GpsFilterState:
    """Mutable state for the GPS outlier filter — one per vehicle."""
    anchor_lat: float | None = None
    anchor_lon: float | None = None
    anchor_ts: float | None = None
    suspect_streak: int = 0
    suspect_lat_sum: float = 0.0
    suspect_lon_sum: float = 0.0


def gps_filter_step(
    state: GpsFilterState,
    point: GpsPoint,
    max_safe_kmh: float = MAX_SAFE_SPEED_KMH,
    speed_multiplier: float = OUTLIER_SPEED_MULTIPLIER,
    relocation_confirm: int = RELOCATION_CONFIRM_POINTS,
    relocation_radius_m: float = RELOCATION_RADIUS_M,
) -> tuple[bool, int]:
    """
    Process one GPS point through the outlier filter.

    Returns:
        (is_valid, quality_bitmask)
        quality_bitmask: 0 = clean, 1 = GPS_SUSPECT

    Side effects: mutates `state`.

    Escape hatch: if ≥ relocation_confirm consecutive suspect points
    cluster within relocation_radius_m of each other, accept the relocation.
    Never use GPS delta speed when reported speed ≈ 0.
    """
    GPS_SUSPECT = 1

    if state.anchor_lat is None:
        # First point — always accept
        state.anchor_lat = point.lat
        state.anchor_lon = point.lon
        state.anchor_ts = point.ts_s
        state.suspect_streak = 0
        return True, 0

    dt_s = point.ts_s - state.anchor_ts  # type: ignore[operator]
    if dt_s <= 0:
        # Same timestamp or non-monotonic — skip speed check, accept
        return True, 0

    dist_km = haversine_km(state.anchor_lat, state.anchor_lon, point.lat, point.lon)  # type: ignore[arg-type]
    implied_kmh = dist_km / (dt_s / 3600.0)

    # Do NOT use implied speed when reported sensor speed is near zero
    # (4m GPS jitter at 1 Hz looks like ~14 km/h)
    reported = point.speed_kmh or 0.0
    near_zero = reported < 2.0

    threshold = max(max_safe_kmh, speed_multiplier * reported) if not near_zero else max_safe_kmh

    if implied_kmh <= threshold:
        # Accept
        state.anchor_lat = point.lat
        state.anchor_lon = point.lon
        state.anchor_ts = point.ts_s
        state.suspect_streak = 0
        state.suspect_lat_sum = 0.0
        state.suspect_lon_sum = 0.0
        return True, 0

    # --- Suspect point ---
    state.suspect_streak += 1
    state.suspect_lat_sum += point.lat
    state.suspect_lon_sum += point.lon

    # Escape hatch: check if recent suspect points cluster tightly
    if state.suspect_streak >= relocation_confirm:
        mean_lat = state.suspect_lat_sum / state.suspect_streak
        mean_lon = state.suspect_lon_sum / state.suspect_streak
        spread_m = haversine_m(mean_lat, mean_lon, point.lat, point.lon)
        if spread_m <= relocation_radius_m:
            # Accept relocation — vehicle was towed or teleported
            state.anchor_lat = point.lat
            state.anchor_lon = point.lon
            state.anchor_ts = point.ts_s
            state.suspect_streak = 0
            state.suspect_lat_sum = 0.0
            state.suspect_lon_sum = 0.0
            return True, 0  # relocation accepted, not flagged as suspect

    return False, GPS_SUSPECT


# ---------------------------------------------------------------------------
# Geohash
# ---------------------------------------------------------------------------


def geohash_encode(lat: float, lon: float, precision: int = 7) -> str:
    """
    Encode a lat/lon pair as a geohash string.

    Args:
        lat: Latitude in decimal degrees (-90 to 90).
        lon: Longitude in decimal degrees (-180 to 180).
        precision: Number of base-32 characters (1–12). Default 7 (≈153 m).

    Returns:
        Geohash string of the requested precision.
    """
    lat_min, lat_max = -90.0, 90.0
    lon_min, lon_max = -180.0, 180.0
    bits = 0
    bit_count = 0
    even = True  # lon first
    result = []

    while len(result) < precision:
        if even:
            mid = (lon_min + lon_max) / 2
            if lon >= mid:
                bits = (bits << 1) | 1
                lon_min = mid
            else:
                bits = bits << 1
                lon_max = mid
        else:
            mid = (lat_min + lat_max) / 2
            if lat >= mid:
                bits = (bits << 1) | 1
                lat_min = mid
            else:
                bits = bits << 1
                lat_max = mid
        even = not even
        bit_count += 1
        if bit_count == 5:
            result.append(_BASE32[bits])
            bits = 0
            bit_count = 0

    return "".join(result)


def geohash_decode(ghash: str) -> tuple[float, float, float, float]:
    """
    Decode a geohash to a bounding box.

    Returns:
        (lat_center, lon_center, lat_error, lon_error)
    """
    lat_min, lat_max = -90.0, 90.0
    lon_min, lon_max = -180.0, 180.0
    even = True

    for ch in ghash:
        val = _BASE32_MAP[ch]
        for i in range(4, -1, -1):
            bit = (val >> i) & 1
            if even:
                mid = (lon_min + lon_max) / 2
                if bit:
                    lon_min = mid
                else:
                    lon_max = mid
            else:
                mid = (lat_min + lat_max) / 2
                if bit:
                    lat_min = mid
                else:
                    lat_max = mid
            even = not even

    lat_c = (lat_min + lat_max) / 2
    lon_c = (lon_min + lon_max) / 2
    lat_err = (lat_max - lat_min) / 2
    lon_err = (lon_max - lon_min) / 2
    return lat_c, lon_c, lat_err, lon_err


def geohash_neighbors(ghash: str) -> dict[str, str]:
    """
    Return the 8 neighbours of a geohash cell.

    Returns:
        Dict with keys: N, NE, E, SE, S, SW, W, NW → geohash strings.
    """
    lat_c, lon_c, lat_err, lon_err = geohash_decode(ghash)
    precision = len(ghash)
    step_lat = lat_err * 2
    step_lon = lon_err * 2
    return {
        "N":  geohash_encode(lat_c + step_lat, lon_c, precision),
        "NE": geohash_encode(lat_c + step_lat, lon_c + step_lon, precision),
        "E":  geohash_encode(lat_c, lon_c + step_lon, precision),
        "SE": geohash_encode(lat_c - step_lat, lon_c + step_lon, precision),
        "S":  geohash_encode(lat_c - step_lat, lon_c, precision),
        "SW": geohash_encode(lat_c - step_lat, lon_c - step_lon, precision),
        "W":  geohash_encode(lat_c, lon_c - step_lon, precision),
        "NW": geohash_encode(lat_c + step_lat, lon_c - step_lon, precision),
    }


def geohash_cover_bbox(
    lat_min: float, lon_min: float, lat_max: float, lon_max: float
) -> tuple[int, list[str]]:
    """
    Return the smallest precision and the set of geohash cells that cover a bounding box.

    Uses the heuristic: choose the largest precision with ≤ 64 covering cells.
    Uses grid sampling (not BFS) to avoid explosion on large bounding boxes.

    Returns:
        (precision, [geohash_strings])
    """
    for precision in range(7, 2, -1):
        cells = _bbox_cells_grid(lat_min, lon_min, lat_max, lon_max, precision)
        if len(cells) <= 64:
            return precision, cells
    # Fall back to precision 3
    return 3, _bbox_cells_grid(lat_min, lon_min, lat_max, lon_max, 3)


def _bbox_cells_grid(
    lat_min: float, lon_min: float, lat_max: float, lon_max: float, precision: int
) -> list[str]:
    """
    Return unique geohash cells covering the bbox at the given precision.
    Uses a grid-sampling approach (encode corners + a grid of interior points).
    Capped at 256 samples to prevent explosion on huge bounding boxes.
    """
    seen: set[str] = set()

    # Always include the four corners
    for lat in (lat_min, lat_max):
        for lon in (lon_min, lon_max):
            seen.add(geohash_encode(
                max(-90.0, min(90.0, lat)),
                max(-180.0, min(180.0, lon)),
                precision,
            ))

    # Sample a grid inside the bbox
    n_steps = min(16, max(2, int((lat_max - lat_min) / 0.5) + 1))
    m_steps = min(16, max(2, int((lon_max - lon_min) / 0.5) + 1))
    lat_step = (lat_max - lat_min) / n_steps if n_steps > 1 else 0.0
    lon_step = (lon_max - lon_min) / m_steps if m_steps > 1 else 0.0
    for i in range(n_steps + 1):
        for j in range(m_steps + 1):
            lat = min(90.0, lat_min + i * lat_step)
            lon = min(180.0, lon_min + j * lon_step)
            seen.add(geohash_encode(lat, lon, precision))
            if len(seen) > 256:
                break
        if len(seen) > 256:
            break

    return list(seen)
