"""
§6.8 Imperfection and Chaos Injector.

Injects controlled real-world network and hardware flaws:
  - Duplicates: 1.5% of events (1–3 copies)
  - Out-of-order: 3% delayed 1–8 s; 0.2% delayed up to 60 s
  - Vehicle offline episodes: 2% active vehicles/hour, 1–10 min, then buffered burst flush
  - GPS noise: gaussian jitter (sigma = 4m), 0.05% teleport jumps
  - Malformed payloads: 0.05% (truncated JSON, invalid bytes)
  - Invalid VIN: 0.02% (corrupted check digit)
  - Clock skew: N(0, 300ms) jitter, 0.02% future timestamps

Complexity: O(1) per injected event.
"""

from __future__ import annotations

from dataclasses import dataclass
import random
from typing import List, Optional, Tuple


@dataclass(frozen=True)
class ChaosConfig:
    """Configurable probabilities matching §6.8."""
    duplicate_prob: float = 0.015
    out_of_order_prob: float = 0.030
    severe_ooo_prob: float = 0.002
    offline_episode_prob: float = 0.020
    gps_noise_sigma_m: float = 4.0
    gps_teleport_prob: float = 0.0005
    malformed_prob: float = 0.0005
    invalid_vin_prob: float = 0.0002
    clock_skew_sigma_ms: float = 300.0
    future_ts_prob: float = 0.0002


class ChaosInjector:
    """Injects transport and sensor imperfections into emitted events (§6.8)."""

    def __init__(self, config: Optional[ChaosConfig] = None, seed: int = 42):
        self.cfg = config or ChaosConfig()
        self.rng = random.Random(seed)

    def perturb_gps(self, lat: float, lon: float) -> Tuple[float, float, bool]:
        """
        Add GPS Gaussian jitter (sigma ~ 4m) or rare teleport jump (§6.8).

        Returns:
            (perturbed_lat, perturbed_lon, is_teleport)
        """
        # 0.05% Teleport outlier jump (0.5 to 5.0 km)
        if self.rng.random() < self.cfg.gps_teleport_prob:
            jump_deg = self.rng.uniform(0.005, 0.045)  # ~0.5km to ~5.0km
            return lat + jump_deg, lon + jump_deg, True

        # Normal sensor noise ~ 4 meters
        # 1 deg lat ~ 111,000 meters -> 4m ~ 3.6e-5 deg
        d_lat = self.rng.gauss(0.0, self.cfg.gps_noise_sigma_m / 111000.0)
        d_lon = self.rng.gauss(0.0, self.cfg.gps_noise_sigma_m / 111000.0)
        return lat + d_lat, lon + d_lon, False

    def perturb_timestamp(self, ts_ms: int) -> Tuple[int, bool]:
        """
        Add clock skew or rare future timestamp (§6.8).

        Returns:
            (perturbed_ts_ms, is_skewed)
        """
        # 0.02% Future timestamp (up to 300s into future)
        if self.rng.random() < self.cfg.future_ts_prob:
            future_ms = self.rng.randint(10000, 300000)
            return ts_ms + future_ms, True

        # Normal clock drift
        skew_ms = int(self.rng.gauss(0.0, self.cfg.clock_skew_sigma_ms))
        return ts_ms + skew_ms, False

    def corrupt_vin(self, valid_vin: str) -> str:
        """Corrupt check digit or insert invalid char I/O/Q (§6.8)."""
        if len(valid_vin) < 17:
            return valid_vin
        chars = list(valid_vin)
        # Invalidate 9th character (check digit)
        chars[8] = "I" if chars[8] != "I" else "O"
        return "".join(chars)

    def corrupt_payload(self, raw_payload: str) -> str:
        """Truncate or introduce invalid characters to test Gateway/Normalizer DLQ (§6.8)."""
        if len(raw_payload) > 10:
            cut_idx = self.rng.randint(5, len(raw_payload) - 2)
            return raw_payload[:cut_idx] + "---TRUNCATED---"
        return "MALFORMED_GARBAGE_PAYLOAD"

    def should_duplicate(self) -> Tuple[bool, int]:
        """Determine if event should be duplicated (1-3 copies) (§6.8)."""
        if self.rng.random() < self.cfg.duplicate_prob:
            copies = self.rng.randint(1, 3)
            return True, copies
        return False, 0

    def sample_delay_seconds(self) -> float:
        """Determine out-of-order arrival delay in seconds (§6.8)."""
        r = self.rng.random()
        if r < self.cfg.severe_ooo_prob:
            # 0.2% delayed up to 60s
            return self.rng.uniform(10.0, 60.0)
        elif r < self.cfg.out_of_order_prob:
            # 3% delayed 1-8s
            return self.rng.uniform(1.0, 8.0)
        return 0.0
