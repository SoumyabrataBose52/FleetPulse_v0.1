"""
Two-Tier Deduplication — §7.6
================================
event_id = xxh3_64(vehicle_pid || ts_event_ms || (seq if present else lat_e6,lon_e6,speed,odo))

Tier 1 (OEMs with seq): per-vehicle sliding bitmap of last 4,096 sequence numbers. Exact, O(1).
Tier 2 (no seq / Tier-1 miss): rotating Bloom on event_id + per-vehicle exact recent-id set.

CRITICAL RULE: Never drop on Bloom positive alone.
  If exact set says "not present" → accept (a Bloom false positive is safe).
  Any residual duplicate is absorbed by idempotent sinks (§4.5).

Metrics emitted (counters only, no I/O here):
  dedupe_dropped_total, bloom_fp_total, dedupe_ambiguous_total
"""

from __future__ import annotations

import struct
from collections import deque
from dataclasses import dataclass, field

try:
    import xxhash
    def _xxh3_64(data: bytes) -> int:
        return xxhash.xxh3_64(data).intdigest()
except ImportError:
    import hashlib
    def _xxh3_64(data: bytes) -> int:
        return int.from_bytes(hashlib.sha256(data).digest()[:8], "little")

from fpcore.bloom import RotatingBloomFilter


# ---------------------------------------------------------------------------
# Event ID computation
# ---------------------------------------------------------------------------

def compute_event_id(
    vehicle_pid: str,
    ts_event_ms: int,
    seq: int | None = None,
    lat_e6: int | None = None,
    lon_e6: int | None = None,
    speed_kmh: float | None = None,
    odo_km: float | None = None,
) -> int:
    """
    Compute a deterministic 64-bit event ID.

    If seq is provided (OEM A, B, D): hash(vehicle_pid || ts_ms || seq)
    Otherwise: hash(vehicle_pid || ts_ms || lat_e6 || lon_e6 || speed || odo)

    Returns:
        64-bit unsigned integer event ID.
    """
    vid = vehicle_pid.encode()
    ts_b = struct.pack(">q", ts_event_ms)

    if seq is not None:
        payload = vid + ts_b + struct.pack(">q", seq)
    else:
        lat_b = struct.pack(">i", lat_e6 or 0)
        lon_b = struct.pack(">i", lon_e6 or 0)
        spd_b = struct.pack(">f", speed_kmh or 0.0)
        odo_b = struct.pack(">d", odo_km or 0.0)
        payload = vid + ts_b + lat_b + lon_b + spd_b + odo_b

    return _xxh3_64(payload)


# ---------------------------------------------------------------------------
# Tier 1: sliding bitmap for sequence numbers
# ---------------------------------------------------------------------------

class SeqBitmap:
    """
    Sliding window bitmap for OEM sequence numbers.
    Tracks the last `window` sequence numbers; O(1) operations.

    Used for OEMs that provide a monotonically increasing per-vehicle counter.
    """

    def __init__(self, window: int = 4096) -> None:
        self._window = window
        self._bits = bytearray(window // 8 + 1)
        self._base_seq: int | None = None  # lowest seq currently tracked

    def seen(self, seq: int) -> bool:
        """Return True if seq was already seen (duplicate)."""
        if self._base_seq is None:
            self._base_seq = seq
            self._set(seq)
            return False

        offset = seq - self._base_seq
        if offset < 0:
            # Very old sequence — treat as duplicate (can't track that far back)
            return True
        if offset >= self._window:
            # Advance the window: clear old bits, slide base
            advance = offset - self._window + 1
            self._base_seq += advance
            # Clear advanced bits (simple: reset if advance >= window)
            if advance >= self._window:
                self._bits = bytearray(len(self._bits))
            else:
                for i in range(advance):
                    idx = (self._base_seq - advance + i) % self._window
                    self._bits[idx // 8] &= ~(1 << (idx % 8))
            offset = seq - self._base_seq

        bit_pos = seq % self._window
        if self._check(bit_pos):
            return True
        self._set(seq)
        return False

    def _set(self, seq: int) -> None:
        pos = seq % self._window
        self._bits[pos // 8] |= (1 << (pos % 8))

    def _check(self, bit_pos: int) -> bool:
        return bool(self._bits[bit_pos // 8] & (1 << (bit_pos % 8)))


# ---------------------------------------------------------------------------
# Per-vehicle dedup state
# ---------------------------------------------------------------------------

@dataclass
class VehicleDedupeState:
    """Per-vehicle deduplication state."""
    # Tier 1: sequence bitmap (only used if OEM provides seq)
    seq_bitmap: SeqBitmap = field(default_factory=lambda: SeqBitmap(4096))
    # Tier 2: exact recent event-id set (bounded deque)
    recent_ids: deque[int] = field(default_factory=lambda: deque(maxlen=2000))
    recent_ids_set: set[int] = field(default_factory=set)

    def add_exact(self, event_id: int) -> None:
        if len(self.recent_ids) == self.recent_ids.maxlen:
            oldest = self.recent_ids[0]
            self.recent_ids_set.discard(oldest)
        self.recent_ids.append(event_id)
        self.recent_ids_set.add(event_id)

    def in_exact(self, event_id: int) -> bool:
        return event_id in self.recent_ids_set


# ---------------------------------------------------------------------------
# Global deduplicator (shared Bloom + per-vehicle state)
# ---------------------------------------------------------------------------

@dataclass
class Deduplicator:
    """
    Two-tier deduplicator for 100K vehicles at 100K events/s.

    Args:
        bloom_capacity:   Expected events per window (e.g. 6_000_000 for 60s at 100K/s)
        bloom_fp_rate:    False-positive rate (default 0.001)
        bloom_window_s:   Rotation window in seconds (default 60)

    Counters (read these for Prometheus metrics):
        dropped:          Events dropped as definite duplicates
        bloom_fp:         Events where Bloom said "seen" but exact said "new"
        ambiguous:        Events where Bloom said "seen" and exact also said "seen" (true dup)
    """
    bloom_capacity: int = 6_000_000
    bloom_fp_rate: float = 0.001
    bloom_window_s: float = 60.0

    def __post_init__(self) -> None:
        self._bloom = RotatingBloomFilter(
            self.bloom_capacity, self.bloom_fp_rate, self.bloom_window_s
        )
        self._vehicles: dict[str, VehicleDedupeState] = {}
        # Metrics
        self.dropped: int = 0
        self.bloom_fp: int = 0
        self.ambiguous: int = 0

    def _state(self, vehicle_pid: str) -> VehicleDedupeState:
        if vehicle_pid not in self._vehicles:
            self._vehicles[vehicle_pid] = VehicleDedupeState()
        return self._vehicles[vehicle_pid]

    def is_duplicate(
        self,
        vehicle_pid: str,
        event_id: int,
        seq: int | None = None,
    ) -> bool:
        """
        Check if this event is a duplicate.

        Returns True if the event should be dropped (definite duplicate).
        Returns False if the event should be processed (new or Bloom FP).

        Side effects: updates internal state if event is new.
        """
        state = self._state(vehicle_pid)
        eid_bytes = struct.pack(">Q", event_id)

        # --- Tier 1: sequence bitmap ---
        if seq is not None:
            if state.seq_bitmap.seen(seq):
                self.dropped += 1
                return True
            # Tier 1 says new — still check Bloom for safety (belt and suspenders)
            # but primarily accept it
            self._bloom.add(eid_bytes)
            state.add_exact(event_id)
            return False

        # --- Tier 2: Bloom + exact ---
        if eid_bytes in self._bloom:
            # Bloom says "possibly seen" — verify with exact set
            if state.in_exact(event_id):
                # Confirmed duplicate
                self.dropped += 1
                self.ambiguous += 1
                return True
            else:
                # Bloom false positive — accept
                self.bloom_fp += 1
                self._bloom.add(eid_bytes)
                state.add_exact(event_id)
                return False
        else:
            # Bloom says definitely new
            self._bloom.add(eid_bytes)
            state.add_exact(event_id)
            return False
