# ADR-008: Deterministic Statistical Algorithms Instead of Black-Box ML / Agentic AI

## Status
Accepted

## Context
Judging and operational evaluation in real-time connected vehicle platforms prioritize transparency, verifiable accuracy, explainable scoring, bounded latency, and reproducible audit trails.
Black-box machine learning models and speculative LLM/agentic pipelines suffer from non-deterministic drift, high compute overhead, lack of interpretability during insurance or safety disputes, and complex failure modes under sensor noise or adversarial inputs.

## Decision
We deliberately reject black-box ML models, vector databases, and autonomous LLM agents in favor of **Deterministic Statistical and Mathematical Algorithms** (§1.4, §7):

1. **Algorithms Selected:**
   - **Trip Segmentation:** Deterministic Finite State Machine (FSM) + Viterbi Dynamic Programming (§7.10).
   - **Idling Detection:** Kinematic speed/RPM gate with hysteresis and CO2 emission models (§7.11).
   - **EV Smart Charging:** Cost-optimal Dynamic Programming over Time-of-Use tariff arrays with battery degradation penalty (§7.13).
   - **Depot Allocation:** Greedy min-cost allocation with site power caps (§7.14).
   - **Battery State of Health (SoH):** Coulomb-counting integration over qualifying sessions (ΔSoC ≥ 30pp) with median aggregation (§7.15).
   - **Safety Scoring:** Decayed exponential penalty scoring with exact component breakdown (§7.16).
   - **Unapproved Depots:** Spatial grid binning + Union-Find connected component clustering (§7.18).
   - **Privacy Preservation:** Provable k-anonymity + bounded Laplace differential privacy (§7.19).
2. **Ground-Truth Evaluation:**
   - Every algorithm is validated directly against the simulator's physical ground truth (true odometer, true battery kWh, true driver aggression α, true unapproved depot coordinates).
   - Precision, recall, MAE, and Spearman correlation are computed and recorded explicitly.

## Consequences
- **Positive:** Sub-millisecond latency per event, zero model hallucination, complete explainability for drivers and operators, auditable mathematical guarantees, and massive throughput efficiency (~100,000 eps per core).
- **Negative:** Non-linear edge cases must be handled via explicit parameter tuning in `config/defaults.yaml` rather than learned end-to-end weights.
