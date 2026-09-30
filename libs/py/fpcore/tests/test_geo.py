"""
Tests for fpcore.geo — Haversine, GPS Filter, Geohash (§7.3, §7.4)
"""

import math
import pytest
from fpcore.geo import (
    haversine_km,
    haversine_m,
    GpsPoint,
    GpsFilterState,
    gps_filter_step,
    geohash_encode,
    geohash_decode,
    geohash_neighbors,
    geohash_cover_bbox,
)


class TestHaversine:
    def test_same_point_is_zero(self):
        assert haversine_km(13.08, 80.27, 13.08, 80.27) == pytest.approx(0.0, abs=1e-9)

    def test_known_distance_equator(self):
        # From (0, 0) to (0, 1°) ≈ 111.195 km at equator
        d = haversine_km(0, 0, 0, 1)
        assert d == pytest.approx(111.195, rel=0.001)

    def test_symmetry(self):
        d1 = haversine_km(13.0, 80.0, 14.0, 81.0)
        d2 = haversine_km(14.0, 81.0, 13.0, 80.0)
        assert d1 == pytest.approx(d2, rel=1e-10)

    def test_metres_is_1000x_km(self):
        km = haversine_km(13.0, 80.0, 13.1, 80.1)
        m = haversine_m(13.0, 80.0, 13.1, 80.1)
        assert m == pytest.approx(km * 1000, rel=1e-10)

    def test_antipodal_points(self):
        # Antipodes are ~20,015 km apart
        d = haversine_km(0, 0, 0, 180)
        assert d == pytest.approx(20015.0, rel=0.01)


class TestGpsFilter:
    def _make_state(self):
        return GpsFilterState()

    def test_first_point_always_accepted(self):
        state = self._make_state()
        p = GpsPoint(lat=13.0, lon=80.0, ts_s=0.0, speed_kmh=0.0)
        valid, quality = gps_filter_step(state, p)
        assert valid is True
        assert quality == 0

    def test_normal_movement_accepted(self):
        state = self._make_state()
        p1 = GpsPoint(13.0000, 80.0000, 0.0, 0.0)
        p2 = GpsPoint(13.0001, 80.0001, 1.0, 20.0)  # ~15 m/s implied
        gps_filter_step(state, p1)
        valid, quality = gps_filter_step(state, p2)
        assert valid is True
        assert quality == 0

    def test_teleport_flagged_as_suspect(self):
        state = self._make_state()
        p1 = GpsPoint(13.0, 80.0, 0.0, 50.0)
        p2 = GpsPoint(14.0, 81.0, 1.0, 50.0)  # 1° in 1s ≈ 160 km/h implied vs 50 km/h reported
        gps_filter_step(state, p1)
        valid, quality = gps_filter_step(state, p2)
        # Implied speed ≈ 156 km/h, threshold = max(250, 3*50)=250, so valid
        # Actually 156 < 250 so it should be valid!
        # Let's use a more extreme jump
        p2_extreme = GpsPoint(18.0, 86.0, 1.0, 50.0)  # ~700 km in 1s
        state2 = self._make_state()
        gps_filter_step(state2, p1)
        valid2, quality2 = gps_filter_step(state2, p2_extreme)
        assert valid2 is False
        assert quality2 == 1  # GPS_SUSPECT

    def test_relocation_escape_hatch(self):
        """3 consecutive suspect points agreeing → relocation accepted."""
        state = self._make_state()
        anchor = GpsPoint(13.0, 80.0, 0.0, 0.0)
        gps_filter_step(state, anchor)

        # 3 points all teleported to same new location
        new_lat, new_lon = 19.0, 73.0  # ~800 km away
        for i in range(3):
            p = GpsPoint(new_lat + i * 0.00001, new_lon + i * 0.00001, float(i + 1), 0.0)
            valid, quality = gps_filter_step(state, p)

        # After 3 agreeing suspect points, the last call should accept relocation
        assert valid is True

    def test_zero_speed_vehicle_jitter_not_flagged(self):
        """4m GPS jitter at 1Hz with speed≈0 must not be flagged as outlier."""
        state = self._make_state()
        p1 = GpsPoint(13.000000, 80.000000, 0.0, speed_kmh=0.0)
        # 4m ≈ 0.000036° lat at this latitude
        p2 = GpsPoint(13.000036, 80.000036, 1.0, speed_kmh=0.0)
        gps_filter_step(state, p1)
        valid, quality = gps_filter_step(state, p2)
        assert valid is True
        assert quality == 0


class TestGeohash:
    def test_encode_decode_roundtrip(self):
        for precision in range(1, 9):
            lat, lon = 13.0827, 80.2707
            h = geohash_encode(lat, lon, precision)
            assert len(h) == precision
            lat_c, lon_c, lat_err, lon_err = geohash_decode(h)
            assert abs(lat_c - lat) <= lat_err + 1e-9
            assert abs(lon_c - lon) <= lon_err + 1e-9

    def test_known_geohash(self):
        # Chennai, India
        h = geohash_encode(13.0827, 80.2707, 6)
        assert len(h) == 6
        lat_c, lon_c, lat_err, lon_err = geohash_decode(h)
        assert abs(lat_c - 13.0827) < 0.01
        assert abs(lon_c - 80.2707) < 0.01

    def test_neighbors_returns_8(self):
        h = geohash_encode(13.0827, 80.2707, 6)
        n = geohash_neighbors(h)
        assert len(n) == 8
        for direction in ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]:
            assert direction in n
            assert len(n[direction]) == 6

    def test_neighbors_are_different_from_center(self):
        h = geohash_encode(13.0827, 80.2707, 6)
        n = geohash_neighbors(h)
        for neighbour in n.values():
            assert neighbour != h

    def test_cover_bbox_small_area(self):
        # A small bbox should be coverable at high precision with ≤ 64 cells
        precision, cells = geohash_cover_bbox(13.0, 80.0, 13.1, 80.1)
        assert len(cells) <= 64
        assert precision >= 5  # small area should use high precision

    def test_cover_bbox_large_area(self):
        # World-spanning bbox: function should not crash and should return something
        # (It won't be ≤64 cells for the whole world, that's expected)
        precision, cells = geohash_cover_bbox(-90, -180, 90, 180)
        assert isinstance(cells, list)
        assert len(cells) > 0
        assert 1 <= precision <= 7

    def test_encode_precision_7_cell_size(self):
        """Precision 7 should have ~153m error (±0.00061° lat, ±0.00122° lon)."""
        h = geohash_encode(13.0827, 80.2707, 7)
        _, _, lat_err, lon_err = geohash_decode(h)
        # Cell half-size at precision 7: ~0.00061° lat × ~0.00122° lon
        assert lat_err < 0.002
        assert lon_err < 0.002

    def test_never_panics(self):
        """Must not raise on any valid lat/lon/precision."""
        import random
        rng = random.Random(42)
        for _ in range(1000):
            lat = rng.uniform(-90, 90)
            lon = rng.uniform(-180, 180)
            prec = rng.randint(1, 9)
            try:
                h = geohash_encode(lat, lon, prec)
                geohash_decode(h)
            except Exception as e:
                pytest.fail(f"Raised {e} for lat={lat} lon={lon} prec={prec}")
