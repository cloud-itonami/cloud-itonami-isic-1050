(ns dairyprocessing.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL dairy-processing actor and renders whatever it produced.
  Nothing on this page is a mock or a hand-typed result:

    - every operation goes through this repo's own
      `dairyprocessing.operation/build` -> `run-operation`, i.e. the real
      advisor (`dairyprocessing.advisor`) -> the real independent Governor
      (`dairyprocessing.governor/check`) -> the real rollout phase gate
      (`dairyprocessing.phase/gate`);
    - the ledger IS the concatenation of the `:audit` vectors those runs
      returned. No fact is appended by hand anywhere in this namespace;
    - every HARD-hold rule name and every violation detail string is the
      Governor's own `:violations` entry off that ledger, never a literal
      written here;
    - the phase-gate matrix is produced by CALLING `phase/gate`, the
      confidence floor / high-stakes set by reading `governor` vars, and
      the jurisdiction and product bounds by reading `facts` vars;
    - the batch table is read back out of the store AFTER the run, so the
      one-way `:processed?` / `:shipment-finalized?` flags shown are the
      ones the run actually set.

  Why this repo has no `langgraph.graph/run*` call: `dairyprocessing.operation`
  says so itself -- \"langgraph integration is deferred. This stub version
  defines the high-level flow\". Its `build` returns a plain
  `(fn [request context] ...)` over `run-operation`. So this driver calls
  `operation/build` and invokes it exactly the way `dairyprocessing.sim`
  (the repo's own `clojure -M:dev:run` demo driver, run BEFORE this file was
  written) calls `operation/run-operation`.

  Why the seed lives here and not in the store: `dairyprocessing.store`
  ships no seed function -- `store/mem-store` takes `:initial-batches` and
  the DRIVER supplies them, which is exactly what `dairyprocessing.sim`
  does. `batch-1050-001` below is copied verbatim from that sim seed (the
  repo's own demo batch, kept byte-for-byte so this console and
  `clojure -M:dev:run` describe the same batch); the other batches are
  additional deliveries that make each Governor rule reachable. Every
  subject driven below exists in that seed -- there are no invented ids.

  Approval: this repo has no resume node (no langgraph), so a human
  sign-off cannot be replayed through the graph. `:log-production-batch`
  and `:coordinate-shipment` therefore stop at the Governor's
  `:approval-requested` fact, and the human decision is recorded in the run
  log (NOT forged into the ledger as a governor fact). An APPROVED
  high-stakes op then has its effect applied through the store's own
  one-way flags (`store/mark-processed`, `store/mark-shipment-finalized`),
  which is what later lets the Governor catch the double-submission.

  Deterministic: no clock, no randomness, no network, no timestamp in the
  page content. Two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [dairyprocessing.advisor :as advisor]
            [dairyprocessing.facts :as facts]
            [dairyprocessing.governor :as governor]
            [dairyprocessing.operation :as operation]
            [dairyprocessing.phase :as phase]
            [dairyprocessing.store :as store]))

;; ----------------------------- seed -----------------------------

(def ^:private operator
  "The plant-operations context every run below is executed under, except
  where a run overrides `:phase` to show the rollout gate.

  Phase 2 (reduced supervision), NOT the phase 1 that `dairyprocessing.sim`
  uses -- and the reason is a real property of `dairyprocessing.phase/gate`
  that was measured, not assumed. At `:phase-1` the gate returns
  `{:disposition :escalate}` for ANY high-stakes request without looking at
  the disposition handed to it, so a Governor HARD hold is rewritten into an
  approval request and never surfaces as a hold. At `:phase-2` the gate
  passes the Governor's disposition through, so a clean high-stakes op still
  escalates to a human (the Governor itself sets `:escalate?` on stakes) and
  a violated one is held. `t19` below drives the phase-1 case on a batch
  that DOES violate a rule, so the page shows this rather than hides it."
  {:actor-id "dairy-processing-01" :phase :phase-2})

(def ^:private batch-order
  "Display order for the batch table. Also the authoritative list of the
  subject ids this demo is allowed to drive -- `run-demo!` asserts that
  every batch subject it uses is one of these AND is present in the store."
  ["batch-1050-001" "batch-1050-002" "batch-1050-003" "batch-1050-004"
   "batch-1050-005" "batch-1050-006" "batch-1050-007" "batch-1050-008"
   "batch-1050-009" "batch-1050-010"])

(def ^:private seed-batches
  "Milk deliveries seeded into `store/mem-store`. `batch-1050-001` is the
  batch `dairyprocessing.sim` seeds, copied verbatim. Every other batch
  differs from a clean batch in exactly ONE field, so the HARD hold it
  provokes names exactly one Governor rule."
  {;; --- verbatim from dairyprocessing.sim -------------------------------
   "batch-1050-001"
   {:id "batch-1050-001" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T08:00:00Z"
    :raw-milk-temp-c 3.5 :scc-cells-ml 350000 :tbc-cfu-ml 50000
    :pasteurization-temp-c 63.5 :pasteurization-hold-time-sec 1810
    :cooling-temp-c 3.8 :holding-time-hours 12 :sanitation-score 85
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- evidence checklist short one item (JP requires :pathogen-test) ---
   "batch-1050-002"
   {:id "batch-1050-002" :jurisdiction "JP" :product-type "yogurt"
    :received-at "2026-07-14T09:10:00Z"
    :raw-milk-temp-c 3.2 :scc-cells-ml 300000 :tbc-cfu-ml 40000
    :pasteurization-temp-c 66.0 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.6 :holding-time-hours 20 :sanitation-score 92
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log]}

   ;; --- raw milk quality over PMO thresholds (SCC 400k / TBC 100k) -------
   "batch-1050-003"
   {:id "batch-1050-003" :jurisdiction "US" :product-type "skim-milk"
    :received-at "2026-07-14T09:40:00Z"
    :raw-milk-temp-c 3.9 :scc-cells-ml 520000 :tbc-cfu-ml 130000
    :pasteurization-temp-c 64.0 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.4 :holding-time-hours 18 :sanitation-score 87
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- pasteurized below the US 63 °C floor ----------------------------
   "batch-1050-004"
   {:id "batch-1050-004" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T10:05:00Z"
    :raw-milk-temp-c 3.1 :scc-cells-ml 240000 :tbc-cfu-ml 35000
    :pasteurization-temp-c 58.0 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.7 :holding-time-hours 14 :sanitation-score 91
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- cold chain broken: cheese cooled only to 9.5 °C (max 8.0) --------
   "batch-1050-005"
   {:id "batch-1050-005" :jurisdiction "EU" :product-type "cheese"
    :received-at "2026-07-14T10:30:00Z"
    :raw-milk-temp-c 4.0 :scc-cells-ml 280000 :tbc-cfu-ml 60000
    :pasteurization-temp-c 64.0 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 9.5 :holding-time-hours 20 :sanitation-score 90
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test
                         :allergen-test]}

   ;; --- held 31 h after pasteurization (US limit 24 h) -------------------
   "batch-1050-006"
   {:id "batch-1050-006" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T11:00:00Z"
    :raw-milk-temp-c 3.3 :scc-cells-ml 310000 :tbc-cfu-ml 55000
    :pasteurization-temp-c 63.2 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.5 :holding-time-hours 31 :sanitation-score 88
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- third-party sanitation audit 62/100 (floor 80) -------------------
   "batch-1050-007"
   {:id "batch-1050-007" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T11:25:00Z"
    :raw-milk-temp-c 3.4 :scc-cells-ml 260000 :tbc-cfu-ml 42000
    :pasteurization-temp-c 63.8 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.6 :holding-time-hours 16 :sanitation-score 62
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- Listeria positive ------------------------------------------------
   "batch-1050-008"
   {:id "batch-1050-008" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T11:50:00Z"
    :raw-milk-temp-c 3.0 :scc-cells-ml 220000 :tbc-cfu-ml 30000
    :pasteurization-temp-c 64.2 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.2 :holding-time-hours 10 :sanitation-score 93
    :pathogen-test-result {:listeria-negative? false :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- contamination flag raised during intake, never cleared -----------
   "batch-1050-009"
   {:id "batch-1050-009" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T12:15:00Z"
    :raw-milk-temp-c 3.6 :scc-cells-ml 290000 :tbc-cfu-ml 48000
    :pasteurization-temp-c 63.6 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.9 :holding-time-hours 15 :sanitation-score 86
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? true :contamination-flag-resolved? false
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}

   ;; --- clean; used twice to show phase 0 vs phase 1 on identical input --
   "batch-1050-010"
   {:id "batch-1050-010" :jurisdiction "US" :product-type "whole-milk"
    :received-at "2026-07-14T12:40:00Z"
    :raw-milk-temp-c 3.5 :scc-cells-ml 275000 :tbc-cfu-ml 46000
    :pasteurization-temp-c 63.4 :pasteurization-hold-time-sec 1800
    :cooling-temp-c 3.7 :holding-time-hours 11 :sanitation-score 89
    :pathogen-test-result {:listeria-negative? true :salmonella-negative? true
                           :ecoli-negative? true}
    :contamination-flag-raised? false :contamination-flag-resolved? true
    :evidence-checklist [:raw-milk-assay :pasteurization-log :temperature-log
                         :holding-time-record :sanitation-log :pathogen-test]}})

;; ------------------------- advisor seam -------------------------

(defrecord ^:private UncitedAdvisor [inner]
  advisor/Advisor
  (-advise [_ st request]
    ;; Delegates to this repo's own MockAdvisor and then drops :cites. This
    ;; is not a fabricated hold: it is the sealed advisor seam behaving the
    ;; way a real LLM misbehaves (proposing without a primary-source
    ;; citation). The Governor's `:no-spec-basis` rule then rejects it
    ;; unaided -- which is the whole point of the separation of powers.
    (dissoc (advisor/-advise inner st request) :cites)))

;; ----------------------------- driver -----------------------------

(def ^:private scenario
  "One entry per operation. `:exercises` is scenario documentation (what the
  run is FOR); every disposition, rule name and detail shown on the page is
  read back out of what the actor returned, not out of this table."
  [{:tid "t01"
    :exercises "Clean US whole-milk batch (the batch dairyprocessing.sim seeds). Governor clean, but :log-production-batch is permanently high-stakes -> escalates. The plant manager approves, which sets the store's one-way :processed? flag."
    :request {:op :log-production-batch :subject "batch-1050-001" :stake :log-production-batch}
    :approval {:status :approved :by "plant-mgr-1"}}

   {:tid "t02"
    :exercises "Shipment of that same finished batch. Escalates for the same reason; approved, setting :shipment-finalized?."
    :request {:op :coordinate-shipment :subject "batch-1050-001" :stake :coordinate-shipment}
    :approval {:status :approved :by "plant-mgr-1"}}

   {:tid "t03"
    :exercises "The SAME batch submitted to production records a second time. Guarded off a dedicated :processed? fact, never a :status value. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-001" :stake :log-production-batch}}

   {:tid "t04"
    :exercises "The SAME shipment finalized a second time. HARD hold."
    :request {:op :coordinate-shipment :subject "batch-1050-001" :stake :coordinate-shipment}}

   {:tid "t05"
    :exercises "JP yogurt batch whose evidence checklist is missing :pathogen-test. The Governor recomputes the jurisdiction's required-evidence set itself. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-002" :stake :log-production-batch}}

   {:tid "t06"
    :exercises "A shipment proposal from an advisor that returned NO citation. Never reaches a human: an uncited proposal cannot be checked against any jurisdiction. HARD hold."
    :request {:op :coordinate-shipment :subject "batch-1050-002" :stake :coordinate-shipment}
    :advisor :uncited}

   {:tid "t07"
    :exercises "Raw milk at SCC 520,000 cells/mL and TBC 130,000 CFU/mL, over the PMO thresholds the registry checks. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-003" :stake :log-production-batch}}

   {:tid "t08"
    :exercises "Governor-clean shipment that the human VETOES. Distinct from a HARD hold: the Governor cleared it, a person did not, and no store flag moves."
    :request {:op :coordinate-shipment :subject "batch-1050-003" :stake :coordinate-shipment}
    :approval {:status :rejected :by "plant-mgr-1"}}

   {:tid "t09"
    :exercises "Pasteurized at 58.0 °C, below the US 63 °C floor -- an under-processed batch presented as compliant. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-004" :stake :log-production-batch}}

   {:tid "t10"
    :exercises "EU cheese cooled only to 9.5 °C against the product's own 8.0 °C ceiling. Cold-chain breach. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-005" :stake :log-production-batch}}

   {:tid "t11"
    :exercises "Held 31 hours after pasteurization against a 24-hour jurisdiction limit. Pathogen multiplication risk. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-006" :stake :log-production-batch}}

   {:tid "t12"
    :exercises "Third-party plant sanitation audit scored 62/100 against a floor of 80. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-007" :stake :log-production-batch}}

   {:tid "t13"
    :exercises "Listeria screening positive. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-008" :stake :log-production-batch}}

   {:tid "t14"
    :exercises "A contamination flag raised at intake and never cleared. HARD hold."
    :request {:op :log-production-batch :subject "batch-1050-009" :stake :log-production-batch}}

   {:tid "t15"
    :exercises "Flagging the food-safety concern on that same batch. Monitoring stake, confidence above the floor -> the Governor commits it without a human."
    :request {:op :flag-food-safety-concern :subject "batch-1050-009" :stake :monitoring}}

   {:tid "t16"
    :exercises "Pasteurizer calibration window. Operational stake -> commits."
    :request {:op :schedule-maintenance :subject "batch-1050-010" :stake :operational}}

   {:tid "t17"
    :exercises "A CLEAN batch submitted while the actor is still in phase 0. No production operation exists in the simulation phase, so the rollout gate holds it even though the Governor found nothing. Not a HARD hold -- the violation list is empty."
    :phase :phase-0
    :request {:op :log-production-batch :subject "batch-1050-010" :stake :log-production-batch}}

   {:tid "t18"
    :exercises "The identical request at phase 2. Same batch, same Governor verdict, different rollout phase -> escalates to a human instead of being held, and the approval lands it."
    :request {:op :log-production-batch :subject "batch-1050-010" :stake :log-production-batch}
    :approval {:status :approved :by "plant-mgr-1"}}

   {:tid "t19"
    :exercises "The Listeria-positive batch again, this time at phase 1. dairyprocessing.phase/gate returns :escalate for ANY high-stakes request at phase 1 without reading the disposition handed to it, so the Governor's HARD violation is rewritten into an approval request. The violation is still in the verdict -- shown here rather than hidden, because it is why the rest of this page runs at phase 2."
    :phase :phase-1
    :request {:op :log-production-batch :subject "batch-1050-008" :stake :log-production-batch}}])

(defn- apply-approved-effect!
  "A human approver's sign-off is the only thing that advances a high-stakes
  op past `:approval-requested` in this repo. The effect is applied through
  the store's OWN one-way flags -- which is what later lets the Governor
  catch a double submission (t03 / t04)."
  [st op subject]
  (case op
    :log-production-batch (store/mark-processed st subject)
    :coordinate-shipment (store/mark-shipment-finalized st subject)
    nil))

(defn run-demo!
  "Drives the real actor over the seeded store and returns
  `{:db store :runs [...] :ledger [...]}`.

  `:ledger` is the concatenation of the `:audit` vectors the actor returned,
  in run order. Nothing is added to it here."
  []
  (let [st (store/mem-store {:initial-batches seed-batches})
        actor (operation/build st)
        uncited (operation/build st {:advisor (->UncitedAdvisor (advisor/mock-advisor))})]
    ;; Subject provenance: refuse to render a console that drove an id the
    ;; seed does not contain (an empty or meaningless ledger row).
    (doseq [{:keys [tid request]} scenario]
      (let [s (:subject request)]
        (when-not (and (some #{s} batch-order) (store/processing-batch st s))
          (throw (ex-info "scenario drove a subject that is not in the store seed"
                          {:tid tid :subject s})))))
    (loop [[{:keys [tid exercises request approval phase] :as step} & more] scenario
           runs []
           ledger []]
      (if (nil? step)
        {:db st :runs runs :ledger ledger}
        (let [ctx (cond-> operator phase (assoc :phase phase))
              invoke (if (= :uncited (:advisor step)) uncited actor)
              result (invoke request ctx)
              approved? (and approval (= :approved (:status approval))
                             (= :escalate (:disposition result)))]
          (when approved?
            (apply-approved-effect! st (:op request) (:subject request)))
          (recur more
                 (conj runs {:tid tid
                             :exercises exercises
                             :request request
                             :phase (:phase ctx)
                             :advisor (if (= :uncited (:advisor step))
                                        "uncited (LLM omitted its citation)"
                                        "mock")
                             :approval approval
                             :effect-applied? (boolean approved?)
                             :result result})
                 (into ledger (:audit result))))))))

;; ----------------------------- derivation -----------------------------

(defn- hard-holds
  "Ledger facts that are Governor HARD holds -- a `:governor-hold` carrying
  at least one rule violation. A phase-gate hold (t17) is a `:governor-hold`
  with an EMPTY violation list and is deliberately not counted here."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn- phase-holds [ledger]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn- fact-count [ledger t]
  (count (filter #(= t (:t %)) ledger)))

(defn- rules-fired
  "Distinct Governor rule keywords that actually fired, in first-seen order."
  [ledger]
  (->> (hard-holds ledger) (mapcat :basis) distinct vec))

(defn- rule-checks-implemented
  "How many `*-violations` rule checks `dairyprocessing.governor` actually
  defines. Counted off the loaded namespace, so it cannot drift away from
  the source the way a hand-maintained number would."
  []
  (count (filter #(str/ends-with? (name %) "-violations")
                 (keys (ns-interns 'dairyprocessing.governor)))))

(defn- outcome
  "How a run ended, folding the human decision into the actor's disposition."
  [{:keys [result approval effect-applied?]}]
  (let [d (:disposition result)
        violated? (seq (:violations (:verdict result)))]
    (cond
      (= :commit d) [:ok "committed"]
      (= :hold d) (if violated? [:critical "HARD hold"] [:err "phase-gate hold"])
      (and (= :escalate d) effect-applied?) [:ok "approved &middot; committed"]
      (and (= :escalate d) approval) [:warn "vetoed by human"]
      (and (= :escalate d) violated?) [:err "rule violated, phase 1 sent it to a human"]
      (= :escalate d) [:warn "awaiting approval"]
      :else [:muted (str d)])))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))

(defn- cls [c body] (str "<span class=\"" (name c) "\">" body "</span>"))

(defn- yn [b] (if b (cls :ok "yes") (cls :muted "no")))

(defn- table [headers rows]
  (str "<table>\n<thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n<tbody>\n"
       (str/join "\n" rows)
       "\n</tbody>\n</table>"))

(defn- row [& cells] (str "<tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lead & body]
  (str "<section class=\"card\">\n<h2>" (esc title) "</h2>\n"
       (when lead (str "<p class=\"muted\">" lead "</p>\n"))
       (str/join "\n" body) "\n</section>"))

;; -- summary

(defn- summary-section [{:keys [runs ledger]}]
  (let [hh (hard-holds ledger)]
    (section
     "This run"
     (str "Every number below is counted off the facts the actor produced "
          "during this build, not recorded from an earlier one.")
     (table ["Measure" "Value"]
            [(row "operations driven" (str "<span class=\"num\">" (count runs) "</span>"))
             (row "ledger facts" (str "<span class=\"num\">" (count ledger) "</span>"))
             (row "advisor proposals" (str "<span class=\"num\">" (fact-count ledger :advisor-proposal) "</span>"))
             (row "committed without a human" (str "<span class=\"num\">" (fact-count ledger :committed) "</span>"))
             (row "escalated to a human" (str "<span class=\"num\">" (fact-count ledger :approval-requested) "</span>"))
             (row (cls :critical "HARD Governor holds")
                  (str "<span class=\"num\">" (count hh) "</span>"))
             (row "phase-gate holds (no rule violated)"
                  (str "<span class=\"num\">" (count (phase-holds ledger)) "</span>"))
             (row "distinct Governor rules fired"
                  (str "<span class=\"num\">" (count (rules-fired ledger)) "</span> of "
                       "<span class=\"num\">" (rule-checks-implemented) "</span> rule checks "
                       "<code>dairyprocessing.governor</code> implements"))
             (row "human approvals granted"
                  (str "<span class=\"num\">" (count (filter :effect-applied? runs)) "</span>"))
             (row "human vetoes"
                  (str "<span class=\"num\">"
                       (count (filter #(= :rejected (:status (:approval %))) runs))
                       "</span>"))]))))

;; -- timeline

(defn- timeline-section [{:keys [runs]}]
  (section
   "Operation timeline"
   (str "One row per <code>dairyprocessing.operation</code> run. "
        "<em>Outcome</em> is the disposition the actor returned; "
        "<em>Governor rules</em> are the rules the Governor found violated, "
        "read off its own verdict — which is why <code>t19</code> shows rules "
        "even though the phase-1 gate turned its hold into an approval request.")
   (table ["#" "Op" "Subject" "Phase" "Advisor" "Outcome" "Governor rules" "What it exercises"]
          (for [{:keys [tid request phase advisor result exercises] :as r} runs
                :let [[c label] (outcome r)
                      basis (mapv :rule (:violations (:verdict result)))]]
            (row (esc tid)
                 (code (:op request))
                 (esc (:subject request))
                 (code phase)
                 (esc advisor)
                 (cls c label)
                 (if (seq basis)
                   (str/join " " (map code basis))
                   (cls :muted "—"))
                 (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

;; -- hard holds

(defn- holds-section [{:keys [ledger]}]
  (let [hh (hard-holds ledger)]
    (section
     "HARD Governor holds"
     (str "Un-overridable. These never reach a human operator. Both the rule "
          "keyword and the detail text are the Governor's own "
          "<code>:violations</code> entry, read straight off the ledger.")
     (table ["Op" "Subject" "Rule" "Governor's detail"]
            (for [f hh
                  v (:violations f)]
              (row (code (:op f))
                   (esc (:subject f))
                   (cls :critical (code (:rule v)))
                   (esc (:detail v))))))))

;; -- human decisions

(defn- decisions-section [{:keys [runs]}]
  (let [ds (filter :approval runs)]
    (section
     "Human decisions"
     (str "The actor stops at <code>:approval-requested</code> for every "
          "high-stakes op. What happens next is a person's, and the store's "
          "one-way flag only moves on an approval.")
     (table ["#" "Op" "Subject" "Approver" "Decision" "Store flag moved"]
            (for [{:keys [tid request approval effect-applied?]} ds]
              (row (esc tid)
                   (code (:op request))
                   (esc (:subject request))
                   (esc (:by approval))
                   (if (= :approved (:status approval))
                     (cls :ok "approved")
                     (cls :warn "rejected"))
                   (yn effect-applied?)))))))

;; -- op gate contract (the ONE hand-written table on this page)

(def ^:private op-gate-contract-rows
  ;; HAND-WRITTEN, and the only hand-written content on this page: a static
  ;; description of this actor's fixed op contract, taken from README `Ops`
  ;; and `dairyprocessing.governor`/`dairyprocessing.phase`. It documents
  ;; fixed behaviour rather than reporting anything measured, so it is not
  ;; derived from the run. Everything else is.
  [[":log-production-batch" "ALWAYS human sign-off &middot; never auto at any phase &middot; all nine food-safety rules recomputed independently from the store"]
   [":coordinate-shipment" "ALWAYS human sign-off &middot; never auto at any phase &middot; citation and double-finalization checked"]
   [":flag-food-safety-concern" "monitoring stake &middot; commits when cited and confident; a concern is surfaced, never suppressed"]
   [":schedule-maintenance" "operational stake &middot; commits; proposes a window only, never actuates equipment"]])

(defn- op-gate-section []
  (section
   "Op gate contract (static)"
   (str "The fixed contract this actor is built to, quoted from the README "
        "and the Governor source. This is the one table on the page that is "
        "documentation rather than a measurement.")
   (table ["Op" "Gate"]
          (for [[op gate] op-gate-contract-rows]
            (row (str "<code>" (esc op) "</code>") gate)))))

;; -- phase matrix, derived by calling phase/gate

(defn- phase-section []
  (let [phases [:phase-0 :phase-1 :phase-2 :phase-3]
        stakes [:log-production-batch :coordinate-shipment :monitoring :operational]]
    (section
     "Rollout phase gate"
     (str "Produced by CALLING <code>dairyprocessing.phase/gate</code> once per "
          "phase &times; stake with a Governor-clean <code>:commit</code>, not by "
          "copying the docstring. Note the phase-0 and phase-1 columns for the "
          "two high-stakes ops: the gate answers <em>without</em> reading the "
          "disposition it was handed, so at those phases its answer replaces the "
          "Governor's — including a HARD hold (see <code>t19</code> above). "
          "Phases 2 and 3 pass the Governor's disposition through unchanged, "
          "which is why this console runs at phase 2.")
     (table (into ["Phase"] (map #(kw %) stakes))
            (for [p phases]
              (apply row (code p)
                     (for [s stakes
                           :let [{:keys [disposition reason]} (phase/gate p {:stake s} :commit)]]
                       (str (cls (case disposition
                                   :commit :ok :escalate :warn :hold :critical :muted)
                                 (kw disposition))
                            (when reason (str " <span class=\"muted\">"
                                              (esc (kw reason)) "</span>"))))))))))

;; -- governor configuration, read off the governor namespace

(defn- governor-section [{:keys [ledger]}]
  (section
   "Governor configuration"
   "Read out of <code>dairyprocessing.governor</code> at build time."
   (table ["Setting" "Value"]
          [(row "confidence floor"
                (str "<span class=\"num\">" governor/confidence-floor "</span>"))
           (row "always high-stakes (human sign-off regardless of phase)"
                (str/join " " (map code (sort-by name governor/high-stakes))))
           (row "rule checks implemented"
                (str "<span class=\"num\">" (rule-checks-implemented) "</span>"))
           (row "rules this scenario actually fired"
                (str/join " " (map code (sort (rules-fired ledger)))))])))

;; -- jurisdiction / product bounds, read off facts

(defn- jurisdiction-section []
  (section
   "Jurisdiction bounds the Governor enforces"
   (str "Read out of <code>dairyprocessing.facts/jurisdictions</code>. These are "
        "the numbers the Governor recomputes each proposal against; the actor "
        "never takes them from the advisor.")
   (table ["Jurisdiction" "Pasteurization °C" "Cooling max °C" "Holding max h" "Required evidence" "Primary source"]
          (for [[id j] (sort-by key facts/jurisdictions)]
            (row (str (esc id) " <span class=\"muted\">" (esc (:name j)) "</span>")
                 (str "<span class=\"num\">" (:pasteurization-temp-min-c j)
                      " – " (:pasteurization-temp-max-c j) "</span>")
                 (str "<span class=\"num\">" (:cooling-temp-max-c j) "</span>")
                 (str "<span class=\"num\">" (:holding-time-max-hours j) "</span>")
                 (str/join " " (map code (:required-evidence j)))
                 (str "<span class=\"muted\">" (esc (:provenance j)) "</span>"))))))

(defn- product-section []
  (section
   "Product bounds"
   "Read out of <code>dairyprocessing.facts/product-types</code>."
   (table ["Product" "Cooling max °C" "Holding max h"]
          (for [[id p] (sort-by key facts/product-types)]
            (row (str "<code>" (esc id) "</code> <span class=\"muted\">"
                      (esc (:name p)) "</span>")
                 (str "<span class=\"num\">" (:cooling-temp-max-c p) "</span>")
                 (str "<span class=\"num\">" (:holding-time-max-hours p) "</span>"))))))

(defn- citation-section []
  (let [{:keys [jurisdictions cited cited-jurisdictions uncited-jurisdictions
                pasteurization-figures-cited note]} (facts/citation-coverage)]
    (section
     "Citation coverage"
     (str "Reported by <code>dairyprocessing.facts/citation-coverage</code>, "
          "including what this repo deliberately does NOT claim a citation for.")
     (table ["Measure" "Value"]
            [(row "jurisdictions" (str "<span class=\"num\">" jurisdictions "</span>"))
             (row "resting on a directly-fetched official source"
                  (str "<span class=\"num\">" cited "</span> — "
                       (str/join " " (map #(str "<code>" (esc %) "</code>") cited-jurisdictions))))
             (row "uncited"
                  (if (seq uncited-jurisdictions)
                    (str/join " " (map #(str "<code>" (esc %) "</code>") uncited-jurisdictions))
                    (cls :muted "none")))
             (row "pasteurization figures actually cited"
                  (str/join " "
                            (for [[id c?] (sort-by key pasteurization-figures-cited)]
                              (str "<code>" (esc id) "</code> "
                                   (if c? (cls :ok "cited") (cls :warn "actor's own limit"))))))])
     (str "<p class=\"muted\">" (esc note) "</p>"))))

;; -- batches, read back out of the store after the run

(defn- batches-section [{:keys [db]}]
  (section
   "Batches after the run"
   (str "Read back out of <code>dairyprocessing.store</code> AFTER the scenario, "
        "so the one-way flags shown are the ones these operations actually set.")
   (table ["Batch" "Jurisdiction" "Product" "Pasteurization °C / hold s" "Cooling °C"
           "Holding h" "SCC / TBC" "Sanitation" "Pathogens clear" "Contamination"
           "Logged" "Shipped"]
          (for [id batch-order
                :let [b (store/processing-batch db id)
                      pt (:pathogen-test-result b)]]
            (row (esc id)
                 (esc (:jurisdiction b))
                 (esc (:product-type b))
                 (str "<span class=\"num\">" (:pasteurization-temp-c b) " / "
                      (:pasteurization-hold-time-sec b) "</span>")
                 (str "<span class=\"num\">" (:cooling-temp-c b) "</span>")
                 (str "<span class=\"num\">" (:holding-time-hours b) "</span>")
                 (str "<span class=\"num\">" (:scc-cells-ml b) " / " (:tbc-cfu-ml b) "</span>")
                 (str "<span class=\"num\">" (:sanitation-score b) "</span>")
                 (yn (and (:listeria-negative? pt) (:salmonella-negative? pt)
                          (:ecoli-negative? pt)))
                 (cond
                   (not (:contamination-flag-raised? b)) (cls :muted "none raised")
                   (:contamination-flag-resolved? b) (cls :ok "raised, resolved")
                   :else (cls :critical "raised, UNRESOLVED"))
                 (yn (store/batch-already-processed? db id))
                 (yn (store/batch-shipment-finalized? db id)))))))

;; -- ledger

(defn- ledger-section [{:keys [ledger]}]
  (section
   "Audit ledger (this run)"
   (str "Every fact the actor emitted, in order. "
        "<code>:advisor-proposal</code> records what the sealed advisor said; "
        "the fact after it records what the Governor and the phase gate did "
        "with it.")
   (table ["#" "Fact" "Op" "Subject" "Basis / reason" "Confidence"]
          (map-indexed
           (fn [i {:keys [t op subject basis reason phase-reason confidence
                          proposal-summary]}]
             (row (str "<span class=\"num\">" (inc i) "</span>")
                  (cls (case t
                         :committed :ok
                         :approval-requested :warn
                         :governor-hold :critical
                         :muted)
                       (code t))
                  (code op)
                  (esc subject)
                  (cond
                    (seq basis) (str/join " " (map code basis))
                    phase-reason (str (cls :err (code phase-reason))
                                      " <span class=\"muted\">(no rule violated)</span>")
                    reason (code reason)
                    proposal-summary (str "<span class=\"muted\">"
                                          (esc proposal-summary) "</span>")
                    :else (cls :muted "—"))
                  (if confidence
                    (str "<span class=\"num\">" confidence "</span>")
                    (cls :muted "—"))))
           ledger))))

(defn render
  "The whole page, from the post-run store, run log and ledger."
  [{:keys [ledger] :as result}]
  (str
   "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
   "<meta name=\"color-scheme\" content=\"light\">"
   "<title>Operator console — cloud-itonami-isic-1050 (dairyprocessing)</title>"
   "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
   "<div class=\"bar\">"
   "<span class=\"badge\">ISIC 1050</span>"
   "<span class=\"badge\">dairyprocessing</span>"
   "<span class=\"badge\">governor: dairy-governor</span>"
   "</div>\n"
   "<h1>Dairy processing coordination — operator console</h1>\n"
   "<p class=\"subtitle\">Raw milk intake &rarr; pasteurization &rarr; cooling &rarr; "
   "finished-product logistics, coordinated by a sealed advisor behind an "
   "independent Governor. Actor <code>" (esc (:actor-id operator)) "</code>. "
   "<strong>Not equipment control</strong> — plant operator authority and "
   "food-safety responsibility remain exclusive to licensed dairy plant staff.</p>\n"
   "<div class=\"banner\"><p>This page is generated at build time by "
   "<code>dairyprocessing.render-html</code> (<code>clojure -M:dev:render-html</code>) "
   "by running the real <code>dairyprocessing.operation</code> actor over a seeded "
   "<code>dairyprocessing.store</code> and rendering what came back. "
   "<strong>" (count (hard-holds ledger)) " HARD Governor holds</strong> below were "
   "reached by genuinely violating a rule, not by writing a fact. The build refuses "
   "to emit this file if that count is zero.</p></div>\n"
   (str/join "\n"
             [(summary-section result)
              (timeline-section result)
              (holds-section result)
              (decisions-section result)
              (op-gate-section)
              (phase-section)
              (governor-section result)
              (jurisdiction-section)
              (product-section)
              (citation-section)
              (batches-section result)
              (ledger-section result)])
   "\n<footer><p>Deterministic build artefact — no clock, no randomness, no network, "
   "no timestamp in the page content; two consecutive runs are byte-identical. "
   "No usage, revenue or performance metric is claimed anywhere on this page. "
   "Sample data only: the batches shown are seeded fixtures, not real milk deliveries.</p>"
   "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [ledger runs] :as result} (run-demo!)
        holds (hard-holds ledger)]
    ;; Build-time invariant: a console that shows no real HARD hold is not
    ;; evidence of a Governor. Refuse to write one.
    (when (empty? holds)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count ledger)
                       :runs (count runs)
                       :dispositions (frequencies (map (comp :disposition :result) runs))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result) :encoding "UTF-8"))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count holds) " HARD holds, "
                  (count (rules-fired ledger)) " distinct governor rules, "
                  (count runs) " operations)"))))
