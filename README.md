# cloud-itonami-isic-1050: Dairy Processing Coordination Actor

**ISIC Rev. 5 1050** — Manufacture of Dairy Products

A distributed actor for autonomous, compliant coordination of dairy processing operations: raw milk intake → pasteurization → cooling → finished product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Plant operator authority and food-safety responsibility remain exclusive to licensed dairy plant staff.

## Scope

This actor coordinates **plant-operations workflow** for dairy processing:
- Raw milk batch intake and quality verification (SCC, TBC, pathogens)
- Pasteurization temperature and hold-time monitoring
- Cooling and cold-chain validation
- Finished product staging and shipment logistics
- Food-safety concern escalation

**Out of scope:**
- Direct pasteurizer/cooler equipment control (operator exclusive)
- Food-safety certification authority (human inspector/regulator only)
- Regulatory interpretation (proposals cite jurisdiction specifications; Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement:
- **Hard HOLD** (un-overridable):
  - No jurisdiction citation (can't verify reqs)
  - Evidence checklist incomplete
  - Raw milk quality out of spec (SCC/TBC)
  - Pasteurization temperature out of range (pathogen kill risk)
  - Cooling temperature insufficient (cold-chain breach)
  - Holding time exceeded (pathogen multiplication)
  - Plant sanitation score insufficient
  - Pathogen screening failed (Listeria/Salmonella/E.coli)
  - Contamination flag unresolved
- **Escalate** (human review required):
  - Low LLM confidence
  - High-stakes actions (production logging, shipment coordination)
- **Commit** (LLM proposal approved, Governor clean, phase allows):
  - Monitoring/flagging operations in appropriate phase

### Operations (Proposals)

Allowed operation types:
- **`:log-production-batch`** — `:effect :propose` — Log intake → pasteurization → storage batch into production records (requires human sign-off)
- **`:coordinate-shipment`** — `:effect :propose` — Finalize shipment of finished product (requires human sign-off)
- **`:flag-food-safety-concern`** — `:effect :propose` — Surface potential contamination/temperature/quality issues (always escalates)
- **`:schedule-maintenance`** — `:effect :propose` — Propose equipment maintenance (operational, low risk)

All operations are `effect :propose`; commit authority is Governor + phase gate + human.

### Phase Rollout (0→3)

- **Phase 0** (Simulation): Monitoring only; production ops HOLD
- **Phase 1** (Supervised): High-stakes ops require human escalation
- **Phase 2** (Reduced): Escalate on Governor violation or low confidence
- **Phase 3** (Autonomous): LLM confidence sufficient for routine ops

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
