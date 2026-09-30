"""
Integration and Acceptance Tests for Phase 9 (S1-S3) and Phase 10 (Security, RBAC, Compliance).
Validates:
- S1: Driver Safety Leaderboard, Decayed Scores, Coaching & Harsh Events
- S2: Geofence CRUD, Asset Anomalies (After Hours, Tow Suspected), Unapproved Depots, Watchlist
- S3: Privacy-Safe Sharing (k-anonymity, DP Laplace noise, epsilon budget), Right-to-Erasure Workflow
- Security: OAuth2/JWT Authentication, RBAC Role Checks, Cross-Tenant Isolation, Location Masking Matrix, Immutable SHA-256 Audit Chain
"""

import time
import pytest
from fastapi.testclient import TestClient

from services.api.main import (
    app,
    create_access_token,
    verify_audit_chain,
    AUDIT_LOG_CHAIN,
    SHARING_BUDGET,
    SHARING_SPENT
)

client = TestClient(app)


# -----------------------------------------------------------------------------
# 1. Driver Safety Tests (§7.16, §8.S1)
# -----------------------------------------------------------------------------

def test_driver_safety_leaderboard_and_sorting():
    # 1. Sort ascending (riskiest drivers first)
    resp_asc = client.get("/v1/safety/drivers?sort=score_asc")
    assert resp_asc.status_code == 200
    data_asc = resp_asc.json()
    assert "items" in data_asc
    items_asc = data_asc["items"]
    assert len(items_asc) >= 3
    # Check ascending order
    for i in range(len(items_asc) - 1):
        assert items_asc[i]["score"] <= items_asc[i + 1]["score"]

    # 2. Sort descending (safest drivers first)
    resp_desc = client.get("/v1/safety/drivers?sort=score_desc")
    assert resp_desc.status_code == 200
    items_desc = resp_desc.json()["items"]
    assert items_desc[0]["score"] >= items_desc[-1]["score"]


def test_driver_coaching_and_risk_factors():
    # Driver 103 has low score (58.5) and high harsh brakes (14)
    resp = client.get("/v1/safety/drivers/103")
    assert resp.status_code == 200
    coach = resp.json()
    assert coach["driver_id"] == 103
    assert coach["score"] < 60.0
    assert coach["top_risk_factor"] == "HARSH_BRAKE"
    assert "braking" in coach["coaching_text"].lower()

    # Non-existent driver
    resp_404 = client.get("/v1/safety/drivers/9999")
    assert resp_404.status_code == 404


def test_vehicle_safety_events():
    resp = client.get("/v1/safety/vehicles/00000000-0000-0000-0000-000000000001/events")
    assert resp.status_code == 200
    events = resp.json()
    assert len(events) >= 2
    types = [e["event_type"] for e in events]
    assert "HARSH_BRAKE" in types
    assert "HARSH_CORNER" in types


# -----------------------------------------------------------------------------
# 2. Geofences & Asset Monitoring Tests (§7.17, §7.18, §8.S2)
# -----------------------------------------------------------------------------

def test_geofence_crud_lifecycle():
    # 1. List existing
    resp_list = client.get("/v1/geofences")
    assert resp_list.status_code == 200
    initial_count = len(resp_list.json())

    # 2. Create new geofence
    new_gf = {
        "fleet_id": 1,
        "name": "North Warehouse Zone",
        "type": "DEPOT",
        "coordinates": [[37.80, -122.40], [37.81, -122.40], [37.81, -122.39], [37.80, -122.39]]
    }
    resp_create = client.post("/v1/geofences", json=new_gf)
    assert resp_create.status_code == 201
    created = resp_create.json()
    gf_id = created["geofence_id"]
    assert created["name"] == "North Warehouse Zone"

    # 3. Get single
    resp_get = client.get(f"/v1/geofences/{gf_id}")
    assert resp_get.status_code == 200
    assert resp_get.json()["name"] == "North Warehouse Zone"

    # 4. Update
    update_payload = dict(new_gf)
    update_payload["name"] = "North Warehouse Superhub"
    resp_put = client.put(f"/v1/geofences/{gf_id}", json=update_payload)
    assert resp_put.status_code == 200
    assert resp_put.json()["name"] == "North Warehouse Superhub"

    # 5. Delete
    resp_del = client.delete(f"/v1/geofences/{gf_id}")
    assert resp_del.status_code == 204

    # Verify deleted
    resp_get_del = client.get(f"/v1/geofences/{gf_id}")
    assert resp_get_del.status_code == 404


def test_asset_anomalies_and_unapproved_depots():
    # 1. Asset anomalies
    resp_anom = client.get("/v1/assets/anomalies")
    assert resp_anom.status_code == 200
    anomalies = resp_anom.json()
    assert len(anomalies) >= 3
    types = [a["type"] for a in anomalies]
    assert "AFTER_HOURS_USE" in types
    assert "TOW_SUSPECTED" in types
    assert "SUSTAINED_OFF_DEPOT" in types

    # 2. Unapproved depots discovered via Union-Find (§7.18)
    resp_depots = client.get("/v1/assets/unapproved-depots")
    assert resp_depots.status_code == 200
    depots = resp_depots.json()
    assert len(depots) > 0
    assert depots[0]["vehicle_count"] >= 5
    assert depots[0]["total_dwell_hours"] > 20.0


def test_watchlist_asset_recovery_flow():
    pid = "00000000-0000-0000-0000-000000000005"

    # 1. Missing or invalid purpose should be rejected
    resp_invalid = client.post(f"/v1/assets/watchlist/{pid}", json={"purpose": "UNAUTHORIZED_TRACKING"})
    assert resp_invalid.status_code == 400

    # 2. Lawful purpose RECOVERY accepted
    resp_ok = client.post(f"/v1/assets/watchlist/{pid}", json={"purpose": "RECOVERY"})
    assert resp_ok.status_code == 200
    assert "watchlist" in resp_ok.json()["message"]

    # 3. Deactivate watchlist
    resp_del = client.delete(f"/v1/assets/watchlist/{pid}")
    assert resp_del.status_code == 204


# -----------------------------------------------------------------------------
# 3. Privacy, Data Sharing & Erasure Tests (§7.19, §8.S3)
# -----------------------------------------------------------------------------

def test_data_sharing_products_and_dp_noise():
    # 1. List sharing products
    resp_prod = client.get("/v1/share/products")
    assert resp_prod.status_code == 200
    products = resp_prod.json()
    assert len(products) == 3
    prod_ids = [p["product_id"] for p in products]
    assert "idle_density" in prod_ids
    assert "ev_charging_demand" in prod_ids

    # 2. Query sharing data with k-anonymity and DP noise
    resp_data = client.get("/v1/share/products/idle_density/data?from=2026-10-01T00:00:00Z&to=2026-10-01T01:00:00Z&precision=6")
    assert resp_data.status_code == 200
    rows = resp_data.json()
    assert len(rows) > 0
    for r in rows:
        # k-anonymity check: no row should have k < 5!
        assert r["k_count"] >= 5
        assert r["metric_value"] > 0
        assert len(r["geohash"]) == 6

    # 3. Exceeding allowed precision rejected
    resp_bad_prec = client.get("/v1/share/products/ev_charging_demand/data?from=2026-10-01T00:00:00Z&to=2026-10-01T01:00:00Z&precision=7")
    assert resp_bad_prec.status_code == 400


def test_privacy_budget_ledger_exhaustion():
    dsa_id = "test-dsa-budget"
    SHARING_BUDGET[dsa_id] = 0.5
    SHARING_SPENT[dsa_id] = 0.0

    # Query 1: 0.2 spent -> total 0.2 <= 0.5 (ok)
    r1 = client.get(f"/v1/share/products/idle_density/data?from=2026-10-01T00:00:00Z&to=2026-10-01T01:00:00Z&dsa_id={dsa_id}")
    assert r1.status_code == 200

    # Query 2: 0.2 spent -> total 0.4 <= 0.5 (ok)
    r2 = client.get(f"/v1/share/products/idle_density/data?from=2026-10-01T00:00:00Z&to=2026-10-01T01:00:00Z&dsa_id={dsa_id}")
    assert r2.status_code == 200

    # Query 3: 0.2 spent -> total 0.6 > 0.5 (exhausted!)
    r3 = client.get(f"/v1/share/products/idle_density/data?from=2026-10-01T00:00:00Z&to=2026-10-01T01:00:00Z&dsa_id={dsa_id}")
    assert r3.status_code == 429
    assert "budget exhausted" in r3.json()["detail"].lower()


def test_right_to_erasure_workflow_and_verification():
    target_pid = "00000000-0000-0000-0000-000000000005"

    # Submit erasure request (§8.S3)
    resp = client.post("/v1/privacy/erasure-requests", json={
        "subject_type": "VEHICLE",
        "subject_id": target_pid
    })
    assert resp.status_code == 202
    erasure = resp.json()
    req_id = erasure["request_id"]
    assert erasure["status"] == "COMPLETED"
    assert erasure["verification_report"]["verification_status"] == "PASSED"
    assert erasure["verification_report"]["records_remaining"] == 0
    assert erasure["verification_report"]["subject_unlinked"] is True

    # Retrieve request
    resp_get = client.get(f"/v1/privacy/erasure-requests/{req_id}")
    assert resp_get.status_code == 200
    assert resp_get.json()["request_id"] == req_id


# -----------------------------------------------------------------------------
# 4. Security, RBAC & Tenant Isolation Tests (§10.1, §10.5)
# -----------------------------------------------------------------------------

def test_rbac_role_enforcement():
    # Token with only FINANCE_ANALYST role
    finance_token = create_access_token(user_id="analyst-01", tenant_id=1, roles=["FINANCE_ANALYST"])
    headers = {"Authorization": f"Bearer {finance_token}"}

    # 1. Can access trips and cost
    r_cost = client.get("/v1/cost/summary", headers=headers)
    assert r_cost.status_code == 200

    # 2. Cannot access driver safety coaching (requires SAFETY_OFFICER or FLEET_MANAGER)
    r_safety = client.get("/v1/safety/drivers/101", headers=headers)
    assert r_safety.status_code == 403
    assert "forbidden" in r_safety.json()["detail"].lower()

    # 3. Cannot access geofence modification
    r_gf = client.post("/v1/geofences", json={
        "fleet_id": 1,
        "name": "Test Zone",
        "type": "DEPOT",
        "coordinates": [[0, 0], [1, 1]]
    }, headers=headers)
    assert r_gf.status_code == 403


def test_cross_tenant_isolation():
    # User belongs to tenant 2
    tenant2_token = create_access_token(user_id="user-t2", tenant_id=2, roles=["FLEET_MANAGER"])
    headers = {"Authorization": f"Bearer {tenant2_token}"}

    # 1. Attempting to query tenant 1 data must be rejected with 403
    r_cross = client.get("/v1/cost/summary?tenant_id=1", headers=headers)
    assert r_cross.status_code == 403
    assert "cross-tenant" in r_cross.json()["detail"].lower()

    # 2. Accessing own tenant 2 data succeeds
    r_own = client.get("/v1/cost/summary?tenant_id=2", headers=headers)
    assert r_own.status_code == 200


def test_location_masking_matrix_by_role():
    pid = "00000000-0000-0000-0000-000000000001"

    # 1. FLEET_MANAGER: receives high-precision GPS (4+ decimal places)
    fm_token = create_access_token(user_id="fm-01", tenant_id=1, roles=["FLEET_MANAGER"])
    resp_fm = client.get(f"/v1/vehicles/{pid}/live", headers={"Authorization": f"Bearer {fm_token}"})
    assert resp_fm.status_code == 200
    fm_lat = resp_fm.json()["lat"]
    assert fm_lat != 0.0

    # 2. SAFETY_OFFICER: receives coordinates masked to 3 decimal places (~110m)
    so_token = create_access_token(user_id="so-01", tenant_id=1, roles=["SAFETY_OFFICER"])
    resp_so = client.get(f"/v1/vehicles/{pid}/live", headers={"Authorization": f"Bearer {so_token}"})
    assert resp_so.status_code == 200
    so_lat = resp_so.json()["lat"]
    # Check that decimal places <= 3
    assert round(so_lat, 3) == so_lat

    # 3. AUDITOR: location is completely suppressed (0.0, 0.0)
    auditor_token = create_access_token(user_id="aud-01", tenant_id=1, roles=["AUDITOR"])
    resp_aud = client.get(f"/v1/vehicles/{pid}/live", headers={"Authorization": f"Bearer {auditor_token}"})
    assert resp_aud.status_code == 200
    assert resp_aud.json()["lat"] == 0.0
    assert resp_aud.json()["lon"] == 0.0


def test_immutable_sha256_audit_chain_integrity():
    # 1. Query audit trail as AUDITOR
    auditor_token = create_access_token(user_id="aud-01", tenant_id=1, roles=["AUDITOR"])
    resp = client.get("/v1/audit", headers={"Authorization": f"Bearer {auditor_token}"})
    assert resp.status_code == 200
    entries = resp.json()
    assert len(entries) > 0

    # 2. Verify cryptographical validity of SHA-256 rolling chain
    assert verify_audit_chain() is True

    # 3. Verify each entry contains row_hash and actor
    for entry in entries:
        assert len(entry["row_hash"]) == 64  # SHA-256 hex length
        assert "actor_id" in entry
        assert "action" in entry
