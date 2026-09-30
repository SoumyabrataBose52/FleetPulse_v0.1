"""
Reorder Buffer with Watermark — §7.7
======================================
Per-vehicle min-heap ordered by (ts_event_ms, seq).
Watermark W = max_ts_seen - L (L = allowed_lateness_s, default 10s).

On event arrival:
  - If ts <= last_emitted_ts → route to telemetry.late (flag LATE)
  - Else push to heap; pop and emit while heap.min.ts <= W

A 1s wall-clock timer flushes vehicles silent for > L seconds.
Buffer capped at 256 events/vehicle: overflow force-emits oldest.

Skew guard: timestamps > now + 5min → flag CLOCK_SKEW, clamp.

Complexity: O(log b) per event, b ≈ 10–20 normally.
"""

from __future__ import annotations

import heapq
import time
from dataclasses import dataclass, field
from typing import NamedTuple


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

ALLOWED_LATENESS_S: float = 10.0
BUFFER_CAP: int = 256
FUTURE_TOLERANCE_S: float = 300.0  # 5 minutes


# ---------------------------------------------------------------------------
# Types
# ---------------------------------------------------------------------------

class EventRef(NamedTuple):
    """Heap element: sortable by (ts_ms, seq)."""
    ts_ms: int
    seq: int         # Use event index as tiebreaker when seq unavailable
    event_id: int    # The actual event_id
    payload: object  # Opaque: whatever the caller passes (dict, bytes, etc.)


@dataclass
class ReorderResult:
    to_emit: list[EventRef] = field(default_factory=list)
    to_late: list[EventRef] = field(default_factory=list)
    quality_flags: dict[int, int] = field(default_factory=dict)  # event_id → quality bitmask


@dataclass
class VehicleReorderState:
    """Mutable per-vehicle reorder state."""
    heap: list[EventRef] = field(default_factory=list)
    last_emitted_ts_ms: int = 0
    last_seen_ts_ms: int = 0
    _seq_counter: int = 0  # internal tiebreaker

    def next_seq(self) -> int:
        self._seq_counter += 1
        return self._seq_counter


# ---------------------------------------------------------------------------
# Quality bit flags (matches canonical Avro schema §5.1)
# ---------------------------------------------------------------------------
QUALITY_LATE       = 2
QUALITY_CLOCK_SKEW = 16


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def process_event(
    state: VehicleReorderState,
    ts_ms: int,
    event_id: int,
    payload: object,
    seq: int | None = None,
    now_ms: int | None = None,
    lateness_s: float = ALLOWED_LATENESS_S,
    buffer_cap: int = BUFFER_CAP,
    future_tolerance_s: float = FUTURE_TOLERANCE_S,
) -> ReorderResult:
    """
    Process one incoming event for a single vehicle.

    Args:
        state:      Per-vehicle mutable state.
        ts_ms:      Event timestamp in milliseconds (device time, UTC).
        event_id:   Deduplicated event ID.
        payload:    Opaque payload passed through unchanged.
        seq:        OEM sequence number (used as heap tiebreaker if provided).
        now_ms:     Wall-clock now in ms (injectable for tests; defaults to real time).
        lateness_s: Allowed lateness window in seconds.
        buffer_cap: Max events buffered per vehicle before force-flush.
        future_tolerance_s: Max seconds in the future before CLOCK_SKEW flag.

    Returns:
        ReorderResult with lists of events to emit (ordered) and events to route late.
    """
    result = ReorderResult()
    if now_ms is None:
        now_ms = int(time.time() * 1000)

    quality = 0

    # --- Skew guard: future timestamps ---
    if ts_ms > now_ms + int(future_tolerance_s * 1000):
        quality |= QUALITY_CLOCK_SKEW
        ts_ms = now_ms + int(future_tolerance_s * 1000)  # clamp

    # --- Late event: older than last emitted ---
    if ts_ms <= state.last_emitted_ts_ms:
        ref = EventRef(ts_ms=ts_ms, seq=seq or state.next_seq(),
                       event_id=event_id, payload=payload)
        result.to_late.append(ref)
        result.quality_flags[event_id] = quality | QUALITY_LATE
        return result

    # --- Update watermark ---
    if ts_ms > state.last_seen_ts_ms:
        state.last_seen_ts_ms = ts_ms

    tiebreaker = seq if seq is not None else state.next_seq()
    ref = EventRef(ts_ms=ts_ms, seq=tiebreaker, event_id=event_id, payload=payload)
    heapq.heappush(state.heap, ref)

    if quality:
        result.quality_flags[event_id] = quality

    # --- Overflow protection ---
    while len(state.heap) > buffer_cap:
        oldest = heapq.heappop(state.heap)
        result.to_emit.append(oldest)
        state.last_emitted_ts_ms = oldest.ts_ms

    # --- Emit events below the watermark ---
    watermark_ms = state.last_seen_ts_ms - int(lateness_s * 1000)
    while state.heap and state.heap[0].ts_ms <= watermark_ms:
        ev = heapq.heappop(state.heap)
        result.to_emit.append(ev)
        state.last_emitted_ts_ms = ev.ts_ms

    return result


def flush_vehicle(
    state: VehicleReorderState,
    wall_now_ms: int | None = None,
    lateness_s: float = ALLOWED_LATENESS_S,
) -> ReorderResult:
    """
    Flush a vehicle's buffer when it has been silent for longer than lateness_s.
    Called by a periodic timer (every 1 second in the orderer service).

    Returns:
        ReorderResult with all buffered events to emit.
    """
    result = ReorderResult()
    if not state.heap:
        return result

    if wall_now_ms is None:
        wall_now_ms = int(time.time() * 1000)

    # If the oldest buffered event is older than wall_now - lateness, flush
    oldest_ms = state.heap[0].ts_ms
    if oldest_ms < wall_now_ms - int(lateness_s * 1000):
        while state.heap:
            ev = heapq.heappop(state.heap)
            result.to_emit.append(ev)
            state.last_emitted_ts_ms = ev.ts_ms

    return result
