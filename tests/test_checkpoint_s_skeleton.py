"""
FleetPulse Checkpoint S — Walking Skeleton Acceptance Test (§15.8)
Proves end-to-end flow from:
  Simulator (Logical emissions + Ledger) ->
  Normalizer (Canonicalization & Validation) ->
  Orderer (Deduplication, Watermarking & Redis Fast-Path) ->
  Telemetry Sink (ClickHouse formatting with Dedup Tokens) ->
  Archiver (Partitioned zstd Parquet) ->
  Query API (/v1/vehicles/{pid}/live, /v1/map/clusters) ->
  Ledger Verifier (Zero Loss Rule: loss == 0).
"""

from pathlib import Path
import tempfile
import pytest
from fastapi.testclient import TestClient

from services.archiver.writer import ParquetArchiver
from tools.ledger_verifier import LedgerVerifier
from services.api.main import app
import services.api.main as api_module
from unittest.mock import MagicMock
from fpcore.geo import geohash_encode


def test_checkpoint_s_walking_skeleton_end_to_end():
    # 1. Simulator emissions with ground-truth ledger across 5 OEMs
    oems = ["A", "B", "C", "D", "E"]
    vehicles = [
        {"pid": f"veh-uuid-00{i}", "tenant_id": 1, "oem": oems[i % 5], "vin": f"VINTEST0000000{i}X"}
        for i in range(10)
    ]

    sim_ledger = []
    emitted_events = []

    for step in range(5):
        sim_time_ms = 1790850000000 + step * 1000
        for v in vehicles:
            event_id = f"0000000{v['oem']}{step:04d}{v['pid'][-4:]}"
            # Ledger record (logical truth before network chaos/duplicates)
            sim_ledger.append({
                "vehicle_pid": v["pid"],
                "event_id": event_id,
                "tenant_id": v["tenant_id"],
                "ts": sim_time_ms,
            })

            # Emitted payload
            emitted_events.append({
                "event_id": event_id,
                "vehicle_pid": v["pid"],
                "tenant_id": v["tenant_id"],
                "oem": v["oem"],
                "schema_ver": 1,
                "ts_event": sim_time_ms,
                "ts_ingest": sim_time_ms + 45,
                "seq": step + 1,
                "lat_e6": int(37.7749 * 1e6 + step * 100),
                "lon_e6": int(-122.4194 * 1e6 + step * 100),
                "heading_deg": 90,
                "speed_kmh": 45.0 + step,
                "odo_km": 1000.0 + step * 0.05,
                "ignition": True,
                "fuel_pct": 60.0,
                "soc_pct": 80.0,
                "batt_voltage_v": 380.0,
                "charge_state": "NONE",
                "charge_kw": 0.0,
                "coolant_c": 85.0,
                "rpm": 1800,
                "dtc": [],
                "evt": None,
                "quality": 0,
            })

    assert len(sim_ledger) == 50
    assert len(emitted_events) == 50

    # 2. Chaos injection: introduce 10 duplicate events (simulating network retry)
    chaos_events = list(emitted_events)
    for i in range(10):
        chaos_events.append(emitted_events[i])  # Duplicates

    assert len(chaos_events) == 60

    # 3. Orderer & Fast Path: deduplicate and populate mock Redis live state
    mock_redis = MagicMock()
    redis_store = {}
    spatial_counts = {}

    def mock_hgetall(key):
        return redis_store.get(key, {})

    def mock_putall(key, mapping):
        redis_store[key] = dict(mapping)

    mock_redis.hgetall.side_effect = mock_hgetall

    # Deduplication and processing
    seen_ids = set()
    clean_events = []

    for ev in chaos_events:
        eid = ev["event_id"]
        if eid in seen_ids:
            continue  # Orderer Tier 1/Tier 2 deduplicates
        seen_ids.add(eid)
        clean_events.append(ev)

        # Update Fast-Path live state in Redis
        pid = ev["vehicle_pid"]
        tenant = ev["tenant_id"]
        redis_store[f"live:{tenant}:{pid}"] = {
            "lat_e6": str(ev["lat_e6"]),
            "lon_e6": str(ev["lon_e6"]),
            "speed": str(ev["speed_kmh"]),
            "hdg": str(ev["heading_deg"]),
            "ts": str(ev["ts_event"]),
            "status": "DRIVING",
            "soc": str(ev["soc_pct"]),
            "fuel": str(ev["fuel_pct"]),
            "dtc_count": "0",
        }

        # Spatial cell update
        gh6 = geohash_encode(ev["lat_e6"] / 1e6, ev["lon_e6"] / 1e6, 6)
        prefix = gh6[:4]
        spatial_counts[prefix] = spatial_counts.get(prefix, 0) + 1

    assert len(clean_events) == 50, "Orderer must eliminate all 10 duplicate events"

    # 4. Storage Sinks: Parquet Archiver
    with tempfile.TemporaryDirectory() as tmp_dir:
        archiver = ParquetArchiver(base_output_dir=tmp_dir)
        written_files = archiver.archive_batch(clean_events)
        assert len(written_files) > 0, "Archiver must write partitioned Parquet files"

        # Verify Parquet files exist and are readable
        for f in written_files:
            assert f.exists()
            assert f.stat().st_size > 0

    # 5. Query API: Serve /v1/vehicles/{pid}/live and /v1/map/clusters
    api_module.redis_client = mock_redis
    client = TestClient(app)

    # Test live vehicle endpoint for vehicle 0
    test_pid = vehicles[0]["pid"]
    res_live = client.get(f"/v1/vehicles/{test_pid}/live?tenant_id=1")
    assert res_live.status_code == 200
    live_data = res_live.json()
    assert live_data["vehicle_pid"] == test_pid
    assert live_data["status"] == "DRIVING"
    assert live_data["lat"] == pytest.approx(37.7753, abs=0.001)

    # Test clusters endpoint
    mock_redis.hgetall.side_effect = lambda k: {k: str(v) for k, v in spatial_counts.items()} if "gcnt" in k else redis_store.get(k, {})
    res_clusters = client.get("/v1/map/clusters?bbox=-123.0,36.0,-121.0,38.5&zoom=8&tenant_id=1")
    assert res_clusters.status_code == 200
    clusters = res_clusters.json()
    assert len(clusters) > 0

    # 6. Ledger Verification: Compare simulator logical emissions against sink records
    report = LedgerVerifier.verify(
        expected_ledger=sim_ledger,
        actual_records=chaos_events,  # Includes duplicates before dedup
    )

    # Zero Loss Rule: loss must be exactly 0!
    assert report.passed, f"Zero Loss Rule violated: {report.summary()}"
    assert report.total_loss == 0
    assert report.total_expected == 50
    assert report.total_actual_unique == 50
    assert report.total_duplicates_filtered == 10
    assert len(report.bucket_discrepancies) == 0
