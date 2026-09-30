#!/usr/bin/env bash
# FleetPulse — Kafka topic initialization
# Run once after Kafka is healthy.

set -euo pipefail

KAFKA_BOOTSTRAP="kafka:9092"

create_topic() {
  local topic=$1
  local partitions=$2
  local retention_ms=$3

  echo "Creating topic: $topic (partitions=$partitions, retention=${retention_ms}ms)"
  kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1 \
    --config retention.ms="$retention_ms" \
    --config compression.type=lz4 \
    --config min.insync.replicas=1
}

# Wait for Kafka to be ready
echo "Waiting for Kafka to be ready..."
kafka-broker-api-versions --bootstrap-server "$KAFKA_BOOTSTRAP" > /dev/null 2>&1
echo "Kafka is ready."

# ---------------------------------------------------------------------------
# Core telemetry topics
# ---------------------------------------------------------------------------
create_topic "raw.telemetry"            64   86400000    # 1 day
create_topic "telemetry.canonical.v1"  64   259200000   # 3 days
create_topic "telemetry.clean.v1"      64   604800000   # 7 days
create_topic "telemetry.late.v1"       16   604800000   # 7 days
create_topic "telemetry.dlq"           16   1209600000  # 14 days

# ---------------------------------------------------------------------------
# Domain event topics
# ---------------------------------------------------------------------------
create_topic "events.trip.v1"          16   1209600000  # 14 days
create_topic "events.idle.v1"          16   1209600000
create_topic "events.safety.v1"        16   1209600000
create_topic "events.charging.v1"      16   1209600000
create_topic "events.geofence.v1"      16   1209600000
create_topic "alerts.v1"               16   1209600000

# ---------------------------------------------------------------------------
# Control / admin topics
# ---------------------------------------------------------------------------
kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --create --if-not-exists \
  --topic "config.oem-mappings" \
  --partitions 1 \
  --replication-factor 1 \
  --config cleanup.policy=compact \
  --config retention.ms=-1

create_topic "commands.erasure.v1"  4   2592000000  # 30 days
create_topic "audit.v1"             8   2592000000  # 30 days

echo ""
echo "All Kafka topics created."
kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --list
