"""
Rotating Bloom Filter — §7.5
==============================
Bit array with k hash functions via double hashing (h1 + i*h2).
Uses xxhash (128-bit) split into two 64-bit halves for h1/h2.

For n expected items and FP rate p:
  m = -n * ln(p) / (ln2)^2  ≈ 14.4 * n bits at p=0.1%
  k = (m/n) * ln2           ≈ 10

Rotating generations: current + previous, each covering a time window.
On window expiry, the older generation is cleared and becomes the new
"current"; the old current becomes the historical filter.
This bounds memory while keeping the FP rate correct for a sliding window.

Thread-safety: NOT thread-safe. Callers must synchronise externally.
Complexity: O(k) insert/lookup.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field

try:
    import xxhash
    def _hash128(data: bytes) -> tuple[int, int]:
        h = xxhash.xxh3_128(data).intdigest()
        return h >> 64, h & 0xFFFF_FFFF_FFFF_FFFF
except ImportError:
    # Fallback: use hashlib (slower but no external dep for CI)
    import hashlib
    def _hash128(data: bytes) -> tuple[int, int]:
        d = hashlib.md5(data).digest()
        h1 = int.from_bytes(d[:8], "little")
        h2 = int.from_bytes(d[8:], "little")
        return h1, h2


# ---------------------------------------------------------------------------
# Core Bloom filter (single generation)
# ---------------------------------------------------------------------------

class BloomFilter:
    """
    A single-generation Bloom filter.

    Args:
        capacity:    Expected number of items.
        fp_rate:     Target false-positive rate (default 0.001 = 0.1%).
    """

    def __init__(self, capacity: int, fp_rate: float = 0.001) -> None:
        if capacity <= 0:
            raise ValueError("capacity must be > 0")
        if not (0 < fp_rate < 1):
            raise ValueError("fp_rate must be in (0, 1)")
        self.capacity = capacity
        self.fp_rate = fp_rate
        # Optimal m and k
        self.m: int = max(1, math.ceil(-capacity * math.log(fp_rate) / (math.log(2) ** 2)))
        self.k: int = max(1, round((self.m / capacity) * math.log(2)))
        self._bits = bytearray(math.ceil(self.m / 8))
        self._count = 0

    def add(self, item: bytes) -> None:
        """Insert an item into the filter."""
        h1, h2 = _hash128(item)
        for i in range(self.k):
            idx = (h1 + i * h2) % self.m
            byte_idx = idx >> 3
            bit_idx = idx & 7
            self._bits[byte_idx] |= (1 << bit_idx)
        self._count += 1

    def __contains__(self, item: bytes) -> bool:
        """Return False if definitely not present; True if possibly present."""
        h1, h2 = _hash128(item)
        for i in range(self.k):
            idx = (h1 + i * h2) % self.m
            byte_idx = idx >> 3
            bit_idx = idx & 7
            if not (self._bits[byte_idx] & (1 << bit_idx)):
                return False
        return True

    def clear(self) -> None:
        """Reset the filter."""
        self._bits = bytearray(math.ceil(self.m / 8))
        self._count = 0

    @property
    def count(self) -> int:
        return self._count

    @property
    def memory_bytes(self) -> int:
        return len(self._bits)


# ---------------------------------------------------------------------------
# Rotating (generational) Bloom filter
# ---------------------------------------------------------------------------

@dataclass
class RotatingBloomFilter:
    """
    Two-generation rotating Bloom filter for deduplication over a sliding window.

    Usage:
        rbf = RotatingBloomFilter(capacity_per_window=100_000, fp_rate=0.001,
                                  window_seconds=60)
        rbf.add(b"event_id_bytes")
        b"event_id_bytes" in rbf  # → True/False

    The filter automatically rotates when the current window expires.
    An item is considered present if found in either generation.
    """
    capacity_per_window: int
    fp_rate: float = 0.001
    window_seconds: float = 60.0

    def __post_init__(self) -> None:
        self._current = BloomFilter(self.capacity_per_window, self.fp_rate)
        self._previous = BloomFilter(self.capacity_per_window, self.fp_rate)
        self._window_start = time.monotonic()

    def _maybe_rotate(self) -> None:
        now = time.monotonic()
        if now - self._window_start >= self.window_seconds:
            # Rotate: previous = current, reset current
            self._previous, self._current = self._current, self._previous
            self._current.clear()
            self._window_start = now

    def add(self, item: bytes) -> None:
        """Insert an item into the current generation."""
        self._maybe_rotate()
        self._current.add(item)

    def __contains__(self, item: bytes) -> bool:
        """Check both generations."""
        self._maybe_rotate()
        return (item in self._current) or (item in self._previous)

    @property
    def memory_bytes(self) -> int:
        return self._current.memory_bytes + self._previous.memory_bytes
