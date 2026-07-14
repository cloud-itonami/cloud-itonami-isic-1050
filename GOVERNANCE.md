# Governance

## Actor Authority & Scope Boundaries

This actor operates under strict separation-of-powers: the **Governor** (independent compliance checker) censors the **Advisor** (LLM decision layer) before any commitment.

**The actor CANNOT and MUST NOT:**
- Control pasteurizer/cooler equipment directly
- Make food-safety certification decisions
- Interpret regulatory specifications beyond published jurisdiction requirements
- Override a Governor HOLD decision
- Commit production logging or shipment without human approval (Phase 1+)

**The actor CAN:**
- Propose batch operations backed by regulatory citations
- Monitor temperature, time, and quality metrics
- Surface food-safety concerns for human review
- Coordinate logistics within approved parameters

## Escalation Hierarchy

1. **Governor HARD violation** → HOLD (no override possible)
2. **Governor escalation** (low confidence, high-stakes) → ESCALATE (human review)
3. **Phase gate rejection** (Phase 0, early rollout) → HOLD
4. **Clean proposal** + **phase allows** → COMMIT (if no human approval required)

## Food-Safety Hold Reasons

A batch is HELD (cannot proceed) if any of the following are true:
- Raw milk quality (SCC/TBC) exceeds limits
- Pasteurization temperature out of spec
- Cooling temperature insufficient
- Pathogen screening failed
- Contamination flag raised and unresolved
- Evidence checklist incomplete per jurisdiction
- Sanitation score below minimum

None of these can be overridden by LLM confidence.

## Audit Trail

Every proposal is logged in append-only audit ledger:
- Advisor proposal (summary + confidence)
- Governor check (violations if any)
- Disposition (hold/escalate/commit)
- Human approval (if required)
- Result commitment (success/failure)

## Versioning & Change

Regulatory requirements evolve (FDA guidance, EFSA updates, MHLW directives). Changes to Governor rules are **additive only**: new hard checks may be introduced; existing hard checks may be refined; no removal of food-safety checks without EXPLICIT DEPRECATION in ADR.

This repository is AGPL-3.0-or-later. Forks are welcome; derivative works must remain open-source.
