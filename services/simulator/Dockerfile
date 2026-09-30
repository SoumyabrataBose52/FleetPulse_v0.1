FROM python:3.10-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Copy fpcore and requirements
COPY libs/py/fpcore /app/libs/py/fpcore
COPY config /app/config
COPY services/simulator /app/services/simulator

# Install fpcore and dependencies
RUN pip install --no-cache-dir pyyaml xxhash fastavro \
    && pip install --no-cache-dir -e /app/libs/py/fpcore

ENV PYTHONPATH=/app
ENTRYPOINT ["python", "services/simulator/main.py"]
CMD ["run", "--vehicles", "1000", "--mode", "file"]
