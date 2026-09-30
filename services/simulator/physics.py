"""
§6.6 Vehicle Physics Integration Engine (1 Hz step, dt = 1.0 s).

Implements deterministic physical integration:
  - Kinematics with aerodynamic drag and rolling resistance
  - EV battery power, auxiliary HVAC load, and regenerative braking
  - ICE fuel consumption and hybrid engine auto-stop at standstill
  - CC-CV tapered charging curve
  - Coolant thermal dynamics lag
  - Numerical odometer integration

Complexity: O(1) per vehicle per 1 Hz simulation tick.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Optional, Tuple


# Physical Constants (§6.6)
RHO_AIR = 1.2         # Air density in kg/m^3
GRAVITY_G = 9.81      # Gravitational acceleration in m/s^2
ETA_DRIVE = 0.90      # EV drivetrain efficiency
ETA_REGEN = 0.65      # EV regen recovery efficiency
ETA_CHARGE = 0.92     # AC/DC charging efficiency
COOLANT_TAU_S = 120.0 # Thermal lag constant in seconds

# ICE Fuel conversion factors (§6.6)
PETROL_L_PER_KWH = 0.446
DIESEL_L_PER_KWH = 0.337


@dataclass(frozen=True)
class VehicleModelSpecs:
    """Model specifications from PostgreSQL vehicle_model table (§5.3)."""
    powertrain: str       # ICE_PETROL, ICE_DIESEL, HYBRID, EV
    body: str             # SEDAN, VAN, TRUCK, BUS
    mass_kg: float
    cda: float            # Drag area in m^2
    crr: float            # Rolling resistance coefficient
    battery_kwh: float
    tank_l: float
    idle_burn_lph: float
    aux_base_kw: float
    max_regen_kw: float
    max_dc_kw: float


@dataclass
class PhysicsState:
    """Mutable dynamic state of a simulated vehicle (§6.6)."""
    speed_kmh: float = 0.0
    odo_km: float = 0.0
    soc_pct: float = 100.0
    soh_true: float = 0.92       # True hidden battery SoH [0.70, 1.00]
    fuel_l: float = 40.0
    batt_voltage_v: float = 380.0
    charge_kw: float = 0.0
    coolant_c: float = 85.0
    rpm: int = 0
    ignition: bool = False


def integrate_kinematics(
    v_curr_kmh: float,
    target_speed_kmh: float,
    max_accel_ms2: float,
    comfort_brake_ms2: float,
    dt_s: float = 1.0,
) -> Tuple[float, float]:
    """
    Integrate longitudinal speed under acceleration/braking limits.

    Returns:
        (new_speed_kmh, accel_ms2)
    """
    v_curr_ms = v_curr_kmh / 3.6
    v_target_ms = target_speed_kmh / 3.6

    delta_v_needed = v_target_ms - v_curr_ms
    target_accel = delta_v_needed / dt_s

    # Clamp acceleration
    accel_ms2 = max(-comfort_brake_ms2, min(max_accel_ms2, target_accel))
    new_v_ms = max(0.0, v_curr_ms + accel_ms2 * dt_s)
    new_v_kmh = new_v_ms * 3.6

    return new_v_kmh, accel_ms2


def calculate_wheel_power_kw(
    mass_kg: float,
    cda: float,
    crr: float,
    v_kmh: float,
    accel_ms2: float,
) -> float:
    """
    Wheel mechanical power equation (§6.6):
      P_wheel = m * a * v + 0.5 * rho * CdA * v^3 + Crr * m * g * v
    """
    v_ms = max(0.0, v_kmh / 3.6)
    f_accel = mass_kg * accel_ms2
    f_aero = 0.5 * RHO_AIR * cda * (v_ms ** 2)
    f_rolling = crr * mass_kg * GRAVITY_G

    p_wheel_watts = (f_accel + f_aero + f_rolling) * v_ms
    return p_wheel_watts / 1000.0


def step_physics(
    state: PhysicsState,
    specs: VehicleModelSpecs,
    target_speed_kmh: float,
    max_accel_ms2: float,
    comfort_brake_ms2: float,
    ambient_temp_c: float,
    dt_s: float = 1.0,
    charger_cap_kw: Optional[float] = None,
) -> float:
    """
    Step vehicle physical model by dt_s seconds (§6.6).

    Returns:
        accel_ms2 achieved during this time step.
    """
    # 1. Kinematics integration if engine/power is on
    if state.ignition:
        new_speed_kmh, accel_ms2 = integrate_kinematics(
            v_curr_kmh=state.speed_kmh,
            target_speed_kmh=target_speed_kmh,
            max_accel_ms2=max_accel_ms2,
            comfort_brake_ms2=comfort_brake_ms2,
            dt_s=dt_s,
        )
        avg_speed_kmh = (state.speed_kmh + new_speed_kmh) / 2.0
        state.speed_kmh = new_speed_kmh
        # Odometer integration
        dist_km = (avg_speed_kmh * dt_s) / 3600.0
        state.odo_km += dist_km
    else:
        state.speed_kmh = 0.0
        accel_ms2 = 0.0

    # 2. Charging behavior when plugged in (vehicle stopped)
    if not state.ignition and charger_cap_kw is not None and charger_cap_kw > 0.0:
        if specs.powertrain in ("EV", "HYBRID") and state.soc_pct < 100.0:
            # CC-CV Charging taper (§6.6): P_max for soc <= 80%, taper to 0.1 * P_max at 100%
            p_limit = min(charger_cap_kw, specs.max_dc_kw)
            if state.soc_pct <= 80.0:
                p_grid = p_limit
            else:
                fraction_above_80 = (state.soc_pct - 80.0) / 20.0
                p_grid = p_limit * (1.0 - 0.9 * fraction_above_80)

            state.charge_kw = max(0.0, p_grid)
            energy_kwh = (state.charge_kw * dt_s / 3600.0) * ETA_CHARGE
            usable_battery_kwh = specs.battery_kwh * state.soh_true
            delta_soc = (energy_kwh / usable_battery_kwh) * 100.0
            state.soc_pct = min(100.0, state.soc_pct + delta_soc)
            state.rpm = 0
            return 0.0

    state.charge_kw = 0.0

    # 3. Running powertrain energy consumption
    p_wheel_kw = calculate_wheel_power_kw(
        mass_kg=specs.mass_kg,
        cda=specs.cda,
        crr=specs.crr,
        v_kmh=state.speed_kmh,
        accel_ms2=accel_ms2,
    )

    # Auxiliary HVAC power
    p_aux = specs.aux_base_kw + 0.05 * abs(ambient_temp_c - 22.0)

    # EV / Hybrid battery integration
    if specs.powertrain in ("EV", "HYBRID") and state.ignition:
        if p_wheel_kw >= 0.0:
            p_batt = (p_wheel_kw / ETA_DRIVE) + p_aux
        else:
            # Regenerative braking
            p_regen = abs(p_wheel_kw) * ETA_REGEN
            p_regen_clamped = min(p_regen, specs.max_regen_kw)
            p_batt = -p_regen_clamped + p_aux

        usable_battery_kwh = specs.battery_kwh * state.soh_true
        delta_soc_pct = -(p_batt * dt_s / 3600.0) / usable_battery_kwh * 100.0
        state.soc_pct = max(0.0, min(100.0, state.soc_pct + delta_soc_pct))

        # Battery voltage curve model
        state.batt_voltage_v = 330.0 + (state.soc_pct / 100.0) * 80.0

    # ICE / Hybrid fuel integration
    if specs.powertrain in ("ICE_PETROL", "ICE_DIESEL", "HYBRID"):
        if not state.ignition:
            state.rpm = 0
        elif state.speed_kmh < 1.0:
            # Standstill
            if specs.powertrain == "HYBRID":
                # Hybrid engine auto-stops at standstill (§6.6)
                state.rpm = 0
            else:
                state.rpm = 800
                fuel_burned = (specs.idle_burn_lph / 3600.0) * dt_s
                state.fuel_l = max(0.0, state.fuel_l - fuel_burned)
        else:
            # Moving
            state.rpm = int(1200 + (state.speed_kmh / 120.0) * 2800)
            f_factor = DIESEL_L_PER_KWH if specs.powertrain == "ICE_DIESEL" else PETROL_L_PER_KWH
            if specs.powertrain == "HYBRID":
                f_factor *= 0.65  # Hybrid efficiency bonus

            fuel_burn_rate = (specs.idle_burn_lph / 3600.0) + (f_factor * max(0.0, p_wheel_kw) / 3600.0)
            fuel_burned = fuel_burn_rate * dt_s
            state.fuel_l = max(0.0, state.fuel_l - fuel_burned)

    # Coolant temperature first-order lag (§6.6)
    p_heat = max(0.0, p_wheel_kw) if state.ignition else 0.0
    t_target = 88.0 + 0.12 * p_heat
    d_temp = ((t_target - state.coolant_c) / COOLANT_TAU_S) * dt_s
    state.coolant_c += d_temp

    return accel_ms2
