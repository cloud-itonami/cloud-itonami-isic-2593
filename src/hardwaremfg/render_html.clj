(ns hardwaremfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2608090800,
  Wave 7): this repo previously had NO demo page and no generator at
  all. This namespace drives the REAL actor stack
  (`hardwaremfg.operation` -> `hardwaremfg.governor` ->
  `hardwaremfg.phase` -> `hardwaremfg.store`) through a scenario
  adapted from this repo's own `hardwaremfg.sim` demo driver
  (`clojure -M:dev:run`, run BEFORE this file was written to confirm
  the entity ids it uses -- `batch-001`..`batch-003`, `forge-001`,
  `grind-002` -- really are the ids `hardwaremfg.store/sample-data!`
  seeds, rather than assuming it; a sibling repo in an earlier wave
  shipped a console naming operators that did not exist in its seed
  data).

  Nothing on the generated page is hand-typed domain content. Every
  batch/equipment row, every ledger fact, every hold rule and detail
  string, every draft record number and the whole action-gate table are
  read back out of the live objects the run produced
  (`hardwaremfg.store` for the SSoT, the langgraph `:audit` channel for
  the transient approval facts, and the `hardwaremfg.phase`/
  `hardwaremfg.governor` vars themselves for the gate description).

  Deterministic: no timestamps, no randomness, no map-order dependence
  in page content -- two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [hardwaremfg.governor :as governor]
            [hardwaremfg.operation :as op]
            [hardwaremfg.phase :as phase]
            [hardwaremfg.store :as store]))

;; ----------------------------- the real run -----------------------------

(def ^:private coordinator
  "The same operator identity this repo's own `hardwaremfg.sim` demo
  driver uses -- phase 3 (`supervised-auto`), the repo's
  `hardwaremfg.phase/default-phase`."
  {:actor-id "coord-1" :actor-role :shop-coordinator :phase 3})

(defn- exec!
  "One coordination request = one supervised actor run."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- resume!
  "Resume a run parked at `interrupt-before #{:request-approval}` with a
  real human decision."
  [actor tid status]
  (g/run* actor {:approval {:status status :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store through a scenario covering every
  disposition this actor can actually reach, and returns
  `{:db <store> :run-audit [<audit fact> ..]}`.

  `:run-audit` is the concatenation of each run's langgraph `:audit`
  channel. It is kept separately from the store ledger ON PURPOSE:
  `hardwaremfg.operation`'s `:request-approval` node attaches the
  approver under the commit record's `:payload` key, but
  `hardwaremfg.store/commit-record!` destructures `:value` and never
  reads `:payload` -- so who approved a write NEVER reaches the SSoT
  and exists only here, in the transient run audit. The console renders
  that gap honestly instead of printing a name the store does not
  hold (see `approvals-section`); it is a fleet-wide scaffold defect,
  not something this renderer may paper over by inventing data.

  Dispositions exercised:
    - auto-commit      -- `:log-production-batch` on `batch-001` with a
                          clean patch. The ONLY op in phase 3's `:auto`
                          set, and the governor is clean, so it commits
                          with no human in the loop.
    - approved escalate -- `:schedule-maintenance` mnt-1 against the
                          verified+registered `forge-001`
                          (`:schedule-maintenance` is deliberately in
                          NO phase's `:auto` set, so it always
                          escalates), `:flag-safety-concern` concern-1
                          (`:stake :coordination/safety-concern` is in
                          `governor/high-stakes`, so it escalates even
                          when clean) and `:coordinate-shipment` ship-1
                          on `batch-001` within its recorded weight.
    - rejected escalate -- `:coordinate-shipment` ship-4, governor-clean
                          and within weight, which the human approver
                          REFUSES. No SSoT mutation; the refusal is
                          logged.
    - HARD hold        -- nine distinct governor refusals, none of which
                          ever reaches a human: a caller whose request
                          `:effect` is not `:propose`; an op outside the
                          closed allowlist; maintenance against the
                          UNVERIFIED/unregistered `grind-002`; a
                          shipment against the UNVERIFIED/unregistered
                          `batch-003`; a shipment whose weight would
                          blow through `batch-002`'s own logged
                          production weight; a maintenance proposal that
                          tries to ACTUATE the forging/grinding line
                          (permanent, no override); a double-schedule of
                          mnt-1; a fabricated product category; and an
                          implausible defect-rate reading."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        audit (volatile! [])
        run! (fn [r] (vswap! audit into (get-in r [:state :audit])) r)]

    ;; --- clean auto-commit (phase 3 :auto, governor clean) ---
    (run! (exec! actor "t1" {:op :log-production-batch :effect :propose
                             :subject "batch-001"
                             :patch {:product-category :cutlery-item
                                     :last-assessed "2026-07-14"}}))

    ;; --- escalations a human approves ---
    (run! (exec! actor "t2" {:op :schedule-maintenance :effect :propose
                             :subject "mnt-1"
                             :value {:equipment-id "forge-001"
                                     :maintenance-type :die-inspection
                                     :scheduled-date "2026-08-01"
                                     :actuate-forge-grind-line? false}}))
    (run! (resume! actor "t2" :approved))

    (run! (exec! actor "t3" {:op :flag-safety-concern :effect :propose
                             :subject "concern-1"
                             :value {:equipment-id "forge-001"
                                     :severity :moderate
                                     :description "鍛造ハンマー周辺で挟まれ点(ピンチポイント)ガードの緩みを確認"}}))
    (run! (resume! actor "t3" :approved))

    (run! (exec! actor "t4" {:op :coordinate-shipment :effect :propose
                             :subject "ship-1"
                             :value {:batch-id "batch-001" :weight-kg 500.0
                                     :destination "buyer-yard-north"}}))
    (run! (resume! actor "t4" :approved))

    ;; --- an escalation the human REFUSES (clean governor, human says no) ---
    (run! (exec! actor "t5" {:op :coordinate-shipment :effect :propose
                             :subject "ship-4"
                             :value {:batch-id "batch-001" :weight-kg 400.0
                                     :destination "buyer-yard-west"}}))
    (run! (resume! actor "t5" :rejected))

    ;; --- HARD holds: each failure mode exercised directly ---
    (run! (exec! actor "h1" {:op :log-production-batch :effect :direct-write
                             :subject "batch-001"
                             :patch {:product-category :cutlery-item}}))

    (run! (exec! actor "h2" {:op :actuate-forge-line :effect :propose
                             :subject "batch-001"}))

    (run! (exec! actor "h3" {:op :schedule-maintenance :effect :propose
                             :subject "mnt-2"
                             :value {:equipment-id "grind-002"
                                     :maintenance-type :wheel-dressing
                                     :scheduled-date "2026-08-01"
                                     :actuate-forge-grind-line? false}}))

    (run! (exec! actor "h4" {:op :coordinate-shipment :effect :propose
                             :subject "ship-2"
                             :value {:batch-id "batch-003" :weight-kg 500.0
                                     :destination "buyer-yard-south"}}))

    (run! (exec! actor "h5" {:op :coordinate-shipment :effect :propose
                             :subject "ship-3"
                             :value {:batch-id "batch-002" :weight-kg 1000.0
                                     :destination "buyer-yard-east"}}))

    (run! (exec! actor "h6" {:op :schedule-maintenance :effect :propose
                             :subject "mnt-3"
                             :value {:equipment-id "forge-001"
                                     :maintenance-type :force-run
                                     :scheduled-date "2026-09-01"
                                     :actuate-forge-grind-line? true}}))

    (run! (exec! actor "h7" {:op :schedule-maintenance :effect :propose
                             :subject "mnt-1"
                             :value {:equipment-id "forge-001"
                                     :maintenance-type :die-inspection
                                     :scheduled-date "2026-08-01"
                                     :actuate-forge-grind-line? false}}))

    (run! (exec! actor "h8" {:op :log-production-batch :effect :propose
                             :subject "batch-001"
                             :patch {:product-category :unobtainium-gadget}}))

    (run! (exec! actor "h9" {:op :log-production-batch :effect :propose
                             :subject "batch-001"
                             :patch {:defect-rate-percent 999.0}}))

    {:db db :run-audit @audit}))

;; ----------------------------- derived views -----------------------------

(defn hard-holds
  "The governor HARD holds this run actually produced, read back out of
  the store's own append-only ledger (NOT counted from the scenario
  source). `-main` throws when this is empty."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- approval-facts
  "`:approval-granted` / `:approval-rejected` facts from the transient
  run audit -- the only place the approver's identity exists."
  [run-audit]
  (filterv #(#{:approval-granted :approval-rejected} (:t %)) run-audit))

(defn- committed-record
  "The record the SSoT actually holds for `op`/`subject`, so the console
  can CHECK (not assume) whether approver attribution survived the
  commit path."
  [db op subject]
  (case op
    :log-production-batch (store/batch db subject)
    :schedule-maintenance (store/maintenance db subject)
    :coordinate-shipment  (store/shipment db subject)
    :flag-safety-concern  (first (filter #(= subject (:id %)) (store/safety-concerns db)))
    nil))

(defn- approver-on-record?
  "Did `:approved-by` actually reach the SSoT? Evaluated against the
  live record, so the page reports the real state of the
  `:payload`-vs-`:value` scaffold defect rather than asserting it."
  [db op subject]
  (contains? (committed-record db op subject) :approved-by))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- yn [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"warn\">no</span>"))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"warn\">approver refused</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map kw (:basis f)))) "</span>")
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id product-category process-type material
                                 weight-kg shipped-weight-kg defect-rate-percent
                                 verified? registered?]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc material) (esc (kw product-category)) (esc (kw process-type))
          (esc weight-kg) (esc shipped-weight-kg) (esc defect-rate-percent)
          (yn verified?) (yn registered?)
          (status-cell ledger id)))

(defn- equipment-row [ledger {:keys [id kind verified? registered?
                                     last-maintenance-date
                                     last-scheduled-maintenance-date]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc (kw kind)) (yn verified?) (yn registered?)
          (esc (or last-maintenance-date "—"))
          (esc (or last-scheduled-maintenance-date "—"))
          (status-cell ledger id)))

(defn- gate-row
  "One row of the action gate, derived from the REAL `hardwaremfg.phase`
  and `hardwaremfg.governor` vars -- not a hand-written description."
  [ph op]
  (let [{:keys [writes auto]} (get phase/phases ph)
        writable? (contains? writes op)
        auto? (contains? auto op)]
    (format "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc (kw op))
            (yn writable?)
            (if auto?
              "<span class=\"ok\">may auto-commit when governor-clean</span>"
              "<span class=\"warn\">human approval always required</span>")
            (if (contains? governor/allowed-ops op)
              "<span class=\"ok\">on the closed op allowlist</span>"
              "<span class=\"critical\">not routable</span>"))))

(defn- hold-row [{:keys [op subject basis violations confidence]}]
  ;; NOTE: escape each rule name individually, THEN join with the markup
  ;; separator -- escaping the already-joined string would turn the
  ;; <code> tags into visible &lt;code&gt; text.
  (format (str "        <tr><td><code>:%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc (kw op)) (esc subject)
          (str/join ", " (map #(str "<code>:" (esc (kw %)) "</code>") basis))
          (esc (str/join " / " (map :detail violations)))
          (esc confidence)))

(defn- ledger-row [{:keys [t op actor subject disposition basis summary]}]
  (format (str "        <tr><td>%s</td><td><code>:%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc (kw t)) (esc (kw op)) (esc subject) (esc actor)
          (esc (kw (or disposition "")))
          (esc (or summary (str/join ", " (map kw basis))))))

(defn- approval-row
  "One human decision. NOTE the asymmetry, rendered rather than papered
  over: `hardwaremfg.operation`'s `:request-approval` node puts the
  approver under `:by` on the `:approval-granted` fact, but its
  REJECTION branch emits `governor/hold-fact` merged with
  `{:t :approval-rejected}` -- and `hold-fact` has no `:by` at all. So a
  refusal genuinely does not record WHO refused. The context `:actor` is
  NOT substituted here: that field is the requesting actor, not the
  approver, and in this scaffold they merely happen to be the same
  string."
  [db {:keys [t op subject by]}]
  (format (str "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td>%s</td></tr>")
          (esc (kw op)) (esc subject)
          (if (= :approval-granted t)
            "<span class=\"ok\">approved</span>"
            "<span class=\"warn\">refused</span>")
          (if by
            (esc by)
            "<span class=\"critical\">not recorded — refusals carry no approver identity</span>")
          (if (approver-on-record? db op subject)
            "<span class=\"ok\">yes</span>"
            "<span class=\"critical\">no — approver not on record</span>")))

(defn- draft-row [r]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (get r "record_id")) (esc (get r "kind"))
          (esc (or (get r "maintenance_id") (get r "shipment_id")))
          (esc (or (get r "equipment_id") "—"))))

(defn render
  "Renders the whole operator console from a completed run. Every value
  below comes out of `db` / `run-audit` / the phase+governor vars."
  [{:keys [db run-audit]}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds db)
        approvals (approval-facts run-audit)
        ph (:phase coordinator)
        {:keys [label]} (get phase/phases ph)]
    (str
     "<html><head><meta charset=\"utf-8\">"
     "<title>cloud-itonami-isic-2593 &middot; hardware-manufacturing</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Cutlery, hand tool &amp; general hardware manufacturing (ISIC 2593) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance scheduling always human-approved · forging/grinding-line actuation permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Run summary</h2>\n"
     "    <p class=\"muted\">Build-time-generated by <code>hardwaremfg.render-html</code> (<code>clojure -M:dev:render-html</code>) by actually running <code>hardwaremfg.operation</code> → <code>hardwaremfg.governor</code> → <code>hardwaremfg.phase</code> → <code>hardwaremfg.store</code>. No value on this page is hand-typed.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Measure</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td>Rollout phase</td><td>%s (<code>%s</code>)</td></tr>\n" ph (esc label))
     (format "        <tr><td>Operator</td><td><code>%s</code> (<code>:%s</code>)</td></tr>\n"
             (esc (:actor-id coordinator)) (esc (kw (:actor-role coordinator))))
     (format "        <tr><td>Governor confidence floor</td><td>%s</td></tr>\n" (esc governor/confidence-floor))
     (format "        <tr><td>Ledger facts written</td><td>%s</td></tr>\n" (count ledger))
     (format "        <tr><td>Committed writes</td><td>%s</td></tr>\n"
             (count (filter #(= :committed (:t %)) ledger)))
     (format "        <tr><td>Governor HARD holds</td><td><span class=\"critical\">%s</span></td></tr>\n" (count holds))
     (format "        <tr><td>Distinct HARD rules fired</td><td>%s</td></tr>\n"
             (count (into #{} (mapcat :basis) holds)))
     (format "        <tr><td>Human decisions</td><td>%s</td></tr>\n" (count approvals))
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">The SSoT after the run. <code>verified?</code>/<code>registered?</code> are the batch's own ground-truth fields — the governor re-derives them independently and never trusts the advisor's rationale.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Material</th><th>Product category</th><th>Process</th><th>Weight (kg)</th><th>Shipped (kg)</th><th>Defect rate (%)</th><th>Verified?</th><th>Registered?</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) (store/all-batches db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Equipment</h2>\n"
     "    <p class=\"muted\">Maintenance may only be scheduled against equipment that is both verified and registered.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Unit</th><th>Kind</th><th>Verified?</th><th>Registered?</th><th>Last maintenance</th><th>Last scheduled</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial equipment-row ledger) (store/all-equipment db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Hardware Shop Plant Operations Governor + phase " ph ")</h2>\n"
     "    <p class=\"muted\">Read directly out of <code>hardwaremfg.phase/phases</code> and <code>hardwaremfg.governor/allowed-ops</code> at build time. <code>:schedule-maintenance</code> is a write op at phase 3 but is in NO phase's <code>:auto</code> set — scheduling real downtime on a forging hammer or grinding wheel is always a human's call.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Writable at phase " ph "?</th><th>Auto-commit?</th><th>Allowlist</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial gate-row ph) (sort-by name phase/write-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">Closed proposal-effect allowlist: "
     (str/join ", " (map #(str "<code>" (esc %) "</code>")
                         (sort (map str governor/allowed-proposal-effects))))
     ". A proposal declaring any other effect is a direct forging/grinding-line-control attempt and is HARD-blocked, permanently.</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds this run (" (count holds) ") — never reached a human</h2>\n"
     "    <p class=\"muted\">A HARD governor violation cannot be overridden by any phase and is never offered to an approver. Every row below is a real refusal read back out of the append-only ledger, with the governor's own detail text.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Subject</th><th>Rule</th><th>Governor detail</th><th>Advisor confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Human decisions this run</h2>\n"
     "    <p class=\"muted\"><strong>Known scaffold defect, rendered honestly:</strong> <code>hardwaremfg.operation</code>'s <code>:request-approval</code> node attaches the approver under the commit record's <code>:payload</code> key, but <code>hardwaremfg.store/commit-record!</code> destructures <code>:value</code> and never reads <code>:payload</code>. The approver therefore never reaches the SSoT. The names below are joined back from this run's transient <code>:audit</code> channel; the last column is computed by looking up the live committed record and checking whether it actually carries <code>:approved-by</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Subject</th><th>Decision</th><th>Approver (run audit)</th><th>On the SSoT record?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial approval-row db) approvals)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and every refusal the SSoT recorded.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Actor</th><th>Disposition</th><th>Summary / basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft records produced</h2>\n"
     "    <p class=\"muted\">Unsigned DRAFTS built by <code>hardwaremfg.registry</code>. This actor never actuates a forging hammer, grinding wheel, heat-treatment furnace or finishing line, and never dispatches a real freight carrier — it only ever produces the record a shop coordinator would keep.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Subject</th><th>Equipment</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map draft-row (concat (store/maintenance-history db)
                                           (store/shipment-history db)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns flagged</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Severity</th><th>Description</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (fn [{:keys [id equipment-id severity description]}]
                           (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
                                   (esc id) (esc equipment-id) (esc (kw severity)) (esc description)))
                         (store/safety-concerns db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer><p class=\"muted\">cloud-itonami-isic-2593 — regenerate with <code>clojure -M:dev:render-html</code>. Deterministic: two consecutive runs are byte-identical.</p></footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db run-audit] :as run} (run-demo!)
        holds (hard-holds db)]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; HARD hold has not demonstrated that the governor can refuse, and
    ;; is not allowed to ship (precedent: cloud-itonami-isic-2513).
    (when (empty? holds)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:hard-holds 0
                       :ledger-facts (count (store/ledger db))})))
    (io/make-parents out)
    (spit out (render run))
    (println "wrote" out
             "(" (count (store/ledger db)) "ledger facts,"
             (count holds) "HARD holds,"
             (count (into #{} (mapcat :basis) holds)) "distinct HARD rules,"
             (count (filter #(#{:approval-granted :approval-rejected} (:t %)) run-audit))
             "human decisions )")))
