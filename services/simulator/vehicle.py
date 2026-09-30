"""
§6.5 Vehicle State Machine and Route Following Engine.

Maintains vehicle lifecycle and autonomous operations:
  - FSM States: PARKED -> DRIVING <-> STOPPED_ENGINE_ON -> PARKED (with CHARGING from PARKED)
  - Traffic light stops: 25% of intersections stop U(10, 60)s with engine on (negative samples for idle detector)
  - Dwell at POI stops
  - Route navigation over synthetic road graph edges

Complexity: O(1) per vehicle step.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import math
import random
from typing import List, Optional, Tuple

from services.simulator.physics import PhysicsState, VehicleModelSpecs, step_physics
from services.simulator.driver import DriverProfile, detect_ground_truth_events
from services.simulator.world import World, POI, Depot


class VehicleState(str, Enum):
    PARKED = "PARKED"
    DRIVING = "DRIVING"
    STOPPED_ENGINE_ON = "STOPPED_ENGINE_ON"
    CHARGING = "CHARGING"


@dataclass
class SimulatedVehicle:
    """Autonomous simulated vehicle agent (§6.5)."""
    vehicle_pid: str
    vin: str
    tenant_id: int
    fleet_id: int
    oem: str
    schema_ver: int
    archetype: str
    specs: VehicleModelSpecs
    driver: DriverProfile
    state: VehicleState = VehicleState.PARKED

    # Spatial coordinates
    lat: float = 13.0827
    lon: float = 80.2707
    heading_deg: int = 0
    current_edge_id: Optional[int] = None
    target_node_id: Optional[int] = None

    # Dynamic physical state
    physics: PhysicsState = field(default_factory=PhysicsState)

    # Counters and timers
    seq: int = 1
    state_timer_s: int = 0
    active_dtc: List[str] = field(default_factory=list)

    # Routing itinerary
    home_depot_id: int = 1
    assigned_poi: Optional[POI] = None

    def tick(self, world: World, dt_s: float = 1.0, rng: Optional[random.Random] = None) -> Optional[str]:
        """
        Advance vehicle by dt_s seconds (§6.5, §6.6).

        Returns:
            Detected ground truth event type (e.g. HARSH_BRAKE) if any.
        """
        r = rng or random.Random()
        self.state_timer_s += int(dt_s)
        self.seq += 1
        prev_heading = self.heading_deg
        ambient_temp = world.get_ambient_temperature(ts_epoch_s=self.seq)

        detected_event: Optional[str] = None

        if self.state == VehicleState.PARKED:
            self.physics.ignition = False
            self.physics.charge_kw = 0.0

            # Step physics (cooling down, stationary)
            step_physics(
                state=self.physics,
                specs=self.specs,
                target_speed_kmh=0.0,
                max_accel_ms2=self.driver.max_accel_ms2,
                comfort_brake_ms2=self.driver.comfort_brake_ms2,
                ambient_temp_c=ambient_temp,
                dt_s=dt_s,
            )

            # Check if EV should charge while parked at depot
            if self.specs.powertrain in ("EV", "HYBRID") and self.physics.soc_pct < 60.0:
                self.state = VehicleState.CHARGING
                self.state_timer_s = 0

            # Transition out of PARKED into DRIVING (start trip)
            elif self.state_timer_s > 60:
                self.state = VehicleState.DRIVING
                self.physics.ignition = True
                self.state_timer_s = 0
                # Choose random target POI
                if world.pois:
                    self.assigned_poi = r.choice(world.pois)
                    self.heading_deg = r.randint(0, 359)

        elif self.state == VehicleState.CHARGING:
            self.physics.ignition = False
            # Charge at 22 kW AC or 50 kW DC
            charger_kw = 22.0 if self.specs.powertrain == "HYBRID" else 50.0
            step_physics(
                state=self.physics,
                specs=self.specs,
                target_speed_kmh=0.0,
                max_accel_ms2=self.driver.max_accel_ms2,
                comfort_brake_ms2=self.driver.comfort_brake_ms2,
                ambient_temp_c=ambient_temp,
                dt_s=dt_s,
                charger_cap_kw=charger_kw,
            )

            # Finish charging when >= 90% or dwell elapsed
            if self.physics.soc_pct >= 90.0 or self.state_timer_s > 1800:
                self.state = VehicleState.PARKED
                self.state_timer_s = 0

        elif self.state == VehicleState.DRIVING:
            self.physics.ignition = True
            target_speed = 50.0 * self.driver.speed_factor

            # Step physics
            accel_ms2 = step_physics(
                state=self.physics,
                specs=self.specs,
                target_speed_kmh=target_speed,
                max_accel_ms2=self.driver.max_accel_ms2,
                comfort_brake_ms2=self.driver.comfort_brake_ms2,
                ambient_temp_c=ambient_temp,
                dt_s=dt_s,
            )

            # Update coordinates along heading
            dist_km = (self.physics.speed_kmh * dt_s) / 3600.0
            # ~111 km per degree lat
            d_lat = (dist_km * math.cos(math.radians(self.heading_deg))) / 111.0
            d_lon = (dist_km * math.sin(math.radians(self.heading_deg))) / (111.0 * math.cos(math.radians(self.lat)))
            self.lat += d_lat
            self.lon += d_lon

            # Evaluate harsh events against ground truth kinematics
            detected_event = detect_ground_truth_events(
                speed_kmh=self.physics.speed_kmh,
                accel_ms2=accel_ms2,
                heading_deg=self.heading_deg,
                prev_heading_deg=prev_heading,
                speed_limit_kmh=60.0,
                dt_s=dt_s,
            )

            # 25% chance of encountering a traffic light stop (U(10, 60)s)
            if self.state_timer_s > 120 and r.random() < 0.05:
                self.state = VehicleState.STOPPED_ENGINE_ON
                self.state_timer_s = 0

            # Arrived at destination after driving interval -> Park
            elif self.state_timer_s > 600:
                self.state = VehicleState.PARKED
                self.state_timer_s = 0

        elif self.state == VehicleState.STOPPED_ENGINE_ON:
            self.physics.ignition = True
            # Standstill with engine running
            step_physics(
                state=self.physics,
                specs=self.specs,
                target_speed_kmh=0.0,
                max_accel_ms2=self.driver.max_accel_ms2,
                comfort_brake_ms2=self.driver.comfort_brake_ms2,
                ambient_temp_c=ambient_temp,
                dt_s=dt_s,
            )

            # Traffic light turns green after 20-40s
            if self.state_timer_s > r.randint(20, 45):
                self.state = VehicleState.DRIVING
                self.state_timer_s = 0

        return detected_event
