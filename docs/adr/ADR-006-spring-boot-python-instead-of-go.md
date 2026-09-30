# ADR-006: Spring Boot + Python Instead of Go for Service Implementation

**Date:** 2026-10-01  
**Status:** Accepted

---

## Context

The original plan specified Go for hot-path services (simulator, gateway, normalizer, orderer, stream engine, sinks). The user requested we use Java Spring Boot or Python instead.

## Decision

Replace Go with:
- **Spring Boot 3.x (Java 21)** for the hot-path Kafka consumers and producers: gateway, normalizer, orderer, stream engines, sinks
- **Python 3.12 + FastAPI** for the query API, batch jobs, and simulator (unchanged from plan)

## Justification

**Spring Boot advantages for Kafka services:**
- Mature `spring-kafka` library with `@KafkaListener`, consumer group management, manual offset commit, batch listeners — all production-tested
- Strong JDBC/JPA ecosystem for Postgres (normalizer VIN lookup, sink writes)
- `spring-web` gives `/healthz`, `/readyz`, `/metrics` endpoints for free with Actuator
- Java 21 virtual threads (`spring.threads.virtual.enabled=true`) give Go-like concurrency without goroutine complexity
- Excellent mTLS support via Spring Boot SSL bundles
- Strong type system with Avro + code generation

**What we keep identical (contracts unchanged):**
- All Kafka topic names, partition counts, retention settings (ADR-001)
- All Avro schemas (canonical event, domain events, alerts)
- All OpenAPI endpoints
- All PostgreSQL/ClickHouse/Redis/MongoDB schemas
- All algorithm specifications (implemented in Python `fpcore` library)

**Performance expectation:**
- Spring Boot with virtual threads can sustain 50K–150K Kafka messages/s per pod on modern JVM
- This meets the 100K events/s target when scaled horizontally (same sizing rule as Go)
- JVM warm-up time (~5s) is acceptable for a stateful service; use `startupProbe` in Kubernetes

## Consequences

- **Positive:** Strong ecosystem, well-tested Kafka client, excellent observability via Micrometer/Prometheus, easier for Java engineers, mTLS built-in.
- **Negative:** Higher baseline memory (~256–512 MB JVM heap vs ~50 MB Go binary). Larger Docker images. JVM GC pauses (mitigated with ZGC or G1 tuning + Java 21 virtual threads).
- **Mitigation:** Use `distroless/java21` base image for small images. Set `JAVA_TOOL_OPTIONS=-XX:+UseZGC -Xmx512m`. Use GraalVM native image for the gateway if startup time matters.
