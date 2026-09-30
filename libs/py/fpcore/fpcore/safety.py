"""
Driver Safety Score — §7.16
==============================
Explainable 0–100 score with exponential decay (half-life 14 days).

Per driver maintains: W (weighted event sum), K (km driven), t_last (last update time)
Decay: on each update, W ← W * 2^(-Δt/h) + w, K ← K * 2^(-Δt/h) + km
Rate R = W / (K / 100)    (weighted events per 100 km)
Score = 100 * exp(-R / R₀), R₀ = 8 (calibrated so fleet median ≈ 80)

Harsh events are RECOMPUTED from speed deltas, NOT trusted from OEM.
OEM events used only for map-matching context.

Event weights: HARSH_BRAKE=3, HARSH_ACCEL=2, HARSH_CORNER=2,
               OVERSPEED = 1 + over_kmh/10 (per episode)

Validation: Spearman(score, -alpha) ≥ 0.8 against simulator ground truth.

Complexity: O(1) per update.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import NamedTuple


# ---------------------------------------------------------------------------
# Config (§18 defaults)
# ---------------------------------------------------------------------------

HARSH_BRAKE_MS2     = 3.0
HARSH_ACCEL_MS2     = 2.5
HARSH_CORNER_MS2    = 3.0
OVERSPEED_FACTOR    = 1.10
OVERSPEED_MIN_S     = 10.0
DECAY_HALF_LIFE_DAYS = 14.0
R0                  = 8.0
RISKY_SCORE_BELOW   = 60.0

EVENT_WEIGHTS = {
    "HARSH_BRAKE":   3.0,
    "HARSH_ACCEL":   2.0,
    "HARSH_CORNER":  2.0,
}


# ---------------------------------------------------------------------------
# Types
# ---------------------------------------------------------------------------

class SafetyEventType(str, Enum):
    HARSH_BRAKE   = "HARSH_BRAKE"
    HARSH_ACCEL   = "HARSH_ACCEL"
    HARSH_CORNER  = "HARSH_CORNER"
    OVERSPEED     = "OVERSPEED"


@dataclass
class SafetyEvent:
    event_type: SafetyEventType
    ts_s: float       # Unix timestamp seconds
    accel_ms2: float  # absolute value
    speed_kmh: float
    speed_limit_kmh: float | None = None  # for OVERSPEED; None if unknown
    over_kmh: float = 0.0  # how much over limit

    @property
    def weight(self) -> float:
        if self.event_type == SafetyEventType.OVERSPEED:
            return 1.0 + self.over_kmh / 10.0
        return EVENT_WEIGHTS.get(self.event_type.value, 1.0)


@dataclass
class DriverSafetyState:
    """
    Per-driver mutable scoring state.
    W and K are decay-weighted accumulators.
    """
    W: float = 0.0          # weighted event sum (decayed)
    K: float = 0.0          # km driven (decayed)
    t_last_s: float | None = None
    # Per-event-type contribution tracking for explainability
    contrib: dict[str, float] = field(default_factory=lambda: {
        "HARSH_BRAKE": 0.0,
        "HARSH_ACCEL": 0.0,
        "HARSH_CORNER": 0.0,
        "OVERSPEED": 0.0,
    })
    # For detecting 10-point drop in 7 days
    score_7d_ago: float | None = None
    t_7d_anchor_s: float | None = None


class ScoreResult(NamedTuple):
    score: float                    # 0–100
    rate_per_100km: float           # R = W / (K/100)
    contributions: dict[str, float] # per event type contribution to R
    coaching_hint: str              # top contributor description
    is_risky: bool                  # score < 60 or dropped ≥ 10 pts in 7d


# ---------------------------------------------------------------------------
# Internal decay helper
# ---------------------------------------------------------------------------

_LN2 = math.log(2)
_HALF_LIFE_S = DECAY_HALF_LIFE_DAYS * 86400.0


def _decay_factor(dt_s: float) -> float:
    """Exponential decay factor for time interval dt_s with half-life 14 days."""
    return math.exp(-_LN2 * dt_s / _HALF_LIFE_S)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def update_on_distance(
    state: DriverSafetyState,
    km: float,
    ts_s: float,
) -> None:
    """
    Update the driver state with distance driven (no event).

    Args:
        state:  Mutable driver state.
        km:     Kilometres driven since last update.
        ts_s:   Current Unix timestamp in seconds.
    """
    _apply_decay(state, ts_s)
    state.K += km
    state.t_last_s = ts_s


def update_on_event(
    state: DriverSafetyState,
    event: SafetyEvent,
) -> None:
    """
    Update driver state with a safety event.

    Args:
        state:  Mutable driver state.
        event:  Safety event with weight.
    """
    _apply_decay(state, event.ts_s)
    state.W += event.weight
    key = event.event_type.value
    state.contrib[key] = state.contrib.get(key, 0.0) + event.weight
    state.t_last_s = event.ts_s


def compute_score(state: DriverSafetyState) -> ScoreResult:
    """
    Compute the current driver score (does not mutate state).

    Returns ScoreResult with score, rate, contributions, coaching hint.
    """
    if state.K < 10.0:
        # Not enough data for a meaningful score
        return ScoreResult(
            score=100.0,
            rate_per_100km=0.0,
            contributions=dict(state.contrib),
            coaching_hint="Insufficient data (< 10 km)",
            is_risky=False,
        )

    R = state.W / (state.K / 100.0) if state.K > 0 else 0.0
    score = 100.0 * math.exp(-R / R0)
    score = max(0.0, min(100.0, score))

    # Contribution breakdown (as % of R)
    total_w = sum(state.contrib.values()) or 1.0
    contribs = {k: (v / total_w) * R for k, v in state.contrib.items()}

    # Coaching hint: top contributor
    top_key = max(state.contrib, key=lambda k: state.contrib[k])
    top_val = state.contrib[top_key]
    if top_val > 0:
        hint = f"Reduce {top_key.lower().replace('_', ' ')} events (contributing {top_val:.1f} weighted events)"
    else:
        hint = "Keep up the safe driving!"

    is_risky = (score < RISKY_SCORE_BELOW or
                (state.score_7d_ago is not None and state.score_7d_ago - score >= 10.0))

    return ScoreResult(
        score=round(score, 2),
        rate_per_100km=round(R, 4),
        contributions=contribs,
        coaching_hint=hint,
        is_risky=is_risky,
    )


def _apply_decay(state: DriverSafetyState, ts_s: float) -> None:
    """Apply exponential decay to W and K since last update."""
    if state.t_last_s is None:
        state.t_last_s = ts_s
        return
    dt_s = max(0.0, ts_s - state.t_last_s)
    if dt_s > 0:
        factor = _decay_factor(dt_s)
        state.W *= factor
        state.K *= factor
        # Decay contributions in the same proportion
        for k in state.contrib:
            state.contrib[k] *= factor
    state.t_last_s = ts_s


# ---------------------------------------------------------------------------
# Event detection from speed deltas (recompute from ordered telemetry)
# ---------------------------------------------------------------------------

@dataclass
class EventDetectorState:
    """Per-vehicle state for detecting harsh events from speed/position deltas."""
    prev_speed_kmh: float | None = None
    prev_ts_s: float | None = None
    prev_heading_deg: float | None = None
    overspeed_start_s: float | None = None
    overspeed_peak_ms2: float = 0.0


def detect_events_from_delta(
    estate: EventDetectorState,
    ts_s: float,
    speed_kmh: float,
    heading_deg: float | None = None,
    speed_limit_kmh: float | None = None,
) -> list[SafetyEvent]:
    """
    Detect safety events from consecutive speed readings.

    Returns any events detected at this sample (0 or 1 per type normally).
    Mutates `estate`.
    """
    events: list[SafetyEvent] = []

    if estate.prev_speed_kmh is None:
        estate.prev_speed_kmh = speed_kmh
        estate.prev_ts_s = ts_s
        estate.prev_heading_deg = heading_deg
        return events

    dt_s = ts_s - estate.prev_ts_s  # type: ignore[operator]  # None case excluded above
    if dt_s <= 0:
        return events

    # Longitudinal acceleration (m/s²)
    dv_ms = (speed_kmh - estate.prev_speed_kmh) / 3.6  # km/h → m/s
    accel = dv_ms / dt_s

    # Harsh brake
    if accel <= -HARSH_BRAKE_MS2:
        events.append(SafetyEvent(
            event_type=SafetyEventType.HARSH_BRAKE,
            ts_s=ts_s,
            accel_ms2=abs(accel),
            speed_kmh=speed_kmh,
        ))

    # Harsh acceleration
    if accel >= HARSH_ACCEL_MS2:
        events.append(SafetyEvent(
            event_type=SafetyEventType.HARSH_ACCEL,
            ts_s=ts_s,
            accel_ms2=abs(accel),
            speed_kmh=speed_kmh,
        ))

    # Lateral acceleration from heading change
    if heading_deg is not None and estate.prev_heading_deg is not None:
        d_heading = heading_deg - estate.prev_heading_deg
        # Normalise to [-180, 180]
        while d_heading > 180:
            d_heading -= 360
        while d_heading < -180:
            d_heading += 360
        omega_rad_s = math.radians(abs(d_heading)) / dt_s
        v_ms = speed_kmh / 3.6
        a_lat = v_ms * omega_rad_s
        if a_lat >= HARSH_CORNER_MS2:
            events.append(SafetyEvent(
                event_type=SafetyEventType.HARSH_CORNER,
                ts_s=ts_s,
                accel_ms2=a_lat,
                speed_kmh=speed_kmh,
            ))

    # Overspeed (only when speed limit known; else use absolute per-body fallback)
    if speed_limit_kmh is not None:
        threshold = speed_limit_kmh * OVERSPEED_FACTOR
        if speed_kmh > threshold:
            if estate.overspeed_start_s is None:
                estate.overspeed_start_s = ts_s
        else:
            if estate.overspeed_start_s is not None:
                duration = ts_s - estate.overspeed_start_s
                if duration >= OVERSPEED_MIN_S:
                    # Emit a single overspeed event for the episode
                    events.append(SafetyEvent(
                        event_type=SafetyEventType.OVERSPEED,
                        ts_s=ts_s,
                        accel_ms2=0.0,
                        speed_kmh=speed_kmh,
                        speed_limit_kmh=speed_limit_kmh,
                        over_kmh=max(0.0, speed_kmh - threshold),
                    ))
                estate.overspeed_start_s = None

    estate.prev_speed_kmh = speed_kmh
    estate.prev_ts_s = ts_s
    estate.prev_heading_deg = heading_deg
    return events
