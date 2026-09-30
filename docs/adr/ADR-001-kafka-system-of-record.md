# ADR-001: Kafka as Durable System-of-Record Log

**Date:** 2026-10-01  
**Status:** Accepted  
**Deciders:** FleetPulse team

---

## Context

FleetPulse ingests telemetry from 100,000 vehicles at ~1 event/s per vehicle (100,000 events/s total), with 3× burst capability. We need a durable, replayable stream that:
- Never loses data even when downstream consumers (ClickHouse, Postgres, Redis) are slow or restarting
- Allows multiple independent consumers (live state updater, orderer, telemetry sink, archiver) to read the same stream at their own pace
- Supports replay for bug fixes, new features, and schema migrations
- Partitions by vehicle ID so per-vehicle ordering is guaranteed within a partition

## Decision

Use **Apache Kafka** (KRaft mode, no ZooKeeper) as the central durable log for all telemetry and domain events.

Key settings:
- **Partition key:** `vehicle_pid` (canonical events onward). Raw events use device/VIN string.
- **Partitions:** 64 for high-throughput topics; 16 for event/alert topics; 1 for compacted config topics.
- **Retention:** 7 days for clean telemetry; 14 days for DLQ/events; 30 days for audit/erasure.
- **Replication:** Factor 1 locally (single broker); Factor 3 in production (min ISR = 2).
- **Producer settings:** `acks=all`, idempotent producers, `compression.type=lz4`.
- **`unclean.leader.election.enable=false`** in production to prevent data loss on leader failover.
- **Partitioning rule:** 64 partitions allow up to 64 parallel consumers per group. A tenant with many vehicles still spreads because the key is the vehicle, not the tenant.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| RabbitMQ | No built-in replay, limited partition model, not designed for 100K events/s |
| Pulsar | Higher operational complexity; Kafka API compatibility layer adds overhead |
| Redis Streams | Good for pub/sub but no durable multi-consumer replay at this scale |
| Direct database writes | Cannot buffer bursts; creates tight coupling; no replay |

## Consequences

- **Positive:** Durable replay enables rebuilding any downstream store from scratch. Consumer lag is observable. Multiple independent consumers can read the same data. Burst absorption is natural.
- **Negative:** Adds operational complexity (Kafka cluster management). Adds ~10-30ms latency vs direct writes. Consumer offset management must be done carefully.
- **Mitigation:** KRaft mode eliminates ZooKeeper. Confluent Platform images provide good defaults. Strimzi operator handles Kubernetes lifecycle.
