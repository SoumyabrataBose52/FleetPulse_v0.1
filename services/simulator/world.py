"""
§6.2 World Model and Synthetic Road Graph.

Generates the synthetic geographical environment for the simulation:
  - 40x40 km metro region (default center 13.0827, 80.2707)
  - Road graph with CSR representation: 3,600 nodes, ~14,000 directed edges
  - Hierarchical edge classes: HIGHWAY (90 km/h), ARTERIAL (55 km/h), LOCAL (35 km/h)
  - Places: 120 approved depots, 800 POIs, 200 chargers, 5 hidden unapproved sites
  - Diurnal sinusoidal temperature model (26-36 °C)

Complexity:
  - World generation: O(N + E)
  - Nearest POI / Charger lookup: O(log K) with spatial indexing
"""

from __future__ import annotations

from dataclasses import dataclass, field
import json
import math
import random
from typing import Dict, List, Optional, Tuple

from fpcore.routing import RoadGraph, Node, Edge
from fpcore.geo import haversine_km, geohash_encode


@dataclass(frozen=True)
class Depot:
    depot_id: int
    fleet_id: int
    name: str
    lat: float
    lon: float
    radius_m: float
    site_power_cap_kw: float
    is_approved: bool = True


@dataclass(frozen=True)
class Charger:
    charger_id: int
    network: str
    lat: float
    lon: float
    power_kw: float
    connector: str
    tariff_id: int
    depot_id: Optional[int] = None


@dataclass(frozen=True)
class POI:
    poi_id: int
    name: str
    lat: float
    lon: float
    archetype_mask: int = 0xFF


class World:
    """Simulation world environment (§6.2)."""

    def __init__(
        self,
        center_lat: float = 13.0827,
        center_lon: float = 80.2707,
        grid_dim: int = 60,
        grid_spacing_m: float = 650.0,
        seed: int = 42,
    ):
        self.center_lat = center_lat
        self.center_lon = center_lon
        self.grid_dim = grid_dim
        self.grid_spacing_m = grid_spacing_m
        self.rng = random.Random(seed)

        self.depots: List[Depot] = []
        self.chargers: List[Charger] = []
        self.pois: List[POI] = []
        self.unapproved_depots: List[Depot] = []

        self.road_graph: RoadGraph = self._build_synthetic_road_graph()
        self._build_places()

    def _build_synthetic_road_graph(self) -> RoadGraph:
        """
        Build ~60x60 grid with diagonal arterials and ring highway (~3,600 nodes, ~14,000 edges).
        """
        # Degrees per meter approximations near latitude 13.0
        m_per_deg_lat = 110574.0
        m_per_deg_lon = 111320.0 * math.cos(math.radians(self.center_lat))

        d_lat = self.grid_spacing_m / m_per_deg_lat
        d_lon = self.grid_spacing_m / m_per_deg_lon

        half_grid = self.grid_dim / 2.0
        start_lat = self.center_lat - half_grid * d_lat
        start_lon = self.center_lon - half_grid * d_lon

        nodes: List[Node] = []
        edges: List[Edge] = []

        # 1. Create nodes with slight realistic jitter (+/- 20m)
        for r in range(self.grid_dim):
            for c in range(self.grid_dim):
                node_id = r * self.grid_dim + c
                jitter_lat = (self.rng.random() - 0.5) * (40.0 / m_per_deg_lat)
                jitter_lon = (self.rng.random() - 0.5) * (40.0 / m_per_deg_lon)
                lat = start_lat + r * d_lat + jitter_lat
                lon = start_lon + c * d_lon + jitter_lon
                nodes.append(Node(node_id=node_id, lat=lat, lon=lon))

        # Helper to compute edge
        def add_bidirectional_edge(u: int, v: int, speed: float, edge_class: str):
            dist_km = haversine_km(nodes[u].lat, nodes[u].lon, nodes[v].lat, nodes[v].lon)
            edges.append(Edge(
                src=u,
                dst=v,
                length_km=dist_km,
                speed_limit_kmh=speed,
                road_class=edge_class,
            ))
            edges.append(Edge(
                src=v,
                dst=u,
                length_km=dist_km,
                speed_limit_kmh=speed,
                road_class=edge_class,
            ))

        # 2. Grid connections (LOCAL: 35 km/h, ARTERIAL: 55 km/h)
        for r in range(self.grid_dim):
            for c in range(self.grid_dim):
                u = r * self.grid_dim + c
                # Arterials every 5 rows/cols
                is_arterial_row = (r % 5 == 0)
                is_arterial_col = (c % 5 == 0)

                # Horizontal edge
                if c + 1 < self.grid_dim:
                    v = r * self.grid_dim + (c + 1)
                    speed = 55.0 if is_arterial_row else 35.0
                    e_cls = "ARTERIAL" if is_arterial_row else "LOCAL"
                    add_bidirectional_edge(u, v, speed, e_cls)

                # Vertical edge
                if r + 1 < self.grid_dim:
                    v = (r + 1) * self.grid_dim + c
                    speed = 55.0 if is_arterial_col else 35.0
                    e_cls = "ARTERIAL" if is_arterial_col else "LOCAL"
                    add_bidirectional_edge(u, v, speed, e_cls)

        # 3. Diagonal arterials across the quadrants
        for i in range(self.grid_dim - 1):
            if i % 3 == 0:
                # Main diagonal
                u = i * self.grid_dim + i
                v = (i + 1) * self.grid_dim + (i + 1)
                add_bidirectional_edge(u, v, 60.0, "ARTERIAL")

                # Anti diagonal
                u2 = i * self.grid_dim + (self.grid_dim - 1 - i)
                v2 = (i + 1) * self.grid_dim + (self.grid_dim - 2 - i)
                add_bidirectional_edge(u2, v2, 60.0, "ARTERIAL")

        # 4. Ring Highway around boundary nodes (HIGHWAY: 90 km/h)
        ring_offset = 2
        ring_r_min, ring_r_max = ring_offset, self.grid_dim - 1 - ring_offset
        ring_c_min, ring_c_max = ring_offset, self.grid_dim - 1 - ring_offset

        # Top & Bottom ring highway
        for c in range(ring_c_min, ring_c_max):
            add_bidirectional_edge(ring_r_min * self.grid_dim + c, ring_r_min * self.grid_dim + c + 1, 90.0, "HIGHWAY")
            add_bidirectional_edge(ring_r_max * self.grid_dim + c, ring_r_max * self.grid_dim + c + 1, 90.0, "HIGHWAY")

        # Left & Right ring highway
        for r in range(ring_r_min, ring_r_max):
            add_bidirectional_edge(r * self.grid_dim + ring_c_min, (r + 1) * self.grid_dim + ring_c_min, 90.0, "HIGHWAY")
            add_bidirectional_edge(r * self.grid_dim + ring_c_max, (r + 1) * self.grid_dim + ring_c_max, 90.0, "HIGHWAY")

        return RoadGraph(nodes=nodes, edges=edges)

    def _build_places(self):
        """Construct depots, POIs, chargers, and unapproved hidden sites (§6.2)."""
        nodes = self.road_graph.nodes
        total_nodes = len(nodes)

        # 1. Depots (~120 approved depots)
        for i in range(120):
            node_idx = self.rng.randint(0, total_nodes - 1)
            node = nodes[node_idx]
            self.depots.append(Depot(
                depot_id=i + 1,
                fleet_id=(i % 40) + 1,
                name=f"Depot-{i+1:03d}",
                lat=node.lat,
                lon=node.lon,
                radius_m=150.0,
                site_power_cap_kw=250.0 + (i % 5) * 50.0,
                is_approved=True,
            ))

        # 2. Hidden unapproved depot clusters (§8.S2)
        for i in range(5):
            node_idx = self.rng.randint(0, total_nodes - 1)
            node = nodes[node_idx]
            self.unapproved_depots.append(Depot(
                depot_id=1000 + i + 1,
                fleet_id=0,
                name=f"UnapprovedSite-{i+1}",
                lat=node.lat,
                lon=node.lon,
                radius_m=100.0,
                site_power_cap_kw=0.0,
                is_approved=False,
            ))

        # 3. Chargers (~200 chargers: DC fast 50/150kW and AC 22kW)
        for i in range(200):
            node_idx = self.rng.randint(0, total_nodes - 1)
            node = nodes[node_idx]
            is_dc = (i % 3 != 0)
            power = 150.0 if (i % 6 == 0) else (50.0 if is_dc else 22.0)
            network = "PulseFastDC" if is_dc else "CityAC"
            tariff_id = 4 if is_dc else 1  # 4 = public DC tariff, 1 = standard TOU
            self.chargers.append(Charger(
                charger_id=i + 1,
                network=network,
                lat=node.lat,
                lon=node.lon,
                power_kw=power,
                connector="CCS2" if is_dc else "TYPE2",
                tariff_id=tariff_id,
            ))

        # 4. Customer / POI delivery destination nodes (~800)
        for i in range(800):
            node_idx = self.rng.randint(0, total_nodes - 1)
            node = nodes[node_idx]
            self.pois.append(POI(
                poi_id=i + 1,
                name=f"POI-{i+1:04d}",
                lat=node.lat,
                lon=node.lon,
            ))

    def get_ambient_temperature(self, ts_epoch_s: float) -> float:
        """
        Diurnal sinusoidal ambient temperature model (§6.2):
        Peaking around 14:00 local time between 26 °C and 36 °C.
        """
        # Time of day in hours [0.0, 24.0)
        hour_of_day = ((ts_epoch_s % 86400) / 3600.0)
        # Peak at hour 14.0 (14.0 - 6.0 = 8.0 rad shift)
        sin_val = math.sin((hour_of_day - 8.0) * (2.0 * math.pi / 24.0))
        temp = 31.0 + 5.0 * sin_val
        return round(temp, 2)
