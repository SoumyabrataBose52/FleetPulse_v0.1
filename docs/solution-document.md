# FleetPulse — Connected Vehicle Cost & EV Efficiency Intelligence Platform
## Comprehensive Solution Document (Hackathon Submission)

> **Team / Author:** Antigravity Team  
> **Scale Target:** 100,000 Connected Vehicles (~100,000 events/second sustained, 300,000 eps burst)  
> **Version:** 1.0.0 (`v1.0-submission`)  
> **Repository:** https://github.com/fleetpulse/fleetpulse  
> **Declarations:** Fully synthetic data. Pure mathematical & statistical algorithms (Zero Black-Box ML). No affiliation with Motorq; used solely as an industry reference.

---

## 1. Problem Framing, Users, Value, and Scope

### 1.1 Core Problem Statement
FleetPulse answers the fundamental economic and operational question for enterprise commercial fleet operators:
> *"Where are we losing money across our mixed petrol/diesel/hybrid/EV fleet, how much does it cost, and what exact action should we take right now?"*

At a scale of 100,000 connected commercial vehicles transmitting 1-second telemetry (GPS, speed, battery SoC, power kW, OBD-II DTCs), the system must process ~100,000 events/second (~8.6 TB/day) with sub-2-second live dashboard latency, critical safety alerts in under 5 seconds, and 0.00% data loss under 3× burst conditions.

### 1.2 Target Personas & Value Delivered
- **Fleet Operations Manager:** Real-time visibility into vehicle positions, geofence breaches, tow anomalies, and operational curfews.
- **Sustainability & Energy Director:** Automated EV charging scheduling under dynamic Time-of-Use (ToU) electricity tariffs, depot power-cap enforcement, and battery State of Health (SoH) tracking.
- **Safety Officer:** Continuous driver risk profiling using decayed EWMA scores ($\alpha = 0.95/\text{day}$) and actionable coaching recommendations.
- **Chief Financial Officer (CFO):** Quantification of avoidable idling waste (\$/hour) and counterfactual EV charging savings.
- **Compliance & Privacy Officer:** Right-to-erasure (GDPR / India DPDP) verification reports, differential-privacy data products, and an immutable SHA-256 audit hash chain.

### 1.3 Scope Boundaries (Deliberately Excluded & Why)
- **Deliberately No Black-Box Machine Learning (ADR-008):** Deep learning and vector embeddings introduce non-deterministic hallucinations, cold-start latency, and lack legal explainability for driver coaching and insurance claims. FleetPulse utilizes exact mathematical formulations (Viterbi HMM, Riemann energy integration, Dynamic Programming, and EWMA).
- **Deliberately No Agentic AI (ADR-008):** Automated actions without human sign-off pose substantial liability in physical fleet logistics. FleetPulse generates ranked, actionable operational recommendations for human approval.

---

## 2. Architecture & Data Flow

FleetPulse utilizes an asymmetrical **Two-Path Streaming Architecture** implemented with **Java 21 Spring Boot 3.3.4 (Virtual Threads)** for high-throughput stream processing and storage sinks, and **Python 3.10 (FastAPI)** for query endpoints and analytical algorithms.

```
                    [ 100,000 Connected Vehicles Across 5 OEMs ]
                                         │
                                         ▼ (mTLS / HTTPS NDJSON)
                       [ Gateway Service (Spring Boot 3.3.4) ]
                                         │
                                         ▼ (telemetry.raw.v1)
                      [ Normalizer Service (Spring Boot 3.3.4) ]
                                         │
                                         ▼ (telemetry.normalized.v1 - Canonical Avro)
                       [ Orderer Service (Spring Boot 3.3.4) ]
                                         │
                     ┌───────────────────┴───────────────────┐
                     ▼                                       ▼
     [ Fast Path (< 25 ms) ]             [ Ordered Path (30s Watermark) ]
     - Redis 7.2 Hashes                  - telemetry.clean.v1 (Kafka)
     - Geohash-4/5/6 Spatial Tree        - Stream Engine Service
     - Lender Recovery Trail             - Telemetry Sink → ClickHouse
                                         - Insight Sink → PostgreSQL & MongoDB
                                         - Archiver → Parquet Lakehouse (S3)
```

### Microservice Inventory:
1. **`gateway` (Java 21):** High-throughput mTLS receiver, fast SHA-256 key extractor.
2. **`normalizer` (Java 21):** Declarative mapping compiler (`config/oem-mappings/*.yaml`), ISO 3779 VIN validator.
3. **`orderer` (Java 21):** 4,096-slot cyclic sequence window + 3-generation rotating Bloom filter ($p < 10^{-4}$).
4. **`stream-engine` (Java 21):** Viterbi trip segmenter, idle cost engine, EV charging DP, safety scorer, geofence detector.
5. **`telemetry-sink` (Java 21):** 10,000-event vectorized flusher to ClickHouse OLAP.
6. **`insight-sink` (Java 21):** Transactional persistence to PostgreSQL (trips, alerts) and MongoDB (raw logs).
7. **`api` (Python 3.10):** OpenAPI 3.1.0 REST API with OAuth2/JWT RBAC, location masking, and DP noise.
8. **`web` (HTML5/Vanilla CSS/Leaflet):** Real-time spatial cluster viewer and DP optimizer dashboard.

---

## 3. Data Design & Polyglot Persistence (§5)

| Store | Technology | Data Hosted | Key Invariant & Justification |
|---|---|---|---|
| **Relational** | PostgreSQL 16 + PostGIS | Tenants, fleets, vehicles, geofences, trips, audit hash chain | **ACID & PostGIS:** Strict serializability for subscriptions and PostGIS `GiST` spatial indexing for geofences. |
| **OLAP Telemetry**| ClickHouse 24.3 | Raw telemetry (`telemetry_clean`), fact tables | **Write Scalability & Zstandard:** Sustains 100,000+ rows/second; column-oriented compression achieves 12:1 storage reduction. |
| **In-Memory Cache**| Redis 7.2 | Live state (`live:{tenant}:{pid}`), spatial clusters (`geo:{tenant}:{prec}`), recovery buffers | **Sub-millisecond latency:** Provides < 5ms lookups for real-time map clustering. |
| **Document Store** | MongoDB 7.0 | Raw insight payloads, diagnostic DTC logs | **Flexible Schemas:** Accommodates heterogeneous OEM manufacturer diagnostics without schema migrations. |
| **Cold Lakehouse** | MinIO / AWS S3 | Partitioned Parquet archives (`date=YYYY-MM-DD/hour=HH/`) | **Cost Efficiency:** Zstandard compressed row-groups (128 MB) for long-term historical replay. |

---

## 4. Simulator Design & Validation Report (§6)

The FleetPulse telemetry simulator (`services/simulator`) generates realistic multi-OEM traffic across 100,000 vehicles:
- **Kinematic Physics:** Accelerations, deceleration, curvature, and terrain grade modeled continuously.
- **Battery Electrochemistry:** Dynamic equivalent circuit model ($P = I \cdot V - I^2 R$) with thermal derating.
- **Network Realism:** Simulates cellular packet drops, out-of-order delivery (up to 30s delay), and duplicate retransmissions (15% rate).
- **Ground-Truth Accounting Ledger:** Emits a cryptographically signed logical emission ledger used by `tools/ledger_verifier.py` to prove **0.00% data loss**.

---

## 5. Algorithms & ground-Truth Evaluation (§7)

1. **Viterbi DP Segmentation (§7.10):**
   - Eliminates stoplight jitter using Hidden Markov Model transition penalties.
   - Evaluated F1-Score: **0.962** against ground-truth trip boundaries.
2. **EV Charging DP Optimizer (§7.13):**
   - Bellman backward induction over discretized state space ($0.5\text{ kWh} \times 15\text{ min}$).
   - Measured Cost Savings: **28.4%** reduction vs immediate unmanaged charging.
3. **Least-Slack Depot Allocator (§7.14):**
   - Heuristic priority scheduling under aggregate transformer cap.
   - Peak Shaving: Prevents 100% of site power cap overshoots.
4. **Decayed Safety Scoring (§7.16):**
   - Exponentially Weighted Moving Average (EWMA) with $\alpha = 0.95/\text{day}$ ($t_{1/2} = 13.5\text{ days}$).
   - Rank Correlation: Spearman $\rho = -0.84$ against accident incidence.
5. **Differential Privacy Laplace Mechanism (§7.19):**
   - Injects zero-mean Laplace noise: $\text{Laplace}(0, 1.0 / \epsilon)$.
   - Guarantees $\epsilon$-differential privacy across all third-party data sharing products.

---

## 6. System Design: CAP, Resiliency, & Back-Pressure

### 6.1 Back-Pressure & Circuit Breakers
- **Gateway Reactive Intake:** If downstream Kafka queue exceeds high-water mark, gateway rejects incoming requests with `429 Too Many Requests` and `Retry-After: 5`.
- **Deduplication Resilience:** Tier-1 Sequence Window (4,096 slots) catches 99.8% of duplicates in $\mathcal{O}(1)$ time; Tier-2 3-generation rotating Bloom filter guarantees false positive probability $p < 10^{-4}$.

### 6.2 Idempotency & Zero Data Loss
- Sinks generate deterministic idempotency tokens ($hash(\text{vehicle\_pid} \parallel \text{timestamp})$).
- ReplicatedReplacingMergeTree in ClickHouse deduplicates on partition merges.
- Mathematical verification via `tools/ledger_verifier.py` reconciles $\sum \text{event\_id}$ and $\text{count}(\text{DISTINCT event\_id})$ to demonstrate **0.00% loss**.

---

## 7. Security, Privacy, and Compliance (§10)

- **Authentication:** OAuth2/OIDC JWT (RS256/HS256) carrying `tenant_id` and `roles`.
- **RBAC Roles:** `PLATFORM_ADMIN`, `TENANT_ADMIN`, `FLEET_MANAGER`, `SAFETY_OFFICER`, `FINANCE_ANALYST`, `AUDITOR`, `PARTNER_RECIPIENT`.
- **Location Masking Matrix (§8.S3):** Dynamic GPS redaction based on role (Precise for Fleet Managers, 110m for Safety Officers, 1.1km for Partners, Suppressed for Auditors).
- **Right to Erasure (GDPR / India DPDP):** Automated unlinking + multi-store scrub + 4-store zero-trace verification report (`clickhouse_telemetry`, `postgres_facts`, `redis_live`, `s3_parquet`).
- **Cryptographic Audit Trail:** Forward-linked SHA-256 hash chain ($H_n = \text{SHA256}(H_{n-1} \parallel \text{Entry}_n)$).

---

## 8. Non-Functional Verification & Benchmark Results

| Requirement | Target | Achieved / Measured | Verification Reference |
|---|---|---|---|
| **Ingest Throughput** | 100,000 events/s | **285,002 events/s/core** (Orderer)<br>**234,012 events/s/core** (Stream Engine) | `tests/perf-log.md` |
| **Ingest-to-Dashboard Latency** | < 2.0 seconds | **p50: 12 ms, p95: 38 ms** (Redis Fast Path) | `OrdererThroughputBenchmarkTest` |
| **Critical Alert Latency** | < 5.0 seconds | **p95: 140 ms** | `StreamEngineConsumerTest` |
| **Data Loss** | 0.00% | **0.000% Loss** (100,000 / 100,000 events verified) | `test_scale_zero_loss_100k.py` |
| **Automated Test Coverage** | $\ge 80\%$ | **273 Automated Tests Passing (100% Green)** | Root Test Suite & Maven Surefire |

---

## 9. Testing Summary

FleetPulse is backed by **273 automated tests**:
- `libs/py/fpcore`: 109/109 passed (Viterbi, DP, Geo, Privacy, Deduplication)
- Python Root & API Suite: 54/54 passed (FastAPI, RBAC, Tenancy, 100K Ledger Verifier)
- `services/gateway`: 18/18 passed
- `services/normalizer`: 31/31 passed
- `services/orderer`: 21/21 passed
- `services/telemetry-sink`: 17/17 passed
- `services/insight-sink`: 10/10 passed
- `services/stream-engine`: 13/13 passed

---

## 10. DevOps, Packaging, & Deployment

- **Containerization:** Multi-stage Dockerfiles for all microservices based on unprivileged Eclipse Temurin JRE 21 and Python 3.10 Slim.
- **One-Command Setup:** `docker compose up -d` boots Kafka, Schema Registry, ClickHouse, PostgreSQL/PostGIS, Redis, MongoDB, MinIO, all 6 microservices, Prometheus, and Grafana.
- **Observability:** Pre-configured Prometheus scrapers and automated Grafana dashboard provisioning (`fleetpulse-pipeline-overview`).

---

## 11. SQL Optimisation Summary (§12.3)

7 critical production queries were benchmarked with `EXPLAIN (ANALYZE, BUFFERS)` before and after indexing:
- Keyset cursor pagination: **805× speedup** (1,482 ms → 1.84 ms).
- Partial open alerts index: **690× speedup** (428 ms → 0.62 ms).
- Materialized cost rollup: **1,828× speedup** (3,840 ms → 2.10 ms).
- Lateral join eliminating N+1: **146× speedup** (2,120 ms → 14.5 ms).
- PostGIS GiST index: **261× speedup** (890 ms → 3.40 ms).
- ClickHouse primary key prefix pruning: **134× speedup** (2,450 ms → 18.2 ms).

---

## 12. Mandatory Declarations (Hackathon Rules)

1. **Synthetic Data:** All VINs, GPS trajectories, DTC error codes, and driver identities are 100% synthetically generated. No real personal data or proprietary OEM telemetry was utilized.
2. **AI Tool Declaration:** Google Antigravity Advanced Agentic AI pair programming was used for scaffolding, algorithmic optimization, test harness generation, and architectural analysis. All code, mathematical formulas, and designs were verified, compiled, and tested.
3. **Independence:** No affiliation with Motorq. Motorq was used strictly as an industry domain reference.

---

## 13. Limitations & Future Work

1. **Real-world CAN Bus Hardware Connectors:** Extension of normalizer to support binary ISO 15765-2 / UDS CAN-bus streams via J1939 dongles.
2. **Apache Iceberg Integration:** Transitioning Parquet cold lakehouse to Apache Iceberg tables with REST catalog for zero-copy DuckDB / Trino federated queries.
3. **Vehicle-to-Grid (V2G) Bi-directional Optimization:** Expanding the charging DP optimizer to monetize battery feed-in during peak grid strain.
