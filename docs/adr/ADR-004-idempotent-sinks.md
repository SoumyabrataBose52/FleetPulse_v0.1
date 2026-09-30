# ADR-004: At-Least-Once Delivery with Idempotent Sinks (Effectively-Once)

## Status
Accepted

## Context
High-throughput stream processing pipelines must handle worker restarts, network blips, and broker rebalances without losing data or producing duplicated analytics counts.
While Kafka offers transactional exactly-once semantics (EOS) via `isolation.level=read_committed` and transactional producers, Kafka EOS does not extend across external heterogeneous datastores (ClickHouse, PostgreSQL, MongoDB, Redis, S3).

## Decision
We adopt an **At-Least-Once Processing + Idempotent Sinks** architecture (§4.5):

1. **Deterministic Identifiers:**
   - Every event, trip, idle episode, charging session, and alert receives a deterministic hash ID derived from immutable domain attributes:
     - `event_id = hash(vehicle_pid, ts_event, seq|content)`
     - `trip_id = hash(vehicle_pid, start_ts)`
     - `idle_id = hash(vehicle_pid, start_ts)`
     - `session_id = hash(vehicle_pid, start_ts)`
     - `alert_key = hash(vehicle_pid, type, window_start)`
2. **Idempotent Storage Engines:**
   - **ClickHouse:** Employs `ReplacingMergeTree` deduplicating on primary keys, combined with Kafka block insert deduplication tokens (`insert_deduplication_token = topic-partition-firstOffset-lastOffset`).
   - **PostgreSQL:** Uses `INSERT ... ON CONFLICT (id) DO NOTHING` or `DO UPDATE`.
   - **MongoDB:** Uses `updateOne(..., upsert=True)` keyed by deterministic insight hashes.
   - **Redis:** Uses idempotent `HSET` and timestamp comparisons.
3. **Commit Semantics:**
   - Offsets are committed to Kafka **only after** the downstream database write or checkpoint has succeeded.

## Consequences
- **Positive:** System guarantees zero data loss while preventing duplicate inflation in analytical reports. No heavy 2-phase commit overhead.
- **Negative:** Sinks must maintain primary keys and deduplication indexes, incurring small storage and compute overhead during compaction/merge.
