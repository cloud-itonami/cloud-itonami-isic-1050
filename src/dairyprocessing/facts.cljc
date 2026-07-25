(ns dairyprocessing.facts
  "Reference facts for dairy processing: jurisdiction requirements for milk
  pasteurization, temperature control, holding-time compliance, and food-safety
  evidence. This namespace contains pure lookup functions for regulatory
  compliance checks -- the Governor calls these to validate proposals against
  jurisdiction requirements.

  CITATION PROVENANCE (2026-07-25), read out of directly-fetched official
  primary sources and re-grepped against the raw markup:

    - US: 21 CFR 1240.61(b) `\"Mandatory pasteurization for all milk and milk
      products in final package form intended for direct human consumption\"`
      via govinfo.gov official CFR XML. Pasteurization means heating every
      particle `\"to one of the temperatures given in the following table and
      held continuously at or above that temperature for at least the
      corresponding specified time\"`: 145 °F (63 °C) 30 minutes; 161 °F
      (72 °C) 15 seconds; 191 °F (89 °C) 1 second; 194 °F (90 °C) 0.5 second;
      201 °F (94 °C) 0.1 second; 204 °F (96 °C) 0.05 second; 212 °F (100 °C)
      0.01 second. Footnote: `\"If the fat content of the milk product is 10
      percent or more, or if it contains added sweeteners, the specified
      temperature shall be increased by 5 °F (3 °C).\"`
    - JP: 乳及び乳製品の成分規格等に関する命令（乳等省令、e-Gov law_id
      326M50000100052）via the e-Gov law_data API:「保持式により摂氏六三度で
      三〇分間加熱殺菌するか、又はこれと同等以上の殺菌効果を有する方法で加熱殺菌
      すること。」特別牛乳は「保持式により摂氏六十三度から摂氏六十五度までの間で
      三十分間加熱殺菌すること。」保存基準は「処理後（殺菌した場合にあつては殺菌後）
      直ちに摂氏十度以下に冷却して保存すること。」
    - EU: Regulation (EC) No 853/2004 Annex III Section IX Chapter II via
      EUR-Lex (CELEX:32004R0853): `\"upon acceptance at a processing
      establishment, milk is quickly cooled to not more than 6oC and kept at
      that temperature until processed\"`.

  NOT CLAIMED FOR THE EU: the pasteurisation temperature/time definition is
  NOT present in the fetched 853/2004 text, so no EU pasteurisation figure is
  asserted here. The EU entry's pasteurization numbers below are this actor's
  own operative limits, not a cited EU requirement -- stated plainly rather
  than dressed up with a citation that does not support them.

  CORRECTION -- HOLD TIME WAS 60x TOO SHORT. `:pasteurization-hold-time-sec`
  was 30 for US and JP: 30 SECONDS at 63-65 °C. Every source above pairs
  63 °C with 30 MINUTES; 15 seconds pairs with 72 °C, not with 63 °C. The
  value is corrected to 1800 (= 30 minutes in seconds), which makes the
  requirement stricter -- the safe direction. The demo batch in
  `dairyprocessing.sim` carried 31 seconds at 63.5 °C and was therefore
  depicting a grossly under-pasteurized batch as compliant; also corrected.

  TWO MODEL DEFECTS VERIFIED BUT DELIBERATELY NOT REWRITTEN HERE (each needs
  a temperature/time-PAIR model, which is a design change, not a fact fix):

    1. The Governor never checks hold time at all. `dairyprocessing.governor`
       gates `:pasteurization-temp-c` against the jurisdiction range and gates
       cooling temperature, but nothing reads `:pasteurization-hold-time-sec`
       -- the field is recorded by the store and never validated. Correcting
       its value therefore does not by itself enforce anything.
       `pasteurization-adequate?` below implements the real rule so that
       wiring it in is a small, well-tested step.
    2. The temperature gate has a MAXIMUM (`:pasteurization-temp-max-c` 65.0),
       so a 72 °C / 15 second HTST batch -- explicitly permitted by the same
       21 CFR 1240.61(b) table -- is rejected as out of range. The range
       encodes only the LTLT band. Exceeding a pasteurization temperature is
       not a safety hazard, so a max belongs to product quality, not to the
       food-safety gate. Left as-is to avoid loosening a live gate on
       reasoning the sources do not settle."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def jurisdictions
  "Dairy processing jurisdictions and their required documentation/evidence
  checklist requirements."
  {"US"
   {:id "US"
    :name "United States (FDA / 21 CFR 1240.61)"
    :legal-basis "21 CFR 1240.61(b) — pasteurization means heating every particle to one of the tabulated temperatures and holding at or above it for at least the corresponding time (63 °C/30 min, 72 °C/15 s, 89 °C/1 s, 90 °C/0.5 s, 94 °C/0.1 s, 96 °C/0.05 s, 100 °C/0.01 s), with the specified temperature raised by 3 °C when fat content is 10 percent or more or sweeteners are added"
    :provenance "https://www.govinfo.gov/content/pkg/CFR-2024-title21-vol8/xml/CFR-2024-title21-vol8-sec1240-61.xml"
    :verbatim {:pasteurization "the process of heating every particle of milk and milk product in properly designed and operated equipment to one of the temperatures given in the following table and held continuously at or above that temperature for at least the corresponding specified time"
               :fat-adjustment "If the fat content of the milk product is 10 percent or more, or if it contains added sweeteners, the specified temperature shall be increased by 5 °F (3 °C)."}
    :pasteurization-temp-min-c 63.0
    :pasteurization-temp-max-c 65.0
    ;; Corrected 30 -> 1800 on 2026-07-25: every verified source pairs
    ;; 63 °C with 30 MINUTES. 15 seconds pairs with 72 °C, not 63 °C.
    :pasteurization-hold-time-sec 1800
    :cooling-temp-max-c 4.0
    :holding-time-max-hours 24
    :required-evidence
    [:raw-milk-assay           ;; milk quality testing (SCC, TBC)
     :pasteurization-log       ;; heat treatment records
     :temperature-log          ;; cooling and storage temperature
     :holding-time-record      ;; time from pasteurization to processing
     :sanitation-log           ;; equipment sanitation records
     :pathogen-test]}         ;; pathogenic organism screening

   "JP"
   {:id "JP"
    :name "日本（厚生労働省／乳等省令）"
    :legal-basis "乳及び乳製品の成分規格等に関する命令（乳等省令）—「保持式により摂氏六三度で三〇分間加熱殺菌するか、又はこれと同等以上の殺菌効果を有する方法で加熱殺菌すること。」特別牛乳は摂氏六十三度から六十五度までの間で三十分間。保存は「直ちに摂氏十度以下に冷却して保存すること。」"
    :provenance "https://laws.e-gov.go.jp/api/2/law_data/326M50000100052"
    :verbatim {:pasteurization "保持式により摂氏六三度で三〇分間加熱殺菌するか、又はこれと同等以上の殺菌効果を有する方法で加熱殺菌すること。"
               :special-milk "殺菌する場合は保持式により摂氏六十三度から摂氏六十五度までの間で三十分間加熱殺菌すること。"
               :storage "処理後（殺菌した場合にあつては殺菌後）直ちに摂氏十度以下に冷却して保存すること。"}
    :statutory-limits {:pasteurization-floor-temp-c 63.0
                       :pasteurization-hold-min 30
                       :storage-max-temp-c 10.0}
    :pasteurization-temp-min-c 65.0
    :pasteurization-temp-max-c 67.0
    ;; Corrected 30 -> 1800 on 2026-07-25: every verified source pairs
    ;; 63 °C with 30 MINUTES. 15 seconds pairs with 72 °C, not 63 °C.
    :pasteurization-hold-time-sec 1800
    :cooling-temp-max-c 4.0
    :holding-time-max-hours 24
    :required-evidence
    [:raw-milk-assay
     :pasteurization-log
     :temperature-log
     :holding-time-record
     :sanitation-log
     :pathogen-test]}

   "EU"
   {:id "EU"
    :name "European Union (Regulation (EC) No 853/2004)"
    :legal-basis "Regulation (EC) No 853/2004 Annex III Section IX Chapter II — milk must be quickly cooled to not more than 6 °C on acceptance at a processing establishment and kept there until processed. The pasteurisation temperature/time definition is NOT in this text, so this entry's pasteurization figures are this actor's own operative limits, not a cited EU requirement."
    :provenance "https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32004R0853"
    :verbatim {:acceptance-cooling "upon acceptance at a processing establishment, milk is quickly cooled to not more than 6oC and kept at that temperature until processed"}
    :statutory-limits {:acceptance-cooling-max-temp-c 6.0
                       :pasteurization-figures-cited? false}
    :pasteurization-temp-min-c 63.0
    :pasteurization-temp-max-c 65.0
    ;; Corrected 30 -> 1800 on 2026-07-25: every verified source pairs
    ;; 63 °C with 30 MINUTES. 15 seconds pairs with 72 °C, not 63 °C.
    :pasteurization-hold-time-sec 1800
    :cooling-temp-max-c 4.0
    :holding-time-max-hours 24
    :required-evidence
    [:raw-milk-assay
     :pasteurization-log
     :temperature-log
     :holding-time-record
     :sanitation-log
     :pathogen-test
     :allergen-test]}})

(def pasteurization-equivalents
  "The 21 CFR 1240.61(b) equivalent-treatment table: minimum hold seconds at
  or above each temperature, verified verbatim from the fetched CFR XML.
  Sorted ascending by temperature."
  [{:temp-c 63.0 :min-hold-sec 1800.0}    ; 145 °F, 30 minutes
   {:temp-c 72.0 :min-hold-sec 15.0}      ; 161 °F, 15 seconds
   {:temp-c 89.0 :min-hold-sec 1.0}       ; 191 °F, 1 second
   {:temp-c 90.0 :min-hold-sec 0.5}       ; 194 °F, 0.5 second
   {:temp-c 94.0 :min-hold-sec 0.1}       ; 201 °F, 0.1 second
   {:temp-c 96.0 :min-hold-sec 0.05}      ; 204 °F, 0.05 second
   {:temp-c 100.0 :min-hold-sec 0.01}])   ; 212 °F, 0.01 second

(def high-fat-or-sweetened-temp-bump-c
  "21 CFR 1240.61(b) footnote: the specified temperature is raised by 3 °C
  when fat content is 10 percent or more, or sweeteners are added."
  3.0)

(defn pasteurization-adequate?
  "Does holding `hold-sec` at `temp-c` satisfy ANY row of
  `pasteurization-equivalents`? This is the real rule the sources state, and
  the reason `:pasteurization-hold-time-sec` alone cannot express it: 63 °C
  needs 1800 s while 72 °C needs only 15 s, so a single required-seconds
  number either rejects legitimate HTST or accepts under-processed LTLT.

  `opts` may carry `:high-fat-or-sweetened? true`, which raises every
  tabulated temperature by 3 °C per the CFR footnote.

  Returns false for missing or non-numeric inputs -- an unmeasured batch is
  not an adequate one."
  ([temp-c hold-sec] (pasteurization-adequate? temp-c hold-sec {}))
  ([temp-c hold-sec {:keys [high-fat-or-sweetened?]}]
   (let [bump (if high-fat-or-sweetened? high-fat-or-sweetened-temp-bump-c 0.0)]
     (boolean
      (and (number? temp-c) (number? hold-sec)
           (some (fn [row]
                   (and (>= temp-c (+ (:temp-c row) bump))
                        (>= hold-sec (:min-hold-sec row))))
                 pasteurization-equivalents))))))

(defn spec-basis
  "The verified primary-source citation for `jurisdiction-id`, or nil."
  [jurisdiction-id]
  (when-let [j (get jurisdictions jurisdiction-id)]
    (select-keys j [:legal-basis :provenance :verbatim :statutory-limits])))

(defn cited?
  "True only with a non-blank :legal-basis, an http(s) :provenance and at
  least one :verbatim quote."
  [jurisdiction-id]
  (let [{:keys [legal-basis provenance verbatim]} (spec-basis jurisdiction-id)]
    (boolean (and (string? legal-basis) (not (str/blank? legal-basis))
                  (string? provenance) (str/starts-with? provenance "http")
                  (map? verbatim) (seq verbatim)))))

(defn citation-coverage
  "Honest coverage report, including what is deliberately NOT cited."
  []
  (let [ids (keys jurisdictions)
        cited (filter cited? ids)]
    {:jurisdictions (count jurisdictions)
     :cited (count cited)
     :cited-jurisdictions (vec (sort cited))
     :uncited-jurisdictions (vec (sort (remove cited? ids)))
     :pasteurization-figures-cited
     (into {} (for [id ids]
                [id (not= false (-> (spec-basis id) :statutory-limits
                                    :pasteurization-figures-cited?))]))
     :note (str "cloud-itonami-isic-1050: " (count cited) "/" (count jurisdictions)
                " jurisdictions rest on a directly-fetched official source "
                "(govinfo 21 CFR 1240.61, e-Gov 乳等省令, EUR-Lex 853/2004). "
                "The EU entry is cited for ACCEPTANCE COOLING only -- the "
                "pasteurisation temperature/time definition is not in the "
                "fetched 853/2004 text, so its pasteurization figures are this "
                "actor's own operative limits and are reported as uncited "
                "rather than given a citation that does not support them.")}))

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that all required-evidence items are present in the batch's
  checklist. Returns true only if every item in the jurisdiction's
  required-evidence list is present in the batch's checklist."
  [jurisdiction-id checklist]
  (let [j (jurisdiction-by-id jurisdiction-id)]
    (if-not j
      false
      (let [required (set (:required-evidence j))
            present (set checklist)]
        (set/subset? required present)))))

(def product-types
  "Valid dairy product categories and their required processing parameters."
  {"whole-milk"
   {:id "whole-milk"
    :name "全脂牛乳"
    :cooling-temp-max-c 4.0
    :holding-time-max-hours 24}

   "skim-milk"
   {:id "skim-milk"
    :name "低脂牛乳"
    :cooling-temp-max-c 4.0
    :holding-time-max-hours 24}

   "yogurt"
   {:id "yogurt"
    :name "ヨーグルト"
    :cooling-temp-max-c 4.0
    :holding-time-max-hours 48}

   "cheese"
   {:id "cheese"
    :name "チーズ"
    :cooling-temp-max-c 8.0
    :holding-time-max-hours 72}})

(defn product-type-by-id [id]
  (get product-types id))
