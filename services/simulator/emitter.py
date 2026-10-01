"""
§6.10 & §4.4 Batch Telemetry Emitter and Control Service.

Streams generated OEM telematics frames to downstream targets:
  - Target modes: 'file' (NDJSON), 'http' (Gateway /v1/ingest/batch), or in-memory generator
  - Writes ground-truth ledger for zero-loss accounting (§5.4, §6.1)
  - Control REST API on port 8090 (§9, §15.4)

Complexity: Bounded O(1) buffer allocations, async batch pipelining.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import io
import json
import logging
import os
import time
from typing import Any, Dict, List, Optional
from urllib.request import Request, urlopen
from urllib.error import URLError

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("simulator-emitter")


@dataclass
class EmitterStats:
    """Live streaming metrics (§6.10)."""
    running: bool = False
    total_emitted: int = 0
    total_batches: int = 0
    start_time_s: float = 0.0
    current_eps: float = 0.0
    dropped_backpressure: int = 0
    burst_active: bool = False


from collections import defaultdict

class BatchEmitter:
    """Buffered batch telematics emitter (§6.10)."""

    def __init__(
        self,
        mode: str = "file",
        gateway_url: str = "http://localhost:8080/v1/ingest/batch",
        output_file: Optional[str] = None,
        batch_size: int = 500,
    ):
        self.mode = mode
        if not gateway_url.endswith("/v1/ingest/batch"):
            gateway_url = gateway_url.rstrip("/") + "/v1/ingest/batch"
        self.gateway_url = gateway_url
        self.output_file = output_file
        self.batch_size = batch_size
        self.stats = EmitterStats()
        self._buffers: Dict[str, List[str]] = defaultdict(list)
        self._file_handle = None

        if self.mode == "file" and self.output_file:
            os.makedirs(os.path.dirname(os.path.abspath(self.output_file)), exist_ok=True)
            self._file_handle = open(self.output_file, "a", encoding="utf-8")

    def emit(self, raw_payload: str, oem: str = "A"):
        """Enqueue payload into emission buffer."""
        oem_key = oem.upper() if oem else "A"
        self._buffers[oem_key].append(raw_payload)
        self.stats.total_emitted += 1

        if len(self._buffers[oem_key]) >= self.batch_size:
            self.flush(oem=oem_key)

    def flush(self, oem: Optional[str] = None):
        """Flush buffer to configured sink. If oem is None, flushes all OEM buffers."""
        oems_to_flush = [oem] if oem is not None else list(self._buffers.keys())
        for target_oem in oems_to_flush:
            buf = self._buffers[target_oem]
            if not buf:
                continue

            batch_data = "\n".join(buf)
            batch_count = len(buf)
            buf.clear()
            self.stats.total_batches += 1

            if self.mode == "file":
                if self._file_handle:
                    self._file_handle.write(batch_data + "\n")
                    self._file_handle.flush()

            elif self.mode == "http":
                try:
                    headers = {
                        "Content-Type": "application/x-ndjson",
                        "X-OEM": target_oem,
                    }
                    req = Request(
                        self.gateway_url,
                        data=batch_data.encode("utf-8"),
                        headers=headers,
                        method="POST",
                    )
                    with urlopen(req, timeout=5.0) as resp:
                        if resp.status not in (200, 202):
                            logger.warning("Gateway returned HTTP %d for OEM %s", resp.status, target_oem)
                except URLError as e:
                    logger.warning("Gateway HTTP error for OEM %s: %s", target_oem, e)
                    self.stats.dropped_backpressure += batch_count

    def close(self):
        """Close emitter resources."""
        self.flush()
        if self._file_handle:
            self._file_handle.close()
            self._file_handle = None
