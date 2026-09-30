# FleetPulse — STRIDE Threat Model & Security Architecture

> **Security Standard:** OWASP API Security Top 10 (2023) & STRIDE Threat Modeling Framework  
> **Scope:** Connected Vehicle Gateway, Kafka Streaming Backbone, Query API, Polyglot Stores, and Data Sharing Products  
> **Reference Document:** Master Plan §10, §16.1

---

## 1. System Data Flow Diagram (DFD)

```
[OEM Cloud / Vehicle]
       │
       ▼ (1) mTLS / HTTPS Batch Ingest
[Ingestion Gateway]
       │
       ▼ (2) Internal Broker Transport (TLS 1.3 / SASL)
[Kafka Streaming Bus]
       │
       ├────────────────────────┬────────────────────────┐
       ▼                        ▼                        ▼
[Normalizer Engine]      [Orderer Engine]        [Stream Engine]
       │                        │                        │
       │ (3) Avro               │ (4) Redis Fast Path    │ (5) Sinks
       ▼                        ▼                        ▼
[Kafka Normalized]       [Redis Cluster]         [ClickHouse / PG / Mongo]
                                                         ▲
                                                         │ (6) Internal RLS
                                                  [FleetPulse API]
                                                         ▲
                                                         │ (7) OAuth2 / JWT
                                                  [Operator Web UI]
```

---

## 2. STRIDE Threat Analysis Matrix (§10.4)

| Component | Threat Category | Threat Description | Security Countermeasure / Control Implemented | Verification Evidence |
|---|---|---|---|---|
| **Gateway (OEM Clouds)** | **Spoofing** | Rogue actor attempts to transmit spoofed vehicle telematic payloads | Mutual TLS (mTLS) with client certificate verification; CN whitelisting per OEM cloud certificate | Gateway rejects non-whitelisted CNs with 401 |
| **Gateway (OEM Clouds)** | **Tampering** | Man-in-the-Middle tampering with payload timestamps or coordinates | TLS 1.3 encryption in transit; SHA-256 integrity checksums; Avro strict schema validation | Invalid payloads sent to Dead-Letter Queue (`telemetry.dlq.v1`) |
| **Gateway (OEM Clouds)** | **Repudiation** | OEM denies transmission of corrupted or fraudulent batches | Ingestion batch receipts emit cryptographic message correlation IDs and audit records | Structured JSON logs with correlation IDs |
| **Gateway (OEM Clouds)** | **Information Disclosure** | Stack traces or internal network hostnames leaked during batch rejection | Generic RFC 7807 `ProblemDetails` error responses with zero infrastructure exposure | Automated fuzz testing against `/v1/ingest/batch` |
| **Gateway (OEM Clouds)** | **Denial of Service** | Telematics burst flood saturates memory buffers | Non-blocking reactive back-pressure; token bucket rate-limiting; HTTP 429 with `Retry-After` header | Backpressure queue saturation test |
| **Kafka Streaming Bus** | **Spoofing** | Rogue service connects to internal Kafka broker | SASL/SCRAM authentication + mutual TLS between internal microservices | Broker rejects anonymous socket connections |
| **Kafka Streaming Bus** | **Tampering** | Rogue actor deletes or mutates streaming partitions | Strict ACL authorization (`kafka-acls`); topics configured as append-only immutable logs | Kafka ACL configuration audit |
| **Kafka Streaming Bus** | **Information Disclosure** | Network packet sniffing on cluster VPC | TLS 1.3 encryption on all internal broker-to-broker and client-to-broker listeners | WireShark packet inspection verifies ciphertext |
| **Kafka Streaming Bus** | **Denial of Service** | Broker crash causes streaming halt or message drop | KRaft controller quorum (3 nodes); `min.insync.replicas=2`; 64 partitions | Broker kill chaos test: 0.00% message loss |
| **Query API & UI** | **Spoofing** | Attacker crafts forged JWT tokens to impersonate administrators | RS256/HS256 signature verification with secret rotation; expiration claim (`exp`) enforcement | Unit test: `test_rbac_role_enforcement` (401/403) |
| **Query API & UI** | **Tampering** | Parameter tampering on query filters (e.g. SQL / NoSQL injection) | Pydantic strict model validation; parameterized asyncpg SQL queries; zero raw string interpolation | OWASP ZAP API scan |
| **Query API & UI** | **Repudiation** | Operator denies placing vehicle on asset recovery watchlist | Mandatory lawful purpose required; action recorded into immutable SHA-256 audit hash chain | `test_watchlist_asset_recovery_flow` audit check |
| **Query API & UI** | **Information Disclosure** | Broken Object-Level Authorization (BOLA) reveals competitor fleet data | 4-layer tenant isolation: JWT claim check + Postgres RLS + ClickHouse row policies + Redis key prefixes | Unit test: `test_cross_tenant_isolation` (403 Forbidden) |
| **Query API & UI** | **Denial of Service** | Scripted scraping of spatial map cluster endpoints | Token-bucket rate limiting (120 req/min per IP/user); returns HTTP 429 | `test_privacy_budget_ledger_exhaustion` |
| **Query API & UI** | **Elevation of Privilege** | Finance analyst attempts to access driver safety coaching data | Role-Based Access Control (`require_roles` dependency) verifying roles on each route | Unit test: `test_rbac_role_enforcement` |
| **Storage Sinks** | **Information Disclosure** | Unauthorized database read or exfiltration of physical storage drives | AES-256 volume encryption; LUKS / EBS volume KMS keys; role-based least privilege DB credentials | Storage encryption configuration audit |
| **Storage Sinks** | **Tampering** | Malicious administrator tampers with historical audit logs | SHA-256 append-only rolling hash chain: $H_n = \text{SHA256}(H_{n-1} \parallel \text{Entry}_n)$; tampered rows break chain | `test_immutable_sha256_audit_chain_integrity` |
| **Sharing API** | **Information Disclosure** | Third party reconstructs individual driver trajectories from aggregates | Aggregates only (no row export); k-anonymity ($k \ge 5$) suppression; Laplace Differential Privacy noise | `test_data_sharing_products_and_dp_noise` |
| **Sharing API** | **Denial of Service** | Partner performs differencing attacks to drain privacy budget | Strict $\epsilon$-budget ledger tracking; queries rejected with 429 when budget exhausted | `test_privacy_budget_ledger_exhaustion` |

---

## 3. OWASP API Security Top 10 (2023) Compliance Checklist

| OWASP Risk | Category | FleetPulse Architectural Control | Test Reference |
|---|---|---|---|
| **API1:2023** | Broken Object Level Authorization (BOLA) | Every vehicle and trip query validates tenant ownership against caller's JWT `tenant_id` claim; cross-tenant access returns 403 Forbidden. | `test_cross_tenant_isolation` |
| **API2:2023** | Broken Authentication | Standard OAuth2/OIDC JWT tokens with cryptographic signatures and expiration validation. No anonymous or hardcoded sessions. | `test_rbac_role_enforcement` |
| **API3:2023** | Broken Object Property Level Authorization | Location Masking Matrix (§8.S3): GPS precision automatically redacted based on role (`FLEET_MANAGER`: 6 decimals, `SAFETY_OFFICER`: 3 decimals, `PARTNER`: 2 decimals, `AUDITOR`: none). | `test_location_masking_matrix_by_role` |
| **API4:2023** | Unrestricted Resource Consumption | Per-client token-bucket rate limiter; keyset cursor pagination with maximum page size caps (`limit <= 200`); ClickHouse query timeouts. | `test_privacy_budget_ledger_exhaustion` |
| **API5:2023** | Broken Function Level Authorization | Fine-grained RBAC roles (`PLATFORM_ADMIN`, `TENANT_ADMIN`, `FLEET_MANAGER`, `SAFETY_OFFICER`, `FINANCE_ANALYST`, `AUDITOR`, `PARTNER_RECIPIENT`) enforced via declarative FastAPI dependencies. | `test_rbac_role_enforcement` |
| **API6:2023** | Unrestricted Access to Sensitive Business Flows | Asset Recovery Watchlist and Right-to-Erasure workflows require declared lawful purpose and create cryptographically verifiable audit hash records. | `test_watchlist_asset_recovery_flow`, `test_right_to_erasure_workflow_and_verification` |
| **API7:2023** | Server-Side Request Forgery (SSRF) | Zero user-supplied URLs are fetched by backend microservices. All webhook and external endpoints are strictly whitelisted and static. | Architectural Invariant (No URL fetching endpoints) |
| **API8:2023** | Security Misconfiguration | Minimal distroless and Alpine containers; unprivileged users (`nonroot:10001`); CORS strict origin filtering; security headers (`X-Content-Type-Options: nosniff`, `Content-Security-Policy`). | Dockerfiles and Nginx configs |
| **API9:2023** | Improper Inventory Management | Single-source OpenAPI 3.1.0 contract in `libs/schemas/openapi.yaml`; all routes versioned under `/v1/`; shadow endpoints strictly audited. | `libs/schemas/openapi.yaml` |
| **API10:2023** | Unsafe Consumption of APIs | Inbound OEM telematics payloads validated against compiled declarative schemas before downstream dispatch; corrupted records isolated to DLQ. | `test_normalizer_dlq_on_corrupt` |
