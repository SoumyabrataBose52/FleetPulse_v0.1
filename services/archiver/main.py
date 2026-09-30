"""
FleetPulse Cold Storage Archiver Service (§4.3, §5.7, §15.8)
Listens to telemetry.clean.v1 and writes partitioned, zstd-compressed Parquet files.
"""

import argparse
import logging
import os
import signal
import sys
import time
from pathlib import Path

from services.archiver.writer import ParquetArchiver

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("archiver")


def parse_args():
    parser = argparse.ArgumentParser(description="FleetPulse Cold Storage Parquet Archiver")
    parser.add_argument("--output-dir", default=os.getenv("ARCHIVE_DIR", "data/archive"), help="Base archive output directory")
    parser.add_argument("--kafka-bootstrap", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"), help="Kafka bootstrap servers")
    parser.add_argument("--topic", default=os.getenv("KAFKA_TOPIC_CLEAN", "telemetry.clean.v1"), help="Clean telemetry Kafka topic")
    return parser.parse_args()


def main():
    args = parse_args()
    logger.info("Starting FleetPulse Parquet Archiver on output dir: %s", args.output_dir)

    archiver = ParquetArchiver(base_output_dir=args.output_dir)

    running = True

    def handle_signal(sig, frame):
        nonlocal running
        logger.info("Received termination signal %d. Shutting down gracefully...", sig)
        running = False

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    logger.info("Archiver ready. Awaiting telemetry batches...")


if __name__ == "__main__":
    main()
