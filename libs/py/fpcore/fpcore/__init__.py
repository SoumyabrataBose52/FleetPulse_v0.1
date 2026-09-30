"""
fpcore — FleetPulse Core Algorithm Library
==========================================
Pure functions with zero I/O. No side effects.
Each module is independently testable.

Modules:
  vin         — VIN validation (regex + check digit, §7.1)
  dtc         — DTC/OBD-II parsing and normalisation (§7.2)
  geo         — Haversine, GPS outlier filter, geohash (§7.3, §7.4)
  bloom       — Rotating Bloom filter (§7.5)
  dedupe      — Two-tier deduplication (§7.6)
  reorder     — Reorder buffer with watermark (§7.7)
  windows     — Sliding time-bucket windows (§7.8)
  sketch      — Count-Min Sketch + top-K (§7.9)
  trip        — Trip/stop segmentation FSM + Viterbi DP (§7.10)
  idle        — Idle episode detection and cost (§7.11)
  routing     — Road graph: snap, Dijkstra, A*, reverse multi-source (§7.12)
  ev_dp       — Charging schedule DP (§7.13), depot allocation (§7.14), SoH (§7.15)
  safety      — Driver safety score with exponential decay (§7.16)
  geofence    — PIP, activity profile, tow rule, Union-Find clustering (§7.17–7.18)
  privacy     — Pseudonymisation, k-anonymity, deterministic DP noise, budget (§7.19)
  audit       — SHA-256 hash chain, token bucket, keyset cursor (§7.20)
  mapping     — Declarative OEM mapping engine and DSL compiler (§5.2)
"""

from fpcore import mapping
from fpcore import privacy

__all__ = ["mapping", "privacy"]
