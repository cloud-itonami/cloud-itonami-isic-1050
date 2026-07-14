(ns dairyprocessing.governor
  "Dairy Processing Governor -- the independent compliance layer that earns
  the DairyProcessingAdvisor the right to commit. The LLM has no notion of:
    - Whether a batch's raw milk meets quality thresholds (SCC, TBC)
    - Whether pasteurization achieved the required temperature and hold time
    - Whether cooling reached the required maximum temperature
    - Whether holding time has been exceeded (pathogen multiplication risk)
    - Whether plant sanitation meets jurisdiction requirements
    - Whether pathogen screening passed
    - Whether an open contamination flag has been resolved
    - Whether a batch's evidence checklist is complete per jurisdiction

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct equipment/line control (NEVER done by this actor -- processing
  equipment operation remains exclusive to licensed dairy plant operators), the
  Governor operates on batch metadata: provenance, pasteurization records,
  cooling records, sanitation records, and food-safety flags. This is
  plant-operations coordination, not process control.

  CRITICAL: Any proposal involving food-safety concerns (contamination,
  sanitation, pasteurization failure, cooling failure, pathogen risk) ALWAYS
  escalates to human operator for final sign-off. The LLM's confidence is
  never sufficient for food-safety decisions.

  Hard violations (always HOLD, no override):
    1. No jurisdiction citation (jurisdiction unknown -> can't verify reqs)
    2. Evidence incomplete (missing required-evidence per jurisdiction)
    3. Raw milk quality insufficient (SCC/TBC out of range)
    4. Pasteurization temperature out of range (pathogen kill risk)
    5. Cooling temperature insufficient (cold-chain breach)
    6. Holding time exceeded (pathogen multiplication risk)
    7. Sanitation score insufficient (plant hygiene not verified)
    8. Pathogen test failed (Listeria/Salmonella/E.coli detected)
    9. Contamination flag unresolved (open food-safety concern)

  Soft gates (always escalate for human):
    - Low confidence
    - Real actuation (`:log-production-batch`, `:coordinate-shipment`)

  This design mirrors `meatprocessing.governor` but specializes dairy
  food-safety concerns (pasteurization, cooling, raw milk quality, pathogens)
  rather than meat-specific requirements."
  (:require [dairyprocessing.facts :as facts]
            [dairyprocessing.registry :as registry]
            [dairyprocessing.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are the
  two real-world actuation events this actor performs. Both require plant
  operator sign-off."
  #{:log-production-batch :coordinate-shipment})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式specificationの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when-not (and b
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b)
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(raw-milk-assay/pasteurization-log/temperature-log等)が充足していない状態での提案"}]))))

(defn- raw-milk-quality-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the raw milk's quality
  (SCC and TBC) meets acceptable thresholds."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (:scc-cells-ml b) (:tbc-cfu-ml b)
                 (not (registry/scc-tbc-acceptable?
                       {:scc-cells-ml (:scc-cells-ml b)
                        :tbc-cfu-ml (:tbc-cfu-ml b)})))
        [{:rule :raw-milk-quality-insufficient
          :detail (str subject " の生乳品質(SCC=" (:scc-cells-ml b)
                      " cells/mL, TBC=" (:tbc-cfu-ml b)
                      " CFU/mL)が基準を下回る -- バッチ登録提案は進められない")}]))))

(defn- pasteurization-temp-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the milk's pasteurization
  temperature stays within the jurisdiction's required range via
  `registry/pasteurization-temp-out-of-range?`. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)
          j (when b (facts/jurisdiction-by-id (:jurisdiction b)))]
      (when (and b j (:pasteurization-temp-c b)
                 (registry/pasteurization-temp-out-of-range?
                  (:pasteurization-temp-c b)
                  (:pasteurization-temp-min-c j)
                  (:pasteurization-temp-max-c j)))
        [{:rule :pasteurization-temp-out-of-range
          :detail (str subject " の殺菌温度(" (:pasteurization-temp-c b)
                      " ℃)が法域要件[" (:pasteurization-temp-min-c j) ", "
                      (:pasteurization-temp-max-c j) "] ℃ の外 -- バッチ登録提案は進められない")}]))))

(defn- cooling-temp-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the cooled milk's
  temperature is sufficiently cold via
  `registry/cooling-temp-out-of-range?`. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:cooling-temp-c b)
                 (registry/cooling-temp-out-of-range?
                  (:cooling-temp-c b)
                  (:cooling-temp-max-c p)))
        [{:rule :cooling-temp-out-of-range
          :detail (str subject " の冷却温度(" (:cooling-temp-c b)
                      " ℃)が最大許容温度(" (:cooling-temp-max-c p)
                      " ℃)を超過 -- バッチ登録提案は進められない")}]))))

(defn- holding-time-exceeded-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  holding time does not exceed the jurisdiction maximum via
  `registry/holding-time-exceeded?`. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)
          j (when b (facts/jurisdiction-by-id (:jurisdiction b)))]
      (when (and b j (:holding-time-hours b)
                 (registry/holding-time-exceeded?
                  (:holding-time-hours b)
                  (:holding-time-max-hours j)))
        [{:rule :holding-time-exceeded
          :detail (str subject " の保持時間(" (:holding-time-hours b)
                      " 時間)が法域限度(" (:holding-time-max-hours j)
                      " 時間)を超過 -- バッチ登録提案は進められない")}]))))

(defn- sanitation-score-insufficient-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the plant's
  sanitation score meets minimum requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (:sanitation-score b)
                 (registry/sanitation-score-insufficient? (:sanitation-score b) 80))
        [{:rule :sanitation-score-insufficient
          :detail (str subject " のプラント衛生スコア(" (:sanitation-score b)
                      ")が最低要件(80)を下回る -- バッチ登録提案は進められない")}]))))

(defn- pathogen-test-failed-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that pathogen screening
  (Listeria, Salmonella, E.coli) passed."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (:pathogen-test-result b)
                 (not (registry/pathogen-test-passed? (:pathogen-test-result b))))
        [{:rule :pathogen-test-failed
          :detail (str subject " が病原体スクリーニングを通過せず(Listeria/Salmonella/E.coli検出) -- バッチ登録提案は進められない")}]))))

(defn- contamination-flag-unresolved-violations
  "An unresolved contamination flag is a HARD, un-overridable hold.
  Contamination concerns (suspected pathogen contamination, temperature
  breach during receipt, visible spoilage, off-odor) raised during intake or
  processing MUST be resolved before the batch can be logged into production.
  Evaluated UNCONDITIONALLY at `:log-production-batch`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and (true? (:contamination-flag-raised? b))
                 (not (true? (:contamination-flag-resolved? b))))
        [{:rule :contamination-flag-unresolved
          :detail (str subject " は未解決の汚染フラグがある -- バッチ登録提案は進められない")}]))))

(defn- already-processed-violations
  "For `:log-production-batch`, refuse to process the SAME batch twice, off
  a dedicated `:processed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (when (store/batch-already-processed? st subject)
      [{:rule :already-processed
        :detail (str subject " は既に登録済み")}])))

(defn- already-shipment-finalized-violations
  "For `:coordinate-shipment`, refuse to finalize the SAME batch's shipment
  twice, off a dedicated `:shipment-finalized?` fact."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when (store/batch-shipment-finalized? st subject)
      [{:rule :already-shipment-finalized
        :detail (str subject " は既に出荷確定済み")}])))

(defn check
  "Censors a DairyProcessingAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (raw-milk-quality-violations request st)
                           (pasteurization-temp-out-of-range-violations request st)
                           (cooling-temp-out-of-range-violations request st)
                           (holding-time-exceeded-violations request st)
                           (sanitation-score-insufficient-violations request st)
                           (pathogen-test-failed-violations request st)
                           (contamination-flag-unresolved-violations request st)
                           (already-processed-violations request st)
                           (already-shipment-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
