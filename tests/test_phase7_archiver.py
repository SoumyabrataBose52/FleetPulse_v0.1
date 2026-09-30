"""
Unit tests for FleetPulse Cold Storage Parquet Archiver (§4.3, §5.7, §15.8)
"""

import tempfile
from pathlib import Path
import pytest
import pyarrow.parquet as pq

from services.archiver.writer import ParquetArchiver, PARQUET_TELEMETRY_SCHEMA


@pytest.fixture
def temp_archive_dir():
    with tempfile.TemporaryDirectory() as tmpdir:
        yield Path(tmpdir)


def test_parquet_archiver_partitioning_and_compression(temp_archive_dir):
    archiver = ParquetArchiver(base_output_dir=temp_archive_dir)

    # Sample events spanning 2 tenants and 2 timestamps
    events = [
        # Tenant 1, 2026-10-01 10:15 UTC
        {
            "tenant_id": 1,
            "vehicle_pid": "veh-b",
            "ts_event": 1790850900000,  # 2026-10-01 10:35:00 UTC
            "event_id": "ev-1",
            "seq": 10,
            "lat_e6": 37774900,
            "lon_e6": -122419400,
            "heading_deg": 180,
            "speed_kmh": 65.0,
            "odo_km": 1000.0,
            "ignition": True,
            "fuel_pct": 50.0,
            "soc_pct": None,
            "batt_voltage_v": 12.6,
            "charge_state": None,
            "charge_kw": None,
            "coolant_c": 90.0,
            "rpm": 2000,
            "dtc": ["P0300"],
            "evt": None,
            "quality": 0,
            "ts_ingest": 1790850900100,
        },
        {
            "tenant_id": 1,
            "vehicle_pid": "veh-a",
            "ts_event": 1790850300000,  # 2026-10-01 10:25:00 UTC
            "event_id": "ev-2",
            "seq": 5,
            "lat_e6": 37774800,
            "lon_e6": -122419300,
            "heading_deg": 90,
            "speed_kmh": 45.0,
            "odo_km": 950.0,
            "ignition": True,
            "fuel_pct": 55.0,
            "soc_pct": None,
            "batt_voltage_v": 12.6,
            "charge_state": None,
            "charge_kw": None,
            "coolant_c": 85.0,
            "rpm": 1800,
            "dtc": [],
            "evt": None,
            "quality": 0,
            "ts_ingest": 1790850300100,
        },
        # Tenant 2, 2026-10-01 11:00 UTC
        {
            "tenant_id": 2,
            "vehicle_pid": "veh-c",
            "ts_event": 1790852400000,  # 2026-10-01 11:00:00 UTC
            "event_id": "ev-3",
            "seq": 1,
            "lat_e6": 40712800,
            "lon_e6": -74006000,
            "heading_deg": 0,
            "speed_kmh": 0.0,
            "odo_km": 5000.0,
            "ignition": False,
            "fuel_pct": None,
            "soc_pct": 80.0,
            "batt_voltage_v": 380.0,
            "charge_state": "CHARGING",
            "charge_kw": 50.0,
            "coolant_c": 30.0,
            "rpm": None,
            "dtc": [],
            "evt": None,
            "quality": 0,
            "ts_ingest": 1790852400050,
        },
    ]

    written_files = archiver.archive_batch(events)
    assert len(written_files) == 2, "Must create 2 partition files (Tenant 1 Hour 10, Tenant 2 Hour 11)"

    # Verify directory structure
    rel_paths = [str(f.relative_to(temp_archive_dir)).replace("\\", "/") for f in written_files]
    assert any("telemetry/tenant=1/date=2026-10-01/hour=10" in p for p in rel_paths)
    assert any("telemetry/tenant=2/date=2026-10-01/hour=11" in p for p in rel_paths)

    # Find Tenant 1 file and read back
    t1_file = [f for f in written_files if "tenant=1" in str(f)][0]
    table = pq.read_table(t1_file)

    assert table.num_rows == 2
    # Verify sorting by (vehicle_pid, ts): veh-a should precede veh-b
    pids = table.column("vehicle_pid").to_pylist()
    assert pids == ["veh-a", "veh-b"], "Parquet partition must be sorted by (vehicle_pid, ts)"

    # Verify compression metadata
    parquet_file = pq.ParquetFile(t1_file)
    metadata = parquet_file.metadata
    assert metadata.row_group(0).column(0).compression == "ZSTD"


def test_parquet_archiver_empty_batch(temp_archive_dir):
    archiver = ParquetArchiver(base_output_dir=temp_archive_dir)
    assert archiver.archive_batch([]) == []
