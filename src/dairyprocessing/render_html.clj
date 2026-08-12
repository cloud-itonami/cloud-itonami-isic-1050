(ns dairyprocessing.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for cloud-itonami-isic-1050: this repo
  had NO demo console and no generator at all. This namespace drives the
  REAL actor stack -- `dairyprocessing.advisor` -> `dairyprocessing.governor`
  -> `dairyprocessing.phase` -> `dairyprocessing.store`, entered through
  `dairyprocessing.operation/run-operation` -- and renders the page from
  what the actor actually did. Nothing on the page is a hand-typed status,
  count, threshold or reason: every violation string is the Governor's own
  `:detail`, every batch number is read back out of the Store, and every
  disposition is the value the phase gate returned.

  WHY `run-operation` AND NOT `langgraph.graph/run*`: unlike the sibling
  actors (`cloud-itonami-isic-2513` / `-9522`), THIS repo's
  `dairyprocessing.operation` is still the pre-langgraph stub -- its own
  docstring says so (\"langgraph integration is deferred\") and `build`
  returns a plain closure over `run-operation`, not a compiled StateGraph.
  There is therefore no `:request-approval` interrupt node and no
  `{:approval {:status :approved}}` resume path to drive. `operator-sign-off!`
  below stands in for that missing node, and is deliberately written so it
  CANNOT be used to fake an approval: it throws unless the decision it is
  handed actually reached a human (`:escalate`), and its only effect is the
  real store mutation (`store/mark-processed` / `store/mark-shipment-finalized`)
  that the resume node would have performed. The proof that the sign-off is
  real, and not decoration, is that the very next run of the same op against
  the same batch is HARD-held by the Governor on `:already-processed` /
  `:already-shipment-finalized` -- a hold that can only exist if the approval
  genuinely changed the SSoT.

  DETERMINISM: no clock, no randomness, no network. Batch ordering comes
  from `demo-batches` (a vector, not the store's map), the ledger is
  accumulated in run order, and `:received-at` is a fixed epoch string
  carried as seed data and never rendered as \"now\". Re-running the
  renderer produces a byte-identical file.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [dairyprocessing.advisor :as advisor]
            [dairyprocessing.facts :as facts]
            [dairyprocessing.governor :as governor]
            [dairyprocessing.operation :as operation]
            [dairyprocessing.store :as store]))

;; ----------------------------- seed data -----------------------------
;; Scenario INPUT (what the plant handed the actor). Everything derived
;; from it below is actor OUTPUT.

(def ^:private clean-us-batch
  "A batch that satisfies every one of the Governor's nine hard rules for
  the US jurisdiction. Each defective batch below is this map with exactly
  ONE field spoiled, so each hold isolates one rule."
  {:jurisdiction "US"
   :product-type "whole-milk"
   :received-at "2026-07-14T08:00:00Z"
   :raw-milk-temp-c 3.5
   :scc-cells-ml 350000
   :tbc-cfu-ml 50000
   ;; 63.5 C for 1810 s is a real LTLT hold (21 CFR 1240.61(b): 63 C / 30 min).
   :pasteurization-temp-c 63.5
   :pasteurization-hold-time-sec 1810
   :cooling-temp-c 3.8
   :holding-time-hours 12
   :sanitation-score 85
   :pathogen-test-result {:listeria-negative? true
                          :salmonella-negative? true
                          :ecoli-negative? true}
   :contamination-flag-raised? false
   :contamination-flag-resolved? true
   :evidence-checklist [:raw-milk-assay :pasteurization-log
                        :temperature-log :holding-time-record
                        :sanitation-log :pathogen-test]})

(def demo-batches
  "Ordered `[batch-id batch]` pairs. A VECTOR, not a map -- the store keeps
  batches in an atom-held map whose seq order is not part of its contract,
  and the console must render the same rows in the same order every run."
  (mapv (fn [[id overrides]] [id (merge clean-us-batch {:id id} overrides)])
        [["batch-1050-001" {}]
         ["batch-1050-002" {:scc-cells-ml 620000}]
         ["batch-1050-003" {:pasteurization-temp-c 58.0}]
         ["batch-1050-004" {:cooling-temp-c 7.2}]
         ["batch-1050-005" {:holding-time-hours 31}]
         ["batch-1050-006" {:sanitation-score 62}]
         ["batch-1050-007" {:pathogen-test-result {:listeria-negative? false
                                                   :salmonella-negative? true
                                                   :ecoli-negative? true}}]
         ["batch-1050-008" {:contamination-flag-raised? true
                            :contamination-flag-resolved? false}]
         ;; EU additionally requires :allergen-test; this checklist is the
         ;; US six, so the EU requirement is unmet.
         ["batch-1050-009" {:jurisdiction "EU"}]
         ;; JP band is 65.0-67.0 C, so a compliant JP batch runs hotter than
         ;; a compliant US one. Yogurt, 18 h held, all evidence present.
         ["batch-1050-010" {:jurisdiction "JP"
                            :product-type "yogurt"
                            :pasteurization-temp-c 65.5
                            :cooling-temp-c 3.6
                            :holding-time-hours 18
                            :sanitation-score 88
                            :scc-cells-ml 300000
                            :tbc-cfu-ml 40000}]]))

(def ^:private operator
  {:actor-id "dairy-processing-01"
   :actor-role :plant-manager
   :phase :phase-2})

(def ^:private simulation-operator
  "Same operator, still on the phase-0 rung of the rollout ladder."
  (assoc operator :phase :phase-0))

;; ------------------------- advisor variants --------------------------

(defrecord UncitedAdvisor [inner]
  advisor/Advisor
  (-advise [_advisor store request]
    ;; An advisor that proposes a real actuation without citing any
    ;; jurisdiction requirement. This is exactly what the Governor's
    ;; `:no-spec-basis` rule exists to catch, and the only way to reach
    ;; that rule -- the mock advisor always cites.
    (assoc (advisor/-advise inner store request) :cites [])))

(defn uncited-advisor []
  (->UncitedAdvisor (advisor/mock-advisor)))

;; ----------------------------- scenario ------------------------------

(defn- exec!
  "Run one real operation and append its real audit facts to `ledger`."
  [ledger st context request & [opts]]
  (let [result (operation/run-operation st request context opts)]
    (swap! ledger into (:audit result))
    result))

(defn- operator-sign-off!
  "The human half of the loop, standing in for the `:request-approval`
  interrupt node this repo's stubbed `operation` does not yet have.

  Refuses to sign anything that did not actually reach a human: if the
  actor's disposition was not `:escalate`, this throws rather than
  minting an approval the actor never asked for. The approval's effect is
  the real store mutation the resume node would perform."
  [ledger st context {:keys [op subject]} result]
  (when-not (= :escalate (:disposition result))
    (throw (ex-info "refusing to sign off on a decision that never reached a human"
                    {:op op :subject subject :disposition (:disposition result)})))
  (let [effect (case op
                 :log-production-batch (do (store/mark-processed st subject) :processed?)
                 :coordinate-shipment (do (store/mark-shipment-finalized st subject)
                                          :shipment-finalized?))]
    (swap! ledger conj {:t :approval-granted
                        :op op
                        :subject subject
                        :disposition :commit
                        :by (:actor-id context)
                        :role (:actor-role context)
                        :effect effect})
    effect))

(defn run-demo!
  "Drives the real actor across every disposition it can reach and returns
  `{:store .. :ledger ..}`.

  Phase ladder: one `:log-production-batch` at `:phase-0` (production
  operation is not permitted in the simulation phase, so the phase gate
  holds it before the Governor's opinion matters), then the rest at
  `:phase-2`.

  Clean paths: `:flag-food-safety-concern` and `:schedule-maintenance` on
  the clean batch auto-commit (Governor clean, confidence above the floor,
  not a real actuation).

  Approved path: `:log-production-batch` and `:coordinate-shipment` on the
  clean batch escalate -- they are `governor/high-stakes`, so no phase ever
  auto-commits them -- are signed off by the operator, and are then RE-RUN
  to show the Governor HARD-holding the repeat on `:already-processed` /
  `:already-shipment-finalized`. That second hold is the evidence the
  sign-off really landed in the store.

  Low confidence: an op the advisor does not recognise falls back to
  confidence 0.0, below `governor/confidence-floor`, and escalates.

  HARD holds, one rule each, never reaching a human: raw milk quality
  (batch-1050-002), pasteurization temperature (003), cooling temperature
  (004), holding time (005), sanitation score (006), pathogen screening
  (007), unresolved contamination flag (008), incomplete EU evidence
  (009), and -- via `uncited-advisor` -- a proposal with no specification
  basis at all (010)."
  []
  (let [st (store/mem-store {:initial-batches (into {} demo-batches)})
        ledger (atom [])
        clean "batch-1050-001"]

    ;; --- rollout ladder: phase 0 refuses production work outright ---
    (exec! ledger st simulation-operator
           {:op :log-production-batch :subject clean :stake :log-production-batch})

    ;; --- clean auto-commits (no human, no hold) ---
    (exec! ledger st operator
           {:op :flag-food-safety-concern :subject clean :stake :monitoring})
    (exec! ledger st operator
           {:op :schedule-maintenance :subject clean :stake :operational})

    ;; --- unrecognised op -> confidence below the floor -> human ---
    (exec! ledger st operator {:op :reroute-tanker :subject clean})

    ;; --- real actuation: escalate -> operator sign-off -> repeat is HARD-held ---
    (let [req {:op :log-production-batch :subject clean :stake :log-production-batch}]
      (operator-sign-off! ledger st operator req (exec! ledger st operator req))
      (exec! ledger st operator req))

    (let [req {:op :coordinate-shipment :subject clean :stake :coordinate-shipment}]
      (operator-sign-off! ledger st operator req (exec! ledger st operator req))
      (exec! ledger st operator req))

    ;; --- one HARD hold per Governor rule ---
    (doseq [id ["batch-1050-002" "batch-1050-003" "batch-1050-004"
                "batch-1050-005" "batch-1050-006" "batch-1050-007"
                "batch-1050-008" "batch-1050-009"]]
      (exec! ledger st operator
             {:op :log-production-batch :subject id :stake :log-production-batch}))

    ;; --- an advisor that cites nothing, against a compliant JP batch ---
    (exec! ledger st operator
           {:op :log-production-batch :subject "batch-1050-010"
            :stake :log-production-batch}
           {:advisor (uncited-advisor)})

    {:store st :ledger @ledger}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- hard-holds [ledger]
  (filter #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn- governor-holds [ledger]
  (filter #(= :governor-hold (:t %)) ledger))

(defn- committed [ledger]
  (filter #(= :committed (:t %)) ledger))

(defn- approvals [ledger]
  (filter #(= :approval-granted (:t %)) ledger))

(defn- last-disposition-fact [ledger batch-id]
  (last (filter #(and (= (:subject %) batch-id)
                      (not= :advisor-proposal (:t %)))
                ledger)))

(defn- status-cell [ledger batch-id]
  (let [f (last-disposition-fact ledger batch-id)]
    (case (:t f)
      :committed "<span class=\"ok\">committed</span>"
      :approval-granted "<span class=\"ok\">approved &amp; committed</span>"
      :approval-requested (str "<span class=\"warn\">awaiting sign-off &middot; "
                               (esc (kw (:reason f))) "</span>")
      :governor-hold
      (if-let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (kw rule)) "</span>")
        (str "<span class=\"warn\">phase hold &middot; "
             (esc (kw (:phase-reason f))) "</span>"))
      "<span class=\"muted\">no activity</span>")))

(defn- lifecycle-cell [b]
  (cond
    (:shipment-finalized? b) "<span class=\"ok\">logged &amp; shipped</span>"
    (:processed? b) "<span class=\"warn\">logged, not yet shipped</span>"
    :else "<span class=\"muted\">in process</span>"))

(defn- batch-row [st ledger batch-id]
  (let [b (store/processing-batch st batch-id)
        q (store/batch-quality-of st batch-id)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td class=\"num\">%s</td><td class=\"num\">%s</td>"
                 "<td class=\"num\">%s</td><td class=\"num\">%s</td>"
                 "<td>%s</td><td>%s</td></tr>")
            (esc batch-id)
            (esc (:jurisdiction b))
            (esc (:product-type b))
            (esc (:pasteurization-temp-c q))
            (esc (:cooling-temp-c q))
            (esc (:holding-time-hours q))
            (esc (:sanitation-score q))
            (lifecycle-cell b)
            (status-cell ledger batch-id))))

(defn- ledger-detail [{:keys [t basis violations summary proposal-summary
                             confidence reason phase-reason phase by role effect]}]
  (case t
    :advisor-proposal
    (str "<span class=\"num\">" (esc confidence) "</span> &middot; " (esc proposal-summary))

    :committed
    (str (esc (str/join ", " (map kw basis)))
         (when summary (str " &middot; " (esc summary))))

    :governor-hold
    (if (seq violations)
      (str "<strong>" (esc (str/join ", " (map (comp kw :rule) violations)))
           "</strong> &middot; " (esc (:detail (first violations))))
      (str "<strong>" (esc (kw phase-reason)) "</strong> &middot; "
           (esc (kw phase)) " does not permit production operations"))

    :approval-requested
    (esc (kw reason))

    :approval-granted
    (str (esc by) " (" (esc (kw role)) ") &middot; store effect <code>"
         (esc (kw effect)) "</code>")

    ""))

(defn- ledger-row [i {:keys [t op subject] :as fact}]
  (format (str "        <tr><td class=\"num\">%s</td><td>%s</td>"
               "<td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>")
          (inc i) (esc (kw t)) (esc (kw op)) (esc subject) (ledger-detail fact)))

(defn- op-contract-row
  "The gate this actor applies to `op`, computed from the real
  `governor/high-stakes` set, the real `governor/confidence-floor`, and the
  confidence the real advisor actually attaches to a proposal for `op`."
  [st op]
  (let [p (advisor/-advise (advisor/mock-advisor) st
                           {:op op :subject "batch-1050-001"})
        stake (:stake p)
        conf (:confidence p)
        high? (contains? governor/high-stakes stake)
        low? (< conf governor/confidence-floor)]
    (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
                 "<td class=\"num\">%s</td><td>%s</td></tr>")
            (esc (kw op)) (esc (kw stake)) (esc conf)
            (cond
              high? (str "<span class=\"warn\">ALWAYS human sign-off &middot; "
                         "real actuation, never auto at any phase</span>")
              low? (str "<span class=\"warn\">always escalates &middot; confidence below floor "
                        (esc governor/confidence-floor) "</span>")
              :else "<span class=\"ok\">auto-commits when the Governor is clean</span>"))))

(defn- rule-row
  "One row per DISTINCT hard rule this run actually reached, carrying the
  Governor's own `:detail` text verbatim."
  [[rule detail subjects]]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw rule)) (esc detail)
          (esc (str/join ", " subjects))))

(defn- observed-rules
  "Distinct `[rule detail subjects]` triples in first-seen order."
  [ledger]
  (let [vs (for [f (hard-holds ledger), v (:violations f)]
             [(:rule v) (:detail v) (:subject f)])]
    (->> vs
         (reduce (fn [acc [rule detail subject]]
                   (if-let [i (some (fn [[i [r _ _]]] (when (= r rule) i))
                                    (map-indexed vector acc))]
                     (update-in acc [i 2] conj subject)
                     (conj acc [rule detail [subject]])))
                 [])
         (mapv (fn [[r d ss]] [r d ss])))))

(def ^:private ops-in-contract
  [:log-production-batch :coordinate-shipment :flag-food-safety-concern
   :schedule-maintenance :reroute-tanker])

(defn render
  "Renders the whole document from a completed `run-demo!` result."
  [{:keys [store ledger]}]
  (let [coverage (facts/citation-coverage)
        batch-rows (str/join "\n" (map #(batch-row store ledger (first %)) demo-batches))
        gate-rows (str/join "\n" (map #(op-contract-row store %) ops-in-contract))
        rule-rows (str/join "\n" (map rule-row (observed-rules ledger)))
        ledger-rows (str/join "\n" (map-indexed ledger-row ledger))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-1050 &middot; dairy processing operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of dairy products (ISIC 1050) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · batch logging and shipment always human-signed</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Processing batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot, generated from <code>dairyprocessing.store</code> by "
     "<code>dairyprocessing.render-html</code> (<code>clojure -M:dev:render-html</code>). "
     "Every status below is the disposition the real actor returned for that batch — nothing here is authored by hand.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Jurisdiction</th><th>Product</th>"
     "<th>Pasteurization °C</th><th>Cooling °C</th><th>Held h</th><th>Sanitation</th>"
     "<th>Lifecycle</th><th>Last disposition</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Dairy Processing Governor)</h2>\n"
     "    <p class=\"muted\">Derived from <code>dairyprocessing.governor/high-stakes</code>, "
     "<code>governor/confidence-floor</code> = <span class=\"num\">" (esc governor/confidence-floor)
     "</span>, and the confidence the advisor actually attaches to each proposal. "
     "Logging a production batch and coordinating a shipment are real-world actuation: they reach a human at every phase, "
     "including full autonomy.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Stake</th><th>Advisor confidence</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     gate-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Hard holds reached in this run</h2>\n"
     "    <p class=\"muted\">A hard hold is not overridable and never reaches a human — the proposal is rejected outright. "
     "Each reason below is the Governor's own <code>:detail</code> string, copied out of the audit fact it emitted.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Governor detail</th><th>Batches</th></tr></thead>\n"
     "      <tbody>\n"
     rule-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Jurisdiction citation coverage</h2>\n"
     "    <p class=\"muted\">From <code>dairyprocessing.facts/citation-coverage</code>. "
     "<span class=\"num\">" (esc (:cited coverage)) "</span> of <span class=\"num\">"
     (esc (:jurisdictions coverage)) "</span> jurisdictions rest on a directly-fetched primary source.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Cited</th><th>Uncited</th><th>Pasteurization figures cited</th><th>Note</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
             (esc (str/join ", " (:cited-jurisdictions coverage)))
             (esc (or (not-empty (str/join ", " (:uncited-jurisdictions coverage))) "—"))
             (str/join ", "
                       (for [[id cited?] (sort-by key (:pasteurization-figures-cited coverage))]
                         (str (esc id) ": "
                              (if cited? "<span class=\"ok\">yes</span>"
                                  "<span class=\"warn\">no</span>"))))
             (esc (:note coverage)))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold, escalation, sign-off and commit "
     "this scenario produced, in the order the actor produced them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Fact</th><th>Op</th><th>Batch</th><th>Detail</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p><span class=\"num\">" (esc (count ledger)) "</span> audit facts · "
     "<span class=\"num\">" (esc (count (hard-holds ledger))) "</span> hard governor holds · "
     "<span class=\"num\">" (esc (count (committed ledger))) "</span> auto-commits · "
     "<span class=\"num\">" (esc (count (approvals ledger))) "</span> operator sign-offs. "
     "Deterministic build artefact — no clock, no randomness, no network.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [ledger] :as result} (run-demo!)
        holds (governor-holds ledger)
        hard (hard-holds ledger)]
    ;; Build-time invariant, not a convention: a console that shows no hold
    ;; is a console that cannot demonstrate containment. Refuse to write it.
    (when (zero? (count holds))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced ZERO :governor-hold facts. "
                           "An operator console that never shows the Governor "
                           "refusing a proposal is not evidence of containment.")
                      {:out out :ledger-facts (count ledger) :governor-holds 0})))
    (when (zero? (count hard))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced no HARD governor hold "
                           "(every hold carried an empty :violations vector). "
                           "At least one proposal must be rejected outright, "
                           "without ever reaching a human.")
                      {:out out :ledger-facts (count ledger)
                       :governor-holds (count holds) :hard-holds 0})))
    (let [f (java.io.File. ^String out)]
      (when-let [parent (.getParentFile f)] (.mkdirs parent)))
    (spit out (render result))
    (println "wrote" out
             (str "(" (count ledger) " audit facts, "
                  (count hard) " hard holds, "
                  (count (committed ledger)) " auto-commits, "
                  (count (approvals ledger)) " operator sign-offs)"))))
