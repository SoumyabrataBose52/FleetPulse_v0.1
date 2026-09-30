# FleetPulse 3NF Relational Core ER Diagram (§5.3)

This document specifies the PostgreSQL 16 3NF schema, relationships, Row-Level Security (RLS) enforcement boundaries, and deliberate denormalizations.

---

## 1. Entity-Relationship Diagram

```mermaid
erDiagram
    TENANT ||--o{ PLAN_SUBSCRIPTION : holds
    PLAN ||--o{ PLAN_SUBSCRIPTION : governs
    TENANT ||--o{ APP_USER : employs
    APP_USER ||--o{ USER_ROLE : assigned
    ROLE ||--o{ USER_ROLE : contains
    TENANT ||--o{ FLEET : organizes
    OEM ||--o{ OEM_MAPPING : defines
    OEM ||--o{ VEHICLE_MODEL : manufactures
    FLEET ||--o{ VEHICLE : contains
    VEHICLE_MODEL ||--o{ VEHICLE : specifies
    TENANT ||--o{ DRIVER : employs
    VEHICLE ||--o{ VEHICLE_DRIVER_ASSIGNMENT : driven_by
    DRIVER ||--o{ VEHICLE_DRIVER_ASSIGNMENT : operates
    FLEET ||--o{ DEPOT : owns
    FLEET ||--o{ GEOFENCE : bounds
    GEOFENCE ||--o{ GEOFENCE_SCHEDULE : scheduled
    DEPOT ||--o{ CHARGER : hosts
    TARIFF ||--o{ TARIFF_PERIOD : defines
    TARIFF ||--o{ CHARGER : tariffs
    VEHICLE ||--o{ TRIP : completes
    DRIVER ||--o{ TRIP : logs
    VEHICLE ||--o{ ALERT : triggers
    TENANT ||--o{ DATA_SHARING_AGREEMENT : grants
    RECIPIENT ||--o{ DATA_SHARING_AGREEMENT : receives
    DATA_SHARING_AGREEMENT ||--o{ PRIVACY_BUDGET_LEDGER : expends
    TENANT ||--o{ AUDIT_LOG : audits

    TENANT {
        int tenant_id PK
        string name
        string archetype
        timestamptz created_at
    }

    PLAN {
        int plan_id PK
        string name
        int max_vehicles
        numeric price_per_vehicle_month
    }

    PLAN_SUBSCRIPTION {
        int subscription_id PK
        int tenant_id FK
        int plan_id FK
        string status
        timestamptz period_start
        timestamptz period_end
    }

    APP_USER {
        int user_id PK
        int tenant_id FK
        string idp_subject UK
        string email
        string display_name
    }

    ROLE {
        int role_id PK
        string code UK
        string description
    }

    USER_ROLE {
        int user_id PK, FK
        int role_id PK, FK
    }

    FLEET {
        int fleet_id PK
        int tenant_id FK
        string name
    }

    OEM {
        int oem_id PK
        string code UK
        string name
    }

    OEM_MAPPING {
        int mapping_id PK
        int oem_id FK
        int schema_ver
        int version
        string status
        jsonb config
    }

    VEHICLE_MODEL {
        int model_id PK
        int oem_id FK
        string name
        string powertrain
        string body
        float battery_kwh
        float tank_l
        float idle_burn_lph
        float mass_kg
        float cda
        float max_dc_kw
        float max_ac_kw
    }

    VEHICLE {
        uuid vehicle_pid PK
        string vin UK
        int fleet_id FK
        int tenant_id FK
        int model_id FK
        int model_year
        string status
        timestamptz erased_at
    }

    DRIVER {
        int driver_id PK
        int tenant_id FK
        string display_name
        timestamptz erased_at
    }

    VEHICLE_DRIVER_ASSIGNMENT {
        uuid vehicle_pid PK, FK
        timestamptz valid_from PK
        int driver_id FK
        timestamptz valid_to
    }

    DEPOT {
        int depot_id PK
        int fleet_id FK
        string name
        geography boundary
        float site_power_cap_kw
    }

    GEOFENCE {
        int geofence_id PK
        int fleet_id FK
        string type
        geography boundary
    }

    GEOFENCE_SCHEDULE {
        int geofence_id PK, FK
        int dow PK
        int start_min PK
        int end_min
    }

    CHARGER {
        int charger_id PK
        string network
        geography location
        float power_kw
        string connector
        int tariff_id FK
        int depot_id FK
    }

    TARIFF {
        int tariff_id PK
        string name
    }

    TARIFF_PERIOD {
        int tariff_id PK, FK
        int dow_mask PK
        int start_min PK
        int end_min
        numeric price_per_kwh
    }

    TRIP {
        string trip_id PK
        uuid vehicle_pid FK
        int tenant_id FK
        int driver_id FK
        timestamptz start_ts
        timestamptz end_ts
        string start_geohash7
        string end_geohash7
        float distance_km
        int duration_s
        int idle_s
        float energy_kwh
        float fuel_l
        numeric cost
    }

    ALERT {
        uuid alert_id PK
        string alert_key UK
        int tenant_id FK
        uuid vehicle_pid FK
        string type
        string severity
        string status
        string assignee
        timestamptz opened_at
        timestamptz closed_at
        string insight_ref
    }

    RECIPIENT {
        int recipient_id PK
        string name
        string oauth_client_id
    }

    DATA_SHARING_AGREEMENT {
        int dsa_id PK
        int tenant_id FK
        int recipient_id FK
        string purpose
        string allowed_products
        int geohash_precision
        int k_min
        float epsilon_daily
        timestamptz valid_from
        timestamptz valid_to
    }

    PRIVACY_BUDGET_LEDGER {
        int dsa_id PK, FK
        date day PK
        float epsilon_spent
    }

    AUDIT_LOG {
        uuid audit_id PK
        timestamptz ts
        string actor_id
        string actor_type
        int tenant_id FK
        string action
        string resource_type
        string resource_id
        string purpose
        string ip
        string prev_hash
        string row_hash
    }
```

---

## 2. Row-Level Security (RLS) Strategy

Tenant isolation is guaranteed by PostgreSQL Row-Level Security policies.
For every tenant-scoped table (`vehicle`, `driver`, `fleet`, `trip`, `alert`, `audit_log`, `data_sharing_agreement`):
1. RLS is forced: `ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;`
2. Isolation policy:
   ```sql
   CREATE POLICY tenant_isolation_policy ON <table>
   AS RESTRICTIVE
   USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::integer);
   ```
3. The API layer applies `SET LOCAL app.tenant_id = :tenant_id` at the start of each transaction based on verified JWT claims. Cross-tenant leakage is physically prevented at the database kernel.

---

## 3. Deliberate Denormalizations (§5.3 Rationale)

1. **`tenant_id` on child tables (`vehicle`, `trip`, `alert`):**
   - *Why:* Enables RLS filtering without requiring expensive cascading joins back to `fleet` or `tenant`.
2. **Aggregated trip totals (`distance_km`, `duration_s`, `cost`, `energy_kwh`):**
   - *Why:* Computing trip aggregates dynamically over millions of raw 1 Hz events would severely degrade query performance. Storing finalized totals on the `trip` record allows sub-50ms operational reporting.
3. **`alert.insight_ref`:**
   - *Why:* Decouples operational alert workflow state (ACID in PostgreSQL) from rich, polymorphic, schema-flexible diagnostic evidence stored in MongoDB.
