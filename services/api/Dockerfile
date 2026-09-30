# Multi-stage Dockerfile for FleetPulse API Service (§9, §15.8, §15.11)
FROM python:3.10-slim AS base

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY services/api/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY libs/schemas /app/libs/schemas
COPY libs/py/fpcore /app/libs/py/fpcore
RUN pip install --no-cache-dir -e /app/libs/py/fpcore

COPY services/api /app/services/api

RUN useradd -m -u 1000 fleetpulse
USER fleetpulse

EXPOSE 8000
ENTRYPOINT ["uvicorn", "services.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
