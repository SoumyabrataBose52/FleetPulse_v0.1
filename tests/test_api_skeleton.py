"""
Unit tests for FleetPulse Minimal Query API (§9, §15.8 Checkpoint S)
"""

from unittest.mock import MagicMock
import pytest
from fastapi.testclient import TestClient

from services.api.main import app, redis_client
import services.api.main as api_module


@pytest.fixture
def client():
    return TestClient(app)


def test_healthz_and_readyz(client):
    res1 = client.get("/healthz")
    assert res1.status_code == 200
    assert res1.json()["status"] == "UP"

    res2 = client.get("/readyz")
    assert res2.status_code == 200
    assert res2.json()["status"] == "UP"


def test_get_vehicle_live_state_from_redis(client, monkeypatch):
    mock_redis = MagicMock()
    mock_redis.hgetall.return_value = {
        "lat_e6": "37774900",
        "lon_e6": "-122419400",
        "speed": "65.5",
        "hdg": "180",
        "ts": "1700000000000",
        "status": "DRIVING",
        "soc": "85.0",
        "fuel": "50.0",
        "dtc_count": "0",
    }
    monkeypatch.setattr(api_module, "redis_client", mock_redis)

    res = client.get("/v1/vehicles/11111111-2222-3333-4444-555555555555/live?tenant_id=1")
    assert res.status_code == 200
    data = res.json()

    assert data["vehicle_pid"] == "11111111-2222-3333-4444-555555555555"
    assert data["lat"] == 37.7749
    assert data["lon"] == -122.4194
    assert data["speed_kmh"] == 65.5
    assert data["heading_deg"] == 180
    assert data["status"] == "DRIVING"
    assert data["soc_pct"] == 85.0
    assert data["fuel_pct"] == 50.0


def test_get_vehicle_live_state_not_found(client, monkeypatch):
    mock_redis = MagicMock()
    mock_redis.hgetall.return_value = {}
    monkeypatch.setattr(api_module, "redis_client", mock_redis)

    res = client.get("/v1/vehicles/missing-veh-pid/live")
    assert res.status_code == 404


def test_get_map_clusters(client, monkeypatch):
    mock_redis = MagicMock()
    # SF bay area geohashes precision 4 (e.g. 9q8y, 9q9p)
    mock_redis.hgetall.return_value = {
        "9q8y": "42",
        "9q9p": "18",
    }
    monkeypatch.setattr(api_module, "redis_client", mock_redis)

    # Viewport over California: min_lon,min_lat,max_lon,max_lat
    res = client.get("/v1/map/clusters?bbox=-123.0,36.0,-121.0,38.5&zoom=8&tenant_id=1")
    assert res.status_code == 200
    clusters = res.json()
    assert len(clusters) >= 1
    assert any(c["geohash"] == "9q8y" and c["count"] == 42 for c in clusters)
    assert "lat" in clusters[0] and "lon" in clusters[0]
