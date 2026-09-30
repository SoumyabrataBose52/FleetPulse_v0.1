# Redis Key Specification & Memory Budget (§5.6)

FleetPulse leverages Redis Stack for ultra-low latency (< 1 ms) fast-path operations, live vehicle tracking, spatial clustering, rate limiting, and temporary state checkpoints.

---

## 1. Key Inventory

| Key Pattern | Redis Type | TTL | Purpose | Read/Write Path |
|---|---|---|---|---|
| `live:{tenant}:{pid}` | Hash | None (persistent while vehicle active) | Last-known live vehicle state: `lat_e6, lon_e6, speed, hdg, ts, status, soc, fuel, dtc_count` | Written by fast-path worker on every event (last-write-wins by `ts_event`). Read by `GET /vehicles/{pid}/live`. |
| `cell:{tenant}:{gh6}` | Set | None | Vehicle UUIDs currently located inside a Geohash-6 cell (~1.2 km × 0.6 km). | Updated **only on cell boundary change** (atomic SADD new + SREM old). Queried during viewport map bounding box lookups. |
| `gcnt:{tenant}:{p}` | Hash | None | Spatial aggregate counts: field `gh{p}` -> integer count of vehicles in cell (precision `p` ∈ 3..7). | Incremented/decremented only on cell transition. Powers server-side clustered map rendering (`GET /map/clusters`). |
| `vs:{proc}:{pid}` | String / Blob | None | Stream engine per-vehicle state checkpoints (≈ 200–500 B/vehicle). | Read lazily on worker restart/rebalance; flushed periodically before Kafka offset commit. |
| `dd:{pid}` | Set | 600 s | Cross-worker deduplication fallback set. | Populated with recent event IDs during partition rebalances. Primary exact dedupe set resides in orderer RAM. |
| `alertcool:{key}` | String | Configurable (e.g. 300 s) | Alert cooldown deduplication lock (`SETNX`). | Written by fast path before raising an alert to prevent spamming identical alerts during sustained anomalies. |
| `alerts:{tenant}` | Stream (`XADD`) | 86,400 s (maxlen capped) | Tenant-scoped event notification stream. | Written by insight sink on new alert; consumed by SSE broadcaster (`GET /alerts/stream`). |
| `ver:{tenant}:{domain}` | Counter | None | Version counter for cache invalidation (e.g. `ver:{tenant}:cost`). | Incremented via `INCR` by batch/stream jobs on state updates; invalidates dependent cached summary API payloads. |
| `rl:{client}:{window}` | Counter | 60 s | Token-bucket rate limiter per API client / IP. | Evaluated and incremented by API middleware (`INCR` + `EXPIRE`). |

---

## 2. Live State Hash Fields (`live:{tenant}:{pid}`)

```redis
HSET live:101:a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d \
    lat_e6 13082700 \
    lon_e6 80270700 \
    speed 64.2 \
    hdg 92 \
    ts 1790331302120 \
    status "DRIVING" \
    soc 41.0 \
    fuel 61.5 \
    dtc_count 1
```

**Conflict Resolution:**
When an out-of-order event arrives at the fast path, the existing `ts` is checked. If `ts_event < existing.ts`, the stale position is ignored.

---

## 3. Spatial Cell Transition Logic

When vehicle `V` moves from geohash cell `G_old` to `G_new` at precision 6:
```redis
MULTI
SREM cell:101:G_old V
SADD cell:101:G_new V
HINCRBY gcnt:101:6 G_old -1
HINCRBY gcnt:101:6 G_new 1
EXEC
```
*Notice:* If `G_old == G_new`, zero Redis operations occur, keeping hot-path Redis write traffic minimal even at 100,000 events/s.

---

## 4. Memory Budget Analysis (100,000 Vehicles)

| Component | Calculation | RAM Footprint |
|---|---|---|
| Live vehicle hashes | 100,000 vehicles × ~300 bytes | ~30 MB |
| Cell sets (gh6) | 100,000 vehicle UUIDs distributed in ~2,000 cells | ~15 MB |
| Cluster counts (gh3..7) | ~5,000 active geohash buckets × 8 bytes | < 1 MB |
| Stream engine checkpoints | 100,000 vehicles × 400 bytes | ~40 MB |
| Overhead & buffers | Dict structures, memory allocators | ~34 MB |
| **Total Redis Memory** | **100K Vehicles Concurrency** | **~120 MB** |

**Conclusion:** A single standard Redis instance (or 256MB Redis container) comfortably handles live operations for all 100,000 vehicles with headroom.
