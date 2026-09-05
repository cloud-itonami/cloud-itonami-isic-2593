(ns hardwaremfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`hardwaremfg.operation` ->
  `hardwaremfg.governor` -> `hardwaremfg.store`) over langgraph
  (`langgraph.graph/run*`) through a scenario adapted from this repo's
  own `hardwaremfg.sim` demo driver (`clojure -M:dev:run`, confirmed
  BEFORE writing this file to produce a sensible ledger against the real
  seeded ids `batch-001`..`batch-003` / `forge-001` / `grind-002` --
  this repo's sim driver uses ids that DO match
  `hardwaremfg.store/sample-data!`, so it was safe to adapt rather than
  author from scratch).

  Every number, id, disposition and hold reason on the page is read back
  out of the real store / real governor verdicts after the run. Nothing
  is hand-typed, with one explicitly-marked exception: the `Action gate`
  table, which describes this actor's own FIXED op contract
  (`hardwaremfg.governor` / `hardwaremfg.phase`) rather than run
  telemetry -- the same carve-out the sibling `applianceshop.render-html`
  makes, and it is labelled as such on the page.

  Deterministic: byte-identical across reruns against the same seed. No
  timestamps, no random, no map-iteration-order dependence -- every
  collection rendered is either the append-only ledger (insertion
  ordered), an explicitly `sort-by`-ed store listing, or a literal
  vector.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [hardwaremfg.store :as store]
            [hardwaremfg.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :shop-coordinator :phase 3})

(defn- exec!
  "One coordination request = one graph run. Returns langgraph's run map."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve!
  "Resume a run paused by `interrupt-before #{:request-approval}` with a
  human shop supervisor's approval."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- audit-of
  "The `:audit` channel of a finished run."
  [run]
  (get-in run [:state :audit]))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach.

  CLEAN LIFECYCLE (all four ops, end to end, against the seeded
  verified+registered `batch-001` / `forge-001`):
    - `:log-production-batch` batch-001 -- governor-clean and the ONLY
      op in phase 3's `:auto` set, so it auto-commits with no human.
    - `:schedule-maintenance` mnt-1 on forge-001 -- governor-clean, but
      `:schedule-maintenance` is deliberately absent from EVERY phase's
      `:auto` set (`hardwaremfg.phase`), so it ALWAYS escalates ->
      approved -> committed.
    - `:flag-safety-concern` concern-1 on forge-001 -- `:stake
      :coordination/safety-concern` is in `governor/high-stakes`, so it
      ALWAYS escalates regardless of confidence -> approved -> committed.
    - `:coordinate-shipment` ship-1 on batch-001 (500 kg against 1200 kg
      logged / 200 kg already shipped) -- clean, escalates, approved,
      committed; the store's own `:shipped-weight-kg` moves to 700.0.

  HARD HOLDS -- all ten of the governor's HARD checks, each exercised
  directly and independently (the `parksafety` ADR-2607071922 Decision 5
  discipline every sibling actor follows: never assert a failure mode
  only via a happy path). NONE of these ever reaches `:request-approval`
  -- a HARD violation maps to `:hold` in `hardwaremfg.phase/gate` before
  the approval branch is even considered, so no human approval can ever
  release one.

  Returns {:db store :approvals [approval-granted facts]} -- the
  approval facts are carried out of the RUNS, not read back from the
  store, because of the scaffold defect documented on
  `approver-attribution` below."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        approvals (atom [])
        approve-and-collect!
        (fn [tid]
          (let [r (approve! actor tid)]
            (swap! approvals into
                   (filter #(= :approval-granted (:t %)) (audit-of r)))
            r))]

    ;; ---- clean lifecycle ------------------------------------------------
    (exec! actor "t1-batch"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :cutlery-item :last-assessed "2026-07-14"}})

    (exec! actor "t2-maintenance"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "forge-001" :maintenance-type :die-inspection
                    :scheduled-date "2026-08-01" :actuate-forge-grind-line? false}})
    (approve-and-collect! "t2-maintenance")

    (exec! actor "t3-safety"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "forge-001" :severity :moderate
                    :description "鍛造ハンマー周辺で挟まれ点(ピンチポイント)ガードの緩みを確認"}})
    (approve-and-collect! "t3-safety")

    (exec! actor "t4-shipment"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 500.0
                    :destination "buyer-yard-north"}})
    (approve-and-collect! "t4-shipment")

    ;; ---- HARD holds -----------------------------------------------------
    ;; 1. request-level propose-only (mis-wired/compromised caller)
    (exec! actor "t5-effect"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-category :cutlery-item}})

    ;; 2 + 3. unrecognized op, and its :noop proposal effect outside the
    ;; closed propose-shaped effect allowlist
    (exec! actor "t6-op"
           {:op :actuate-forge-line :effect :propose :subject "batch-001"})

    ;; 4. permanent forge/grind-line ACTUATE block -- never overridable
    (exec! actor "t7-actuate"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "forge-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-forge-grind-line? true}})

    ;; 5. equipment not verified/registered (grind-002)
    (exec! actor "t8-equipment"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "grind-002" :maintenance-type :wheel-dressing
                    :scheduled-date "2026-08-01" :actuate-forge-grind-line? false}})

    ;; 6. double-schedule guard (mnt-1 was committed above)
    (exec! actor "t9-double"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "forge-001" :maintenance-type :die-inspection
                    :scheduled-date "2026-08-01" :actuate-forge-grind-line? false}})

    ;; 7. batch not verified/registered (batch-003)
    (exec! actor "t10-batch"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 500.0
                    :destination "buyer-yard-south"}})

    ;; 8. independent shipment-weight recompute (batch-002: 5600 + 1000 > 6000)
    (exec! actor "t11-weight"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-yard-east"}})

    ;; 9. fabricated product-category
    (exec! actor "t12-category"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :unobtainium-gadget}})

    ;; 10. implausible defect-rate reading
    (exec! actor "t13-defect"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 999.0}})

    {:db db :approvals @approvals}))

;; ----------------------------- derivation -----------------------------

(defn governor-holds
  "The HARD governor holds this run actually produced -- ledger facts
  that both carry the `:governor-hold` tag AND a non-empty
  `:violations` vector. `-main` throws when this is empty, so a
  regression that silently stopped censoring proposals fails the BUILD
  rather than quietly shipping a console with nothing held."
  [db]
  (vec (filter #(and (= :governor-hold (:t %)) (seq (:violations %)))
               (store/ledger db))))

(defn- store-entity
  "Resolve a committed subject id back to its own store record, across
  the four entity kinds this actor writes."
  [db subject]
  (or (store/maintenance db subject)
      (store/shipment db subject)
      (first (filter #(= subject (:id %)) (store/safety-concerns db)))
      (store/batch db subject)))

(defn approver-attribution
  "Joins each `:approval-granted` run fact back against the store record
  it approved, and reports whether the store ACTUALLY holds the
  approver.

  KNOWN FLEET-WIDE SCAFFOLD DEFECT (rendered honestly rather than
  papered over): `hardwaremfg.operation`'s `:request-approval` node
  attaches the approver as `(assoc record :payload (assoc (:value
  proposal) :approved-by (:by approval)))`, but
  `hardwaremfg.store/commit-record!` destructures `{:keys [effect path
  value]}` and never reads `:payload`. The approver therefore never
  reaches the SSoT. This function verifies that against the live store
  at build time -- it does not assume it -- so the page prints who
  approved from the RUN's audit facts while stating plainly that the
  store record does not carry the attribution."
  [db approvals]
  (mapv (fn [{:keys [op subject by]}]
          (let [rec (store-entity db subject)]
            {:op op :subject subject :by by
             :on-record? (contains? rec :approved-by)
             :recorded-by (:approved-by rec)}))
        approvals))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- yes-no [b]
  (if (true? b)
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">no</span>"))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (kw (or (-> f :violations first :rule) :unknown))) "</span>")
      (= :approval-rejected (:t f)) "<span class=\"critical\">approval rejected</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id product-category process-type material
                                 weight-kg shipped-weight-kg defect-rate-percent
                                 verified? registered?]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc (kw product-category)) (esc (kw process-type)) (esc material)
          (esc weight-kg) (esc shipped-weight-kg)
          (esc (- (double weight-kg) (double (or shipped-weight-kg 0.0))))
          (esc defect-rate-percent)
          (yes-no verified?) (yes-no registered?)
          (status-cell ledger id)))

(defn- equipment-row [ledger {:keys [id kind verified? registered?
                                     last-maintenance-date
                                     last-scheduled-maintenance-date]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc (kw kind))
          (yes-no verified?) (yes-no registered?)
          (if last-maintenance-date (esc last-maintenance-date)
              "<span class=\"muted\">never</span>")
          (if last-scheduled-maintenance-date (esc last-scheduled-maintenance-date)
              "<span class=\"muted\">none</span>")
          (status-cell ledger id)))

(defn- hold-row [{:keys [op subject violations confidence]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (kw op)) (esc subject)
          (str/join "<br>" (map #(str "<span class=\"critical\">" (esc (kw (:rule %))) "</span>")
                                violations))
          (str/join "<br>" (map #(esc (:detail %)) violations))
          (esc confidence)))

(defn- approval-row [{:keys [op subject by on-record? recorded-by]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw op)) (esc subject) (esc by)
          (if on-record?
            (str "<span class=\"ok\">yes &middot; " (esc recorded-by) "</span>")
            (str "<span class=\"warn\">no &middot; approver is NOT on the store record; "
                 "shown above from this run&#39;s <code>:approval-granted</code> audit fact</span>"))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw t)) (esc (kw (or op :n-a))) (esc subject)
          (esc (kw (or disposition "")))
          (esc (or (some->> basis (map kw) (str/join ", ")) ""))))

(defn- draft-row [rec ks]
  (format "        <tr>%s</tr>"
          (str/join (map #(str "<td><code>" (esc (get rec %)) "</code></td>") ks))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (`hardwaremfg.governor/allowed-ops`, `hardwaremfg.phase/phases`) --
  ;; documentation of fixed behaviour, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean &middot; the ONLY member of any phase&#39;s <code>:auto</code> set</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; deliberately absent from EVERY phase&#39;s <code>:auto</code> set &middot; equipment verified/registered re-checked independently</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; <code>:coordination/safety-concern</code> is permanently high-stakes regardless of confidence</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; shipment weight independently recomputed against the batch&#39;s own logged production weight</span></td></tr>"
   "        <tr><td><code>:forge-hammer/actuate</code>, <code>:grind-wheel/run</code>, any other effect</td><td><span class=\"critical\">PERMANENTLY BLOCKED &middot; HARD hold &middot; no phase and no human approval can ever release it</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!`, the run-carried `approvals`, and the
  `holds` counted by `-main`."
  [db approvals holds]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        attribution (approver-attribution db approvals)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2593 &middot; cutlery, hand tools &amp; general hardware</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of cutlery, hand tools and general hardware (ISIC 2593) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · propose-only · forging/grinding-line actuation permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>hardwaremfg.store</code> via <code>hardwaremfg.render-html</code> (<code>clojure -M:dev:render-html</code>). Headroom is recomputed here from the batch&#39;s own logged weight minus its own cumulative shipped weight — the same ground truth <code>hardwaremfg.governor</code> recomputes independently, never a self-reported figure.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product category</th><th>Process</th><th>Material</th><th>Logged kg</th><th>Shipped kg</th><th>Headroom kg</th><th>Defect %</th><th>Verified</th><th>Registered</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Equipment</h2>\n"
     "    <p class=\"muted\">A forging-hammer / grinding-wheel / heat-treatment-furnace / finishing-line unit may only be scheduled for maintenance when its OWN record is both verified and registered — re-derived by the governor from these fields, never from the advisor&#39;s rationale.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Verified</th><th>Registered</th><th>Last maintenance</th><th>Last scheduled</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial equipment-row ledger) equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Hardware Shop Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">Fixed op contract — described from <code>hardwaremfg.governor</code> and <code>hardwaremfg.phase</code>, not from this run. Every request must itself declare <code>:effect :propose</code>; this actor drafts records and never actuates a forging hammer, grinding wheel, heat-treatment furnace or finishing line, and never dispatches a real freight carrier.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD governor holds (this run) — " (count holds) "</h2>\n"
     "    <p class=\"muted\">Every row below is a proposal the governor REFUSED. A HARD violation maps straight to <code>:hold</code> in <code>hardwaremfg.phase/gate</code>, before the approval branch is ever considered — so none of these ever reached a human, and no human approval could have released them. Rules and details are the governor&#39;s own verdict output.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Subject</th><th>Rule</th><th>Governor detail</th><th>Advisor confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Human approvals (this run)</h2>\n"
     "    <p class=\"muted\">Runs paused by <code>interrupt-before #{:request-approval}</code> and resumed by a human shop supervisor. The right-hand column is checked against the LIVE store at build time: <code>hardwaremfg.operation</code> attaches the approver under the record&#39;s <code>:payload</code> key, but <code>hardwaremfg.store/commit-record!</code> destructures <code>:value</code> and never reads <code>:payload</code>, so the attribution does not reach the SSoT. It is reported here from the run&#39;s own <code>:approval-granted</code> audit fact rather than printing a name the store does not hold.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Subject</th><th>Approved by (run audit fact)</th><th>On the store record?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map approval-row attribution)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft records</h2>\n"
     "    <p class=\"muted\">Unsigned DRAFTS produced by <code>hardwaremfg.registry</code> — a maintenance window a coordinator would keep, and a shipment a coordinator would propose. Signing is the human approver&#39;s act, never this actor&#39;s.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Maintenance</th><th>Equipment</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map #(draft-row % ["record_id" "kind" "maintenance_id" "equipment_id"])
                         (store/maintenance-history db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Shipment</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map #(draft-row % ["record_id" "kind" "shipment_id"])
                         (store/shipment-history db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run) — " (count ledger) " facts</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and every hold this scenario produced, in order.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db approvals]} (run-demo!)
        holds (governor-holds db)
        hold-count (count holds)]
    ;; Build-time invariant, not a convention: an operator console that
    ;; shows nothing held is a console that cannot demonstrate the
    ;; governor censors anything. If a regression ever stops the governor
    ;; producing HARD holds for this scenario, the BUILD fails here
    ;; rather than shipping a page that quietly claims everything is fine.
    (when (zero? hold-count)
      (throw (ex-info (str "render-html: refusing to write " out
                           " -- the scenario produced ZERO HARD governor holds. "
                           "This scenario deliberately exercises all ten of "
                           "hardwaremfg.governor's HARD checks; zero holds means "
                           "the governor stopped censoring proposals.")
                      {:holds hold-count
                       :ledger-facts (count (store/ledger db))
                       :out out})))
    (spit out (render db approvals holds))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             hold-count "HARD governor holds,"
             (count approvals) "human approvals,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts )")))
