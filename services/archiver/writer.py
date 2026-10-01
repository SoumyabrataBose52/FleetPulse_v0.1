"""
FleetPulse Cold Storage Parquet Archiver (§4.3, §5.7, §15.8)
Consumes canonical clean telemetry and writes hourly zstd-compressed Parquet files
partitioned by tenant and timestamp:
  s3://fleetpulse-archive/telemetry/tenant=<id>/date=YYYY-MM-DD/hour=HH/part-<hash>.parquet
Sorted by (vehicle_pid, ts) for maximum columnar compression and fast range scans.
"""

from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional
import uuid

import pyarrow as pa
import pyarrow.parquet as pq

# Canonical Parquet schema matching ClickHouse telemetry table (§5.1, §5.2)
PARQUET_TELEMETRY_SCHEMA = pa.schema([
    ("tenant_id", pa.int32()),
    ("vehicle_pid", pa.string()),
    ("ts", pa.timestamp("ms", tz="UTC")),
    ("event_id", pa.string()),
    ("seq", pa.int64()),
    ("lat_e6", pa.int32()),
    ("lon_e6", pa.int32()),
    ("heading_deg", pa.int32()),
    ("speed_kmh", pa.float32()),
    ("odo_km", pa.float64()),
    ("ignition", pa.bool_()),
    ("fuel_pct", pa.float32()),
    ("soc_pct", pa.float32()),
    ("batt_voltage_v", pa.float32()),
    ("charge_state", pa.string()),
    ("charge_kw", pa.float32()),
    ("coolant_c", pa.float32()),
    ("rpm", pa.int32()),
    ("dtc", pa.list_(pa.string())),
    ("evt", pa.string()),
    ("quality", pa.int32()),
    ("ingest_ts", pa.timestamp("ms", tz="UTC")),
])


class ParquetArchiver:
    """Writes batched canonical events to partitioned Parquet files (§5.7)."""

    def __init__(self, base_output_dir: str | Path, row_group_size: int = 100_000):
        self.base_output_dir = Path(base_output_dir)
        self.base_output_dir.mkdir(parents=True, exist_ok=True)
        self.row_group_size = row_group_size

    def archive_batch(self, events: List[Dict[str, Any]]) -> List[Path]:
        """
        Partitions events by (tenant_id, date, hour), sorts by (vehicle_pid, ts),
        and writes zstd-compressed Parquet files.
        """
        if not events:
            return []

        # 1. Group records by partition key: (tenant_id, YYYY-MM-DD, HH)
        partitions: Dict[tuple, List[Dict[str, Any]]] = {}
        for ev in events:
            tenant_id = ev.get("tenant_id", 0)
            raw_ts = ev.get("ts_event", ev.get("ts", 0))
            if isinstance(raw_ts, datetime):
                dt = raw_ts if raw_ts.tzinfo is not None else raw_ts.replace(tzinfo=timezone.utc)
            elif isinstance(raw_ts, (int, float)):
                dt = datetime.fromtimestamp(raw_ts / 1000.0, tz=timezone.utc)
            else:
                dt = datetime.now(timezone.utc)

            date_str = dt.strftime("%Y-%m-%d")
            hour_str = dt.strftime("%H")

            key = (tenant_id, date_str, hour_str)
            if key not in partitions:
                partitions[key] = []
            partitions[key].append(ev)

        written_files: List[Path] = []

        def _sort_key(x):
            v_pid = str(x.get("vehicle_pid", ""))
            t = x.get("ts_event", x.get("ts", 0))
            t_num = t.timestamp() if isinstance(t, datetime) else float(t or 0)
            return (v_pid, t_num)

        # 2. Write each partition group
        for (tenant_id, date_str, hour_str), partition_events in partitions.items():
            # Sort partition by (vehicle_pid, ts) for optimal delta/dictionary compression
            partition_events.sort(key=_sort_key)

            # Build PyArrow columnar arrays
            table = self._build_arrow_table(partition_events)

            # Target directory: base/telemetry/tenant=<id>/date=YYYY-MM-DD/hour=HH/
            partition_dir = (
                self.base_output_dir
                / "telemetry"
                / f"tenant={tenant_id}"
                / f"date={date_str}"
                / f"hour={hour_str}"
            )
            partition_dir.mkdir(parents=True, exist_ok=True)

            part_id = uuid.uuid4().hex[:8]
            output_file = partition_dir / f"part-{part_id}.parquet"

            pq.write_table(
                table,
                output_file,
                compression="zstd",
                compression_level=3,
                row_group_size=self.row_group_size,
                use_dictionary=True,
            )
            written_files.append(output_file)

        return written_files

    def _build_arrow_table(self, records: List[Dict[str, Any]]) -> pa.Table:
        """Converts list of dict records into PyArrow Table matching canonical schema."""
        cols: Dict[str, list] = {name: [] for name in PARQUET_TELEMETRY_SCHEMA.names}

        for r in records:
            cols["tenant_id"].append(r.get("tenant_id"))
            cols["vehicle_pid"].append(r.get("vehicle_pid"))

            ts_val = r.get("ts_event", r.get("ts"))
            if isinstance(ts_val, (int, float)):
                ts_val = datetime.fromtimestamp(ts_val / 1000.0, tz=timezone.utc)
            elif isinstance(ts_val, datetime) and ts_val.tzinfo is None:
                ts_val = ts_val.replace(tzinfo=timezone.utc)
            cols["ts"].append(ts_val)

            cols["event_id"].append(str(r.get("event_id", "")))
            cols["seq"].append(r.get("seq"))
            cols["lat_e6"].append(r.get("lat_e6"))
            cols["lon_e6"].append(r.get("lon_e6"))
            cols["heading_deg"].append(r.get("heading_deg"))
            cols["speed_kmh"].append(r.get("speed_kmh"))
            cols["odo_km"].append(r.get("odo_km"))
            cols["ignition"].append(r.get("ignition"))
            cols["fuel_pct"].append(r.get("fuel_pct"))
            cols["soc_pct"].append(r.get("soc_pct"))
            cols["batt_voltage_v"].append(r.get("batt_voltage_v"))
            cols["charge_state"].append(r.get("charge_state"))
            cols["charge_kw"].append(r.get("charge_kw"))
            cols["coolant_c"].append(r.get("coolant_c"))
            cols["rpm"].append(r.get("rpm"))
            cols["dtc"].append(r.get("dtc") if r.get("dtc") is not None else [])
            cols["evt"].append(r.get("evt"))
            cols["quality"].append(r.get("quality", 0))

            ingest_val = r.get("ts_ingest", r.get("ingest_ts", r.get("ts_event", 0)))
            if isinstance(ingest_val, (int, float)):
                ingest_val = datetime.fromtimestamp(ingest_val / 1000.0, tz=timezone.utc)
            elif isinstance(ingest_val, datetime) and ingest_val.tzinfo is None:
                ingest_val = ingest_val.replace(tzinfo=timezone.utc)
            cols["ingest_ts"].append(ingest_val)

        return pa.Table.from_pydict(cols, schema=PARQUET_TELEMETRY_SCHEMA)
