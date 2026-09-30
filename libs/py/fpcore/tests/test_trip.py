"""Tests for fpcore.trip — Trip FSM + Viterbi DP (§7.10)"""
import pytest
from fpcore.trip import (
    TripFSMState, TripSample, trip_fsm_step, viterbi_segment,
    TripEndReason, TripConfig,
)


class TestTripFSM:
    def _state(self):
        return TripFSMState()

    def _sample(self, t_s, speed, lat=13.0, lon=80.0, ignition=None, odo=None):
        return TripSample(ts_ms=int(t_s * 1000), speed_kmh=speed, lat=lat, lon=lon,
                         ignition=ignition, odo_km=odo)

    def test_no_trip_from_jitter(self):
        """Stationary vehicle with GPS jitter should produce zero trips."""
        state = self._state()
        trips = []
        for i in range(600):  # 10 minutes
            s = self._sample(i, speed=0.5, lat=13.0 + (i % 3) * 0.00004,
                             lon=80.0 + (i % 3) * 0.00004)
            t = trip_fsm_step(state, s)
            if t:
                trips.append(t)
        assert len(trips) == 0, "GPS jitter should not produce trips"

    def test_trip_starts_on_sustained_speed(self):
        """Trip should start when speed >= 5 km/h for >= 10s."""
        state = self._state()
        trips = []
        for i in range(300):
            speed = 60.0 if i >= 5 else 0.0
            lat = 13.0 + i * 0.001
            s = self._sample(i, speed=speed, lat=lat, lon=80.0)
            t = trip_fsm_step(state, s)
            if t:
                trips.append(t)
        # Should have at least started a trip (trip_start_ts set)
        assert state.trip_start_ts_ms is not None or len(trips) > 0

    def test_trip_ends_on_ignition_off(self):
        """Trip should close when ignition turns off."""
        state = self._state()
        trips = []
        # Drive for 5 minutes
        for i in range(300):
            ign = True if i < 280 else False
            speed = 60.0 if i < 280 else 0.0
            s = self._sample(i, speed=speed, lat=13.0 + i * 0.001, lon=80.0, ignition=ign)
            t = trip_fsm_step(state, s)
            if t:
                trips.append(t)
        closed = [t for t in trips if t.end_reason == TripEndReason.IGNITION_OFF]
        assert len(closed) >= 1

    def test_data_gap_truncates_trip(self):
        """A 20-minute data gap during a trip should produce a truncated trip."""
        state = self._state()
        trips = []
        # Drive 5 minutes
        for i in range(300):
            s = self._sample(i, speed=60.0, lat=13.0 + i * 0.001, lon=80.0, ignition=True)
            t = trip_fsm_step(state, s)
            if t:
                trips.append(t)
        # Gap: jump 20 minutes
        s_after_gap = self._sample(300 + 1200, speed=0.0, lat=14.0, lon=81.0, ignition=False)
        t = trip_fsm_step(state, s_after_gap)
        if t:
            trips.append(t)
        truncated = [t for t in trips if t.truncated]
        assert len(truncated) >= 1

    def test_trip_duration_positive(self):
        """Completed trips must have positive duration and distance."""
        state = self._state()
        trips = []
        for i in range(400):
            ign = i < 380
            speed = 60.0 if ign else 0.0
            s = self._sample(i, speed=speed, lat=13.0 + i * 0.001, lon=80.0, ignition=ign)
            t = trip_fsm_step(state, s)
            if t:
                trips.append(t)
        for t in trips:
            assert t.duration_s > 0
            assert t.distance_km >= 0.0


class TestViterbiDP:
    def test_empty(self):
        assert viterbi_segment([]) == []

    def test_all_moving(self):
        speeds = [60.0] * 20
        labels = viterbi_segment(speeds)
        assert all(l == "MOVING" for l in labels)

    def test_all_stopped(self):
        speeds = [0.0] * 20
        labels = viterbi_segment(speeds)
        assert all(l == "STOPPED" for l in labels)

    def test_transition_detected(self):
        """Clear STOPPED → MOVING transition at sample 10."""
        speeds = [0.0] * 10 + [60.0] * 10
        labels = viterbi_segment(speeds, theta=5.0, lam=8.0)
        assert "STOPPED" in labels
        assert "MOVING" in labels
        # Transition should not be at extremes
        stopped_indices = [i for i, l in enumerate(labels) if l == "STOPPED"]
        moving_indices = [i for i, l in enumerate(labels) if l == "MOVING"]
        assert max(stopped_indices) < min(moving_indices)

    def test_transition_penalty_smooths_noise(self):
        """Single noisy sample should not cause spurious state change."""
        speeds = [60.0] * 5 + [1.0] + [60.0] * 5  # one slow sample in a moving sequence
        labels = viterbi_segment(speeds, lam=8.0)
        # With high penalty, the single slow sample should stay as MOVING
        assert labels[5] == "MOVING"

    def test_output_length_matches_input(self):
        import random
        rng = random.Random(7)
        for _ in range(20):
            n = rng.randint(1, 100)
            speeds = [rng.uniform(0, 120) for _ in range(n)]
            labels = viterbi_segment(speeds)
            assert len(labels) == n
