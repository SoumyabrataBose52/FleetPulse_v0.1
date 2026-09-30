-- FleetPulse — PostgreSQL Indexes
-- File: 003_indexes.sql
-- All indexes chosen based on the query patterns in §12.3 of the plan.

-- =============================================================================
-- VEHICLE lookups
-- =============================================================================
CREATE INDEX idx_vehicle_fleet    ON vehicle(fleet_id);
CREATE INDEX idx_vehicle_tenant   ON vehicle(tenant_id);
CREATE INDEX idx_vehicle_vin      ON vehicle(vin);  -- normalizer VIN lookup

-- =============================================================================
-- TRIP queries (Q1, Q3, Q4 from §12.3)
-- Q1: trips of a vehicle in date range, paged → keyset pagination
-- =============================================================================
CREATE INDEX idx_trip_vehicle_start ON trip(vehicle_pid, start_ts DESC, trip_id DESC);
CREATE INDEX idx_trip_tenant_start  ON trip(tenant_id, start_ts DESC);
CREATE INDEX idx_trip_driver        ON trip(driver_id) WHERE driver_id IS NOT NULL;

-- =============================================================================
-- ALERT queries (Q2 from §12.3)
-- Partial index on OPEN alerts (most queries filter by status='OPEN')
-- =============================================================================
CREATE INDEX idx_alert_open ON alert(tenant_id, severity, opened_at DESC)
  WHERE status = 'OPEN';
CREATE INDEX idx_alert_vehicle ON alert(vehicle_pid, opened_at DESC);

-- =============================================================================
-- GEOFENCE spatial (Q5 from §12.3)
-- =============================================================================
CREATE INDEX idx_geofence_boundary ON geofence USING GIST(boundary);
CREATE INDEX idx_geofence_fleet    ON geofence(fleet_id);
CREATE INDEX idx_depot_boundary    ON depot USING GIST(boundary);

-- =============================================================================
-- CHARGER spatial
-- =============================================================================
CREATE INDEX idx_charger_location ON charger USING GIST(location);

-- =============================================================================
-- AUDIT (Q6 from §12.3)
-- BRIN on ts for sequential append workload + B-tree for filtered lookups
-- =============================================================================
CREATE INDEX idx_audit_ts_brin     ON audit_log USING BRIN(ts);
CREATE INDEX idx_audit_actor_ts    ON audit_log(tenant_id, actor_id, ts DESC);
CREATE INDEX idx_audit_resource    ON audit_log(resource_type, resource_id, ts DESC);

-- =============================================================================
-- OEM MAPPING (normalizer hot-path)
-- =============================================================================
CREATE INDEX idx_oem_mapping_active ON oem_mapping(oem_id, schema_ver)
  WHERE status = 'ACTIVE';

-- =============================================================================
-- DRIVER ASSIGNMENT (normalizer: who is driving right now)
-- =============================================================================
CREATE INDEX idx_driver_assign_vehicle ON vehicle_driver_assignment(vehicle_pid, valid_from DESC);

-- =============================================================================
-- PRIVACY / SHARING
-- =============================================================================
CREATE INDEX idx_dsa_tenant_recipient ON data_sharing_agreement(tenant_id, recipient_id);
CREATE INDEX idx_consent_subject      ON consent(subject_type, subject_id);
CREATE INDEX idx_erasure_status       ON erasure_request(status, requested_at);
