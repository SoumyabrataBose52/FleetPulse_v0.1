# FleetPulse — Connected Vehicle Cost & EV Efficiency Intelligence Platform

> **Hackathon project.** Simulated data only. No real vehicles, no real PII.

---

## What is FleetPulse?

FleetPulse answers one question for mixed-fleet operators:

> **"Where are we losing money, how much can we save, and which vehicles, drivers or depots should we act on right now?"**

It processes a **real-time stream of 100,000 connected vehicles** (1 event/s each = 100K events/s) to surface idling costs, EV charging savings, driver safety scores, asset anomalies, and cost-per-km analytics — all in under 2 seconds end-to-end.

---

## Quick Start (local — 1K vehicles)

**Prerequisites:** Docker Desktop with at least 8 GB RAM allocated.

```bash
# 1. Clone and enter the repo
git clone <repo-url> && cd fleetpulse

# 2. Copy env template
cp .env.example .env

# 3. Start core infrastructure
make up

# 4. Start application services (builds all Java + Python images)
make up-app

# 5. Start the simulator with 1,000 vehicles
make up-sim

# 6. View live data
open http://localhost:8000/docs   # API docs
open http://localhost:9001        # MinIO console
open http://localhost:3001        # Grafana (admin/admin)
```

**Service ports:**

| Service | Port | URL |
|---|---|---|
| Gateway (Spring Boot) | 8080 | http://localhost:8080 |
| Normalizer | 8082 | http://localhost:8082 |
| Orderer | 8083 | http://localhost:8083 |
| Stream Engine (fast) | 8084 | http://localhost:8084 |
| Telemetry Sink | 8086 | http://localhost:8086 |
| Insight Sink | 8087 | http://localhost:8087 |
| Query API (FastAPI) | 8000 | http://localhost:8000/docs |
| Simulator Control | 8090 | http://localhost:8090 |
| Kafka | 9092 | — |
| Schema Registry | 8081 | http://localhost:8081 |
| PostgreSQL | 5432 | — |
| ClickHouse HTTP | 8123 | http://localhost:8123 |
| Redis | 6379 | — |
| MongoDB | 27017 | — |
| MinIO | 9001 | http://localhost:9001 |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3001 | http://localhost:3001 |

---

## Architecture

```
Simulator (Python, 1K–100K vehicles)
    │  HTTP batch (NDJSON) / MQTT
    ▼
Gateway (Spring Boot) ──► Kafka: raw.telemetry
    │
    ▼
Normalizer (Spring Boot) ──► Kafka: telemetry.canonical.v1
    │                    └──► Kafka: telemetry.dlq → MongoDB (quarantine)
    ▼
  ┌─────────────────────────────────────────┐
  │  Fast Path (Spring Boot)                │
  │  Updates Redis live state in <1s        │
  │  Raises critical alerts                 │
  └────────────────┬────────────────────────┘
                   │
  ┌────────────────▼────────────────────────┐
  │  Orderer (Spring Boot)                  │
  │  Dedupe (Bloom + exact) + reorder       │
  │  ──► Kafka: telemetry.clean.v1          │
  └────────────────┬────────────────────────┘
                   │
  ┌────────────────▼────────────────────────┐
  │  Stream Engine (Spring Boot)            │
  │  Trips, Idle, EV, Safety, Geofence      │
  │  ──► Kafka: events.* topics             │
  └────┬───────────┬───────────┬────────────┘
       │           │           │
  ClickHouse   PostgreSQL   MongoDB
  (telemetry)  (trips,      (insights,
  (cost)       alerts)      docs)
       │
    S3/MinIO
   (Parquet)
       │
  FastAPI (Python) ◄── React UI
```

**Stack:** Java Spring Boot (gateway, normalizer, orderer, stream engines, sinks) · Python FastAPI (query API, batch jobs, simulator) · React TypeScript (UI)  
**Why not Go:** The plan originally specified Go; we chose Spring Boot for the hot-path services (strong Kafka Streams support, mTLS, excellent JDBC ecosystem) and Python for API/simulator (rapid development, FastAPI OpenAPI for free).

---

## Project Structure

```
fleetpulse/
  config/             # defaults.yaml, scenarios/, oem-mappings/
  libs/
    py/fpcore/        # Python algorithm library (pure functions, no I/O)
    schemas/avro/     # Avro schemas for all Kafka messages
  services/
    simulator/        # Python — vehicle physics, world model, OEM encoders
    gateway/          # Spring Boot — HTTP batch + MQTT ingest, mTLS
    normalizer/       # Spring Boot — OEM → canonical Avro, hot-reload mappings
    orderer/          # Spring Boot — dedupe + reorder buffer
    stream-engine/    # Spring Boot — trip/idle/EV/safety/geofence processors
    telemetry-sink/   # Spring Boot — Kafka → ClickHouse batched inserts
    archiver/         # Spring Boot — Kafka → hourly Parquet on MinIO/S3
    insight-sink/     # Spring Boot — events → Postgres + MongoDB + Redis SSE
    api/              # Python FastAPI — REST + SSE query/sharing API
    batch/            # Python — cost rollups, sharing aggregates, erasure
    web/              # React TypeScript — dashboard SPA
  migrations/
    postgres/         # 001_extensions → 002_core_schema → 003_indexes → 004_rls
    clickhouse/       # 001_telemetry (all fact tables + MVs)
    kafka-topics/     # create-topics.sh
  deploy/
    compose/          # docker-compose profiles
    observability/    # Prometheus + Grafana configs
  tests/
    eval/             # ground-truth precision/recall tests
    bdd/features/     # Gherkin BDD scenarios
    load/             # k6 load tests
    chaos/            # chaos scenarios
  docs/adr/           # 8 Architecture Decision Records
```

---

## Build Phases & Verification Status

- [x] **Phase 0** — Repo, tooling, Docker Compose, Makefile, configuration ✅
- [x] **Phase 1** — `fpcore` algorithm library (109 pure algorithm tests, 100% green) ✅
- [x] **Phase 2** — Contracts (Canonical Avro schemas, OpenAPI 3.1.0, PostgreSQL & ClickHouse migrations) ✅
- [x] **Phase 3** — Simulator (100K vehicles, kinematic physics, 5 OEM formats, emission ledger) ✅
- [x] **Phase 4** — Ingestion Gateway (Java 21 Spring Boot Virtual Threads, mTLS, back-pressure) ✅
- [x] **Phase 5** — Normalizer + Hot-Reload OEM Mappings (ISO 3779 VIN, DTC parsing, Avro encoder) ✅
- [x] **Phase 6** — Orderer (Tier-1 Sequence Window + Tier-2 Rotating Bloom Filter, Redis fast-path) ✅
- [x] **Phase 7** — Storage Sinks (ClickHouse telemetry-sink & PostgreSQL/Mongo insight-sink) ✅
- [x] **Phase 8** — Main Modules (Viterbi Trips FSM, Idle Avoidable Cost, EV Charging DP, Battery SoH) ✅
- [x] **Phase 9** — Side Features (Driver Safety Leaderboard, Geofence Ray-Casting, DP Sharing, Erasure) ✅
- [x] **Phase 10** — API Hardening & Security (OAuth2/JWT RBAC, 4-layer tenant isolation, SHA-256 audit chain) ✅
- [x] **Phase 11** — Web UI Dashboard (7 tabs: Live Map, EV DP, Trips, Cost, Safety, Alerts, Privacy) ✅
- [x] **Phase 12** — Observability & Perf (Prometheus scrapers, Grafana dashboards, 7 SQL optimizations) ✅
- [x] **Phase 13 & 14** — DevOps & Acceptance (Docker Compose, 100,000-event zero-loss ledger verification) ✅
- [x] **Phase 15** — Deliverables & Solution Document (Solution Document, Architecture, STRIDE Threat Model) ✅

---

## Testing

```bash
make test          # all unit + integration tests
make test-fpcore   # algorithm library tests with coverage
make load          # k6 load test (requires running services)
make chaos         # chaos scenarios
```

---

## Key Design Decisions

See [`docs/adr/`](docs/adr/) for full Architecture Decision Records.

| ADR | Decision |
|---|---|
| ADR-001 | Kafka as durable system-of-record; partition by `vehicle_pid`; CP settings |
| ADR-002 | Polyglot persistence (Postgres/ClickHouse/Redis/MongoDB/S3); **no vector store** |
| ADR-003 | Two-path processing: fast path (<1s) for live state, ordered path for correctness |
| ADR-004 | At-least-once + idempotent sinks (effectively-once) |
| ADR-005 | Pseudonymous telemetry keys; erasure by unlinking |
| ADR-006 | Spring Boot + Python instead of Go (same contracts, equivalent performance) |
| ADR-007 | Hot-reloadable OEM mappings as data (zero downtime OEM onboarding) |
| ADR-008 | No ML, no agentic AI; deterministic algorithms evaluated against ground truth |

---

## Security

- JWT/OIDC authentication (Keycloak) with `tenant_id` claim
- PostgreSQL Row-Level Security on all tenant-scoped tables
- ClickHouse row policies with mandatory tenant filter
- Redis key namespacing by tenant
- Audit log with SHA-256 hash chain (append-only)
- Location masking by role (precise for fleet managers, geohash-7 for analysts)
- Right-to-erasure workflow across all stores

---

## Declared Tools & Libraries

| Tool | Used for |
|---|---|
| Antigravity AI (Google DeepMind) | Code generation, architecture guidance, boilerplate |
| Apache Kafka | Message streaming |
| Spring Boot 3.x | Java microservices |
| FastAPI | Python REST API |
| PostgreSQL + PostGIS | Relational core + spatial queries |
| ClickHouse | Time-series analytics |
| Redis | Live state + caching |
| MongoDB | Insight documents |
| MinIO | S3-compatible object store |

*All data is fully synthetic. No Motorq affiliation.*
