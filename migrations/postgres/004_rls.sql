-- FleetPulse — PostgreSQL Row-Level Security
-- File: 004_rls.sql

-- =============================================================================
-- Application roles
-- =============================================================================

-- api_user: the FastAPI service connects with this role
-- It must SET LOCAL app.tenant_id = '<id>' before any tenant-scoped query
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'api_user') THEN
    CREATE ROLE api_user LOGIN PASSWORD 'api_user_dev';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharing_reader') THEN
    CREATE ROLE sharing_reader LOGIN PASSWORD 'sharing_reader_dev';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE fleetpulse TO api_user, sharing_reader;
GRANT USAGE ON SCHEMA public TO api_user, sharing_reader;

-- Grant select on all current and future tables
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO api_user;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sharing_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO api_user;

-- =============================================================================
-- Enable RLS on all tenant-scoped tables
-- =============================================================================

ALTER TABLE fleet                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle                ENABLE ROW LEVEL SECURITY;
ALTER TABLE driver                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user               ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription           ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_daily            ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice                ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE trip                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_sharing_agreement ENABLE ROW LEVEL SECURITY;
ALTER TABLE privacy_budget_ledger  ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent                ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure_request        ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log              ENABLE ROW LEVEL SECURITY;

-- =============================================================================
-- RLS policies (tenant isolation)
-- current_setting('app.tenant_id', TRUE) returns NULL if not set (missing_ok=TRUE)
-- If NULL, no rows are returned — safe default.
-- =============================================================================

CREATE POLICY tenant_isolation ON fleet
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON vehicle
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON driver
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON app_user
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON subscription
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON usage_daily
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON invoice
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON alert
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON trip
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

CREATE POLICY tenant_isolation ON data_sharing_agreement
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT);

-- audit_log: a tenant can only see their own audit records; auditors see all
CREATE POLICY tenant_isolation ON audit_log
  USING (
    tenant_id IS NULL  -- system events visible to all
    OR tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::INT
  );

-- PLATFORM_ADMIN bypasses RLS (set via SET SESSION AUTHORIZATION or separate role)
-- The api_user role must NOT have BYPASSRLS to enforce this everywhere.
