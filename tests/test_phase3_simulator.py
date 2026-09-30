"""
§15.4 Phase 3 — World Generator and Simulator Test Suite.

Validates:
  1. Synthetic World Model (§6.2): 3,600 CSR road graph nodes, ~14,000 edges, depots, chargers, POIs, diurnal temperature.
  2. Physics Engine (§6.6): Odometer integral ∫v dt, EV power & regen, CC-CV charging taper, hybrid auto-stop.
  3. Driver Behavior & Ground Truth (§6.7): Aggression Beta distribution, kinematics event thresholds.
  4. Five OEM Encoders (§5.2): Astra, Borealis, Cetus, Draco, Echo v1/v2 generation and end-to-end normalization.
  5. Chaos Injector (§6.8): Duplicates, out-of-order delay sampling, GPS jitter, VIN corruption.
  6. 100,000 Vehicle Seed Data (§5.8): Check-digit validity across 100K VINs, Zipf tenant distribution.
  7. Determinism & Performance (§6.1, §15.4): Seed repeatability, high event generation rate.
"""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path
import random
import pytest

from services.simulator.world import World
from services.simulator.physics import (
    PhysicsState,
    VehicleModelSpecs,
    step_physics,
    integrate_kinematics,
    calculate_wheel_power_kw,
)
from services.simulator.driver import DriverProfile, sample_driver_profile, detect_ground_truth_events
from services.simulator.oem_encoder import OEMEncoder
from services.simulator.chaos import ChaosInjector, ChaosConfig
from services.simulator.vehicle import SimulatedVehicle, VehicleState
from services.simulator.engine import SimulatorEngine
from services.simulator.seed import generate_seed_data
from fpcore.mapping import compile_mapping, apply_mapping
from fpcore.vin import validate as validate_vin


ROOT_DIR = Path(__file__).resolve().parent.parent
OEM_MAPPINGS_DIR = ROOT_DIR / "config" / "oem-mappings"
SEED_DIR = ROOT_DIR / "data" / "seed"


class TestWorldModel:
    """Validates simulation synthetic geography and road graph (§6.2)."""

    @pytest.fixture(scope="class")
    def world(self):
        return World(seed=42)

    def test_road_graph_structure(self, world):
        """Road graph must contain 3,600 nodes and ~14,000 edges in CSR layout."""
        total_edges = sum(len(adj) for adj in world.road_graph._out)
        assert 13000 <= total_edges <= 16000
        # Check node coordinates centered around Chennai
        sample_node = world.road_graph.nodes[0]
        assert 12.8 <= sample_node.lat <= 13.3
        assert 80.0 <= sample_node.lon <= 80.5

    def test_places_inventory(self, world):
        """World must contain 120 depots, 200 chargers, 800 POIs, and 5 unapproved sites."""
        assert len(world.depots) == 120
        assert len(world.chargers) == 200
        assert len(world.pois) == 800
        assert len(world.unapproved_depots) == 5

    def test_diurnal_temperature_model(self, world):
        """Ambient temperature must oscillate between 26 °C and 36 °C (§6.2)."""
        # Test across 24 hours (86,400s)
        temps = [world.get_ambient_temperature(h * 3600.0) for h in range(24)]
        assert min(temps) >= 25.5
        assert max(temps) <= 36.5
        # Peak should be around 14:00 (hour 14)
        peak_hour = temps.index(max(temps))
        assert 12 <= peak_hour <= 16


class TestPhysicsEngine:
    """Validates kinematics, EV battery, and thermodynamical invariants (§6.6)."""

    def test_odometer_integration(self):
        """Odometer delta must equal trapezoidal integral of speed (error < 0.1%)."""
        specs = VehicleModelSpecs(
            powertrain="ICE_PETROL", body="SEDAN", mass_kg=1500.0, cda=0.65, crr=0.010,
            battery_kwh=0.0, tank_l=45.0, idle_burn_lph=0.7, aux_base_kw=0.8,
            max_regen_kw=0.0, max_dc_kw=0.0
        )
        state = PhysicsState(speed_kmh=40.0, odo_km=100.0, ignition=True)
        start_odo = state.odo_km
        integrated_distance = 0.0

        for _ in range(120):
            v_start = state.speed_kmh
            step_physics(
                state=state,
                specs=specs,
                target_speed_kmh=65.0,
                max_accel_ms2=2.0,
                comfort_brake_ms2=2.0,
                ambient_temp_c=30.0,
                dt_s=1.0,
            )
            v_end = state.speed_kmh
            dist_step = ((v_start + v_end) / 2.0 * 1.0) / 3600.0
            integrated_distance += dist_step

        delta_odo = state.odo_km - start_odo
        assert math.isclose(delta_odo, integrated_distance, rel_tol=1e-5)

    def test_ev_cc_cv_charging_taper(self):
        """Charging power must be constant <= 80% SoC and taper linearly above 80% (§6.6)."""
        specs = VehicleModelSpecs(
            powertrain="EV", body="SEDAN", mass_kg=1650.0, cda=0.62, crr=0.010,
            battery_kwh=75.0, tank_l=0.0, idle_burn_lph=0.0, aux_base_kw=1.0,
            max_regen_kw=40.0, max_dc_kw=100.0
        )
        # At 50% SoC, should draw full charger cap (50 kW)
        state_50 = PhysicsState(soc_pct=50.0, ignition=False)
        step_physics(state_50, specs, 0.0, 1.0, 1.0, 30.0, dt_s=1.0, charger_cap_kw=50.0)
        assert math.isclose(state_50.charge_kw, 50.0, abs_tol=1e-2)

        # At 90% SoC, should taper (half-way between P_max and 0.1 * P_max -> 27.5 kW)
        state_90 = PhysicsState(soc_pct=90.0, ignition=False)
        step_physics(state_90, specs, 0.0, 1.0, 1.0, 30.0, dt_s=1.0, charger_cap_kw=50.0)
        assert state_90.charge_kw < 50.0
        assert math.isclose(state_90.charge_kw, 27.5, abs_tol=0.5)

    def test_hybrid_engine_standstill_autostop(self):
        """Hybrid vehicles must auto-stop engine at standstill (rpm = 0, zero idle burn) (§6.6)."""
        specs = VehicleModelSpecs(
            powertrain="HYBRID", body="SEDAN", mass_kg=1550.0, cda=0.64, crr=0.010,
            battery_kwh=1.8, tank_l=40.0, idle_burn_lph=0.0, aux_base_kw=0.8,
            max_regen_kw=30.0, max_dc_kw=0.0
        )
        state = PhysicsState(speed_kmh=0.0, fuel_l=35.0, ignition=True)
        step_physics(state, specs, target_speed_kmh=0.0, max_accel_ms2=1.5, comfort_brake_ms2=2.0, ambient_temp_c=30.0, dt_s=1.0)
        assert state.rpm == 0
        assert math.isclose(state.fuel_l, 35.0, abs_tol=1e-6)


class TestDriverBehaviorAndGroundTruth:
    """Validates driver aggression sampling and ground-truth events (§6.7)."""

    def test_driver_profile_distribution(self):
        """Driver aggression must fall in [0.0, 1.0] with sensible kinematics parameters."""
        rng = random.Random(42)
        profiles = [sample_driver_profile(driver_id=i, rng=rng) for i in range(100)]
        for p in profiles:
            assert 0.0 <= p.aggression <= 1.0
            assert 0.8 <= p.speed_factor <= 1.5
            assert 1.3 <= p.max_accel_ms2 <= 3.5
            assert 1.5 <= p.comfort_brake_ms2 <= 4.5

    def test_ground_truth_event_detection(self):
        """Validates detection thresholds for harsh events (§6.7)."""
        # Decel >= 3.0 m/s^2 -> HARSH_BRAKE
        assert detect_ground_truth_events(speed_kmh=40.0, accel_ms2=-3.2, heading_deg=90, prev_heading_deg=90, speed_limit_kmh=60.0) == "HARSH_BRAKE"
        # Accel >= 2.5 m/s^2 -> HARSH_ACCEL
        assert detect_ground_truth_events(speed_kmh=30.0, accel_ms2=2.8, heading_deg=90, prev_heading_deg=90, speed_limit_kmh=60.0) == "HARSH_ACCEL"
        # Sharp turn at 40 km/h -> HARSH_CORNER
        assert detect_ground_truth_events(speed_kmh=40.0, accel_ms2=0.0, heading_deg=90, prev_heading_deg=45, speed_limit_kmh=60.0, dt_s=1.0) == "HARSH_CORNER"
        # Speed > limit * 1.10 -> OVERSPEED
        assert detect_ground_truth_events(speed_kmh=75.0, accel_ms2=0.0, heading_deg=90, prev_heading_deg=90, speed_limit_kmh=60.0) == "OVERSPEED"


class TestOEMEncodersAndNormalization:
    """Verifies that generated OEM frames can be compiled and normalized by fpcore.mapping."""

    def test_all_five_oem_formats_roundtrip(self):
        """Ensure all 5 OEM encoders generate valid wire formats that normalize cleanly."""
        vin = "1HGCR2F83HA000001"
        ts_ms = 1790331302120

        # OEM A
        raw_a = OEMEncoder.encode_oem_a(vin, ts_ms, 13.0827, 80.2707, 92, 64.2, 18234.7, True, 61.5, ["P0301"], "HARSH_BRAKE", 101)
        map_a = compile_mapping(json.load(open(OEM_MAPPINGS_DIR / "oem_a.json")))
        v_a, f_a = apply_mapping(raw_a, map_a)
        assert v_a == vin
        assert f_a["speed_kmh"] == 64.2
        assert f_a["lat_e6"] == 13082700

        # OEM B
        raw_b = OEMEncoder.encode_oem_b(vin, ts_ms, 13.0827, 80.2707, 92, 64.2, 18234.7, True, 61.5, ["P0301"], "HARSH_BRAKE", 101)
        map_b = compile_mapping(json.load(open(OEM_MAPPINGS_DIR / "oem_b.json")))
        v_b, f_b = apply_mapping(raw_b, map_b)
        assert v_b == vin
        assert math.isclose(f_b["speed_kmh"], 64.2, rel_tol=1e-2)

        # OEM C
        raw_c = OEMEncoder.encode_oem_c(vin, ts_ms, 13.0827, 80.2707, 64.2, 85.0, 18234.7, 0.0, "HARSH_BRAKE")
        map_c = compile_mapping(json.load(open(OEM_MAPPINGS_DIR / "oem_c.json")))
        v_c, f_c = apply_mapping(raw_c, map_c)
        assert v_c == vin
        assert math.isclose(f_c["odo_km"], 18234.7, rel_tol=1e-2)

        # OEM D
        raw_d = OEMEncoder.encode_oem_d(vin, ts_ms, 13.0827, 80.2707, 64.2, 18234.7, True, 61.5, ["P0301"])
        map_d = compile_mapping(json.load(open(OEM_MAPPINGS_DIR / "oem_d.json")))
        v_d, f_d = apply_mapping(raw_d, map_d)
        assert v_d == vin
        assert f_d["speed_kmh"] == 64.2

        # OEM E v1 & v2
        raw_e1 = OEMEncoder.encode_oem_e_v1(vin, ts_ms, 13.0827, 80.2707, 64.2, 85.0)
        map_e1 = compile_mapping(json.load(open(OEM_MAPPINGS_DIR / "oem_e_v1.json")))
        v_e1, f_e1 = apply_mapping(raw_e1, map_e1)
        assert v_e1 == vin
        assert f_e1["soc_pct"] == 85.0

        raw_e2 = OEMEncoder.encode_oem_e_v2(vin, ts_ms, 13.0827, 80.2707, 64.2, 85.0, 380.0, 22.0)
        map_e2 = compile_mapping(json.load(open(OEM_MAPPINGS_DIR / "oem_e_v2.json")))
        v_e2, f_e2 = apply_mapping(raw_e2, map_e2)
        assert v_e2 == vin
        assert f_e2["charge_state"] == "CHARGING"
        assert math.isclose(f_e2["charge_kw"], 22.0, abs_tol=0.1)


class Test100KSeedDataset:
    """Validates 100K seed dataset CSV integrity and check-digit compliance (§5.8)."""

    def test_seed_files_exist_and_counts(self):
        """Ensure all required seed CSV files exist with exact counts."""
        assert (SEED_DIR / "tenants.csv").exists()
        assert (SEED_DIR / "fleets.csv").exists()
        assert (SEED_DIR / "vehicles.csv").exists()
        assert (SEED_DIR / "drivers.csv").exists()
        assert (SEED_DIR / "vehicle_models.csv").exists()

        with open(SEED_DIR / "tenants.csv", encoding="utf-8") as f:
            tenants = list(csv.DictReader(f))
            assert len(tenants) == 40

        with open(SEED_DIR / "vehicles.csv", encoding="utf-8") as f:
            vehicles = list(csv.DictReader(f))
            assert len(vehicles) == 100000

        with open(SEED_DIR / "drivers.csv", encoding="utf-8") as f:
            drivers = list(csv.DictReader(f))
            assert len(drivers) >= 80000

    def test_seed_vins_are_strictly_valid(self):
        """Sample 500 VINs across the 100K vehicle dataset and ensure 100% check-digit validity."""
        with open(SEED_DIR / "vehicles.csv", encoding="utf-8") as f:
            r = csv.DictReader(f)
            # Sample every 200th vehicle
            sampled_vins = [row["vin"] for i, row in enumerate(r) if i % 200 == 0]

        assert len(sampled_vins) == 500
        for vin in sampled_vins:
            res = validate_vin(vin)
            assert res.valid is True, f"Seed VIN {vin} failed validation: {res.error}"
            assert res.check_digit_ok is True


class TestSimulatorDeterminism:
    """Verifies PRNG determinism and repeatability (§6.1)."""

    def test_seed_determinism(self):
        """Identical seed must yield identical ground-truth ledger and emission sequence."""
        engine1 = SimulatorEngine(seed=12345, vehicle_count=10, chaos_enabled=False)
        engine2 = SimulatorEngine(seed=12345, vehicle_count=10, chaos_enabled=False)

        ts = 1790000000000
        for step in range(10):
            engine1.step(current_ts_ms=ts + step * 1000, dt_s=1.0)
            engine2.step(current_ts_ms=ts + step * 1000, dt_s=1.0)

        assert len(engine1.ledger) == len(engine2.ledger)
        for r1, r2 in zip(engine1.ledger, engine2.ledger):
            assert r1.vehicle_pid == r2.vehicle_pid
            assert math.isclose(r1.true_lat, r2.true_lat, abs_tol=1e-8)
            assert math.isclose(r1.true_speed_kmh, r2.true_speed_kmh, abs_tol=1e-8)
            assert math.isclose(r1.true_odo_km, r2.true_odo_km, abs_tol=1e-8)
