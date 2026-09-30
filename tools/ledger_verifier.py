"""
FleetPulse Zero-Loss Accounting Ledger Verifier Tool (§5.4, §6.15, §15.8)
Compares ground-truth logical emission ledger from simulator against
actual records landed in ClickHouse/sinks.
Zero-Loss Invariant:
  Loss count must be EXACTLY 0.
  count(DISTINCT event_id) and sum(event_id) must match per vehicle bucket.
"""

from dataclasses import dataclass, field
import hashlib
import json
import logging
from typing import Any, Dict, List, Optional, Set

logger = logging.getLogger("ledger_verifier")


@dataclass
class BucketStats:
    vehicle_pid: str
    expected_count: int = 0
    actual_count: int = 0
    expected_sum: int = 0
    actual_sum: int = 0
    duplicates_filtered: int = 0
    missing_count: int = 0
    missing_ids: Set[str] = field(default_factory=set)


@dataclass
class VerificationReport:
    total_expected: int
    total_actual_unique: int
    total_duplicates_filtered: int
    total_loss: int
    passed: bool
    bucket_discrepancies: List[BucketStats] = field(default_factory=list)

    def summary(self) -> str:
        status = "PASSED (ZERO LOSS)" if self.passed else "FAILED (DATA LOSS DETECTED)"
        return (
            f"=== FleetPulse Ledger Verification Report ===\n"
            f"Status:                      {status}\n"
            f"Total Logical Events:        {self.total_expected}\n"
            f"Total Unique Landed:         {self.total_actual_unique}\n"
            f"Duplicates Filtered:         {self.total_duplicates_filtered}\n"
            f"Loss Count:                  {self.total_loss} (Expected: 0)\n"
            f"Discrepant Vehicle Buckets:  {len(self.bucket_discrepancies)}\n"
            f"============================================="
        )


def _event_id_to_int(event_id: Any) -> int:
    """Safely converts event_id to 64-bit integer for sum verification."""
    if event_id is None:
        return 0
    s = str(event_id).strip()
    if not s:
        return 0
    try:
        if len(s) == 16 and all(c in "0123456789abcdefABCDEF" for c in s):
            return int(s, 16)
        if s.isdigit():
            return int(s, 10)
    except Exception:
        pass
    # Fallback md5 64-bit int
    h = hashlib.md5(s.encode("utf-8")).digest()
    return int.from_bytes(h[:8], byteorder="big", signed=False)


class LedgerVerifier:
    """Verifies end-to-end accounting against the simulator ledger (§6.15)."""

    @staticmethod
    def verify(
        expected_ledger: List[Dict[str, Any]],
        actual_records: List[Dict[str, Any]],
    ) -> VerificationReport:
        """
        Runs mathematical reconciliation between expected simulator emissions
        and actual landed records.
        """
        # 1. Index expected emissions by vehicle_pid
        expected_by_vehicle: Dict[str, Set[str]] = {}
        for rec in expected_ledger:
            pid = str(rec.get("vehicle_pid", ""))
            eid = str(rec.get("event_id", ""))
            if not pid or not eid:
                continue
            expected_by_vehicle.setdefault(pid, set()).add(eid)

        # 2. Index actual landed records (track total vs distinct for deduplication metric)
        actual_by_vehicle: Dict[str, Set[str]] = {}
        total_received_per_vehicle: Dict[str, int] = {}

        for rec in actual_records:
            pid = str(rec.get("vehicle_pid", ""))
            eid = str(rec.get("event_id", ""))
            if not pid or not eid:
                continue
            total_received_per_vehicle[pid] = total_received_per_vehicle.get(pid, 0) + 1
            actual_by_vehicle.setdefault(pid, set()).add(eid)

        # 3. Reconcile per vehicle bucket
        all_vehicles = set(expected_by_vehicle.keys()) | set(actual_by_vehicle.keys())
        total_expected = sum(len(e) for e in expected_by_vehicle.values())
        total_actual_unique = sum(len(a) for a in actual_by_vehicle.values())
        total_duplicates_filtered = sum(
            total_received_per_vehicle.get(v, 0) - len(actual_by_vehicle.get(v, set()))
            for v in all_vehicles
        )

        total_loss = 0
        discrepancies: List[BucketStats] = []

        for v in sorted(all_vehicles):
            exp_set = expected_by_vehicle.get(v, set())
            act_set = actual_by_vehicle.get(v, set())

            missing = exp_set - act_set
            exp_sum = sum(_event_id_to_int(eid) for eid in exp_set)
            act_sum = sum(_event_id_to_int(eid) for eid in act_set)

            dups = total_received_per_vehicle.get(v, 0) - len(act_set)

            if len(missing) > 0 or exp_sum != act_sum:
                total_loss += len(missing)
                stats = BucketStats(
                    vehicle_pid=v,
                    expected_count=len(exp_set),
                    actual_count=len(act_set),
                    expected_sum=exp_sum,
                    actual_sum=act_sum,
                    duplicates_filtered=dups,
                    missing_count=len(missing),
                    missing_ids=missing,
                )
                discrepancies.append(stats)

        passed = (total_loss == 0) and (total_actual_unique == total_expected)

        return VerificationReport(
            total_expected=total_expected,
            total_actual_unique=total_actual_unique,
            total_duplicates_filtered=total_duplicates_filtered,
            total_loss=total_loss,
            passed=passed,
            bucket_discrepancies=discrepancies,
        )
