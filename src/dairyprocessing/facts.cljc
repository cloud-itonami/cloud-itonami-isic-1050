(ns dairyprocessing.facts
  "Reference facts for dairy processing: jurisdiction requirements for milk
  pasteurization, temperature control, holding-time compliance, and food-safety
  evidence. This namespace contains pure lookup functions for regulatory
  compliance checks -- the Governor calls these to validate proposals against
  jurisdiction requirements."
  (:require [clojure.string :as str]))

(def jurisdictions
  "Dairy processing jurisdictions and their required documentation/evidence
  checklist requirements."
  {"US"
   {:id "US"
    :name "United States (FDA/PMO)"
    :pasteurization-temp-min-c 63.0
    :pasteurization-temp-max-c 65.0
    :pasteurization-hold-time-sec 30
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
    :name "日本 (厚生労働省)"
    :pasteurization-temp-min-c 65.0
    :pasteurization-temp-max-c 67.0
    :pasteurization-hold-time-sec 30
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
    :name "European Union (EFSA)"
    :pasteurization-temp-min-c 63.0
    :pasteurization-temp-max-c 65.0
    :pasteurization-hold-time-sec 30
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
        (clojure.set/subset? required present)))))

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
