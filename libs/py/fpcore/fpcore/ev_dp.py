"""
Charging Schedule DP + Depot Allocation + Battery SoH — §7.13 / §7.14 / §7.15
=================================================================================

Charging DP (§7.13):
  Single vehicle, minimise cost to deliver E_target kWh given:
    - slot length Δ = 15 min, slot prices p_i, charger limit P_chg
    - CC–CV taper curve: full power ≤ 80% SoC, linear taper to 10% at 100%
    - charge efficiency η_c = 0.92
  DP: f[i][e] = min cost to have e kWh at start of slot i
  Complexity: O(T × E × A), e.g. 48 × 200 × 30 ≈ 288K

Depot allocation (§7.14):
  Coupled: N vehicles share site_power_cap_kw per slot.
  Heuristic: sort by least-slack, run single-vehicle DP sequentially
  against residual capacity. Not optimal but tractable.

SoH estimator (§7.15):
  E_batt = ∫ P_grid × η_c dt  (over a session with ΔSoC ≥ 30pp)
  SoH_est = E_batt / (ΔSoC/100 × nominal_kWh)
  Report median of last 5 qualifying sessions.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from statistics import median
from typing import NamedTuple


# ---------------------------------------------------------------------------
# Config (§18)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ChargingConfig:
    slot_minutes: int = 15
    energy_step_kwh: float = 0.5
    charge_efficiency: float = 0.92
    taper_start_soc: float = 0.80
    taper_end_power_fraction: float = 0.10
    reserve_fraction: float = 0.10
    soh_min_delta_soc_pp: float = 30.0
    soh_sessions_median: int = 5


DEFAULT_CHARGING_CONFIG = ChargingConfig()


# ---------------------------------------------------------------------------
# Charging DP (§7.13)
# ---------------------------------------------------------------------------

class ChargingPlan(NamedTuple):
    slot_kwh: list[float]          # energy per slot
    total_cost: float
    final_soc_pct: float
    baseline_cost: float           # "charge immediately" cost
    saving: float                  # baseline_cost - total_cost (≥ 0)
    feasible: bool                 # True if E_target reached
    best_achievable_kwh: float     # filled when infeasible


def charging_dp(
    e0_kwh: float,          # current energy in battery (kWh)
    e_target_kwh: float,    # desired energy at departure (kWh)
    e_max_kwh: float,       # battery usable capacity (nominal × SoH)
    slot_prices: list[float],  # price per kWh for each slot
    charger_kw: float,      # charger power limit (kW)
    vehicle_kw: float,      # vehicle onboard charger limit (kW)
    config: ChargingConfig = DEFAULT_CHARGING_CONFIG,
) -> ChargingPlan:
    """
    Optimal single-vehicle charging schedule via DP.

    Returns ChargingPlan with per-slot energy, total cost, and savings vs baseline.
    If E_target is infeasible, returns the best achievable plan (feasible=False).
    """
    T = len(slot_prices)
    if T == 0:
        return ChargingPlan([], 0.0, e0_kwh / e_max_kwh * 100 if e_max_kwh > 0 else 0.0,
                            0.0, 0.0, e0_kwh >= e_target_kwh, e0_kwh)

    delta = config.slot_minutes / 60.0        # slot length in hours
    eta = config.charge_efficiency
    step = config.energy_step_kwh
    P_max = min(charger_kw, vehicle_kw)

    # Discretise energy levels
    n_levels = math.ceil(e_max_kwh / step) + 1
    e_levels = [i * step for i in range(n_levels)]

    # Find the nearest discrete energy level (index)
    def level_idx(e: float) -> int:
        return max(0, min(n_levels - 1, round(e / step)))

    INF = float("inf")
    # f[e_idx] = min cost to reach level e_idx at start of current slot
    f = [INF] * n_levels
    parent: list[list[int]] = [[e_idx for e_idx in range(n_levels)] for _ in range(T + 1)]

    e0_idx = level_idx(e0_kwh)
    f[e0_idx] = 0.0

    # Taper: P(soc) = P_max for soc ≤ taper_start, linear taper to P_max*end_frac at 100%
    def taper_power(e_kwh: float) -> float:
        soc = e_kwh / e_max_kwh if e_max_kwh > 0 else 1.0
        if soc <= config.taper_start_soc:
            return P_max
        t_range = 1.0 - config.taper_start_soc
        t_pos = (soc - config.taper_start_soc) / t_range if t_range > 0 else 1.0
        return P_max * (1.0 - t_pos * (1.0 - config.taper_end_power_fraction))

    history = [f[:]]
    for i in range(T):
        f_next = [INF] * n_levels
        p_next = list(range(n_levels))  # parent: same state by default
        p_i = slot_prices[i]

        for e_idx in range(n_levels):
            if f[e_idx] == INF:
                continue
            e_kwh = e_levels[e_idx]
            # How many steps can we charge in this slot?
            p_slot = taper_power(e_kwh)
            max_kwh = p_slot * delta * eta
            max_steps = min(n_levels - e_idx - 1, math.floor(max_kwh / step))

            for a in range(max_steps + 1):  # a steps charged
                e_next_idx = min(n_levels - 1, e_idx + a)
                e_charged = a * step
                cost_a = (e_charged / eta) * p_i  # grid energy × price
                new_cost = f[e_idx] + cost_a
                if new_cost < f_next[e_next_idx]:
                    f_next[e_next_idx] = new_cost
                    p_next[e_next_idx] = e_idx

        f = f_next
        history.append(f[:])
        parent.append(p_next)

    # Find best final state
    e_target_idx = level_idx(e_target_kwh)
    best_idx = e_target_idx
    best_cost = f[e_target_idx]
    feasible = best_cost < INF

    if not feasible:
        # Find best achievable
        for e_idx in range(n_levels - 1, -1, -1):
            if f[e_idx] < INF:
                best_idx = e_idx
                best_cost = f[e_idx]
                break

    # Reconstruct schedule
    slot_kwh = [0.0] * T
    cur_idx = best_idx
    for i in range(T, 0, -1):
        prev_idx = parent[i][cur_idx]
        slot_kwh[i - 1] = (cur_idx - prev_idx) * step
        cur_idx = prev_idx

    # Baseline cost: charge at max power immediately (dumb plug-in)
    baseline_cost = _baseline_cost(e0_kwh, e_target_kwh, e_max_kwh, slot_prices, P_max, eta,
                                   delta, step, config)

    final_soc = min(100.0, (e_levels[best_idx] / e_max_kwh * 100) if e_max_kwh > 0 else 0.0)

    return ChargingPlan(
        slot_kwh=slot_kwh,
        total_cost=round(best_cost, 4),
        final_soc_pct=round(final_soc, 2),
        baseline_cost=round(baseline_cost, 4),
        saving=round(max(0.0, baseline_cost - best_cost), 4),
        feasible=feasible,
        best_achievable_kwh=e_levels[best_idx],
    )


def _baseline_cost(e0: float, e_target: float, e_max: float, prices: list[float],
                   P_max: float, eta: float, delta_h: float, step: float,
                   config: ChargingConfig) -> float:
    """Simulate greedy 'charge immediately at max power' strategy."""
    e = e0
    total = 0.0
    for i, p in enumerate(prices):
        if e >= e_target:
            break
        soc = e / e_max if e_max > 0 else 1.0
        if soc <= config.taper_start_soc:
            pw = P_max
        else:
            t_range = 1.0 - config.taper_start_soc
            t_pos = (soc - config.taper_start_soc) / t_range if t_range > 0 else 1.0
            pw = P_max * (1.0 - t_pos * (1.0 - config.taper_end_power_fraction))
        added = min(pw * delta_h * eta, e_target - e, e_max - e)
        grid_kwh = added / eta
        total += grid_kwh * p
        e += added
    return total


# ---------------------------------------------------------------------------
# Depot-level allocation (§7.14) — heuristic
# ---------------------------------------------------------------------------

@dataclass
class VehicleChargingRequest:
    vehicle_id: str
    e0_kwh: float
    e_target_kwh: float
    e_max_kwh: float
    vehicle_kw: float
    n_slots_available: int  # dwell time in slots


@dataclass
class DepotAllocationResult:
    vehicle_plans: dict[str, ChargingPlan]  # vehicle_id → plan
    residual_cap: list[float]               # remaining kW per slot
    infeasible_vehicles: list[str]          # couldn't reach target


def depot_allocate(
    requests: list[VehicleChargingRequest],
    site_power_cap_kw: float,
    slot_prices: list[float],
    charger_kw: float,
    config: ChargingConfig = DEFAULT_CHARGING_CONFIG,
) -> DepotAllocationResult:
    """
    Heuristic depot-level charging allocation.

    Strategy: sort by least-slack (available_time / energy_needed),
    run single-vehicle DP sequentially against residual capacity per slot.

    Not globally optimal (documented as heuristic).
    """
    delta = config.slot_minutes / 60.0
    T = len(slot_prices)
    residual = [site_power_cap_kw] * T

    # Sort by least slack: (slots available) / (energy needed / P_max)
    def slack(r: VehicleChargingRequest) -> float:
        need = max(0.001, r.e_target_kwh - r.e0_kwh)
        p = min(charger_kw, r.vehicle_kw)
        min_slots = math.ceil(need / (p * delta * config.charge_efficiency))
        return r.n_slots_available - min_slots

    sorted_reqs = sorted(requests, key=slack)

    plans: dict[str, ChargingPlan] = {}
    infeasible: list[str] = []

    for req in sorted_reqs:
        # Build per-slot power limits from residual capacity
        effective_charger = [min(charger_kw, r) for r in residual[:req.n_slots_available]]
        # Use the minimum residual as the charger limit (conservative)
        min_cap = min(effective_charger) if effective_charger else 0.0
        slot_p = slot_prices[:req.n_slots_available]

        plan = charging_dp(
            e0_kwh=req.e0_kwh,
            e_target_kwh=req.e_target_kwh,
            e_max_kwh=req.e_max_kwh,
            slot_prices=slot_p,
            charger_kw=min_cap,
            vehicle_kw=req.vehicle_kw,
            config=config,
        )
        plans[req.vehicle_id] = plan
        if not plan.feasible:
            infeasible.append(req.vehicle_id)

        # Reduce residual capacity
        for i, kwh in enumerate(plan.slot_kwh):
            kw_used = (kwh / config.charge_efficiency) / delta
            residual[i] = max(0.0, residual[i] - kw_used)

    return DepotAllocationResult(
        vehicle_plans=plans,
        residual_cap=residual,
        infeasible_vehicles=infeasible,
    )


# ---------------------------------------------------------------------------
# Battery SoH Estimator (§7.15)
# ---------------------------------------------------------------------------

@dataclass
class SohSession:
    """One qualifying charging session for SoH estimation."""
    delta_soc_pp: float  # ΔSoC in percentage points (e.g. 40.0)
    energy_grid_kwh: float  # energy from grid side
    nominal_kwh: float      # nominal battery capacity


@dataclass
class SohEstimatorState:
    """Per-vehicle SoH estimation state."""
    sessions: list[float] = field(default_factory=list)  # recent SoH estimates
    max_sessions: int = 5


def estimate_soh(
    state: SohEstimatorState,
    session: SohSession,
    eta_c: float = 0.92,
    config: ChargingConfig = DEFAULT_CHARGING_CONFIG,
) -> float | None:
    """
    Update the SoH estimate from a new qualifying charging session.

    Args:
        state:   Per-vehicle estimator state (mutated).
        session: Completed charging session data.
        eta_c:   Charge efficiency assumed (0.92).

    Returns:
        Current median SoH estimate (%) if ≥ 1 session; else None.
        Clamped to [50, 100].
    """
    if session.delta_soc_pp < config.soh_min_delta_soc_pp:
        return None  # Not enough SoC swing for a reliable estimate

    if session.nominal_kwh <= 0:
        return None

    e_batt = session.energy_grid_kwh * eta_c  # energy actually into battery
    soh = (e_batt / (session.delta_soc_pp / 100.0 * session.nominal_kwh)) * 100.0
    soh = max(50.0, min(100.0, soh))

    state.sessions.append(soh)
    if len(state.sessions) > state.max_sessions:
        state.sessions = state.sessions[-state.max_sessions:]

    return float(median(state.sessions))
