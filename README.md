# FleetPulse — Connected Vehicle Cost & EV Efficiency Intelligence Platform

> **Connected Vehicle Telematics & Fleet Intelligence Platform.** Real-time streaming, processing, and cost optimization for **100,000 connected vehicles** across 40 enterprise fleets.

---

## What is FleetPulse?

FleetPulse answers one question for mixed-fleet operators:

> **"Where are we losing money, how much can we save, and which vehicles, drivers or depots should we act on right now?"**

It processes a **real-time stream of 100,000 connected vehicles** (1 event/s each = 100K events/s) to surface idling costs, EV charging savings, driver safety scores, asset anomalies, and cost-per-km analytics — all in under 2 seconds end-to-end.

---

## Quick Start (100,000 Connected Vehicle Enterprise Fleet)

**Prerequisites:** Docker Desktop with at least 8 GB RAM allocated.

```bash
# 1. Clone and enter the repo
git clone https://github.com/SoumyabrataBose52/FleetPulse_v0.1.git && cd FleetPulse_v0.1

# 2. Start everything in ONE command (Pulls pre-built GHCR images & boots cluster + dashboard in seconds!)
docker compose up -d

# 3. View live dashboard & services
open http://localhost:3000        # FleetPulse Web Dashboard (Flighty UI)
open http://localhost:8000/docs   # Query API & OpenAPI docs
open http://localhost:3001        # Grafana Observability Dashboards (admin/admin)
open http://localhost:9090        # Prometheus Metrics Explorer
open http://localhost:9001        # MinIO Object Storage Console (fleetpulse/fleetpulse_dev)
```

**Service Endpoints:**

| Service | Port | Description & URL |
|---|---|---|
| **Web Dashboard** | 3000 | Flighty-inspired Glassmorphism UI: [http://localhost:3000](http://localhost:3000) |
| **Query API (FastAPI)** | 8000 | REST & OpenAPI Docs: [http://localhost:8000/docs](http://localhost:8000/docs) |
| **Grafana** | 3001 | Pre-configured Dashboards: [http://localhost:3001](http://localhost:3001) (`admin`/`admin`) |
| **Prometheus** | 9090 | Metrics & TSDB Explorer: [http://localhost:9090](http://localhost:9090) |
| **Ingestion Gateway** | 8080 | Java 21 Spring Boot Virtual Threads: [http://localhost:8080/healthz](http://localhost:8080/healthz) |
| **Normalizer** | 8082 | Multi-OEM Avro Translator: [http://localhost:8082/healthz](http://localhost:8082/healthz) |
| **Orderer** | 8083 | Fast-Path Dedupe & Reorderer: [http://localhost:8083/healthz](http://localhost:8083/healthz) |
| **Stream Engine** | 8084 | Trips, Idle & EV State Processor: [http://localhost:8084/actuator/health](http://localhost:8084/actuator/health) |
| **Telemetry Sink** | 8086 | High-throughput ClickHouse Batch Sink: [http://localhost:8086/actuator/health](http://localhost:8086/actuator/health) |
| **Insight Sink** | 8087 | Postgres & MongoDB Event Sink: [http://localhost:8087/actuator/health](http://localhost:8087/actuator/health) |
| **Simulator Control** | 8090 | 100K Fleet Physics Engine API: [http://localhost:8090](http://localhost:8090) |
| **MinIO Console** | 9001 | S3 Cold Storage Console: [http://localhost:9001](http://localhost:9001) |
| **ClickHouse HTTP** | 8123 | Analytical Database: [http://localhost:8123](http://localhost:8123) |
| **Kafka Broker** | 9092 | Event Streaming Backbone (KRaft mode) |
| **Schema Registry** | 8081 | Confluent Schema Registry (Avro) |
| **PostgreSQL** | 5432 | Relational Core + PostGIS 16 |
| **Redis** | 6379 | In-memory Live State Cache |
| **MongoDB** | 27017 | Unstructured Document & DLQ Quarantine Store |

---

## Microservices Architecture & System Decomposition

FleetPulse is built strictly upon an **Enterprise-Grade Distributed Microservices Architecture**. Instead of a monolithic backend, the system is decomposed into 10 decoupled, independently scalable services that communicate asynchronously over an event backbone.

```
                  ┌────────────────────────────────────────────────────────┐
                  │   Simulator (Python — 100,000 Connected Vehicles)      │
                  │   Kinematic Physics, 7 Logistics Corridors, 5 OEM Codecs│
                  └─────────────────────────┬──────────────────────────────┘
                                            │ HTTP Batch (NDJSON) / MQTT
                                            ▼
                  ┌────────────────────────────────────────────────────────┐
                  │    Ingestion Gateway Microservice (Java 21 / Spring)    │
                  │    Rate Limiting, mTLS, Token Bucket Ingestion          │
                  └─────────────────────────┬──────────────────────────────┘
                                            │ Kafka: telemetry.raw
                                            ▼
                  ┌────────────────────────────────────────────────────────┐
                  │    Normalizer Microservice (Java 21 / Spring Boot)     │
                  │    Hot-Reload OEM Mappings, ISO 3779 VIN, Canonical Avro│
                  └─────────────────────────┬──────────────────────────────┘
                         │                  │ Kafka: telemetry.canonical.v1
                         ▼ (Malformed)      ▼
                  [DLQ → MongoDB]     ┌────────────────────────────────────┐
                                      │  Fast-Path Orderer Microservice    │
                                      │  Rotating Bloom Filter Deduplication│
                                      │  Sub-second Redis State Cache (<1s)│
                                      └─────────────┬──────────────────────┘
                                                    │ Kafka: telemetry.clean.v1
                                                    ▼
                       ┌───────────────────────────────────────────────────┐
                       │   Stream Engine Microservice (Java 21 / Spring)   │
                       │   Viterbi HMM Trips, Bellman DP EV Optimizer,      │
                       │   EWMA Safety Scores, Ray-Casting Geofences       │
                       └───────────┬─────────────────┬─────────────────┬───┘
                                   │                 │                 │
            Kafka: telemetry.clean │    Kafka: events.trips/alerts/ev  │ Kafka: telemetry.clean
                                   ▼                 ▼                 ▼
                       ┌───────────────────┐ ┌───────────────────┐ ┌───────────────┐
                       │Telemetry Sink Svc │ │ Insight Sink Svc  │ │ Archiver Svc  │
                       │(Batched ClickHouse│ │(Postgres + Mongo  │ │(Hourly Parquet│
                       │  50K rows/flush)  │ │  + Redis SSE)     │ │  to MinIO S3) │
                       └─────────┬─────────┘ └─────────┬─────────┘ └───────┬───────┘
                                 │                     │                   │
                                 ▼                     ▼                   ▼
                            [ClickHouse]      [PostGIS / MongoDB]      [MinIO S3]
                                 │                     │                   │
                                 └──────────────┬──────┴───────────────────┘
                                                │ Polyglot SQL / GeoJSON
                                                ▼
                               ┌───────────────────────────────────┐
                               │  Query API Microservice (FastAPI) │
                               │  OpenAPI 3.1, JWT RBAC, SSE Stream│
                               └────────────────┬──────────────────┘
                                                │ REST / EventSource
                                                ▼
                               ┌───────────────────────────────────┐
                               │  Web Dashboard Micro-Frontend     │
                               │  React Vite + Nginx (Flighty UI)  │
                               └───────────────────────────────────┘
```

### Why Microservices?
1. **Decoupled Bounded Contexts (Single Responsibility):**
   - Each microservice has one single, well-defined responsibility.
   - For example, if OEM telemetry schemas change or a new manufacturer is onboarded, only the **Normalizer Microservice** hot-reloads its mapping rules without touching the **Ingestion Gateway** or the **Stream Engine**.
2. **Event-Driven Choreography via Kafka:**
   - Services communicate through strictly versioned Apache Avro contracts stored in Confluent Schema Registry.
   - Zero synchronous temporal coupling between ingestion and storage: if ClickHouse or PostgreSQL undergoes maintenance, Kafka partitions buffer incoming telemetry safely with zero data loss.
3. **Polyglot Persistence (Database-per-Domain):**
   - No single database bottleneck. Each microservice uses the optimal datastore for its workload:
     - **ClickHouse:** High-throughput time-series metrics ($100\text{K events/s}$ append-only fact tables).
     - **PostgreSQL + PostGIS:** Relational transactions, spatial geofences, and vehicle registry.
     - **Redis:** Sub-second live vehicle coordinates, speed, and geohash cluster caching.
     - **MongoDB:** Flexible JSON insight cards and quarantined DLQ payloads.
     - **MinIO:** Columnar Parquet files for cost-effective long-term cold analytics.
4. **Independent Horizontal Scalability:**
   - The Ingestion Gateway and Normalizer can be scaled to 20+ replicas behind a load balancer during peak rush hours without modifying downstream analytical sinks.
5. **Observability as a First-Class Citizen:**
   - Microservices expose standardized Prometheus actuator endpoints scraped every 5 seconds.
   - Pre-provisioned Grafana dashboards display end-to-end latency, Kafka lag, and ingestion throughput.

---

## Project Structure

```
fleetpulse/
  config/             # defaults.yaml, scenarios/, oem-mappings/
  data/seed/          # 40 Enterprise Tenants & 100,000 Seed Vehicles (Zipf distribution)
  libs/
    py/fpcore/        # Pure Python algorithm library (Viterbi HMM, Bellman DP, Haversine)
    schemas/avro/     # Canonical Avro contracts for all Kafka topics
  services/
    simulator/        # Python — 100K vehicle physics, world model, 5 OEM codecs
    gateway/          # Spring Boot (Java 21) — Ingestion Gateway, mTLS, backpressure
    normalizer/       # Spring Boot (Java 21) — Multi-OEM to canonical Avro & DLQ
    orderer/          # Spring Boot (Java 21) — Tier-1 sequence window + Tier-2 Bloom filter
    stream-engine/    # Spring Boot (Java 21) — Trip FSM, Idle Cost, EV DP, Safety, Geofencing
    telemetry-sink/   # Spring Boot (Java 21) — Kafka → ClickHouse batched inserts
    insight-sink/     # Spring Boot (Java 21) — Kafka → Postgres + MongoDB + Redis
    archiver/         # Python — Kafka → hourly columnar Parquet on MinIO/S3
    api/              # Python FastAPI — REST, GeoJSON, OpenAPI 3.1, JWT RBAC
    web/              # Nginx reverse proxy serving React production bundle
    web_app/          # React Vite SPA — Flighty-inspired porcelain & glassmorphism UI
  migrations/
    postgres/         # PostGIS extensions, core tables, spatial indexes, RLS policies
    clickhouse/       # Telemetry fact tables & continuous aggregating materialized views
    kafka-topics/     # Topic provisioning scripts with partitioned replication
  deploy/
    observability/    # Prometheus scrape targets & pre-provisioned Grafana dashboards
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
- [x] **Phase 11** — Web UI Dashboard (Flighty UI: Live Radar, EV DP, Trips, Cost, Safety, Alerts, 40-tenant popover) ✅
- [x] **Phase 12** — Observability & Perf (Prometheus scrapers, Grafana dashboards, ClickHouse optimizations) ✅
- [x] **Phase 13 & 14** — DevOps & Acceptance (1-Command Docker Compose, 100K Zero-Loss Ledger Verification) ✅
- [x] **Phase 15** — Deliverables & Solution Document (Complete Solution Architecture & Threat Model) ✅

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

## Security & Multi-Tenancy

- **4-Layer Tenant Isolation:** PostgreSQL Row-Level Security (RLS), ClickHouse row policies, Redis key namespacing, and MongoDB tenant scoping.
- **Role-Based Access Control (RBAC):** JWT Bearer authentication with claims for `tenant_id` and role permissions.
- **Immutable Audit Chain:** Append-only SHA-256 cryptographic hash chain verifying every administrative and operational action.
- **Differential Privacy & Data Masking:** Dynamic coordinate truncation (Geohash-7) for analyst roles; full precision for dispatch managers.

---

*All vehicle telemetry and fleet data is synthetically generated for demonstration.*
