# cloud-itonami-isic-2593: Manufacture of cutlery, hand tools and general hardware

Open Business Blueprint for **ISIC Rev.5 2593**: manufacture of cutlery, hand tools and general hardware — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **cutlery/hand-tool/general-hardware shop plant operations**: production-batch data logging (product-category/weight/defect-rate), forging-hammer/grinding-wheel/heat-treatment-furnace/finishing-line maintenance scheduling, safety-concern flagging, and outbound cutlery/hand-tool/general-hardware product shipment coordination.

This repository designs a forkable OSS business for cutlery/hand-tool/
general-hardware shop plant operations: run by a qualified operator so
a shop keeps its own operating records instead of renting a closed
SaaS.

## Scope: the cutlery/hand-tool/general-hardware shop, not forging/pressing, coating, or other fabricated metal products

ISIC 2593 covers the shop that forges, grinds, heat-treats and
finishes stock into cutlery (knives, forks, spoons), hand tools
(hammers, wrenches, screwdrivers, pliers) and general hardware (locks,
hinges, fasteners, garden tools). This is distinct from
`cloud-itonami-isic-2591` (Forging, pressing, stamping and roll-
forming of metal), the primary heavy metal-forming process upstream of
this shop's own forging line; from `cloud-itonami-isic-2592`
(Treatment and coating of metals), a distinct surface-finishing
process; and from `cloud-itonami-isic-2599` (Manufacture of other
fabricated metal products n.e.c.), the residual stamping/pressing/
wire-forming shop, a distinct, more general product family. This
actor's own hazard profile centers on sharp-edge laceration risk from
freshly forged/ground blades and edges, forging-hammer pinch/crush
hazard, grinding-wheel/abrasive-dust exposure, and heat-treatment-
furnace burn/radiant-heat exposure.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-category/weight/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — forging-hammer/grinding-wheel/heat-treatment-furnace/finishing-line maintenance scheduling proposal
- `:flag-safety-concern` — surface a sharp-edge-laceration/forging-hammer-pinch-crush/grinding-wheel-abrasive-dust/heat-treatment-furnace-burn/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound cutlery/hand-tool/general-hardware product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-relevant domain**
(forging-hammer pinch/crush hazard, sharp-edge laceration risk from
freshly forged/ground blades and edges, grinding-wheel/abrasive-dust
exposure, heat-treatment-furnace burn/radiant-heat exposure):

- Does NOT control the forging hammer, grinding wheel, heat-treatment furnace or finishing line directly
- Does NOT make shop-safety or materials-safety decisions (that's the shop supervisor's exclusive human authority)
- Does NOT actuate the forging hammer, grinding wheel, heat-treatment furnace or finishing line (human shop supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`hardwaremfg.operation/build`, a langgraph-clj StateGraph):
1. **`hardwaremfg.advisor`** (sealed intelligence node, `HardwareShopAdvisor`): proposes decisions only, never commits
2. **`hardwaremfg.governor`** (independent, `Hardware Shop Plant Operations Governor`): validates against domain rules, re-derived from `hardwaremfg.registry`'s pure functions and `hardwaremfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Shop/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct forging/grinding-line-equipment control)
     - Any proposal touching forging/grinding-line-equipment control is a hard, permanent block (`:actuate-forge-grind-line? true` on a `:schedule-maintenance` proposal)
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`hardwaremfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`hardwaremfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
kbb -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
kbb -M:dev:test

# Run the demo
kbb -M:dev:run

# Lint
kbb -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
