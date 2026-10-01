"""
FleetPulse Cold Storage Archiver Service (§4.3, §5.7, §15.8)
Listens to telemetry.clean.v1 and writes partitioned, zstd-compressed Parquet files.
"""

import argparse
import io
import json
import logging
import os
from pathlib import Path
import signal
import sys
import time

from confluent_kafka import Consumer, KafkaError
import fastavro

from services.archiver.writer import ParquetArchiver

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("archiver")


def parse_args():
    parser = argparse.ArgumentParser(description="FleetPulse Cold Storage Parquet Archiver")
    parser.add_argument("--output-dir", default=os.getenv("ARCHIVE_DIR", "data/archive"), help="Base archive output directory")
    parser.add_argument("--kafka-bootstrap", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"), help="Kafka bootstrap servers")
    parser.add_argument("--topic", default=os.getenv("KAFKA_TOPIC_CLEAN", "telemetry.clean.v1"), help="Clean telemetry Kafka topic")
    return parser.parse_args()


def load_avro_schema() -> dict:
    candidates = [
        Path("libs/schemas/avro/canonical_event.avsc"),
        Path("/app/libs/schemas/avro/canonical_event.avsc"),
        Path("../../libs/schemas/avro/canonical_event.avsc"),
    ]
    for c in candidates:
        if c.exists():
            with open(c, "r", encoding="utf-8") as f:
                return fastavro.parse_schema(json.load(f))
    raise FileNotFoundError("Could not find canonical_event.avsc schema")


def main():
    args = parse_args()
    logger.info("Starting FleetPulse Parquet Archiver on output dir: %s", args.output_dir)

    archiver = ParquetArchiver(base_output_dir=args.output_dir)
    avro_schema = load_avro_schema()

    conf = {
        'bootstrap.servers': args.kafka_bootstrap,
        'group.id': 'fleetpulse-archiver',
        'auto.offset.reset': 'earliest',
        'enable.auto.commit': False,
    }

    running = True

    def handle_signal(sig, frame):
        nonlocal running
        logger.info("Received termination signal %d. Shutting down gracefully...", sig)
        running = False

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    consumer = Consumer(conf)
    consumer.subscribe([args.topic])
    logger.info("Archiver ready. Subscribed to %s, awaiting telemetry batches...", args.topic)

    batch = []
    last_flush = time.time()

    try:
        while running:
            msg = consumer.poll(timeout=1.0)
            if msg is not None:
                if msg.error():
                    if msg.error().code() != KafkaError._PARTITION_EOF:
                        logger.error("Kafka consumer error: %s", msg.error())
                else:
                    try:
                        bio = io.BytesIO(msg.value())
                        rec = fastavro.schemaless_reader(bio, avro_schema)
                        batch.append(rec)
                    except Exception as ex:
                        logger.warning("Failed to decode canonical Avro record: %s", ex)

            now = time.time()
            if len(batch) >= 100 or (batch and now - last_flush >= 5.0):
                try:
                    paths = archiver.archive_batch(batch)
                    logger.info("Flushed %d telemetry records to %d Parquet partition files", len(batch), len(paths))
                    consumer.commit(asynchronous=False)
                except Exception as e:
                    logger.error("Failed to archive Parquet batch: %s", e)
                finally:
                    batch.clear()
                    last_flush = now
    finally:
        if batch:
            try:
                paths = archiver.archive_batch(batch)
                logger.info("Final flush: %d telemetry records to %d Parquet files", len(batch), len(paths))
                consumer.commit(asynchronous=False)
            except Exception as e:
                logger.error("Failed to flush final Parquet batch: %s", e)
        consumer.close()
        logger.info("Archiver closed successfully.")


if __name__ == "__main__":
    main()
