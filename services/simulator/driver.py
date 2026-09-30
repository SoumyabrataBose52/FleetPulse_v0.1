"""
§6.7 Driver Behavior Model and Ground Truth Event Detector.

Simulates driver personality traits and detects ground-truth safety events:
  - Latent aggression alpha ~ Beta(2, 5)
  - Modulates target speed multiplier, max acceleration, and braking aggression
  - Lateral acceleration: a_lat = v * d_psi / dt
  - Ground truth harsh events: HARSH_BRAKE, HARSH_ACCEL, HARSH_CORNER, OVERSPEED

Complexity: O(1) per driver evaluation step.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
import random
from typing import Optional, Tuple


@dataclass(frozen=True)
class DriverProfile:
    """Driver behavioral characteristics (§6.7)."""
    driver_id: int
    aggression: float          # alpha drawn from Beta(2, 5) [0.0, 1.0]
    speed_factor: float        # 0.95 + 0.25 * alpha
    max_accel_ms2: float       # 1.3 + 2.0 * alpha
    comfort_brake_ms2: float   # 1.5 + 2.5 * alpha


def sample_driver_profile(driver_id: int, rng: Optional[random.Random] = None) -> DriverProfile:
    """Sample driver personality using Beta(2, 5) distribution (§6.7)."""
    r = rng if rng is not None else random.Random()
    alpha = r.betavariate(2.0, 5.0)

    speed_factor = 0.95 + 0.25 * alpha + r.gauss(0.0, 0.02)
    max_accel = 1.3 + 2.0 * alpha
    comfort_brake = 1.5 + 2.5 * alpha

    return DriverProfile(
        driver_id=driver_id,
        aggression=round(alpha, 4),
        speed_factor=round(speed_factor, 4),
        max_accel_ms2=round(max_accel, 3),
        comfort_brake_ms2=round(comfort_brake, 3),
    )


def detect_ground_truth_events(
    speed_kmh: float,
    accel_ms2: float,
    heading_deg: int,
    prev_heading_deg: int,
    speed_limit_kmh: float,
    dt_s: float = 1.0,
) -> Optional[str]:
    """
    Evaluate true kinematics against ground truth physical thresholds (§6.7, §7.16):
      - Deceleration >= 3.0 m/s^2 -> HARSH_BRAKE
      - Acceleration >= 2.5 m/s^2 -> HARSH_ACCEL
      - Lateral acceleration >= 3.0 m/s^2 -> HARSH_CORNER
      - Speed > limit * 1.10 -> OVERSPEED
    """
    # 1. Harsh Braking (deceleration magnitude >= 3.0 m/s^2)
    if accel_ms2 <= -3.0:
        return "HARSH_BRAKE"

    # 2. Harsh Acceleration (accel >= 2.5 m/s^2)
    if accel_ms2 >= 2.5:
        return "HARSH_ACCEL"

    # 3. Harsh Cornering: a_lat = v * d_psi / dt
    if dt_s > 0 and speed_kmh > 15.0:
        v_ms = speed_kmh / 3.6
        d_heading = abs(heading_deg - prev_heading_deg)
        if d_heading > 180:
            d_heading = 360 - d_heading
        d_psi_rad = math.radians(d_heading)
        yaw_rate = d_psi_rad / dt_s
        a_lat = v_ms * yaw_rate
        if a_lat >= 3.0:
            return "HARSH_CORNER"

    # 4. Overspeeding check
    if speed_limit_kmh > 0 and speed_kmh > (speed_limit_kmh * 1.10):
        return "OVERSPEED"

    return None
