"""Tests for fpcore.safety, fpcore.ev_dp, fpcore.routing"""
import math
import pytest
from fpcore.safety import (
    DriverSafetyState, SafetyEvent, SafetyEventType,
    update_on_distance, update_on_event, compute_score,
    EventDetectorState, detect_events_from_delta,
    DECAY_HALF_LIFE_DAYS,
)
from fpcore.ev_dp import (
    charging_dp, depot_allocate, estimate_soh,
    VehicleChargingRequest, SohEstimatorState, SohSession,
)
from fpcore.routing import RoadGraph, Node, Edge


# ===========================================================================
# Safety score tests
# ===========================================================================

class TestSafetyScore:
    def test_fresh_driver_scores_100(self):
        state = DriverSafetyState()
        # No events, no distance → insufficient data
        result = compute_score(state)
        assert result.score == 100.0

    def test_perfect_driver_scores_100(self):
        state = DriverSafetyState()
        t = 0.0
        for _ in range(50):  # 50 segments, no events
            update_on_distance(state, km=5.0, ts_s=t)
            t += 3600.0
        result = compute_score(state)
        assert result.score == pytest.approx(100.0, abs=0.01)

    def test_many_harsh_events_lower_score(self):
        state = DriverSafetyState()
        t = 0.0
        for i in range(100):
            update_on_distance(state, km=1.0, ts_s=t)
            update_on_event(state, SafetyEvent(
                event_type=SafetyEventType.HARSH_BRAKE, ts_s=t + 1,
                accel_ms2=4.0, speed_kmh=80.0
            ))
            t += 60.0
        result = compute_score(state)
        assert result.score < 60.0

    def test_decay_halves_impact(self):
        """After one half-life, score should recover towards 100."""
        state = DriverSafetyState()
        t = 0.0
        # Create a bad score with enough km so score isn't 0.0
        for i in range(20):
            update_on_distance(state, km=5.0, ts_s=t)  # 5km each step = 100km total
            update_on_event(state, SafetyEvent(
                event_type=SafetyEventType.HARSH_BRAKE, ts_s=t + 1,
                accel_ms2=4.0, speed_kmh=80.0
            ))
            t += 100.0

        score_before = compute_score(state).score
        assert score_before < 90.0  # confirm we made it bad

        # Advance by one half-life, add some km to keep K non-zero
        t += DECAY_HALF_LIFE_DAYS * 86400
        update_on_distance(state, km=100.0, ts_s=t)  # drive more (triggers decay)
        score_after = compute_score(state).score

        assert score_after > score_before, "Score should improve after decay"

    def test_event_detector_harsh_brake(self):
        estate = EventDetectorState()
        # First call at 80 km/h sets anchor state (no event yet)
        events = detect_events_from_delta(estate, ts_s=0.0, speed_kmh=80.0)
        assert len(events) == 0  # first call just initialises
        # Second call: 80 → 0 in 1 second = -22.2 m/s² → harsh brake
        events = detect_events_from_delta(estate, ts_s=1.0, speed_kmh=0.0)
        harsh = [e for e in events if e.event_type == SafetyEventType.HARSH_BRAKE]
        assert len(harsh) >= 1

    def test_event_detector_no_false_positives_at_rest(self):
        estate = EventDetectorState()
        events = []
        for i in range(60):
            events += detect_events_from_delta(estate, ts_s=float(i), speed_kmh=0.0)
        assert len(events) == 0

    def test_risky_flag_low_score(self):
        state = DriverSafetyState()
        t = 0.0
        for _ in range(200):
            update_on_distance(state, km=0.5, ts_s=t)
            update_on_event(state, SafetyEvent(SafetyEventType.HARSH_BRAKE, t + 1, 5.0, 100.0))
            t += 30.0
        result = compute_score(state)
        assert result.is_risky is True


# ===========================================================================
# Charging DP tests
# ===========================================================================

class TestChargingDP:
    def test_no_charging_needed(self):
        """If already at target, plan should be zero cost."""
        plan = charging_dp(
            e0_kwh=40.0, e_target_kwh=40.0, e_max_kwh=80.0,
            slot_prices=[0.14] * 4, charger_kw=22.0, vehicle_kw=11.0
        )
        assert plan.feasible is True
        assert all(k == 0.0 for k in plan.slot_kwh)

    def test_prefer_cheap_slots(self):
        """With one cheap slot and many expensive ones, should charge in cheap slot."""
        prices = [0.35, 0.35, 0.08, 0.35]  # off-peak at slot 2
        plan = charging_dp(
            e0_kwh=20.0, e_target_kwh=25.0, e_max_kwh=80.0,
            slot_prices=prices, charger_kw=22.0, vehicle_kw=22.0
        )
        assert plan.feasible is True
        # Slot 2 (index 2) should have the most charging
        assert plan.slot_kwh[2] >= max(plan.slot_kwh[0], plan.slot_kwh[1], plan.slot_kwh[3])

    def test_saving_non_negative(self):
        """Smart charging saving must always be ≥ 0 vs baseline."""
        prices = [0.22, 0.22, 0.14, 0.08, 0.08, 0.14]
        plan = charging_dp(
            e0_kwh=10.0, e_target_kwh=50.0, e_max_kwh=80.0,
            slot_prices=prices, charger_kw=22.0, vehicle_kw=22.0
        )
        assert plan.saving >= -0.001  # floating point tolerance

    def test_infeasible_when_not_enough_time(self):
        """With only 1 slot and 70 kWh needed, should be infeasible."""
        plan = charging_dp(
            e0_kwh=10.0, e_target_kwh=80.0, e_max_kwh=80.0,
            slot_prices=[0.14],  # only 1 × 15-min slot
            charger_kw=22.0, vehicle_kw=22.0
        )
        assert plan.feasible is False
        assert plan.best_achievable_kwh < 80.0

    def test_total_cost_positive(self):
        prices = [0.14] * 8
        plan = charging_dp(
            e0_kwh=20.0, e_target_kwh=60.0, e_max_kwh=80.0,
            slot_prices=prices, charger_kw=22.0, vehicle_kw=11.0
        )
        if plan.feasible:
            assert plan.total_cost > 0


class TestSoH:
    def test_healthy_battery(self):
        """A healthy battery should estimate near 100%."""
        state = SohEstimatorState()
        for _ in range(5):
            soh = estimate_soh(state, SohSession(
                delta_soc_pp=50.0,    # 30% → 80%
                energy_grid_kwh=42.0, # 42 kWh from grid
                nominal_kwh=80.0,     # 80 kWh nominal
            ))
        # SoH = (42 × 0.92) / (0.50 × 80) = 38.64 / 40 = 96.6%
        assert soh is not None
        assert 90.0 <= soh <= 100.0

    def test_degraded_battery(self):
        """A degraded battery should estimate lower SoH."""
        state = SohEstimatorState()
        for _ in range(5):
            soh = estimate_soh(state, SohSession(
                delta_soc_pp=50.0,
                energy_grid_kwh=32.0,  # less energy for same ΔSoC
                nominal_kwh=80.0,
            ))
        assert soh is not None
        assert soh < 90.0

    def test_insufficient_delta_soc_skipped(self):
        state = SohEstimatorState()
        result = estimate_soh(state, SohSession(10.0, 8.0, 80.0))  # only 10pp ΔSoC
        assert result is None

    def test_clamped_at_100(self):
        state = SohEstimatorState()
        soh = estimate_soh(state, SohSession(50.0, 100.0, 80.0))  # unrealistically high
        if soh is not None:
            assert soh <= 100.0


# ===========================================================================
# Routing tests
# ===========================================================================

def _make_simple_graph():
    """
    Triangle graph: A(0) → B(1) → C(2) → A
    A: (0.0, 0.0), B: (0.0, 0.01), C: (0.01, 0.005)
    """
    nodes = [
        Node(0, 0.0, 0.0),
        Node(1, 0.0, 0.01),
        Node(2, 0.01, 0.005),
    ]
    edges = [
        Edge(0, 1, 1.11, 50.0, "ARTERIAL"),
        Edge(1, 2, 1.24, 50.0, "ARTERIAL"),
        Edge(2, 0, 1.34, 50.0, "ARTERIAL"),
        Edge(0, 2, 1.56, 90.0, "HIGHWAY"),  # direct, highway
    ]
    return RoadGraph(nodes, edges)


class TestRouting:
    def test_snap_nearest_node(self):
        g = _make_simple_graph()
        nid = g.snap(0.0001, 0.0001)
        assert nid == 0  # closest to origin

    def test_dijkstra_src_to_self(self):
        g = _make_simple_graph()
        dist, parent = g.dijkstra(0, dst=0)
        assert dist[0] == pytest.approx(0.0)

    def test_dijkstra_finds_path(self):
        g = _make_simple_graph()
        dist, parent = g.dijkstra(0, dst=2)
        assert dist[2] < float("inf")
        assert dist[2] > 0

    def test_astar_same_or_lower_than_dijkstra(self):
        """A* energy cost should equal Dijkstra (admissible heuristic)."""
        g = _make_simple_graph()
        d_dist, _ = g.dijkstra(0, dst=2)
        d_cost = d_dist[2]  # Dijkstra cost to node 2
        a_cost, path = g.astar(0, 2)
        assert a_cost == pytest.approx(d_cost, rel=0.01)
        assert 0 in path and 2 in path

    def test_astar_unreachable(self):
        """Disconnected destination should return inf."""
        nodes = [Node(0, 0.0, 0.0), Node(1, 1.0, 1.0)]  # no edge
        g = RoadGraph(nodes, [])
        cost, path = g.astar(0, 1)
        assert cost == float("inf")
        assert path == []

    def test_charger_distance_table(self):
        g = _make_simple_graph()
        g.build_charger_distance_table([2])  # node 2 is a charger
        e = g.energy_to_nearest_charger(0)
        assert e > 0
        assert e < float("inf")

    def test_charger_distance_table_charger_node_is_zero(self):
        g = _make_simple_graph()
        g.build_charger_distance_table([2])
        assert g.energy_to_nearest_charger(2) == pytest.approx(0.0)

    def test_nearest_reachable_chargers(self):
        g = _make_simple_graph()
        chargers = [2]
        results = g.nearest_reachable_chargers(0, usable_kwh=10.0, charger_node_ids=chargers)
        assert len(results) >= 1
        assert results[0][0] == 2

    def test_energy_formula_increases_with_speed(self):
        g = _make_simple_graph()
        e_slow = g.edge_energy_kwh(10.0, 30.0, "SEDAN")
        e_fast = g.edge_energy_kwh(10.0, 120.0, "SEDAN")
        assert e_fast > e_slow

    def test_van_uses_more_energy_than_sedan(self):
        g = _make_simple_graph()
        e_sedan = g.edge_energy_kwh(10.0, 60.0, "SEDAN")
        e_van = g.edge_energy_kwh(10.0, 60.0, "VAN")
        assert e_van > e_sedan
