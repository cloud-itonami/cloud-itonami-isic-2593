# ADR-0001: HardwareShopAdvisor ⊣ Hardware Shop Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2593` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2593` publishes an OSS blueprint for a cutlery/
hand-tool/general-hardware shop's **plant operations coordination**
(production-batch product-category/weight/defect-rate data logging,
forging-hammer/grinding-wheel/heat-treatment-furnace/finishing-line
maintenance scheduling, safety-concern flagging, and outbound cutlery/
hand-tool/general-hardware product shipment coordination). Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2599`
(Manufacture of other fabricated metal products n.e.c.): both are
back-office coordination actors for a fixed processing PLANT with
heavy manufacturing equipment and a real physical safety dimension,
and both share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination). The two
verticals are, however, distinct plants with distinct hazard profiles:
2599's central physical hazard is sharp-edge/burr laceration risk from
freshly stamped/pressed sheet metal, stamping-press pinch-point/crush
hazard, wire-forming-machine entanglement hazard, and coating/
lubricant/degreasing chemical exposure, while 2593's is sharp-edge
laceration risk from freshly forged/ground blades and edges (knife/
tool cutting edges), forging-hammer pinch/crush hazard, grinding-
wheel/abrasive-dust exposure, and heat-treatment-furnace burn/
radiant-heat exposure. This build mirrors 2599's architecture closely
but adapts the hazard profile and equipment/product vocabulary to the
cutlery/hand-tool/general-hardware shop: 2593's permanent equipment-
actuation block guards a forging hammer/grinding wheel/heat-treatment
furnace/finishing line (`:actuate-forge-grind-line?`) rather than a
stamping press/pressing line (`:actuate-press-line?`); and 2593's
production-batch record declares a `:product-category` (spanning
cutlery, hand tools, general hardware, garden tools, kitchen utensils,
and lock hardware, per ISIC 2593's own scope) rather than 2599's
`:product-category` covering stamped/pressed sheet-metal parts, wire
products and metal household goods.

`cloud-itonami-isic-2593` is also distinct from three sibling classes
in the same ISIC 259 group, none of which are `:implemented` in this
fleet yet as of this build (`kotoba-lang/industry` registry,
`:maturity :spec`): `cloud-itonami-isic-2591` (Forging, pressing,
stamping and roll-forming of metal -- the primary heavy metal-forming
process upstream of this shop's own forging line), `cloud-itonami-
isic-2592` (Treatment and coating of metals -- a distinct surface-
finishing process). ISIC 2593 is the specific "cutlery, hand tools and
general hardware" class: a shop that forges, grinds, heat-treats and
finishes stock into cutlery (knives/forks/spoons), hand tools
(hammers/wrenches/screwdrivers/pliers) and general hardware (locks,
hinges, fasteners, garden tools) -- a distinct plant, distinct process
shape (forging/grinding/heat-treatment/finishing, not sheet-metal
stamping/pressing/wire-forming or heavy roll-forming), and this build
follows the 2599-style four-op propose-only pattern specified for this
class.

This vertical has NO pre-existing `kotoba-lang/hardwaremfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`hardwaremfg.registry` (equipment/batch verification, shipment-weight
recompute, product-category validation, defect-rate plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2599`'s `metalfabmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:hardware-shop-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "hardware-shop-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external cutlery/hand-tool/hardware capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
cutlery/hand-tool/general-hardware vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-weight / product-category / defect-rate validation functions
live as pure functions in `hardwaremfg.registry` and are re-verified
independently by `hardwaremfg.governor` — the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2599`'s `metalfabmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of cutlery/hand-
tool/general-hardware shop plant operations. It does NOT:
- Control the forging hammer, grinding wheel, heat-treatment furnace or finishing line directly
- Make shop-safety or materials-safety decisions (exclusive to the human shop supervisor)
- Actuate the forging hammer, grinding wheel, heat-treatment furnace or finishing line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human shop-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: cutlery/hand-tool/general-hardware
manufacturing has real physical hazards (sharp-edge laceration risk
from freshly forged/ground blades and edges, forging-hammer pinch/
crush hazard, grinding-wheel/abrasive-dust exposure, heat-treatment-
furnace burn/radiant-heat exposure). Safety-concern flagging NEVER
auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (sharp-edge laceration risk, forging-hammer
pinch/crush hazard, grinding-wheel/abrasive-dust exposure, heat-
treatment-furnace burn/radiant-heat exposure, equipment-safety
concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" — it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2599`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "shop/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`hardwaremfg.governor`, mirroring `cloud-itonami-isic-2599`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Shop/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Any proposal touching forging/grinding-line-equipment control, or direct forge/grind-line actuation, is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Cutlery/hand-tool/general-hardware shop plant operations
back-office now has a documented, governed, auditable coordination
layer that funnels all decisions through independent validation before
human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human shop-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or forge/grind-line actuation. Safety
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and forge/grind-line operation
remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2593`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, forge-grind-line-
  actuate-blocked, already-scheduled, invalid-product-category,
  invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
