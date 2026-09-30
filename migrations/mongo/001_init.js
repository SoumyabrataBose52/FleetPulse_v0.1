/**
 * FleetPulse MongoDB Initialisation Script (§5.5)
 * Configures collections, compound indexes, and TTL expiration for:
 *   1. insights  — Polymorphic insight evidence documents (TTL: 180 days)
 *   2. quarantine — Malformed / schema-violation DLQ payloads (TTL: 14 days)
 */

db = db.getSiblingDB("fleetpulse");

// 1. Insights collection (§5.5)
db.createCollection("insights");

// Compound index for tenant-scoped time-series queries
db.insights.createIndex(
  { tenant_id: 1, ts: -1 },
  { name: "idx_insights_tenant_ts" }
);

// Compound index for filtering by insight type (e.g. IDLING_EXCESS, RANGE_RISK)
db.insights.createIndex(
  { tenant_id: 1, type: 1, ts: -1 },
  { name: "idx_insights_tenant_type_ts" }
);

// Per-vehicle insight timeline
db.insights.createIndex(
  { vehicle_pid: 1, ts: -1 },
  { name: "idx_insights_vehicle_ts" }
);

// TTL index: expire documents older than 180 days (180 * 86400 = 15,552,000 s)
db.insights.createIndex(
  { ts: 1 },
  { expireAfterSeconds: 15552000, name: "ttl_insights_180d" }
);

// 2. Quarantine collection (§5.5)
db.createCollection("quarantine");

// Compound index for error analysis and troubleshooting
db.quarantine.createIndex(
  { error_code: 1, ts: -1 },
  { name: "idx_quarantine_error_ts" }
);

// Source OEM index for DLQ inspection
db.quarantine.createIndex(
  { oem: 1, ts: -1 },
  { name: "idx_quarantine_oem_ts" }
);

// TTL index: expire quarantined payloads older than 14 days (14 * 86400 = 1,209,600 s)
db.quarantine.createIndex(
  { ts: 1 },
  { expireAfterSeconds: 1209600, name: "ttl_quarantine_14d" }
);

print("FleetPulse MongoDB collections and indexes initialized successfully.");
