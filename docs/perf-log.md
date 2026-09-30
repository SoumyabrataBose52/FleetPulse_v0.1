# FleetPulse Performance Log (§12.4, §15.6)

Performance measurements, per-stage sizing, and benchmark results against the 100,000-vehicle scale target (~100,000 events/second).

---

## 1. Pipeline Stage Sizing & Throughput Summary

| Stage | Target Rate | Measured Throughput / Core | Cores Needed (100K eps) | Technology |
|---|---|---|---|---|
| **Simulator** | 100K eps (burst 300K) | **150,000+ eps** (virtual time) | 1–2 cores | Python 3.10+ (NumPy/CSR) |
| **Ingestion Gateway** | 100K eps | **100,000+ eps** (bounded buffer) | 4–6 pods | Java 21/25 Virtual Threads + Netty |
| **Normalizer** | 100K eps | **80,199.2 eps/core** | 2 cores (8 pods for 4x burst) | Java 21/25 Virtual Threads + Avro |
| **Orderer** | 100K eps | **305,336.4 eps/core** | 1 core (handles 3x burst easily) | Java 21/25 Virtual Threads |
| **Telemetry Sink** | 100K eps | **314,301.9 eps/core** | 1 core (batched ClickHouse inserts) | Java 21/25 Virtual Threads + TSV |
| **Archiver** | 100K eps | **120,000+ eps** (partitioned zstd) | 1 core | Python 3.10+ / PyArrow Parquet |

---

## 2. Stage Benchmark Evidence

### Phase 3 — Simulator Validation & Benchmark
- **Validation:** `python services/simulator/main.py validate`
  - Status: `ALL BANDS PASS`
  - Total distance error vs ∫v dt: **0.0 m**
  - Battery CC-CV charging taper: Valid, monotonically non-increasing
  - Seed dataset: 100,000 verified VINs, 40 tenants, 80 fleets, 89,999 drivers.
- **Generation Throughput:** Sustained > 150,000 events/s in virtual time.

### Phase 4 — Ingestion Gateway
- **Tests:** 18/18 unit & integration tests passing.
- **Backpressure:** Bounded concurrency queue with high-watermark shedding (HTTP 429 + `Retry-After: 1`).
- **mTLS:** Hardware/CA-verified client certificate CN check with untrusted rejection.

### Phase 5 — Normalizer Service
- **Benchmark:** `NormalizerThroughputBenchmarkTest`
  - Iterations: 30,000 timed events across mixed OEM formats (A, B, C, D, E v1, E v2)
  - Time elapsed: **0.374 seconds**
  - Measured throughput: **80,199.2 events/second/core**
- **Hot-Reload:** Tested mid-stream activation of new mapping versions (`HotReloadTest`) with zero service restarts and zero DLQ errors.
- **DLQ Routing:** Verified all 6 reason codes:
  - `MALFORMED`
  - `UNKNOWN_OEM`
  - `MAPPING_ERROR`
  - `INVALID_VIN`
  - `UNKNOWN_VEHICLE`
  - `SCHEMA_VIOLATION`
- **Quality Bitmask:** Verified `VIN_WARN` (bit 8) and `CLOCK_SKEW` (bit 16).

### Phase 6 — Orderer and Fast Path Service
- **Benchmark:** `OrdererThroughputBenchmarkTest`
  - Iterations: 50,000 timed events through combined dedupe + reorder buffer
  - Time elapsed: **0.164 seconds**
  - Measured throughput: **305,336.4 events/second/core**
- **Deduplication:**
  - Tier 1: Sliding sequence window (4,096 elements, O(1)).
  - Tier 2: Rotating double-hashing Bloom filter (500K items/gen, 0.1% FP) + confirming exact recent-id cache.
  - Zero-Loss Rule verified: never drops on Bloom positive alone.
- **Reordering:**
  - Watermark min-heap ($W = \max(\text{ts}) - L$).
  - Late events routed to `telemetry.late.v1` with `QUALITY_LATE` (bit 2).
  - Force-emit on 256-item buffer cap overflow.

### Phase 7 — Storage Sinks, Archive & Checkpoint S (Walking Skeleton)
- **Telemetry Sink Benchmark (`TelemetrySinkBenchmarkTest`):**
  - Iterations: 100,000 timed events in 5,000-event contiguous batches
  - Time elapsed: **0.318 seconds**
  - Measured throughput: **314,301.9 events/second/core**
  - Deduplication Token: `topic-partition-firstOffset-lastOffset` strictly verified
  - Idempotent Replay: Re-submitting identical Kafka offset ranges generates identical dedup tokens
- **Insight Sink:**
  - Idempotent PostgreSQL upserts to `trip` (`ON CONFLICT (trip_id, start_ts) DO UPDATE`) and `alert` (`ON CONFLICT (alert_key) DO NOTHING`)
  - MongoDB `insights` polymorphic upserts keyed by `alert_key`
  - Redis alert fanout via Stream `stream:alerts:{tenant}` and Pub/Sub `channel:alerts:{tenant}`
  - ClickHouse fact tables: `trip_fact`, `idle_episode`, `charging_session`, `safety_event`
- **Archiver (`ParquetArchiver`):**
  - Hourly Parquet partitions: `s3://fleetpulse-archive/telemetry/tenant=<id>/date=YYYY-MM-DD/hour=HH/part-<hash>.parquet`
  - Compression: `zstd` level 3 with dictionary encoding
  - Column sorting: strictly sorted by `(vehicle_pid, ts)` for delta/dictionary efficiency
- **Ledger Verifier (`tools/ledger_verifier.py`):**
  - Zero-Loss Rule verified mathematically: `loss == 0.00%`
  - Duplicates correctly identified and subtracted from count comparisons
- **Checkpoint S Walking Skeleton (`test_checkpoint_s_walking_skeleton_end_to_end`):**
  - End-to-end integration passing: Simulator -> Gateway -> Normalizer -> Orderer -> ClickHouse Sink + Parquet Archiver -> FastAPI Query API (`/v1/vehicles/{pid}/live`, `/v1/map/clusters`) -> Leaflet Web UI.


---

## 2. Stage Benchmark Evidence

### Phase 3 — Simulator Validation & Benchmark
- **Validation:** `python services/simulator/main.py validate`
  - Status: `ALL BANDS PASS`
  - Total distance error vs ∫v dt: **0.0 m**
  - Battery CC-CV charging taper: Valid, monotonically non-increasing
  - Seed dataset: 100,000 verified VINs, 40 tenants, 80 fleets, 89,999 drivers.
- **Generation Throughput:** Sustained > 150,000 events/s in virtual time.

### Phase 4 — Ingestion Gateway
- **Tests:** 18/18 unit & integration tests passing.
- **Backpressure:** Bounded concurrency queue with high-watermark shedding (HTTP 429 + `Retry-After: 1`).
- **mTLS:** Hardware/CA-verified client certificate CN check with untrusted rejection.

### Phase 5 — Normalizer Service
- **Benchmark:** `NormalizerThroughputBenchmarkTest`
  - Iterations: 30,000 timed events across mixed OEM formats (A, B, C, D, E v1, E v2)
  - Time elapsed: **0.374 seconds**
  - Measured throughput: **80,199.2 events/second/core**
- **Hot-Reload:** Tested mid-stream activation of new mapping versions (`HotReloadTest`) with zero service restarts and zero DLQ errors.
- **DLQ Routing:** Verified all 6 reason codes:
  - `MALFORMED`
  - `UNKNOWN_OEM`
  - `MAPPING_ERROR`
  - `INVALID_VIN`
  - `UNKNOWN_VEHICLE`
  - `SCHEMA_VIOLATION`
- **Quality Bitmask:** Verified `VIN_WARN` (bit 8) and `CLOCK_SKEW` (bit 16).

### Phase 6 — Orderer and Fast Path Service
- **Benchmark:** `OrdererThroughputBenchmarkTest`
  - Iterations: 50,000 timed events through combined dedupe + reorder buffer
  - Time elapsed: **0.164 seconds**
  - Measured throughput: **305,336.4 events/second/core**
- **Deduplication:**
  - Tier 1: Sliding sequence window (4,096 elements, O(1)).
  - Tier 2: Rotating double-hashing Bloom filter (500K items/gen, 0.1% FP) + confirming exact recent-id cache.
  - Zero-Loss Rule verified: never drops on Bloom positive alone.
- **Reordering:**
  - Watermark min-heap ($W = \max(\text{ts}) - L$).
  - Late events routed to `telemetry.late.v1` with `QUALITY_LATE` (bit 2).
  - Force-emit on 256-item buffer cap overflow.
  - Silent vehicle sweep: flushes inactive vehicles after watermark lag.
- **Fast-Path:**
  - Last-write-wins stale drop protection.
  - Geohash-6 cell and spatial hierarchy counts ($p \in 3..7$).
  - Critical DTC detection (`P03`, `P02`, `C00`) with Redis cooldown lock.
