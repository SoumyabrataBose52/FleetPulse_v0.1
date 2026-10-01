-- FleetPulse — PostgreSQL Seed Initialization
-- File: 005_seed.sql

-- 1. Default Tenant (ensures FKs succeed even before CSV bulk load)
INSERT INTO tenant (tenant_id, name, archetype) VALUES
  (1, 'Enterprise Fleet 01 (LAST_MILE_DELIVERY)', 'LAST_MILE_DELIVERY')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO fleet (fleet_id, tenant_id, name) VALUES
  (1, 1, 'Fleet-01-Metro')
ON CONFLICT (fleet_id) DO NOTHING;

-- 2. Default Plans
INSERT INTO plan (plan_id, name, max_vehicles, price_per_vehicle_month) VALUES
  (1, 'Starter Fleet', 500, 10.00),
  (2, 'Growth Fleet', 2500, 8.50),
  (3, 'Enterprise Fleet', 50000, 6.00)
ON CONFLICT (plan_id) DO NOTHING;

-- 3. Default Roles and Users
INSERT INTO app_user (user_id, tenant_id, idp_subject, email, display_name) VALUES
  ('a0000000-0000-0000-0000-000000000001', 1, 'dev-admin', 'admin@fleetpulse.local', 'Platform Admin'),
  ('a0000000-0000-0000-0000-000000000002', 1, 'operator-01', 'operator@fleetpulse.local', 'Fleet Manager')
ON CONFLICT (idp_subject) DO NOTHING;

INSERT INTO user_role (user_id, role_id) VALUES
  ('a0000000-0000-0000-0000-000000000001', 1), -- PLATFORM_ADMIN
  ('a0000000-0000-0000-0000-000000000002', 3)  -- FLEET_MANAGER
ON CONFLICT DO NOTHING;

-- 4. Default Tariffs
INSERT INTO tariff (tariff_id, name) VALUES
  (1, 'Time-of-Use Standard')
ON CONFLICT (tariff_id) DO NOTHING;

INSERT INTO tariff_period (tariff_id, dow_mask, start_min, end_min, price_per_kwh) VALUES
  (1, 127, 0, 360, 0.08),     -- 00:00 - 06:00 Off-peak: $0.08
  (1, 127, 360, 1080, 0.14),   -- 06:00 - 18:00 Standard: $0.14
  (1, 127, 1080, 1320, 0.22),  -- 18:00 - 22:00 Peak: $0.22
  (1, 127, 1320, 1440, 0.08)   -- 22:00 - 24:00 Off-peak: $0.08
ON CONFLICT DO NOTHING;

-- 5. Bulk Ingest Seed CSVs if mounted
DO $$
BEGIN
  BEGIN
    -- Temporary table to stage tenants without conflict
    CREATE TEMP TABLE tmp_tenant (tenant_id INT, name VARCHAR(255), archetype VARCHAR(50));
    COPY tmp_tenant FROM '/data/seed/tenants.csv' WITH (FORMAT csv, HEADER true);
    INSERT INTO tenant (tenant_id, name, archetype)
      SELECT tenant_id, name, archetype FROM tmp_tenant
      ON CONFLICT (tenant_id) DO UPDATE SET name = EXCLUDED.name, archetype = EXCLUDED.archetype;
    DROP TABLE tmp_tenant;

    CREATE TEMP TABLE tmp_fleet (fleet_id INT, tenant_id INT, name VARCHAR(255));
    COPY tmp_fleet FROM '/data/seed/fleets.csv' WITH (FORMAT csv, HEADER true);
    INSERT INTO fleet (fleet_id, tenant_id, name)
      SELECT fleet_id, tenant_id, name FROM tmp_fleet
      ON CONFLICT (fleet_id) DO UPDATE SET name = EXCLUDED.name;
    DROP TABLE tmp_fleet;

    COPY vehicle_model(model_id, oem_id, name, powertrain, body, battery_kwh, tank_l, idle_burn_lph, mass_kg, cda, max_dc_kw, max_ac_kw) 
      FROM '/data/seed/vehicle_models.csv' 
      WITH (FORMAT csv, HEADER true);

    COPY driver(driver_id, tenant_id, display_name) 
      FROM '/data/seed/drivers.csv' 
      WITH (FORMAT csv, HEADER true);

    COPY vehicle(vehicle_pid, vin, fleet_id, tenant_id, model_id, model_year, status) 
      FROM '/data/seed/vehicles.csv' 
      WITH (FORMAT csv, HEADER true);
      
    PERFORM setval('tenant_tenant_id_seq', COALESCE((SELECT MAX(tenant_id) FROM tenant), 1));
    PERFORM setval('fleet_fleet_id_seq', COALESCE((SELECT MAX(fleet_id) FROM fleet), 1));
    PERFORM setval('driver_driver_id_seq', COALESCE((SELECT MAX(driver_id) FROM driver), 1));
    PERFORM setval('vehicle_model_model_id_seq', COALESCE((SELECT MAX(model_id) FROM vehicle_model), 1));
    
    -- Assign subscriptions to seeded tenants
    INSERT INTO subscription (tenant_id, plan_id, status, period_start, period_end)
      SELECT tenant_id, 3, 'ACTIVE', NOW() - INTERVAL '30 days', NOW() + INTERVAL '335 days'
      FROM tenant
      ON CONFLICT DO NOTHING;

    RAISE NOTICE 'FleetPulse seed data loaded successfully.';
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Seed CSVs loading skipped or partially failed: %', SQLERRM;
  END;
END $$;
