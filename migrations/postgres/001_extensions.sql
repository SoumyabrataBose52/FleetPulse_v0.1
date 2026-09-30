-- FleetPulse — PostgreSQL migrations (executed in order)
-- File: 001_extensions.sql

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- App setting used by RLS policies
-- The API sets: SET LOCAL app.tenant_id = '<id>'
-- So pg_catalog.current_setting('app.tenant_id', TRUE) returns the current tenant.
