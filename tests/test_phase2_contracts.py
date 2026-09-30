"""
§15.3 Phase 2 — Contracts and Data Model Test Suite.

Validates:
  1. Avro Schemas (§5.1): canonical event, raw event, domain events (trip, idle, safety, charging, geofence), alerts, erasure commands, audit logs.
  2. OEM Mappings (§5.2): DSL schema validation, compilation, and golden sample conversions (A..E + schema drift v1/v2).
  3. Serialization round-trips: FastAvro binary encode/decode for canonical and domain events.
  4. OpenAPI 3.1 Contract (§9): schema completeness and OpenAPI validation.
  5. Persistence & Architecture contracts: Mongo init indexes, Redis key specs, ER diagram, ADRs.
"""

from __future__ import annotations

import glob
import io
import json
import math
from pathlib import Path
import pytest
import yaml

import fastavro
from jsonschema import validate as jsonschema_validate
from openapi_spec_validator import validate as openapi_validate

from fpcore.mapping import (
    CompiledMapping,
    compile_mapping,
    apply_mapping,
    extract_raw_vin,
    parse_iso8601_to_ms,
    epoch_s_to_ms,
)


ROOT_DIR = Path(__file__).resolve().parent.parent
SCHEMAS_DIR = ROOT_DIR / "libs" / "schemas"
AVRO_DIR = SCHEMAS_DIR / "avro"
OEM_MAPPINGS_DIR = ROOT_DIR / "config" / "oem-mappings"
DOCS_DIR = ROOT_DIR / "docs"
MIGRATIONS_DIR = ROOT_DIR / "migrations"


class TestAvroSchemas:
    """Validates all Avro schema definitions per §5.1, §4.3."""

    def test_all_avro_schemas_parse(self):
        """All .avsc files must be strictly valid Avro schemas."""
        schema_files = list(AVRO_DIR.glob("*.avsc"))
        assert len(schema_files) >= 8, f"Expected >= 8 schemas, found {len(schema_files)}"

        for sf in schema_files:
            schema = fastavro.schema.load_schema(str(sf))
            assert isinstance(schema, dict), f"Failed to load schema {sf.name}"
            assert "name" in schema
            assert "type" in schema
            assert schema["type"] == "record"

    def test_canonical_event_schema_fields(self):
        """Canonical telemetry event must contain all §5.1 required attributes."""
        schema = fastavro.schema.load_schema(str(AVRO_DIR / "canonical_event.avsc"))
        fields = {f["name"]: f for f in schema["fields"]}

        required_names = [
            "event_id", "vehicle_pid", "tenant_id", "oem", "schema_ver",
            "ts_event", "ts_ingest", "seq", "lat_e6", "lon_e6", "heading_deg",
            "speed_kmh", "odo_km", "ignition", "fuel_pct", "soc_pct",
            "batt_voltage_v", "charge_state", "charge_kw", "coolant_c",
            "rpm", "dtc", "evt", "quality"
        ]
        for name in required_names:
            assert name in fields, f"Missing field in canonical schema: {name}"

    def test_canonical_event_serialization_roundtrip(self):
        """Verify binary serialization and deserialization of a full canonical event."""
        schema = fastavro.schema.load_schema(str(AVRO_DIR / "canonical_event.avsc"))
        record = {
            "event_id": "evt_test_hash_12345",
            "vehicle_pid": "01923f5b-7c6a-7d4e-8f90-123456789abc",
            "tenant_id": 42,
            "oem": "A",
            "schema_ver": 1,
            "ts_event": 1790331302120,
            "ts_ingest": 1790331303000,
            "seq": 1001,
            "lat_e6": 13082700,
            "lon_e6": 80270700,
            "heading_deg": 90,
            "speed_kmh": 65.5,
            "odo_km": 12500.25,
            "ignition": True,
            "fuel_pct": 55.0,
            "soc_pct": 82.5,
            "batt_voltage_v": 380.0,
            "charge_state": "NONE",
            "charge_kw": 0.0,
            "coolant_c": 88.0,
            "rpm": 1850,
            "dtc": ["P0301"],
            "evt": "HARSH_BRAKE",
            "quality": 0,
        }

        buf = io.BytesIO()
        fastavro.schemaless_writer(buf, schema, record)
        buf.seek(0)
        decoded = fastavro.schemaless_reader(buf, schema)

        assert decoded["event_id"] == record["event_id"]
        assert decoded["vehicle_pid"] == record["vehicle_pid"]
        assert decoded["lat_e6"] == record["lat_e6"]
        assert decoded["lon_e6"] == record["lon_e6"]
        assert math.isclose(decoded["speed_kmh"], record["speed_kmh"], abs_tol=1e-3)
        assert decoded["dtc"] == ["P0301"]
        assert decoded["evt"] == "HARSH_BRAKE"

    def test_domain_events_serialization(self):
        """Verify binary serialization for all domain events (§4.3, §5.3, §5.4)."""
        # 1. TripEvent
        trip_schema = fastavro.schema.load_schema(str(AVRO_DIR / "trip_event.avsc"))
        trip_rec = {
            "trip_id": "trip_001",
            "vehicle_pid": "01923f5b-7c6a-7d4e-8f90-123456789abc",
            "tenant_id": 1,
            "driver_id": 10,
            "start_ts": 1790330000000,
            "end_ts": 1790331800000,
            "start_lat_e6": 13082700,
            "start_lon_e6": 80270700,
            "start_geohash7": "tf34567",
            "end_lat_e6": 13092700,
            "end_lon_e6": 80280700,
            "end_geohash7": "tf34589",
            "distance_km": 14.5,
            "duration_s": 1800,
            "idle_s": 240,
            "energy_kwh": 3.2,
            "fuel_l": None,
            "cost": 0.448,
            "co2_kg": 0.0,
            "status": "COMPLETED",
        }
        buf = io.BytesIO()
        fastavro.schemaless_writer(buf, trip_schema, trip_rec)
        buf.seek(0)
        assert fastavro.schemaless_reader(buf, trip_schema)["trip_id"] == "trip_001"

        # 2. IdleEvent
        idle_schema = fastavro.schema.load_schema(str(AVRO_DIR / "idle_event.avsc"))
        idle_rec = {
            "idle_id": "idle_001",
            "vehicle_pid": "01923f5b-7c6a-7d4e-8f90-123456789abc",
            "tenant_id": 1,
            "driver_id": 10,
            "start_ts": 1790330500000,
            "end_ts": 1790330800000,
            "duration_s": 300,
            "lat_e6": 13082700,
            "lon_e6": 80270700,
            "geohash7": "tf34567",
            "fuel_burned_l": 0.1,
            "cost": 0.13,
            "avoidable_cost": 0.08,
            "co2_kg": 0.23,
        }
        buf = io.BytesIO()
        fastavro.schemaless_writer(buf, idle_schema, idle_rec)
        buf.seek(0)
        assert fastavro.schemaless_reader(buf, idle_schema)["idle_id"] == "idle_001"

        # 3. ChargingSessionEvent
        charge_schema = fastavro.schema.load_schema(str(AVRO_DIR / "charging_session.avsc"))
        charge_rec = {
            "session_id": "chg_001",
            "vehicle_pid": "01923f5b-7c6a-7d4e-8f90-123456789abc",
            "tenant_id": 1,
            "charger_id": 5,
            "depot_id": None,
            "start_ts": 1790330000000,
            "end_ts": 1790333600000,
            "start_soc_pct": 20.0,
            "end_soc_pct": 80.0,
            "energy_kwh": 35.0,
            "avg_power_kw": 35.0,
            "peak_power_kw": 50.0,
            "baseline_cost": 7.70,
            "smart_cost": 4.90,
            "cost_saving": 2.80,
            "soh_estimate": 94.5,
        }
        buf = io.BytesIO()
        fastavro.schemaless_writer(buf, charge_schema, charge_rec)
        buf.seek(0)
        assert fastavro.schemaless_reader(buf, charge_schema)["session_id"] == "chg_001"

        # 4. AlertEvent
        alert_schema = fastavro.schema.load_schema(str(AVRO_DIR / "alert.avsc"))
        alert_rec = {
            "alert_id": "01923f5b-7c6a-7d4e-8f90-alert1234567",
            "alert_key": "range_risk_key_1",
            "tenant_id": 1,
            "vehicle_pid": "01923f5b-7c6a-7d4e-8f90-123456789abc",
            "type": "RANGE_RISK",
            "severity": "CRITICAL",
            "ts": 1790331302120,
            "message": "Vehicle SoC below reachable depot margin",
            "details": {"soc": "12.0", "nearest_km": "18.5"},
            "insight_ref": None,
        }
        buf = io.BytesIO()
        fastavro.schemaless_writer(buf, alert_schema, alert_rec)
        buf.seek(0)
        assert fastavro.schemaless_reader(buf, alert_schema)["alert_key"] == "range_risk_key_1"


class TestOEMMappings:
    """Validates OEM mapping DSL, schemas, and conversions (§5.2)."""

    @pytest.fixture
    def mapping_schema(self):
        schema_path = OEM_MAPPINGS_DIR / "oem_mapping_schema.json"
        with open(schema_path) as f:
            return json.load(f)

    def test_oem_mappings_validate_against_schema(self, mapping_schema):
        """All OEM mapping configuration JSON files must validate against oem_mapping_schema.json."""
        mapping_files = [
            f for f in OEM_MAPPINGS_DIR.glob("oem_*.json")
            if not f.name.endswith("schema.json")
        ]
        assert len(mapping_files) == 6, f"Expected 6 mapping configs, found {len(mapping_files)}"

        for mf in mapping_files:
            with open(mf) as f:
                cfg = json.load(f)
            jsonschema_validate(instance=cfg, schema=mapping_schema)

    def test_golden_samples_conversion(self):
        """Every golden sample must normalize accurately to the expected canonical dictionary."""
        golden_file = OEM_MAPPINGS_DIR / "golden_samples.json"
        with open(golden_file) as f:
            golden_data = json.load(f)

        canonical_schema = fastavro.schema.load_schema(str(AVRO_DIR / "canonical_event.avsc"))

        for key, test_case in golden_data.items():
            config_file = OEM_MAPPINGS_DIR / f"{key}.json"
            assert config_file.exists(), f"Missing config file {config_file}"

            with open(config_file) as f:
                cfg = json.load(f)

            compiled = compile_mapping(cfg)
            vin, canonical_fields = apply_mapping(test_case["raw"], compiled)

            expected = test_case["expected"]
            assert vin == expected["vin"], f"VIN mismatch for {key}: {vin} != {expected['vin']}"

            for field_name, expected_val in expected.items():
                if field_name == "vin":
                    continue
                assert field_name in canonical_fields, f"Field {field_name} missing for {key}"
                actual_val = canonical_fields[field_name]

                if isinstance(expected_val, float):
                    assert math.isclose(actual_val, expected_val, rel_tol=1e-2, abs_tol=1e-2), \
                        f"{key}.{field_name}: expected {expected_val}, got {actual_val}"
                else:
                    assert actual_val == expected_val, \
                        f"{key}.{field_name}: expected {expected_val}, got {actual_val}"

            # Verify canonical Avro roundtrip with full event wrapper
            full_record = {
                "event_id": f"evt_{key}_001",
                "vehicle_pid": "01923f5b-7c6a-7d4e-8f90-123456789abc",
                "tenant_id": 1,
                "oem": test_case["oem"],
                "schema_ver": test_case["schema_ver"],
                "ts_ingest": 1790331303000,
                **canonical_fields,
            }
            buf = io.BytesIO()
            fastavro.schemaless_writer(buf, canonical_schema, full_record)
            buf.seek(0)
            decoded = fastavro.schemaless_reader(buf, canonical_schema)
            assert decoded["event_id"] == full_record["event_id"]


class TestOpenAPISpecification:
    """Validates OpenAPI 3.1 specification per §9."""

    @pytest.fixture
    def openapi_spec(self):
        spec_path = SCHEMAS_DIR / "openapi.yaml"
        assert spec_path.exists(), "openapi.yaml does not exist"
        with open(spec_path) as f:
            return yaml.safe_load(f)

    def test_openapi_spec_is_valid_31(self, openapi_spec):
        """OpenAPI spec must strictly validate against OpenAPI 3.1 meta-schema."""
        openapi_validate(openapi_spec)

    def test_required_endpoints_exist(self, openapi_spec):
        """All mandatory API paths per §9 must be present."""
        paths = openapi_spec["paths"]
        expected_paths = [
            "/healthz",
            "/readyz",
            "/metrics",
            "/v1/ingest/batch",
            "/v1/vehicles",
            "/v1/vehicles/{pid}",
            "/v1/vehicles/{pid}/live",
            "/v1/vehicles/{pid}/trace",
            "/v1/map/clusters",
            "/v1/map/vehicles",
            "/v1/trips",
            "/v1/trips/{id}",
            "/v1/cost/summary",
            "/v1/cost/idling/top",
            "/v1/cost/utilization",
            "/v1/cost/opportunities",
            "/v1/ev/fleet-status",
            "/v1/ev/nearest-charger",
            "/v1/ev/plan",
            "/v1/ev/depot-plan",
            "/v1/ev/battery-health/{pid}",
            "/v1/safety/drivers",
            "/v1/safety/drivers/{id}",
            "/v1/safety/vehicles/{pid}/events",
            "/v1/geofences",
            "/v1/assets/anomalies",
            "/v1/assets/unapproved-depots",
            "/v1/assets/watchlist/{pid}",
            "/v1/alerts",
            "/v1/alerts/stream",
            "/v1/share/products",
            "/v1/share/products/{id}/data",
            "/v1/privacy/erasure-requests",
            "/v1/audit",
            "/v1/admin/oem-mappings",
            "/v1/admin/sim/start",
            "/v1/admin/sim/status",
        ]
        for ep in expected_paths:
            assert ep in paths, f"Mandatory endpoint {ep} missing in openapi.yaml"


class TestContractDocumentationAndADRs:
    """Verifies existence and structural completeness of ADRs and database specs."""

    def test_mongo_initialization_script_exists(self):
        """Mongo init script must configure insights (TTL 180d) and quarantine (TTL 14d)."""
        mongo_script = MIGRATIONS_DIR / "mongo" / "001_init.js"
        assert mongo_script.exists()
        content = mongo_script.read_text(encoding="utf-8")
        assert "insights" in content
        assert "quarantine" in content
        assert "15552000" in content  # 180 days TTL
        assert "1209600" in content   # 14 days TTL

    def test_redis_keys_spec_exists(self):
        """Redis key spec document must cover all §5.6 key patterns."""
        redis_doc = DOCS_DIR / "redis-keys.md"
        assert redis_doc.exists()
        content = redis_doc.read_text(encoding="utf-8")
        assert "live:{tenant}:{pid}" in content
        assert "cell:{tenant}:{gh6}" in content
        assert "gcnt:{tenant}:{p}" in content
        assert "alertcool:{key}" in content

    def test_er_diagram_exists(self):
        """ER diagram document must detail 3NF Postgres relational core."""
        er_doc = DOCS_DIR / "er-diagram.md"
        assert er_doc.exists()
        content = er_doc.read_text(encoding="utf-8")
        assert "VEHICLE" in content
        assert "TRIP" in content
        assert "ALERT" in content
        assert "AUDIT_LOG" in content

    def test_all_adrs_present(self):
        """All 8 ADRs mandated in §4.12 must exist."""
        adr_dir = DOCS_DIR / "adr"
        expected_adrs = [
            "ADR-001-kafka-system-of-record.md",
            "ADR-002-polyglot-persistence.md",
            "ADR-003-two-path-processing.md",
            "ADR-004-idempotent-sinks.md",
            "ADR-005-pseudonymous-keys-erasure.md",
            "ADR-006-spring-boot-python-instead-of-go.md",
            "ADR-007-hot-reloadable-oem-mappings.md",
            "ADR-008-deterministic-algorithms-no-ml.md",
        ]
        for adr in expected_adrs:
            path = adr_dir / adr
            assert path.exists(), f"Mandatory ADR missing: {adr}"
            assert path.stat().st_size > 200, f"ADR {adr} appears empty"
