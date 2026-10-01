"""
§6.1 & §15.4 FleetPulse Simulator Main CLI and Service Entry Point.

Usage:
  python services/simulator/main.py seed [--vehicles 100000] [--seed 42]
  python services/simulator/main.py validate
  python services/simulator/main.py run [--vehicles 1000] [--duration 60] [--mode file|http]
  python services/simulator/main.py serve [--port 8090]
"""

from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
import sys
import time

# Ensure project root is in sys.path
root_dir = str(Path(__file__).resolve().parent.parent.parent)
fpcore_dir = str(Path(root_dir) / "libs" / "py" / "fpcore")
if root_dir not in sys.path:
    sys.path.insert(0, root_dir)
if fpcore_dir not in sys.path:
    sys.path.insert(0, fpcore_dir)

from services.simulator.seed import generate_seed_data
from services.simulator.engine import SimulatorEngine
from services.simulator.emitter import BatchEmitter

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("simulator")


def cmd_seed(args: argparse.Namespace):
    logger.info("Generating deterministic seed data for %d vehicles (seed=%d)...", args.vehicles, args.seed)
    stats = generate_seed_data(output_dir=args.out, total_vehicles=args.vehicles, seed=args.seed)
    logger.info("Seed data generated successfully: %s", stats)


def cmd_validate(args: argparse.Namespace):
    logger.info("Executing simulator physical invariant validation (§15.4)...")
    engine = SimulatorEngine(seed=42, vehicle_count=10, chaos_enabled=False)
    results = engine.validate_physics_invariants(test_duration_ticks=200)

    print("\n--- Simulator Validation Report (§15.4) ---")
    print(f"Odometer Integration (integral(v dt) == delta_odo): {'PASS' if results['odometer_integration_valid'] else 'FAIL'} (err: {results['odometer_relative_error']})")
    print(f"EV Charging CC-CV Taper Curve:                       {'PASS' if results['charging_taper_valid'] else 'FAIL'} ({results['power_below_80_kw']} kW -> {results['power_at_90_kw']} kW)")
    print(f"Overall Physical Invariant Status:                   {'ALL BANDS PASS' if results['passed'] else 'FAIL'}\n")

    if not results["passed"]:
        sys.exit(1)


def cmd_run(args: argparse.Namespace):
    logger.info("Starting simulator run: %d vehicles for %d ticks (mode=%s)...", args.vehicles, args.duration, args.mode)
    emitter = BatchEmitter(
        mode=args.mode,
        gateway_url=args.gateway,
        output_file=args.out,
        batch_size=500,
    )
    engine = SimulatorEngine(
        seed=args.seed,
        vehicle_count=args.vehicles,
        chaos_enabled=args.chaos,
        emitter=emitter,
    )

    start_time = time.time()
    total_events = 0
    now_ms = int(time.time() * 1000)

    for tick in range(args.duration):
        current_ts = now_ms + (tick * 1000)
        emitted = engine.step(current_ts_ms=current_ts, dt_s=1.0)
        total_events += emitted
        if (tick + 1) % 10 == 0:
            elapsed = time.time() - start_time
            rate = total_events / max(0.001, elapsed)
            logger.info("Tick %d/%d: Emitted %d events (rate: %.1f events/s)", tick + 1, args.duration, total_events, rate)

    emitter.close()
    elapsed = time.time() - start_time
    logger.info("Simulation completed: %d events in %.2fs (avg rate: %.1f events/s)", total_events, elapsed, total_events / elapsed)


def main():
    parser = argparse.ArgumentParser(description="FleetPulse Connected Vehicle Intelligence Simulator (§6)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Seed command
    p_seed = subparsers.add_parser("seed", help="Generate 100K seed CSV datasets")
    p_seed.add_argument("--vehicles", type=int, default=100000, help="Number of vehicles (default: 100000)")
    p_seed.add_argument("--seed", type=int, default=42, help="PRNG seed")
    p_seed.add_argument("--out", type=str, default="data/seed", help="Output directory")

    # Validate command
    p_val = subparsers.add_parser("validate", help="Validate physical invariants and determinism")

    # Run command
    p_run = subparsers.add_parser("run", help="Run telemetry generator")
    p_run.add_argument("--vehicles", type=int, default=1000, help="Number of vehicles")
    p_run.add_argument("--duration", type=int, default=30, help="Duration in seconds (ticks)")
    p_run.add_argument("--mode", type=str, choices=["file", "http"], default="file", help="Emitter mode")
    p_run.add_argument("--out", type=str, default="data/telemetry_out.ndjson", help="Output file for file mode")
    p_run.add_argument("--gateway", type=str, default="http://localhost:8080/v1/ingest/batch", help="Gateway URL for http mode")
    p_run.add_argument("--seed", type=int, default=42, help="PRNG seed")
    p_run.add_argument("--chaos", action=argparse.BooleanOptionalAction, default=True, help="Enable or disable chaos")

    args = parser.parse_args()

    if args.command == "seed":
        cmd_seed(args)
    elif args.command == "validate":
        cmd_validate(args)
    elif args.command == "run":
        cmd_run(args)


if __name__ == "__main__":
    main()
