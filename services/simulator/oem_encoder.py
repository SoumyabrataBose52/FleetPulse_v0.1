"""
§5.2 OEM Payload Encoders.

Serializes internal simulated vehicle telemetry state into 5 distinct OEM payload formats:
  - OEM A "Astra": JSON camelCase, ISO-8601 UTC time, nested position, array DTCs
  - OEM B "Borealis": JSON snake_case, imperial units, semicolon-joined DTCs, short event codes
  - OEM C "Cetus": JSON EV signal list, epoch ms, meters odometer, fraction SoC
  - OEM D "Draco": Pipe-delimited positional string, scaled integers, MQTT-style
  - OEM E "Echo": JSON supporting OTA schema drift (v1 flat -> v2 nested [lon, lat] and negative kW)

Complexity: O(1) serialization per event.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
from typing import Any, Dict, List, Optional, Union


def format_iso8601(ts_epoch_ms: int) -> str:
    """Format UTC millisecond epoch to ISO-8601 string with millisecond resolution."""
    dt = datetime.fromtimestamp(ts_epoch_ms / 1000.0, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class OEMEncoder:
    """Encodes vehicle telemetry into OEM-specific wire representations (§5.2)."""

    @staticmethod
    def encode_oem_a(
        vin: str,
        ts_ms: int,
        lat: float,
        lon: float,
        heading_deg: int,
        speed_kmh: float,
        odo_km: float,
        ignition: bool,
        fuel_pct: Optional[float],
        dtc_list: List[str],
        event: Optional[str],
        seq: int,
    ) -> str:
        """OEM A (Astra): JSON camelCase (§5.2)."""
        payload = {
            "vehicleId": vin,
            "timestamp": format_iso8601(ts_ms),
            "position": {
                "latitude": round(lat, 6),
                "longitude": round(lon, 6),
                "headingDeg": int(heading_deg) % 360,
            },
            "speedKph": round(speed_kmh, 1),
            "odometerKm": round(odo_km, 1),
            "ignition": "ON" if ignition else "OFF",
            "fuelLevelPct": round(fuel_pct, 1) if fuel_pct is not None else 0.0,
            "dtcList": dtc_list,
            "msgSeq": seq,
        }
        if event:
            payload["event"] = event
        return json.dumps(payload)

    @staticmethod
    def encode_oem_b(
        vin: str,
        ts_ms: int,
        lat: float,
        lon: float,
        heading_deg: int,
        speed_kmh: float,
        odo_km: float,
        ignition: bool,
        fuel_pct: Optional[float],
        dtc_list: List[str],
        event: Optional[str],
        seq: int,
    ) -> str:
        """OEM B (Borealis): JSON snake_case, imperial units (§5.2)."""
        # Map canonical event names to short OEM codes
        code_map = {
            "HARSH_BRAKE": "HB",
            "HARSH_ACCEL": "HA",
            "HARSH_CORNER": "HC",
            "OVERSPEED": "OS",
        }
        evt_code = code_map.get(event, "") if event else None

        payload: Dict[str, Any] = {
            "vin": vin,
            "ts_epoch": round(ts_ms / 1000.0, 3),
            "gps": {
                "lat": round(lat, 6),
                "lng": round(lon, 6),
                "hdg": int(heading_deg) % 360,
            },
            "speed_mph": round(speed_kmh / 1.609344, 2),
            "odo_mi": round(odo_km / 1.609344, 2),
            "ign": 1 if ignition else 0,
            "fuel_frac": round((fuel_pct / 100.0) if fuel_pct is not None else 0.0, 4),
            "trouble_codes": ";".join(dtc_list),
            "counter": seq,
        }
        if evt_code:
            payload["evt_code"] = evt_code
        return json.dumps(payload)

    @staticmethod
    def encode_oem_c(
        vin: str,
        ts_ms: int,
        lat: float,
        lon: float,
        speed_kmh: float,
        soc_pct: float,
        odo_km: float,
        charge_kw: float,
        event: Optional[str],
    ) -> str:
        """OEM C (Cetus): JSON EV signal list (§5.2)."""
        signals = [
            {"n": "SPD", "v": round(speed_kmh, 1)},
            {"n": "SOC", "v": round(soc_pct / 100.0, 4)},
            {"n": "ODO", "v": int(round(odo_km * 1000.0))},  # Meters
            {"n": "LAT", "v": round(lat, 6)},
            {"n": "LON", "v": round(lon, 6)},
            {"n": "CHG_KW", "v": round(charge_kw, 2)},
        ]
        flags = []
        if event == "HARSH_BRAKE":
            flags.append("HB")
        elif event == "HARSH_ACCEL":
            flags.append("HA")

        payload = {
            "deviceId": vin,
            "tsMs": ts_ms,
            "signals": signals,
            "flags": flags,
        }
        return json.dumps(payload)

    @staticmethod
    def encode_oem_d(
        vin: str,
        ts_ms: int,
        lat: float,
        lon: float,
        speed_kmh: float,
        odo_km: float,
        ignition: bool,
        fuel_pct: Optional[float],
        dtc_list: List[str],
    ) -> str:
        """OEM D (Draco): Pipe-delimited scaled integers string (§5.2)."""
        lat_e5 = int(round(lat * 100000))
        lon_e5 = int(round(lon * 100000))
        spd_x10 = int(round(speed_kmh * 10))
        odo_x10 = int(round(odo_km * 10))
        ign = 1 if ignition else 0
        fuel_x10 = int(round((fuel_pct or 0.0) * 10))
        dtc_str = ",".join(dtc_list) if dtc_list else ""

        # D1|<VIN>|<tsMs>|<lat_e5>|<lon_e5>|<spd_x10>|<odo_x10>|<ign>|<fuel_x10>|<dtc>
        return f"D1|{vin}|{ts_ms}|{lat_e5}|{lon_e5}|{spd_x10}|{odo_x10}|{ign}|{fuel_x10}|{dtc_str}"

    @staticmethod
    def encode_oem_e_v1(
        vin: str,
        ts_ms: int,
        lat: float,
        lon: float,
        speed_kmh: float,
        soc_pct: float,
    ) -> str:
        """OEM E (Echo v1): Simple JSON format (§5.2)."""
        payload = {
            "v": 1,
            "vin": vin,
            "t": format_iso8601(ts_ms),
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "spd": round(speed_kmh, 1),
            "soc": round(soc_pct, 1),
        }
        return json.dumps(payload)

    @staticmethod
    def encode_oem_e_v2(
        vin: str,
        ts_ms: int,
        lat: float,
        lon: float,
        speed_kmh: float,
        soc_pct: float,
        batt_voltage_v: float,
        charge_kw: float,
    ) -> str:
        """OEM E (Echo v2): Schema drift with array loc [lon, lat], m/s, negative kW charging (§5.2)."""
        # Negative kW indicates charging power
        reported_kw = -round(charge_kw, 2) if charge_kw > 0.0 else 0.0

        payload = {
            "v": 2,
            "vin": vin,
            "t": format_iso8601(ts_ms),
            "loc": [round(lon, 6), round(lat, 6)],   # [lon, lat]!
            "spd_ms": round(speed_kmh / 3.6, 2),
            "batt": {
                "soc": round(soc_pct / 100.0, 4),
                "volt": round(batt_voltage_v, 1),
                "kw": reported_kw,
            },
        }
        return json.dumps(payload)
