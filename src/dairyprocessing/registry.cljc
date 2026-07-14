(ns dairyprocessing.registry
  "Pure validation functions for dairy processing parameters. These are called
  by the Governor to independently verify physical/operational constraints --
  the LLM advisor's confidence is NOT sufficient to override these checks."
  (:require [dairyprocessing.facts :as facts]))

(defn pasteurization-temp-out-of-range?
  "Independently verify that the milk's pasteurization temperature stays
  within the jurisdiction's required range [min,max]. Both bounds are
  inclusive. Pasteurization at too low a temperature risks pathogens;
  too high risks protein denaturation. Both are HARD limits."
  [actual-temp-c min-temp-c max-temp-c]
  (or (< actual-temp-c min-temp-c)
      (> actual-temp-c max-temp-c)))

(defn cooling-temp-out-of-range?
  "Independently verify that the cooled milk stays within the maximum
  cooling temperature. Milk must be rapidly cooled to ≤4°C after
  pasteurization to inhibit pathogenic growth."
  [actual-temp-c max-temp-c]
  (> actual-temp-c max-temp-c))

(defn holding-time-exceeded?
  "Independently verify that the batch's actual time at ambient temperature
  does not exceed the jurisdiction's maximum holding-time. Time zero is when
  the batch completes pasteurization and begins cooling; time is recorded
  continuously via temperature logger."
  [actual-hours-held max-hours-allowed]
  (> actual-hours-held max-hours-allowed))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's pre-processing sanitation score
  meets the minimum required by jurisdiction. Score is 0-100, assessed by
  a third-party auditor against FDA/MHLW/EFSA dairy sanitation standards
  within 7 days prior to batch processing."
  [actual-score min-score-required]
  (< actual-score min-score-required))

(defn scc-tbc-acceptable?
  "Raw milk quality check: Somatic Cell Count (SCC) and Total Bacterial
  Count (TBC) must meet minimum thresholds for dairy processing.
  SCC ≤ 400k cells/mL, TBC ≤ 100k CFU/mL (US PMO standards)."
  [{:keys [scc-cells-ml tbc-cfu-ml]}]
  (and (<= (or scc-cells-ml 0) 400000)
       (<= (or tbc-cfu-ml 0) 100000)))

(defn pathogen-test-passed?
  "Pathogen screening (Listeria, Salmonella, E. coli) must be negative.
  Returns true only if all tested pathogens are negative."
  [{:keys [listeria-negative? salmonella-negative? ecoli-negative?]}]
  (and (true? listeria-negative?)
       (true? salmonella-negative?)
       (true? ecoli-negative?)))

(defn holding-time-excessive-after-concern?
  "For a batch with contamination concern raised, verify that the batch has
  not sat idle beyond a safe holding window after contamination was flagged.
  If a concern is raised and more than 2 hours pass without being resolved,
  the batch cannot be processed (risk of pathogen multiplication)."
  [hours-since-flagged]
  (> hours-since-flagged 2.0))
