# Multi-stage Dockerfile for FleetPulse Archiver Service (§4.3, §5.7, §15.8)
FROM python:3.10-slim AS base

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY services/archiver/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY libs/schemas /app/libs/schemas
COPY services/archiver /app/services/archiver

RUN useradd -m -u 1000 fleetpulse
USER fleetpulse

ENTRYPOINT ["python", "-m", "services.archiver.main"]
