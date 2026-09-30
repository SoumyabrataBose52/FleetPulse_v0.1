"""
Unit tests for FleetPulse Ledger Verifier Tool (§5.4, §6.15, §15.8)
"""

import pytest
from tools.ledger_verifier import LedgerVerifier, _event_id_to_int


def test_event_id_to_int_parsing():
    assert _event_id_to_int("00000000000000ff") == 255
    assert _event_id_to_int("12345") == 12345
    assert _event_id_to_int("") == 0
    assert _event_id_to_int(None) == 0


def test_ledger_verification_zero_loss_exact_match():
    expected = [
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},
        {"vehicle_pid": "v1", "event_id": "0000000000000002"},
        {"vehicle_pid": "v2", "event_id": "0000000000000003"},
    ]
    actual = [
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},
        {"vehicle_pid": "v1", "event_id": "0000000000000002"},
        {"vehicle_pid": "v2", "event_id": "0000000000000003"},
    ]

    report = LedgerVerifier.verify(expected, actual)
    assert report.passed
    assert report.total_loss == 0
    assert report.total_expected == 3
    assert report.total_actual_unique == 3
    assert report.total_duplicates_filtered == 0
    assert len(report.bucket_discrepancies) == 0


def test_ledger_verification_with_duplicates():
    # Simulator emits 3 logical events, but network/sink experiences 2 duplicates
    expected = [
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},
        {"vehicle_pid": "v1", "event_id": "0000000000000002"},
    ]
    actual = [
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},  # duplicate
        {"vehicle_pid": "v1", "event_id": "0000000000000002"},
        {"vehicle_pid": "v1", "event_id": "0000000000000002"},  # duplicate
    ]

    report = LedgerVerifier.verify(expected, actual)
    assert report.passed
    assert report.total_loss == 0
    assert report.total_expected == 2
    assert report.total_actual_unique == 2
    assert report.total_duplicates_filtered == 2
    assert len(report.bucket_discrepancies) == 0


def test_ledger_verification_detects_data_loss():
    expected = [
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},
        {"vehicle_pid": "v1", "event_id": "0000000000000002"},  # missing in sink!
        {"vehicle_pid": "v2", "event_id": "0000000000000003"},
    ]
    actual = [
        {"vehicle_pid": "v1", "event_id": "0000000000000001"},
        {"vehicle_pid": "v2", "event_id": "0000000000000003"},
    ]

    report = LedgerVerifier.verify(expected, actual)
    assert not report.passed
    assert report.total_loss == 1
    assert len(report.bucket_discrepancies) == 1
    assert "0000000000000002" in report.bucket_discrepancies[0].missing_ids
