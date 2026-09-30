# ADR-007: Hot-Reloadable Declarative OEM Mappings as Data

## Status
Accepted

## Context
Commercial fleets comprise vehicles from multiple Original Equipment Manufacturers (OEMs), third-party telematics control units (TCUs), and aftermarket OBD-II dongles. Each OEM outputs data in disparate schemas: JSON camelCase, imperial units with semicolon-delimited fault codes, EV signal lists, or raw pipe-delimited binary strings. Furthermore, OEMs periodically push Over-The-Air (OTA) firmware updates that alter data formats (schema drift).
Requiring code changes, CI/CD builds, and service restarts to onboard a new OEM or adapt to an OTA update introduces downtime, operational fragility, and delays.

## Decision
We treat OEM mappings as **Declarative Data** backed by a compacted Kafka topic and database store (§4.9, §5.2):

1. **Declarative Mapping DSL:**
   - Mapping rules define field-level transformations (unit scaling, coordinate parsing, enum translations, list delimiters, bit-twiddling).
   - Stored in PostgreSQL `oem_mapping.config` (JSONB) and validated against `oem_mapping_schema.json`.
2. **Compacted Kafka Topic (`config.oem-mappings`):**
   - Whenever an administrator publishes or updates a mapping via `POST /v1/admin/oem-mappings`, it is written to the compacted Kafka topic `config.oem-mappings`.
3. **Hot-Reload in Normalizer Workers:**
   - Normalizer services consume `config.oem-mappings`.
   - On receiving an updated mapping version, the worker validates the rules against pre-compiled golden test vectors in-memory. If tests pass, the worker atomically swaps its active mapping dictionary without restarting or dropping packets.
4. **Shadow Mode:**
   - New mapping versions can run in "SHADOW" status alongside the active version to compare output fidelity before full activation.

## Consequences
- **Positive:** Zero-downtime OEM onboarding and instantaneous resilience to OTA schema drift.
- **Negative:** Requires strict JSON schema validation and golden sample verification to prevent syntax errors in production mapping definitions.
