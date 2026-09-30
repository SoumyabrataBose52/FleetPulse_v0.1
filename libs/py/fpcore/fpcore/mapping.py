"""
§5.2 Multi-OEM Normalization and Mapping DSL Engine.

Provides pure functions to compile and execute declarative OEM mapping configurations
converting heterogeneous OEM telematics payloads (JSON, signal-list, pipe-delimited)
into normalized canonical dictionaries ready for Avro serialization.

Complexity:
  - compile_mapping: O(F) where F is the number of field mapping rules.
  - apply_mapping: O(F + S) where S is the size/depth of the raw payload.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import math
from typing import Any, Callable, Dict, List, Optional, Tuple, Union


# §5.2 Quality bitmask flags
QUALITY_GPS_SUSPECT = 1
QUALITY_LATE = 2
QUALITY_IMPUTED = 4
QUALITY_VIN_WARN = 8
QUALITY_CLOCK_SKEW = 16


def parse_iso8601_to_ms(ts_str: str) -> int:
    """Parse ISO-8601 UTC timestamp string to epoch milliseconds (§5.2)."""
    clean_str = ts_str.strip()
    if clean_str.endswith("Z"):
        clean_str = clean_str[:-1] + "+00:00"
    dt = datetime.fromisoformat(clean_str)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def epoch_s_to_ms(val: Union[int, float]) -> int:
    """Convert epoch seconds (int or float) to epoch milliseconds integer."""
    return int(round(float(val) * 1000.0))


def get_nested_value(data: Any, path: str) -> Any:
    """Extract nested value via dot-separated path (e.g. 'position.latitude')."""
    if not path:
        return None
    current = data
    parts = path.split(".")
    for part in parts:
        if isinstance(current, dict):
            if part in current:
                current = current[part]
            else:
                return None
        elif isinstance(current, (list, tuple)):
            try:
                idx = int(part)
                current = current[idx]
            except (ValueError, IndexError):
                return None
        else:
            return None
    return current


def extract_signal_value(signals: List[Dict[str, Any]], signal_name: str) -> Any:
    """Extract value from a list of {n: name, v: value} signal objects (§5.2 OEM C)."""
    if not isinstance(signals, list):
        return None
    for item in signals:
        if isinstance(item, dict) and item.get("n") == signal_name:
            return item.get("v")
    return None


@dataclass(frozen=True)
class FieldRule:
    """Compiled rule for a single canonical field."""
    canonical_name: str
    source: str
    transform: str
    required: bool = False
    default: Any = None
    factor: Optional[float] = None
    offset: float = 0.0
    mapping: Dict[str, Any] = field(default_factory=dict)
    separator: str = ";"
    index: Optional[int] = None


@dataclass(frozen=True)
class CompiledMapping:
    """Compiled executable mapping definition for an OEM format."""
    oem: str
    schema_ver: int
    format: str
    delimiter: str
    vin_field: str
    rules: List[FieldRule]


def compile_mapping(config: Dict[str, Any]) -> CompiledMapping:
    """
    Compile a declarative JSON mapping config dictionary into an optimized
    executable CompiledMapping structure.
    """
    oem = config["oem"]
    schema_ver = int(config.get("schema_ver", 1))
    fmt = config.get("format", "json")
    delimiter = config.get("delimiter", "|")
    vin_field = config.get("vin_field", "vin")

    compiled_rules: List[FieldRule] = []
    fields_dict = config.get("fields", {})

    for c_name, rule_def in fields_dict.items():
        compiled_rules.append(
            FieldRule(
                canonical_name=c_name,
                source=rule_def["source"],
                transform=rule_def.get("transform", "direct"),
                required=rule_def.get("required", False),
                default=rule_def.get("default", None),
                factor=float(rule_def["factor"]) if "factor" in rule_def else None,
                offset=float(rule_def.get("offset", 0.0)),
                mapping=dict(rule_def.get("mapping", {})),
                separator=rule_def.get("separator", ";"),
                index=int(rule_def["index"]) if "index" in rule_def else None,
            )
        )

    return CompiledMapping(
        oem=oem,
        schema_ver=schema_ver,
        format=fmt,
        delimiter=delimiter,
        vin_field=vin_field,
        rules=compiled_rules,
    )


def extract_raw_vin(raw_payload: Any, mapping: CompiledMapping) -> Optional[str]:
    """Extract raw VIN or device ID string from payload."""
    if mapping.format == "delimited":
        if isinstance(raw_payload, str):
            parts = raw_payload.split(mapping.delimiter)
            try:
                idx = int(mapping.vin_field)
                if 0 <= idx < len(parts):
                    return parts[idx].strip()
            except (ValueError, IndexError):
                return None
        return None
    elif mapping.format == "signal_list" or mapping.format == "json":
        if isinstance(raw_payload, str):
            try:
                raw_payload = json.loads(raw_payload)
            except Exception:
                return None
        if isinstance(raw_payload, dict):
            val = get_nested_value(raw_payload, mapping.vin_field)
            return str(val).strip() if val is not None else None
    return None


def transform_value(raw_val: Any, rule: FieldRule) -> Any:
    """Apply transformation rule to raw value (§5.2)."""
    if raw_val is None:
        return rule.default

    t = rule.transform

    if t == "direct":
        if isinstance(raw_val, str):
            if rule.canonical_name in ("ts_event", "ts_ingest", "seq", "heading_deg", "rpm", "quality", "lat_e6", "lon_e6"):
                try:
                    return int(raw_val)
                except ValueError:
                    pass
            elif rule.canonical_name in ("speed_kmh", "odo_km", "fuel_pct", "soc_pct", "batt_voltage_v", "charge_kw", "coolant_c"):
                try:
                    return float(raw_val)
                except ValueError:
                    pass
        return raw_val

    elif t == "scale":
        try:
            num = float(raw_val)
            f = rule.factor if rule.factor is not None else 1.0
            res = num * f + rule.offset
            if rule.canonical_name.endswith("_e6"):
                return int(round(res))
            return res
        except (ValueError, TypeError):
            return rule.default

    elif t == "iso8601_to_ms":
        try:
            return parse_iso8601_to_ms(str(raw_val))
        except Exception:
            return rule.default

    elif t == "epoch_s_to_ms":
        try:
            return epoch_s_to_ms(raw_val)
        except Exception:
            return rule.default

    elif t == "enum_map":
        key = str(raw_val)
        if key in rule.mapping:
            return rule.mapping[key]
        return rule.default

    elif t == "split_list":
        if isinstance(raw_val, list):
            return [str(x).strip() for x in raw_val if str(x).strip()]
        s = str(raw_val)
        parts = [p.strip() for p in s.split(rule.separator) if p.strip()]
        return parts

    elif t == "array_index":
        if isinstance(raw_val, (list, tuple)) and rule.index is not None:
            if 0 <= rule.index < len(raw_val):
                sub_val = raw_val[rule.index]
                if rule.factor is not None:
                    try:
                        res = float(sub_val) * rule.factor + rule.offset
                        if rule.canonical_name.endswith("_e6"):
                            return int(round(res))
                        return res
                    except (ValueError, TypeError):
                        return rule.default
                return sub_val
        return rule.default

    elif t == "negative_to_charge_kw":
        try:
            val_f = float(raw_val)
            if val_f < 0.0:
                return abs(val_f)
            return 0.0
        except (ValueError, TypeError):
            return 0.0

    elif t == "negative_to_charge_state":
        try:
            val_f = float(raw_val)
            return "CHARGING" if val_f < 0.0 else "NONE"
        except (ValueError, TypeError):
            return "NONE"

    return raw_val


def apply_mapping(
    raw_payload: Union[str, Dict[str, Any]],
    mapping: CompiledMapping,
) -> Tuple[Optional[str], Dict[str, Any]]:
    """
    Apply a compiled mapping to a raw payload.

    Returns:
        (vin, canonical_fields_dict)
    """
    # 1. Parse payload if necessary
    parsed_payload: Any = raw_payload
    if mapping.format in ("json", "signal_list") and isinstance(raw_payload, str):
        parsed_payload = json.loads(raw_payload)

    # 2. Extract VIN
    vin = extract_raw_vin(raw_payload, mapping)

    # 3. Process fields
    result: Dict[str, Any] = {}

    if mapping.format == "delimited":
        parts = []
        if isinstance(raw_payload, str):
            parts = raw_payload.split(mapping.delimiter)

        for rule in mapping.rules:
            raw_val = None
            try:
                idx = int(rule.source)
                if 0 <= idx < len(parts):
                    raw_val = parts[idx].strip()
            except (ValueError, IndexError):
                raw_val = None

            val = transform_value(raw_val, rule)
            if val is not None:
                result[rule.canonical_name] = val

    elif mapping.format == "signal_list":
        signals = parsed_payload.get("signals", []) if isinstance(parsed_payload, dict) else []
        for rule in mapping.rules:
            raw_val = None
            if isinstance(parsed_payload, dict) and rule.source in parsed_payload:
                raw_val = parsed_payload[rule.source]
            else:
                raw_val = extract_signal_value(signals, rule.source)

            # Special case for flags list mapping to evt
            if rule.source == "flags" and isinstance(raw_val, list) and raw_val:
                raw_val = raw_val[0]

            val = transform_value(raw_val, rule)
            if val is not None:
                result[rule.canonical_name] = val

    else:  # standard JSON
        for rule in mapping.rules:
            raw_val = get_nested_value(parsed_payload, rule.source)
            val = transform_value(raw_val, rule)
            if val is not None:
                result[rule.canonical_name] = val

    return vin, result
