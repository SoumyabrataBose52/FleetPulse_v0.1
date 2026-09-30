# ADR-002: Polyglot Persistence — No Vector Store

**Date:** 2026-10-01  
**Status:** Accepted

---

## Context

FleetPulse handles four fundamentally different data workloads:
1. **ACID transactional** — tenants, billing, vehicles, trips, audit, RBAC
2. **High-throughput time-series analytics** — 100K events/s telemetry, aggregations over billions of rows
3. **Sub-millisecond live state** — current position/status of 100K vehicles
4. **Schema-flexible insight documents** — polymorphic evidence per alert type that evolves frequently

No single database handles all four well. We also evaluated whether vector similarity search is needed.

## Decision

Use exactly five stores, each justified:

| Store | Use case | Justification |
|---|---|---|
| **PostgreSQL 16 + PostGIS** | ACID core: tenants, billing, vehicles, trips (partitioned), alerts, geofences, audit | Strongest ACID, RLS for tenant isolation, PostGIS for spatial queries, mature ecosystem |
| **ClickHouse** | Time-series analytics: telemetry ingest (100K rows/s), cost rollups, top-K queries, batch scans over billions of rows | Columnar storage, vectorised execution, ReplacingMergeTree dedup, TTL + storage tiering |
| **Redis** | Live vehicle state, geohash cluster counters, dedupe sets, rate limiting, alert cooldowns, SSE fan-out | Sub-millisecond reads, 100K vehicles × ~1KB ≈ 100MB RAM (fits in one node), atomic Lua for rate limiting |
| **MongoDB** | Polymorphic insight documents (IDLING_EXCESS, RANGE_RISK, GEOFENCE_BREACH, etc.), DLQ quarantine | Schema-flexible, TTL indexes, easy to add new insight types without migrations |
| **MinIO / S3** | Cold archive as Parquet (zstd), retention, batch replay | Cheapest storage per GB, Parquet is query-able by ClickHouse `s3()` and DuckDB |

**No vector store:** FleetPulse uses only deterministic statistical algorithms (EWMA, DP, graph search, sliding windows). There is no embedding generation, semantic search, or similarity retrieval use-case. Adding a vector store (Pinecone, Weaviate, pgvector) would be fake depth — it would either be unused or force-fitted to a problem better solved by one of the existing stores.

## Alternatives Considered

- **TimescaleDB instead of ClickHouse:** Lower scale ceiling for our write rate; hypertable partitioning is less efficient than ClickHouse's columnar compression at 100K rows/s.
- **Cassandra instead of MongoDB:** Better write scale, but schema management and operational complexity outweigh the benefit for a few million insight documents.
- **Single PostgreSQL for everything:** Fails under 100K events/s write amplification and mixed analytical workload.
- **Vector store (pgvector, etc.):** No use-case; would be added only to tick a checklist box.

## Consequences

- **Positive:** Each store does one job well. Cross-store queries go through the API layer (no distributed joins). ClickHouse + Redis handle the hot path; Postgres is never in the ingest critical path.
- **Negative:** Five stores to operate and monitor. Data consistency across stores is eventual (by design — CQRS/event sourcing pattern).
- **Mitigation:** Docker Compose with health checks makes local operation trivial. Each store has its own TTL/retention policy. The API layer abstracts the multi-store complexity from consumers.
