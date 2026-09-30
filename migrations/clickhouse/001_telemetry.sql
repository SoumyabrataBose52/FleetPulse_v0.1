-- FleetPulse — ClickHouse DDL
-- File: 001_telemetry.sql
-- Executed via docker-entrypoint-initdb.d

-- =============================================================================
-- Main telemetry table (hot tier, 7-day TTL → warm)
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.telemetry
(
    tenant_id    UInt32,
    vehicle_pid  UUID,
    ts           DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD),
    event_id     UInt64              CODEC(Delta, ZSTD),
    seq          Nullable(UInt64),
    lat_e6       Nullable(Int32)     CODEC(Delta, ZSTD),
    lon_e6       Nullable(Int32)     CODEC(Delta, ZSTD),
    heading_deg  Nullable(UInt16),
    speed_kmh    Nullable(Float32),
    odo_km       Nullable(Float64)   CODEC(Delta, ZSTD),
    ignition     Nullable(UInt8),
    fuel_pct     Nullable(Float32),
    soc_pct      Nullable(Float32),
    batt_voltage_v Nullable(Float32),
    charge_state LowCardinality(Nullable(String)),
    charge_kw    Nullable(Float32),
    coolant_c    Nullable(Float32),
    rpm          Nullable(UInt32),
    dtc          Array(LowCardinality(String)),
    evt          LowCardinality(Nullable(String)),
    quality      UInt8               DEFAULT 0,
    ingest_ts    DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree
PARTITION BY toDate(ts)
ORDER BY (tenant_id, vehicle_pid, ts, event_id)
TTL toDateTime(ts) + INTERVAL 7 DAY TO VOLUME 'warm',
    toDateTime(ts) + INTERVAL 90 DAY DELETE
SETTINGS storage_policy = 'default';
-- Note: In production, change storage_policy to 'hot_warm_cold' once storage volumes are configured.

-- =============================================================================
-- 10-second downsampled telemetry (warm tier materialized view)
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.telemetry_10s
(
    tenant_id      UInt32,
    vehicle_pid    UUID,
    ts_bucket      DateTime('UTC'),       -- floor to 10s
    avg_speed_kmh  AggregateFunction(avg, Nullable(Float32)),
    max_speed_kmh  AggregateFunction(max, Nullable(Float32)),
    last_lat_e6    AggregateFunction(argMax, Nullable(Int32), DateTime64(3,'UTC')),
    last_lon_e6    AggregateFunction(argMax, Nullable(Int32), DateTime64(3,'UTC')),
    min_soc_pct    AggregateFunction(min, Nullable(Float32))
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(ts_bucket)
ORDER BY (tenant_id, vehicle_pid, ts_bucket);

CREATE MATERIALIZED VIEW IF NOT EXISTS fleetpulse.telemetry_10s_mv
TO fleetpulse.telemetry_10s
AS SELECT
    tenant_id,
    vehicle_pid,
    toStartOfInterval(ts, INTERVAL 10 SECOND) AS ts_bucket,
    avgState(speed_kmh)         AS avg_speed_kmh,
    maxState(speed_kmh)         AS max_speed_kmh,
    argMaxState(lat_e6, ts)     AS last_lat_e6,
    argMaxState(lon_e6, ts)     AS last_lon_e6,
    minState(soc_pct)           AS min_soc_pct
FROM fleetpulse.telemetry
GROUP BY tenant_id, vehicle_pid, ts_bucket;

-- =============================================================================
-- Per-vehicle per-minute summary (feeds cost analytics)
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.vehicle_minute
(
    tenant_id       UInt32,
    vehicle_pid     UUID,
    minute_ts       DateTime('UTC'),
    distance_km     Float64,
    engine_on_s     UInt32,
    idle_s          UInt32,
    energy_kwh      Float64,
    fuel_l          Float64,
    co2_kg          Float64
)
ENGINE = SummingMergeTree((distance_km, engine_on_s, idle_s, energy_kwh, fuel_l, co2_kg))
PARTITION BY toDate(minute_ts)
ORDER BY (tenant_id, vehicle_pid, minute_ts);

-- =============================================================================
-- Trip facts (mirror of Postgres trip for analytics)
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.trip_fact
(
    trip_id          UUID,
    vehicle_pid      UUID,
    tenant_id        UInt32,
    driver_id        Nullable(UUID),
    start_ts         DateTime64(3, 'UTC'),
    end_ts           DateTime64(3, 'UTC'),
    start_geohash7   LowCardinality(String),
    end_geohash7     LowCardinality(String),
    distance_km      Float64,
    duration_s       UInt32,
    idle_s           UInt32,
    energy_kwh       Float64,
    fuel_l           Float64,
    cost             Float64
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(start_ts)
ORDER BY (tenant_id, vehicle_pid, start_ts, trip_id);

-- =============================================================================
-- Idle episodes
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.idle_episode
(
    episode_id       UUID,
    vehicle_pid      UUID,
    tenant_id        UInt32,
    trip_id          Nullable(UUID),
    start_ts         DateTime64(3, 'UTC'),
    end_ts           DateTime64(3, 'UTC'),
    duration_s       UInt32,
    geohash7         LowCardinality(String),
    fuel_burn_l      Float64,
    cost             Float64,
    co2_kg           Float64,
    avoidable_cost   Float64,
    powertrain       LowCardinality(String)
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(start_ts)
ORDER BY (tenant_id, vehicle_pid, start_ts, episode_id);

-- =============================================================================
-- Charging sessions
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.charging_session
(
    session_id       UUID,
    vehicle_pid      UUID,
    tenant_id        UInt32,
    charger_id       Nullable(UUID),
    plug_in_ts       DateTime64(3, 'UTC'),
    plug_out_ts      DateTime64(3, 'UTC'),
    soc_start_pct    Float32,
    soc_end_pct      Float32,
    energy_kwh       Float64,
    avg_kw           Float32,
    price_paid       Float64,
    baseline_cost    Float64,
    smart_cost       Float64,
    soh_estimate     Nullable(Float32)
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(plug_in_ts)
ORDER BY (tenant_id, vehicle_pid, plug_in_ts, session_id);

-- =============================================================================
-- Safety events
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.safety_event
(
    event_id         UInt64,
    vehicle_pid      UUID,
    tenant_id        UInt32,
    driver_id        Nullable(UUID),
    ts               DateTime64(3, 'UTC'),
    event_type       LowCardinality(String),
    accel_ms2        Float32,
    speed_kmh        Float32,
    speed_limit_kmh  Nullable(Float32),
    weight           Float32
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, vehicle_pid, ts, event_id);

-- =============================================================================
-- Daily driver scores
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.driver_score_daily
(
    driver_id        UUID,
    tenant_id        UInt32,
    day              Date,
    score            Float32,
    harsh_brake_w    Float64,
    harsh_accel_w    Float64,
    harsh_corner_w   Float64,
    overspeed_w      Float64,
    total_km         Float64,
    event_count      UInt32
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (tenant_id, driver_id, day);

-- =============================================================================
-- Geofence events
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.geofence_event
(
    event_id      UUID,
    vehicle_pid   UUID,
    tenant_id     UInt32,
    geofence_id   UUID,
    event_type    LowCardinality(String),  -- ENTER, EXIT, BREACH
    ts            DateTime64(3, 'UTC'),
    lat_e6        Nullable(Int32),
    lon_e6        Nullable(Int32)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, vehicle_pid, ts, event_id);

-- =============================================================================
-- Daily cost rollup (built by batch job)
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.cost_daily
(
    tenant_id     UInt32,
    fleet_id      UUID,
    vehicle_pid   UUID,
    day           Date,
    energy_cost   Float64,
    idle_cost     Float64,
    charging_cost Float64,
    total_cost    Float64,
    distance_km   Float64,
    cost_per_km   Float64,
    co2_kg        Float64,
    utilisation   Float32,
    avoidable_cost Float64
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (tenant_id, vehicle_pid, day);

-- =============================================================================
-- Simulator ledger (test/validation only)
-- =============================================================================
CREATE TABLE IF NOT EXISTS fleetpulse.sim_ledger
(
    vehicle_pid  UUID,
    seq          UInt64,
    event_id     UInt64,
    ts           DateTime64(3, 'UTC'),
    oem          LowCardinality(String)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (vehicle_pid, seq);
