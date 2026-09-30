"""
Trip & Stop Segmentation — §7.10
===================================
Streaming FSM (per vehicle, on ordered clean stream) + Viterbi DP batch refinement.

FSM states: PARKED → DRIVING ⇄ STOPPED_ENGINE_ON → PARKED
  Trip START: ignition on AND (speed ≥ 5 km/h for ≥ 10s OR displacement > 50m from anchor)
  Trip END:   ignition off confirmed, or stationary ≥ 300s (no ignition), or gap ≥ 15min

Negative test: stationary vehicle with σ=4m GPS jitter → zero trips produced.

Viterbi DP (batch, §7.10):
  Label each sample MOVING / STOPPED minimising:
    Σ cost(label_i | speed_i) + λ · [label_i ≠ label_{i-1}]
  cost_move(s) = ((θ-s)/θ)² for s < θ else 0
  cost_stop(s) = min(1, (s/θ)²)
  θ = 5 km/h, λ ≈ 8.  O(n), two states.

Complexity: O(1) per FSM sample, O(n) Viterbi.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import NamedTuple


# ---------------------------------------------------------------------------
# Config (matches §18 defaults.yaml)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class TripConfig:
    move_enter_kmh: float = 5.0       # speed to enter MOVING state
    move_exit_kmh: float = 1.0        # speed to exit MOVING (hysteresis)
    start_confirm_s: float = 10.0     # seconds at speed ≥ move_enter before trip starts
    start_displacement_m: float = 50.0  # displacement from anchor to start trip
    anchor_radius_m: float = 30.0     # GPS jitter anchor radius
    ignition_confirm_s: float = 30.0  # seconds to confirm ignition change
    no_ignition_stop_s: float = 300.0  # stationary time to end trip without ignition
    gap_truncate_s: float = 900.0     # data gap → TRUNCATED trip end
    dp_theta_kmh: float = 5.0        # Viterbi threshold speed
    dp_lambda: float = 8.0           # Viterbi transition penalty


DEFAULT_TRIP_CONFIG = TripConfig()


# ---------------------------------------------------------------------------
# FSM Types
# ---------------------------------------------------------------------------

class VehiclePhase(Enum):
    PARKED = "PARKED"
    DRIVING = "DRIVING"
    STOPPED_ENGINE_ON = "STOPPED_ENGINE_ON"


class TripEndReason(Enum):
    IGNITION_OFF = "IGNITION_OFF"
    STATIONARY_TIMEOUT = "STATIONARY_TIMEOUT"
    DATA_GAP = "DATA_GAP_TRUNCATED"
    NORMAL = "NORMAL"


@dataclass
class Trip:
    trip_id: str                # deterministic hash set by caller
    vehicle_pid: str
    start_ts_ms: int
    end_ts_ms: int
    distance_km: float
    duration_s: float
    idle_s: float
    start_lat: float | None
    start_lon: float | None
    end_lat: float | None
    end_lon: float | None
    end_reason: TripEndReason = TripEndReason.NORMAL
    truncated: bool = False


@dataclass
class TripSample:
    """One telemetry reading for trip segmentation."""
    ts_ms: int
    speed_kmh: float
    lat: float | None = None
    lon: float | None = None
    ignition: bool | None = None
    odo_km: float | None = None


@dataclass
class TripFSMState:
    """Mutable per-vehicle FSM state."""
    phase: VehiclePhase = VehiclePhase.PARKED
    trip_start_ts_ms: int | None = None
    trip_start_lat: float | None = None
    trip_start_lon: float | None = None
    anchor_lat: float | None = None
    anchor_lon: float | None = None
    moving_since_ms: int | None = None   # when we first hit move_enter speed
    stationary_since_ms: int | None = None
    last_ts_ms: int | None = None
    last_lat: float | None = None
    last_lon: float | None = None
    cumulative_distance_km: float = 0.0
    idle_s: float = 0.0
    last_odo_km: float | None = None
    _trip_seq: int = 0

    def next_trip_id(self, vehicle_pid: str) -> str:
        self._trip_seq += 1
        return f"{vehicle_pid}_{self.trip_start_ts_ms}_{self._trip_seq}"


# ---------------------------------------------------------------------------
# Haversine (inline to avoid circular import)
# ---------------------------------------------------------------------------

def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371088.0  # metres
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    return _haversine_m(lat1, lon1, lat2, lon2) / 1000.0


# ---------------------------------------------------------------------------
# FSM: process one sample
# ---------------------------------------------------------------------------

def trip_fsm_step(
    state: TripFSMState,
    sample: TripSample,
    config: TripConfig = DEFAULT_TRIP_CONFIG,
) -> Trip | None:
    """
    Process one ordered, deduplicated telemetry sample through the trip FSM.

    Returns a completed Trip if one is closed by this sample, else None.
    Mutates `state`.
    """
    prev_ts = state.last_ts_ms
    state.last_ts_ms = sample.ts_ms
    dt_s = (sample.ts_ms - prev_ts) / 1000.0 if prev_ts is not None else 0.0

    # --- Data gap check ---
    if prev_ts is not None and dt_s > config.gap_truncate_s:
        if state.phase == VehiclePhase.DRIVING:
            trip = _close_trip(state, sample, TripEndReason.DATA_GAP, truncated=True)
            state.phase = VehiclePhase.PARKED
            _reset_trip_state(state, sample)
            return trip

    # --- Distance accumulation ---
    if (state.last_lat is not None and state.last_lon is not None
            and sample.lat is not None and sample.lon is not None
            and dt_s > 0):
        if state.phase == VehiclePhase.DRIVING:
            if state.last_odo_km is not None and sample.odo_km is not None:
                delta = sample.odo_km - state.last_odo_km
                if 0 < delta < 5.0:  # sanity check (< 5 km/sample)
                    state.cumulative_distance_km += delta
            else:
                state.cumulative_distance_km += _haversine_km(
                    state.last_lat, state.last_lon, sample.lat, sample.lon
                )

    # --- Idle tracking ---
    if state.phase == VehiclePhase.STOPPED_ENGINE_ON and dt_s > 0:
        state.idle_s += dt_s

    # --- State transitions ---
    is_moving = sample.speed_kmh >= config.move_enter_kmh
    is_stationary = sample.speed_kmh < config.move_exit_kmh
    ignition_off = sample.ignition is False

    completed_trip = None

    if state.phase == VehiclePhase.PARKED:
        if is_moving:
            if state.moving_since_ms is None:
                state.moving_since_ms = sample.ts_ms
            moving_duration_s = (sample.ts_ms - state.moving_since_ms) / 1000.0
            # Displacement from anchor
            displaced = False
            if (state.anchor_lat is not None and sample.lat is not None
                    and _haversine_m(state.anchor_lat, state.anchor_lon or 0.0,
                                     sample.lat, sample.lon or 0.0) > config.start_displacement_m):
                displaced = True

            if moving_duration_s >= config.start_confirm_s or displaced:
                state.phase = VehiclePhase.DRIVING
                state.trip_start_ts_ms = state.moving_since_ms
                state.trip_start_lat = sample.lat
                state.trip_start_lon = sample.lon
                state.cumulative_distance_km = 0.0
                state.idle_s = 0.0
        else:
            state.moving_since_ms = None
            if sample.lat is not None:
                state.anchor_lat = sample.lat
                state.anchor_lon = sample.lon

    elif state.phase == VehiclePhase.DRIVING:
        if ignition_off:
            completed_trip = _close_trip(state, sample, TripEndReason.IGNITION_OFF)
            state.phase = VehiclePhase.PARKED
            _reset_trip_state(state, sample)
        elif is_stationary:
            if sample.ignition is True:
                state.phase = VehiclePhase.STOPPED_ENGINE_ON
                state.stationary_since_ms = sample.ts_ms
            else:
                if state.stationary_since_ms is None:
                    state.stationary_since_ms = sample.ts_ms
                stationary_s = (sample.ts_ms - state.stationary_since_ms) / 1000.0
                if stationary_s >= config.no_ignition_stop_s:
                    completed_trip = _close_trip(state, sample, TripEndReason.STATIONARY_TIMEOUT)
                    state.phase = VehiclePhase.PARKED
                    _reset_trip_state(state, sample)

    elif state.phase == VehiclePhase.STOPPED_ENGINE_ON:
        if ignition_off:
            completed_trip = _close_trip(state, sample, TripEndReason.IGNITION_OFF)
            state.phase = VehiclePhase.PARKED
            _reset_trip_state(state, sample)
        elif is_moving:
            state.phase = VehiclePhase.DRIVING
            state.stationary_since_ms = None

    state.last_lat = sample.lat
    state.last_lon = sample.lon
    state.last_odo_km = sample.odo_km
    return completed_trip


def _close_trip(state: TripFSMState, sample: TripSample, reason: TripEndReason,
                truncated: bool = False) -> Trip | None:
    if state.trip_start_ts_ms is None:
        return None
    duration_s = (sample.ts_ms - state.trip_start_ts_ms) / 1000.0
    if duration_s < 30:
        return None  # Too short to be a real trip
    trip_id = state.next_trip_id(sample.lat and "V" or "V")
    return Trip(
        trip_id=trip_id,
        vehicle_pid="",  # caller fills in
        start_ts_ms=state.trip_start_ts_ms,
        end_ts_ms=sample.ts_ms,
        distance_km=state.cumulative_distance_km,
        duration_s=duration_s,
        idle_s=state.idle_s,
        start_lat=state.trip_start_lat,
        start_lon=state.trip_start_lon,
        end_lat=sample.lat,
        end_lon=sample.lon,
        end_reason=reason,
        truncated=truncated,
    )


def _reset_trip_state(state: TripFSMState, sample: TripSample) -> None:
    state.trip_start_ts_ms = None
    state.trip_start_lat = None
    state.trip_start_lon = None
    state.moving_since_ms = None
    state.stationary_since_ms = None
    state.cumulative_distance_km = 0.0
    state.idle_s = 0.0
    state.anchor_lat = sample.lat
    state.anchor_lon = sample.lon


# ---------------------------------------------------------------------------
# Viterbi DP (batch, §7.10)
# ---------------------------------------------------------------------------

def viterbi_segment(
    speeds: list[float],
    theta: float = 5.0,
    lam: float = 8.0,
) -> list[str]:
    """
    Two-state Viterbi DP for MOVING/STOPPED labelling.

    Args:
        speeds:  List of speed values in km/h.
        theta:   Speed threshold (5 km/h).
        lam:     Transition penalty.

    Returns:
        List of labels: "MOVING" or "STOPPED", same length as speeds.

    Complexity: O(n), two states.
    """
    n = len(speeds)
    if n == 0:
        return []

    MOVING, STOPPED = 0, 1

    def cost_move(s: float) -> float:
        return ((theta - s) / theta) ** 2 if s < theta else 0.0

    def cost_stop(s: float) -> float:
        return min(1.0, (s / theta) ** 2)

    # dp[state] = min cost to reach this state at current step
    dp = [cost_move(speeds[0]), cost_stop(speeds[0])]
    parent: list[list[int]] = [[0, 0]]  # parent[t][state]

    for t in range(1, n):
        s = speeds[t]
        cm = cost_move(s)
        cs = cost_stop(s)
        # From MOVING
        from_m_to_m = dp[MOVING] + cm
        from_s_to_m = dp[STOPPED] + lam + cm
        # From STOPPED
        from_m_to_s = dp[MOVING] + lam + cs
        from_s_to_s = dp[STOPPED] + cs

        if from_m_to_m <= from_s_to_m:
            new_m, par_m = from_m_to_m, MOVING
        else:
            new_m, par_m = from_s_to_m, STOPPED

        if from_m_to_s <= from_s_to_s:
            new_s, par_s = from_m_to_s, MOVING
        else:
            new_s, par_s = from_s_to_s, STOPPED

        dp = [new_m, new_s]
        parent.append([par_m, par_s])

    # Traceback
    labels: list[str] = [""] * n
    cur = MOVING if dp[MOVING] <= dp[STOPPED] else STOPPED
    for t in range(n - 1, -1, -1):
        labels[t] = "MOVING" if cur == MOVING else "STOPPED"
        if t > 0:
            cur = parent[t][cur]

    return labels
