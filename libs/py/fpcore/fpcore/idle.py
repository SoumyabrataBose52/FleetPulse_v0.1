"""
Idle Detection and Cost Engine — §7.11
========================================
Detects engine-idle episodes from an ordered telemetry stream and computes
fuel/electricity cost, CO₂ emissions, and avoidable idle cost.

Definitions:
  - Engine ON: rpm > 0, or (ignition ON and powertrain != hybrid-autostop)
  - Idle candidate: engine ON, sensor speed < idle.speed_max_kmh (2 km/h)
  - Episode CONFIRMED: candidate duration >= min_idle_s (120 s)
  - NOT idle: traffic stops (< 120 s) → negative examples, produce NO episode
  - Hybrid auto-stop: engine off at standstill → NOT idle (rpm = 0, no idle burn)

State machine (per vehicle):
  INACTIVE → (candidate opens) → CANDIDATE → (≥120s) → ACTIVE
  ACTIVE   → (speed ≥ 5 km/h or ignition off or gap) → INACTIVE (emit episode)
  CANDIDATE → (< 120s + speed resumes) → INACTIVE (no episode)

Complexity: O(1) per sample.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import NamedTuple


# ---------------------------------------------------------------------------
# Config (matches config/defaults.yaml)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class IdleConfig:
    speed_max_kmh: float = 2.0       # km/h threshold to be considered idle
    resume_kmh: float = 5.0          # km/h to exit idle
    min_idle_s: float = 120.0        # seconds before an episode is confirmed
    gap_close_s: float = 60.0        # data gap (s) → close episode with GAP flag
    allowed_idle_min_per_day: float = 10.0  # per-vehicle daily allowance
    # CO₂ factors in kg per litre
    co2_per_l: dict[str, float] = field(default_factory=lambda: {
        "ICE_PETROL": 2.31,
        "ICE_DIESEL": 2.68,
        "HYBRID": 2.31 * 0.65,
    })


DEFAULT_CONFIG = IdleConfig()


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

class Powertrain(str, Enum):
    ICE_PETROL = "ICE_PETROL"
    ICE_DIESEL = "ICE_DIESEL"
    HYBRID = "HYBRID"
    EV = "EV"


@dataclass
class IdleSample:
    """One telemetry reading relevant to idle detection."""
    ts_s: float            # Unix timestamp (seconds)
    speed_kmh: float       # sensor speed (0.0 if unknown)
    ignition: bool | None  # None if not provided
    rpm: int | None        # None if not provided
    powertrain: Powertrain
    # Cost inputs
    idle_burn_lph: float = 0.0   # ICE/hybrid idle fuel burn L/h
    aux_kw: float = 0.0          # EV: auxiliary load kW while idling
    fuel_price: float = 1.30     # per litre
    tariff_kwh: float = 0.14     # per kWh (EV idle cost)


@dataclass
class IdleEpisode:
    """A confirmed idle episode (duration >= min_idle_s)."""
    start_ts: float
    end_ts: float
    duration_s: float
    powertrain: Powertrain
    # Cost fields
    fuel_burn_l: float = 0.0
    energy_kwh: float = 0.0
    cost: float = 0.0
    co2_kg: float = 0.0
    avoidable_cost: float = 0.0  # computed at the end of the day, not per-episode
    close_reason: str = "NORMAL"  # NORMAL | GAP | IGNITION_OFF | SPEED_RESUME


class _CandidateState(Enum):
    INACTIVE = "INACTIVE"
    CANDIDATE = "CANDIDATE"
    ACTIVE = "ACTIVE"


@dataclass
class IdleDetectorState:
    """Mutable per-vehicle state for the idle detector."""
    phase: _CandidateState = _CandidateState.INACTIVE
    candidate_start_ts: float | None = None
    active_start_ts: float | None = None
    last_ts: float | None = None
    # Accumulators for the active episode
    _accu_fuel_l: float = 0.0
    _accu_energy_kwh: float = 0.0
    _accu_cost: float = 0.0
    _accu_co2_kg: float = 0.0


# ---------------------------------------------------------------------------
# Engine-on inference
# ---------------------------------------------------------------------------

def _engine_on(sample: IdleSample) -> bool:
    """
    Infer whether the engine is running.
    Hybrid auto-stop: rpm == 0 at standstill → engine off, no idle cost.
    """
    if sample.rpm is not None:
        return sample.rpm > 0

    if sample.powertrain == Powertrain.HYBRID:
        # Hybrid auto-stops at standstill (speed < 2 km/h)
        return sample.speed_kmh >= 2.0 or (sample.ignition is True)

    if sample.ignition is not None:
        return sample.ignition

    # No signal — assume on if moving
    return sample.speed_kmh > 0


# ---------------------------------------------------------------------------
# Per-sample cost increment
# ---------------------------------------------------------------------------

def _cost_increment(sample: IdleSample, dt_s: float) -> tuple[float, float, float, float]:
    """Return (fuel_l, energy_kwh, cost, co2_kg) for one idle sample duration dt_s."""
    dt_h = dt_s / 3600.0
    if sample.powertrain in (Powertrain.ICE_PETROL, Powertrain.ICE_DIESEL, Powertrain.HYBRID):
        fuel_l = sample.idle_burn_lph * dt_h
        co2_factor = DEFAULT_CONFIG.co2_per_l.get(sample.powertrain.value, 2.31)
        co2_kg = fuel_l * co2_factor
        cost = fuel_l * sample.fuel_price
        return fuel_l, 0.0, cost, co2_kg
    elif sample.powertrain == Powertrain.EV:
        energy_kwh = sample.aux_kw * dt_h
        cost = energy_kwh * sample.tariff_kwh
        return 0.0, energy_kwh, cost, 0.0
    return 0.0, 0.0, 0.0, 0.0


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def process_sample(
    state: IdleDetectorState,
    sample: IdleSample,
    config: IdleConfig = DEFAULT_CONFIG,
) -> IdleEpisode | None:
    """
    Process one ordered telemetry sample.

    Returns an IdleEpisode if one is completed by this sample, else None.
    Mutates `state`.

    Args:
        state: Per-vehicle mutable idle detector state.
        sample: One telemetry reading.
        config: Idle detection thresholds.

    Returns:
        Completed IdleEpisode, or None.
    """
    prev_ts = state.last_ts
    state.last_ts = sample.ts_s

    engine = _engine_on(sample)
    idle_candidate = engine and sample.speed_kmh < config.speed_max_kmh

    dt_s = (sample.ts_s - prev_ts) if prev_ts is not None else 0.0

    # --- Data gap check ---
    if prev_ts is not None and dt_s > config.gap_close_s:
        if state.phase in (_CandidateState.ACTIVE, _CandidateState.CANDIDATE):
            ep = _close_episode(state, sample.ts_s, "GAP", sample)
            state.phase = _CandidateState.INACTIVE
            if ep is not None:
                return ep

    if state.phase == _CandidateState.INACTIVE:
        if idle_candidate:
            state.phase = _CandidateState.CANDIDATE
            state.candidate_start_ts = sample.ts_s
        return None

    elif state.phase == _CandidateState.CANDIDATE:
        if not idle_candidate:
            # Candidate cancelled — traffic stop, no episode emitted
            state.phase = _CandidateState.INACTIVE
            state.candidate_start_ts = None
            state._accu_fuel_l = 0.0
            state._accu_energy_kwh = 0.0
            state._accu_cost = 0.0
            state._accu_co2_kg = 0.0
            return None

        # Still a candidate — accumulate cost
        fl, ek, c, co2 = _cost_increment(sample, dt_s)
        state._accu_fuel_l += fl
        state._accu_energy_kwh += ek
        state._accu_cost += c
        state._accu_co2_kg += co2

        duration = sample.ts_s - (state.candidate_start_ts or sample.ts_s)
        if duration >= config.min_idle_s:
            # Promote to active episode
            state.phase = _CandidateState.ACTIVE
            state.active_start_ts = state.candidate_start_ts
        return None

    elif state.phase == _CandidateState.ACTIVE:
        if not idle_candidate:
            reason = "IGNITION_OFF" if not engine else "SPEED_RESUME"
            ep = _close_episode(state, sample.ts_s, reason, sample)
            state.phase = _CandidateState.INACTIVE
            return ep

        # Still active — accumulate
        fl, ek, c, co2 = _cost_increment(sample, dt_s)
        state._accu_fuel_l += fl
        state._accu_energy_kwh += ek
        state._accu_cost += c
        state._accu_co2_kg += co2
        return None

    return None


def _close_episode(
    state: IdleDetectorState,
    end_ts: float,
    reason: str,
    sample: IdleSample,
) -> IdleEpisode | None:
    start = state.active_start_ts or state.candidate_start_ts
    if start is None:
        return None
    duration = end_ts - start
    if duration < DEFAULT_CONFIG.min_idle_s:
        # Candidate that never promoted
        return None
    return IdleEpisode(
        start_ts=start,
        end_ts=end_ts,
        duration_s=duration,
        powertrain=sample.powertrain,
        fuel_burn_l=state._accu_fuel_l,
        energy_kwh=state._accu_energy_kwh,
        cost=state._accu_cost,
        co2_kg=state._accu_co2_kg,
        close_reason=reason,
    )


def compute_avoidable_cost(
    episodes: list[IdleEpisode],
    day_total_idle_min: float,
    allowed_min: float = DEFAULT_CONFIG.allowed_idle_min_per_day,
) -> float:
    """
    Compute the avoidable idle cost for a vehicle-day.

    Avoidable idle = total idle minutes above the daily allowance × cost per minute.

    Args:
        episodes: All idle episodes for the vehicle on this day.
        day_total_idle_min: Pre-computed total idle minutes (sum of episode durations).
        allowed_min: Policy allowance in minutes per day.

    Returns:
        Avoidable cost in currency units.
    """
    excess_min = max(0.0, day_total_idle_min - allowed_min)
    if excess_min == 0 or not episodes:
        return 0.0
    total_min = sum(e.duration_s / 60.0 for e in episodes)
    if total_min == 0:
        return 0.0
    cost_per_min = sum(e.cost for e in episodes) / total_min
    return excess_min * cost_per_min
