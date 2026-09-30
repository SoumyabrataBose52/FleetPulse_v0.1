-- FleetPulse — PostgreSQL Core Schema (3NF)
-- File: 002_core_schema.sql

-- =============================================================================
-- ENUM TYPES
-- =============================================================================

CREATE TYPE powertrain_type AS ENUM ('ICE_PETROL', 'ICE_DIESEL', 'HYBRID', 'EV');
CREATE TYPE body_type AS ENUM ('SEDAN', 'VAN', 'TRUCK', 'BUS');
CREATE TYPE vehicle_status AS ENUM ('ACTIVE', 'INACTIVE', 'ERASED');
CREATE TYPE subscription_status AS ENUM ('ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED');
CREATE TYPE mapping_status AS ENUM ('DRAFT', 'SHADOW', 'ACTIVE', 'RETIRED');
CREATE TYPE alert_status AS ENUM ('OPEN', 'ACK', 'RESOLVED');
CREATE TYPE alert_severity AS ENUM ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO');
CREATE TYPE geofence_type AS ENUM ('DEPOT', 'ALLOWED_ZONE', 'RESTRICTED');
CREATE TYPE erasure_status AS ENUM ('REQUESTED', 'APPROVED', 'EXECUTING', 'VERIFYING', 'COMPLETED', 'FAILED');

-- =============================================================================
-- TENANT & BILLING
-- =============================================================================

CREATE TABLE tenant (
  tenant_id     SERIAL PRIMARY KEY,
  name          VARCHAR(255) NOT NULL,
  archetype     VARCHAR(50),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE plan (
  plan_id                    SERIAL PRIMARY KEY,
  name                       VARCHAR(100) NOT NULL,
  max_vehicles               INT NOT NULL DEFAULT 1000,
  price_per_vehicle_month    NUMERIC(10,4) NOT NULL DEFAULT 0
);

CREATE TABLE subscription (
  subscription_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id         INT  NOT NULL REFERENCES tenant(tenant_id),
  plan_id           INT  NOT NULL REFERENCES plan(plan_id),
  status            subscription_status NOT NULL DEFAULT 'ACTIVE',
  period_start      TIMESTAMPTZ NOT NULL,
  period_end        TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE usage_daily (
  tenant_id        INT  NOT NULL REFERENCES tenant(tenant_id),
  day              DATE NOT NULL,
  active_vehicles  INT  NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, day)
);

CREATE TABLE invoice (
  invoice_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id        INT  NOT NULL REFERENCES tenant(tenant_id),
  period_start     DATE NOT NULL,
  period_end       DATE NOT NULL,
  amount           NUMERIC(12,2) NOT NULL,
  idempotency_key  VARCHAR(128) UNIQUE NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- USERS & RBAC
-- =============================================================================

CREATE TABLE app_user (
  user_id      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id    INT NOT NULL REFERENCES tenant(tenant_id),
  idp_subject  VARCHAR(255) UNIQUE NOT NULL,  -- Keycloak sub claim
  email        VARCHAR(255) NOT NULL,
  display_name VARCHAR(255),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  erased_at    TIMESTAMPTZ
);

CREATE TABLE role (
  role_id   SERIAL PRIMARY KEY,
  name      VARCHAR(50) UNIQUE NOT NULL
  -- PLATFORM_ADMIN, TENANT_ADMIN, FLEET_MANAGER, FINANCE_ANALYST, SAFETY_OFFICER, AUDITOR, PARTNER_RECIPIENT
);

INSERT INTO role(name) VALUES
  ('PLATFORM_ADMIN'), ('TENANT_ADMIN'), ('FLEET_MANAGER'),
  ('FINANCE_ANALYST'), ('SAFETY_OFFICER'), ('AUDITOR'), ('PARTNER_RECIPIENT');

CREATE TABLE user_role (
  user_id   UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  role_id   INT  NOT NULL REFERENCES role(role_id),
  PRIMARY KEY (user_id, role_id)
);

-- =============================================================================
-- OEM & VEHICLE MODEL
-- =============================================================================

CREATE TABLE oem (
  oem_id   SERIAL PRIMARY KEY,
  code     VARCHAR(10) UNIQUE NOT NULL,  -- A, B, C, D, E
  name     VARCHAR(100) NOT NULL
);

INSERT INTO oem(code, name) VALUES
  ('A', 'Astra'), ('B', 'Borealis'), ('C', 'Cetus'), ('D', 'Draco'), ('E', 'Echo');

CREATE TABLE oem_mapping (
  mapping_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  oem_id       INT NOT NULL REFERENCES oem(oem_id),
  schema_ver   INT NOT NULL DEFAULT 1,
  version      INT NOT NULL DEFAULT 1,
  status       mapping_status NOT NULL DEFAULT 'DRAFT',
  config       JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  activated_at TIMESTAMPTZ,
  UNIQUE (oem_id, schema_ver, version)
);

CREATE TABLE vehicle_model (
  model_id         SERIAL PRIMARY KEY,
  oem_id           INT NOT NULL REFERENCES oem(oem_id),
  name             VARCHAR(100) NOT NULL,
  powertrain       powertrain_type NOT NULL,
  body             body_type NOT NULL,
  battery_kwh      NUMERIC(8,2),    -- EV / hybrid
  tank_l           NUMERIC(8,2),    -- ICE / hybrid
  idle_burn_lph    NUMERIC(6,3),    -- ICE idle fuel burn L/h
  energy_scale     NUMERIC(4,2) NOT NULL DEFAULT 1.0,
  mass_kg          INT NOT NULL,
  cda              NUMERIC(6,3) NOT NULL,
  max_dc_kw        NUMERIC(8,2),
  max_ac_kw        NUMERIC(8,2),
  aux_kw_base      NUMERIC(6,2) NOT NULL DEFAULT 0.8
);

-- =============================================================================
-- FLEET, VEHICLE, DRIVER
-- =============================================================================

CREATE TABLE fleet (
  fleet_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id   INT NOT NULL REFERENCES tenant(tenant_id),
  name        VARCHAR(255) NOT NULL
);

CREATE TABLE vehicle (
  vehicle_pid  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  vin          VARCHAR(17) UNIQUE NOT NULL,
  fleet_id     UUID NOT NULL REFERENCES fleet(fleet_id),
  tenant_id    INT  NOT NULL REFERENCES tenant(tenant_id),
  model_id     INT  NOT NULL REFERENCES vehicle_model(model_id),
  model_year   SMALLINT NOT NULL,
  status       vehicle_status NOT NULL DEFAULT 'ACTIVE',
  erased_at    TIMESTAMPTZ
  -- NOTE: soh_true is intentionally NOT stored here (simulator ground-truth only)
);

CREATE TABLE driver (
  driver_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id    INT NOT NULL REFERENCES tenant(tenant_id),
  display_name VARCHAR(255) NOT NULL,  -- synthetic name
  erased_at    TIMESTAMPTZ
);

CREATE TABLE vehicle_driver_assignment (
  vehicle_pid   UUID NOT NULL REFERENCES vehicle(vehicle_pid),
  driver_id     UUID NOT NULL REFERENCES driver(driver_id),
  valid_from    TIMESTAMPTZ NOT NULL,
  valid_to      TIMESTAMPTZ,
  PRIMARY KEY (vehicle_pid, valid_from)
);

-- =============================================================================
-- DEPOTS, GEOFENCES, CHARGERS, TARIFFS
-- =============================================================================

CREATE TABLE depot (
  depot_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  fleet_id          UUID NOT NULL REFERENCES fleet(fleet_id),
  name              VARCHAR(255) NOT NULL,
  boundary          GEOGRAPHY(Polygon, 4326) NOT NULL,
  site_power_cap_kw NUMERIC(10,2)
);

CREATE TABLE geofence (
  geofence_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  fleet_id      UUID NOT NULL REFERENCES fleet(fleet_id),
  type          geofence_type NOT NULL,
  name          VARCHAR(255),
  boundary      GEOGRAPHY(Polygon, 4326) NOT NULL
);

CREATE TABLE geofence_schedule (
  geofence_id  UUID NOT NULL REFERENCES geofence(geofence_id) ON DELETE CASCADE,
  dow          SMALLINT NOT NULL CHECK (dow BETWEEN 0 AND 6),  -- 0=Sun
  start_min    SMALLINT NOT NULL,
  end_min      SMALLINT NOT NULL,
  PRIMARY KEY (geofence_id, dow, start_min)
);

CREATE TABLE tariff (
  tariff_id  SERIAL PRIMARY KEY,
  name       VARCHAR(100) NOT NULL
);

CREATE TABLE tariff_period (
  tariff_id     INT NOT NULL REFERENCES tariff(tariff_id),
  dow_mask      SMALLINT NOT NULL DEFAULT 127,  -- bitmask Sun=1..Sat=64
  start_min     SMALLINT NOT NULL,
  end_min       SMALLINT NOT NULL,
  price_per_kwh NUMERIC(8,4) NOT NULL,
  PRIMARY KEY (tariff_id, dow_mask, start_min)
);

CREATE TABLE charger (
  charger_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  network      VARCHAR(100),
  location     GEOGRAPHY(Point, 4326) NOT NULL,
  power_kw     NUMERIC(8,2) NOT NULL,
  connector    VARCHAR(50),
  tariff_id    INT REFERENCES tariff(tariff_id),
  depot_id     UUID REFERENCES depot(depot_id)
);

CREATE TABLE fuel_price (
  fuel_type   VARCHAR(20) NOT NULL,
  valid_from  TIMESTAMPTZ NOT NULL,
  price_per_l NUMERIC(8,4) NOT NULL,
  PRIMARY KEY (fuel_type, valid_from)
);

CREATE TABLE dtc_catalog (
  code        VARCHAR(10) PRIMARY KEY,
  system      VARCHAR(50),
  description TEXT,
  severity    alert_severity NOT NULL DEFAULT 'MEDIUM'
);

-- =============================================================================
-- TRIPS & ALERTS
-- =============================================================================

CREATE TABLE trip (
  trip_id          UUID NOT NULL,
  vehicle_pid      UUID NOT NULL REFERENCES vehicle(vehicle_pid),
  tenant_id        INT  NOT NULL REFERENCES tenant(tenant_id),
  driver_id        UUID REFERENCES driver(driver_id),
  start_ts         TIMESTAMPTZ NOT NULL,
  end_ts           TIMESTAMPTZ NOT NULL,
  start_geohash7   VARCHAR(7),
  end_geohash7     VARCHAR(7),
  distance_km      NUMERIC(10,3),
  duration_s       INT,
  idle_s           INT NOT NULL DEFAULT 0,
  energy_kwh       NUMERIC(10,3),
  fuel_l           NUMERIC(10,3),
  cost             NUMERIC(12,4),
  PRIMARY KEY (trip_id, start_ts)
) PARTITION BY RANGE (start_ts);

-- Monthly partitions will be created dynamically or via seed
CREATE TABLE trip_2026_10 PARTITION OF trip
  FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE trip_2026_11 PARTITION OF trip
  FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE trip_2026_12 PARTITION OF trip
  FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE trip_2027_01 PARTITION OF trip
  FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

CREATE TABLE alert (
  alert_id     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  alert_key    VARCHAR(128) UNIQUE NOT NULL,  -- deterministic idempotency key
  tenant_id    INT NOT NULL REFERENCES tenant(tenant_id),
  vehicle_pid  UUID NOT NULL REFERENCES vehicle(vehicle_pid),
  type         VARCHAR(50) NOT NULL,
  severity     alert_severity NOT NULL,
  status       alert_status NOT NULL DEFAULT 'OPEN',
  assignee     UUID REFERENCES app_user(user_id),
  opened_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  closed_at    TIMESTAMPTZ,
  insight_ref  VARCHAR(50)  -- MongoDB ObjectId
);

-- =============================================================================
-- PRIVACY & SHARING
-- =============================================================================

CREATE TABLE recipient (
  recipient_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name            VARCHAR(255) NOT NULL,
  oauth_client_id VARCHAR(255) UNIQUE NOT NULL,
  hmac_key_ref    VARCHAR(255)  -- Vault key reference
);

CREATE TABLE data_sharing_agreement (
  dsa_id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id         INT  NOT NULL REFERENCES tenant(tenant_id),
  recipient_id      UUID NOT NULL REFERENCES recipient(recipient_id),
  purpose           VARCHAR(255) NOT NULL,
  allowed_products  TEXT[] NOT NULL DEFAULT '{}',
  geohash_precision SMALLINT NOT NULL DEFAULT 5,
  k_min             INT NOT NULL DEFAULT 10,
  epsilon_daily     NUMERIC(8,4) NOT NULL DEFAULT 5.0,
  valid_from        DATE NOT NULL,
  valid_to          DATE NOT NULL
);

CREATE TABLE privacy_budget_ledger (
  dsa_id        UUID NOT NULL REFERENCES data_sharing_agreement(dsa_id),
  day           DATE NOT NULL,
  epsilon_spent NUMERIC(10,6) NOT NULL DEFAULT 0,
  PRIMARY KEY (dsa_id, day)
);

CREATE TABLE consent (
  consent_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  subject_type  VARCHAR(50) NOT NULL,
  subject_id    UUID NOT NULL,
  purpose       VARCHAR(255) NOT NULL,
  granted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  revoked_at    TIMESTAMPTZ
);

CREATE TABLE erasure_request (
  request_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  subject_type        VARCHAR(50) NOT NULL,
  subject_id          UUID NOT NULL,
  status              erasure_status NOT NULL DEFAULT 'REQUESTED',
  requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at        TIMESTAMPTZ,
  verification_report JSONB
);

-- =============================================================================
-- AUDIT (append-only hash chain)
-- =============================================================================

CREATE TABLE audit_log (
  audit_id       UUID NOT NULL,
  ts             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  actor_id       UUID,
  actor_type     VARCHAR(50),
  tenant_id      INT,
  action         VARCHAR(100) NOT NULL,
  resource_type  VARCHAR(100),
  resource_id    TEXT,
  purpose        VARCHAR(255),
  ip             INET,
  prev_hash      CHAR(64),   -- SHA-256 hex of previous row
  row_hash       CHAR(64),   -- SHA-256 hex of this row's canonical JSON
  PRIMARY KEY (audit_id, ts)
) PARTITION BY RANGE (ts);

CREATE TABLE audit_log_2026_10 PARTITION OF audit_log
  FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE audit_log_2026_11 PARTITION OF audit_log
  FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE audit_log_2026_12 PARTITION OF audit_log
  FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE audit_log_2027_01 PARTITION OF audit_log
  FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- Prevent UPDATE and DELETE on audit_log (append-only guarantee)
CREATE RULE audit_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;
