"""
Integration and Acceptance Tests for Phase 8 API Endpoints (§8.M2–M5, §9, §15.9).
Verifies Trips, Cost Analytics, EV Intelligence (Charging DP + Depot Allocator), and Alerts.
"""

import time
import pytest
from fastapi.testclient import TestClient

from services.api.main import app

client = TestClient(app)


def test_trips_endpoints():
    # 1. List trips
    resp = client.get("/v1/trips?limit=10")
    assert resp.status_code == 200
    data = resp.json()
    assert "items" in data
    assert len(data["items"]) >= 2
    trip0 = data["items"][0]
    assert "trip_id" in trip0
    assert "distance_km" in trip0
    assert "cost" in trip0
    assert trip0["status"] == "COMPLETED"

    # 2. Get single trip
    trip_id = trip0["trip_id"]
    resp_single = client.get(f"/v1/trips/{trip_id}")
    assert resp_single.status_code == 200
    detail = resp_single.json()
    assert detail["trip_id"] == trip_id
    assert "start_lat" in detail
    assert "start_geohash7" in detail
    assert "end_geohash7" in detail
    assert detail["distance_km"] > 0


def test_cost_endpoints():
    # 1. Cost summary
    resp = client.get("/v1/cost/summary?tenant_id=1")
    assert resp.status_code == 200
    summary = resp.json()
    assert summary["total_cost"] > 0
    assert summary["energy_cost"] > 0
    assert summary["idle_cost"] > 0
    assert summary["potential_savings"] > 0
    assert summary["cost_per_km"] > 0

    # 2. Top Idlers
    resp_top = client.get("/v1/cost/idling/top?limit=3")
    assert resp_top.status_code == 200
    idlers = resp_top.json()
    assert len(idlers) <= 3
    assert len(idlers) > 0
    assert "vehicle_pid" in idlers[0]
    assert idlers[0]["idle_minutes"] > 0
    assert idlers[0]["wasted_cost"] > 0


def test_ev_fleet_status_and_battery_health():
    # 1. EV fleet status
    resp = client.get("/v1/ev/fleet-status")
    assert resp.status_code == 200
    status = resp.json()
    assert status["total_evs"] > 0
    assert status["charging_now"] >= 0
    assert status["at_range_risk"] >= 0
    assert "0-20%" in status["soc_histogram"]

    # 2. Nearest reachable chargers
    resp_chargers = client.get("/v1/ev/nearest-charger?vehicle_pid=00000000-0000-0000-0000-000000000001&k=3")
    assert resp_chargers.status_code == 200
    chargers = resp_chargers.json()
    assert len(chargers) == 3
    assert chargers[0]["power_kw"] > 0
    assert chargers[0]["reachable"] is True

    # 3. Battery State of Health
    resp_soh = client.get("/v1/ev/battery-health/00000000-0000-0000-0000-000000000001")
    assert resp_soh.status_code == 200
    soh_data = resp_soh.json()
    assert 50.0 <= soh_data["current_soh_est"] <= 100.0
    assert soh_data["qualifying_sessions_count"] > 0


def test_ev_charging_dp_single_vehicle_plan():
    # Calculate single vehicle optimal DP schedule (§7.13, §8.M4)
    now = int(time.time())
    payload = {
        "vehicle_pid": "00000000-0000-0000-0000-000000000001",
        "plug_out_ts": now + 7200,  # 2 hours dwell
        "target_soc": 80.0,
        "charger_max_kw": 22.0
    }

    resp = client.post("/v1/ev/plan", json=payload)
    assert resp.status_code == 200
    plan = resp.json()

    assert "baseline_cost" in plan
    assert "smart_cost" in plan
    assert "saving" in plan
    assert plan["smart_cost"] <= plan["baseline_cost"] + 1e-4, "Smart cost must be <= baseline cost"
    assert plan["saving"] >= 0.0
    assert len(plan["schedule"]) > 0
    assert "kw" in plan["schedule"][0]
    assert "soc" in plan["schedule"][0]


def test_ev_depot_allocation_plan():
    # Calculate depot site-power cap allocation (§7.14, §8.M4)
    now = int(time.time())
    payload = {
        "depot_id": 101,
        "site_power_cap_kw": 50.0,
        "vehicles": [
            {
                "vehicle_pid": "00000000-0000-0000-0000-000000000001",
                "plug_out_ts": now + 14400,
                "needed_kwh": 30.0
            },
            {
                "vehicle_pid": "00000000-0000-0000-0000-000000000002",
                "plug_out_ts": now + 14400,
                "needed_kwh": 25.0
            }
        ]
    }

    resp = client.post("/v1/ev/depot-plan", json=payload)
    assert resp.status_code == 200
    depot_plan = resp.json()

    assert "total_baseline_cost" in depot_plan
    assert "total_smart_cost" in depot_plan
    assert "total_saving" in depot_plan
    assert depot_plan["total_saving"] >= 0.0
    assert depot_plan["peak_drawn_kw"] <= 50.0


def test_alerts_endpoint():
    resp = client.get("/v1/alerts?limit=5")
    assert resp.status_code == 200
    alerts = resp.json()
    assert len(alerts) > 0
    assert alerts[0]["severity"] in ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    assert "RANGE_RISK" in [a["type"] for a in alerts]
