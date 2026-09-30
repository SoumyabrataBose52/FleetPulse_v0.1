# ADR-005: Pseudonymous Telemetry Keys and Instant Unlink Erasure

## Status
Accepted

## Context
Compliance frameworks (GDPR Article 17, India DPDP Act) mandate a "Right to Erasure" (Right to be Forgotten) for vehicle owners and drivers. Telemetry streams continuously record fine-grained timestamps and GPS coordinates. If personal identifiers such as VINs or Driver License numbers are embedded in high-volume columnar stores (ClickHouse) or cold archives (Parquet on S3), executing real-time `DELETE` operations across billions of rows causes massive write amplification, storage churn, and severe read degradation.

## Decision
We enforce a **Two-Tier Pseudonymization and Instant Unlinking** architecture (§4.12, §8.S3):

1. **Pseudonymous Telemetry Keys (`vehicle_pid`):**
   - The Vehicle Identification Number (VIN) is stripped immediately at the Normalizer stage (§4.2).
   - Downstream topics (`telemetry.canonical.v1`, `telemetry.clean.v1`), ClickHouse tables, MongoDB documents, and S3 Parquet files store only pseudonymous UUIDs (`vehicle_pid`).
   - The mapping between `vin` and `vehicle_pid` is stored exclusively in PostgreSQL in the `vehicle` table.
2. **Instant Anonymization via Unlinking:**
   - Upon receiving an erasure request (`POST /v1/privacy/erasure-requests`), the system immediately executes step 1:
     - Nullifies or deletes the VIN and driver links in PostgreSQL (`UPDATE vehicle SET erased_at = NOW(), vin = NULL WHERE vehicle_pid = :pid`).
     - Flushes Redis vehicle caches.
   - Result: Any retained historical telemetry is immediately rendered anonymous because it can no longer be linked to a physical vehicle or identity.
3. **Asynchronous Physical Purge:**
   - The system dispatches an `ErasureCommand` over `commands.erasure.v1`.
   - Asynchronous batch jobs perform targeted partition rewrites in ClickHouse and S3 to physically delete rows within the statutory SLA (30 days), generating a verifiable cryptographic audit certificate.

## Consequences
- **Positive:** Complies with privacy regulations immediately (sub-second unlink SLA) without impacting real-time analytical pipeline throughput.
- **Negative:** Telemetry cannot be re-identified after the Postgres unlink step; audit logs must capture the erasure authorization.
