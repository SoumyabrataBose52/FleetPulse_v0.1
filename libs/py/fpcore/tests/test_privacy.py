"""
Unit tests for fpcore.privacy module (§7.19, §8.S3).
Verifies:
- HMAC-SHA256 pseudonymisation properties (uniqueness, unlinkability, deterministic).
- Location rounding / spatial precision reduction.
- Sensitive depot / residence buffer suppression.
- Differential privacy Laplace noise generation.
- Right-to-erasure audit verification.
"""

import random
import pytest

from fpcore.privacy import (
    pseudonymize_pid,
    mask_location,
    is_within_privacy_buffer,
    add_laplace_noise,
    verify_erasure_evidence
)


class TestPseudonymisation:
    def test_deterministic_for_same_key(self):
        pid = "11111111-2222-3333-4444-555555555555"
        key = "partner_a_2026_q4"
        p1 = pseudonymize_pid(pid, key)
        p2 = pseudonymize_pid(pid, key)
        assert p1 == p2
        assert len(p1) == 16
        assert p1.isalnum()

    def test_unlinkable_across_different_recipients(self):
        pid = "11111111-2222-3333-4444-555555555555"
        key_partner_a = "partner_a_2026_q4"
        key_partner_b = "partner_b_2026_q4"
        p_a = pseudonymize_pid(pid, key_partner_a)
        p_b = pseudonymize_pid(pid, key_partner_b)
        assert p_a != p_b, "Different recipients must obtain unlinkable pseudonyms"

    def test_different_pids_produce_different_pseudonyms(self):
        pid1 = "11111111-2222-3333-4444-555555555555"
        pid2 = "99999999-8888-7777-6666-555555555555"
        key = "shared_analytics_key"
        p1 = pseudonymize_pid(pid1, key)
        p2 = pseudonymize_pid(pid2, key)
        assert p1 != p2

    def test_empty_input_raises_error(self):
        with pytest.raises(ValueError):
            pseudonymize_pid("", "key")
        with pytest.raises(ValueError):
            pseudonymize_pid("pid", "")


class TestLocationMaskingAndBuffer:
    def test_mask_location_precision(self):
        lat, lon = 37.774929, -122.419416
        m_lat, m_lon = mask_location(lat, lon, decimal_places=3)
        assert m_lat == 37.775
        assert m_lon == -122.419

        m_lat_coarse, m_lon_coarse = mask_location(lat, lon, decimal_places=1)
        assert m_lat_coarse == 37.8
        assert m_lon_coarse == -122.4

    def test_is_within_privacy_buffer(self):
        depot_lat, depot_lon, radius_m = 37.774929, -122.419416, 500.0
        buffers = [(depot_lat, depot_lon, radius_m)]

        # Point inside buffer (~50m away)
        assert is_within_privacy_buffer(37.7753, -122.4194, buffers) is True

        # Point outside buffer (~2km away)
        assert is_within_privacy_buffer(37.7950, -122.4194, buffers) is False


class TestDifferentialPrivacy:
    def test_laplace_noise_properties(self):
        true_value = 100.0
        sensitivity = 1.0
        epsilon = 0.5

        # With fixed RNG seed
        rng1 = random.Random(42)
        val1 = add_laplace_noise(true_value, sensitivity, epsilon, rng1)

        rng2 = random.Random(42)
        val2 = add_laplace_noise(true_value, sensitivity, epsilon, rng2)

        assert val1 == val2
        assert val1 != true_value, "Noise must perturb the true value"
        # Zero-mean check across 1,000 samples
        rng = random.Random(123)
        samples = [add_laplace_noise(true_value, sensitivity, epsilon, rng) for _ in range(1000)]
        mean_sample = sum(samples) / len(samples)
        assert abs(mean_sample - true_value) < 1.0, "Sample mean should approximate true value"

    def test_invalid_parameters_raise(self):
        with pytest.raises(ValueError):
            add_laplace_noise(10.0, 1.0, 0.0)  # epsilon <= 0
        with pytest.raises(ValueError):
            add_laplace_noise(10.0, -1.0, 1.0)  # sensitivity < 0


class TestRightToErasure:
    def test_verify_erasure_evidence_detects_clean(self):
        records = [
            {"vehicle_pid": "veh-001", "event_id": "e1"},
            {"vehicle_pid": "veh-002", "event_id": "e2"},
        ]
        assert verify_erasure_evidence(records, "veh-target-to-delete") is True

    def test_verify_erasure_evidence_detects_residue(self):
        records = [
            {"vehicle_pid": "veh-001", "event_id": "e1"},
            {"vehicle_pid": "veh-erased", "event_id": "e2"},
        ]
        assert verify_erasure_evidence(records, "veh-erased") is False
