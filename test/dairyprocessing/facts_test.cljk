(ns dairyprocessing.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [dairyprocessing.facts :as facts]))

(deftest jurisdiction-lookup
  (testing "Retrieve US jurisdiction"
    (let [us (facts/jurisdiction-by-id "US")]
      (is (some? us))
      (is (= "US" (:id us)))
      (is (= 63.0 (:pasteurization-temp-min-c us)))
      (is (= 4.0 (:cooling-temp-max-c us)))))

  (testing "Retrieve JP jurisdiction"
    (let [jp (facts/jurisdiction-by-id "JP")]
      (is (some? jp))
      (is (= "JP" (:id jp)))
      (is (= 65.0 (:pasteurization-temp-min-c jp)))))

  (testing "Unknown jurisdiction returns nil"
    (let [unknown (facts/jurisdiction-by-id "XX")]
      (is (nil? unknown)))))

(deftest required-evidence-check
  (testing "Complete evidence satisfies requirement"
    (let [satisfied? (facts/required-evidence-satisfied?
                      "US"
                      [:raw-milk-assay :pasteurization-log :temperature-log
                       :holding-time-record :sanitation-log :pathogen-test])]
      (is (true? satisfied?))))

  (testing "Incomplete evidence fails requirement"
    (let [satisfied? (facts/required-evidence-satisfied?
                      "US"
                      [:raw-milk-assay :pasteurization-log])]
      (is (false? satisfied?))))

  (testing "Unknown jurisdiction returns false"
    (let [satisfied? (facts/required-evidence-satisfied?
                      "XX"
                      [:raw-milk-assay])]
      (is (false? satisfied?)))))

(deftest product-type-lookup
  (testing "Retrieve whole milk product type"
    (let [whole (facts/product-type-by-id "whole-milk")]
      (is (some? whole))
      (is (= "whole-milk" (:id whole)))
      (is (= 4.0 (:cooling-temp-max-c whole)))))

  (testing "Retrieve cheese product type"
    (let [cheese (facts/product-type-by-id "cheese")]
      (is (some? cheese))
      (is (= 8.0 (:cooling-temp-max-c cheese)))))

  (testing "Unknown product type returns nil"
    (let [unknown (facts/product-type-by-id "unknown")]
      (is (nil? unknown)))))

;; ───────── Verified primary-source citations (2026-07-25) ─────────

(deftest cited-jurisdictions-rest-on-a-fetched-source
  (doseq [id (keys facts/jurisdictions)]
    (is (facts/cited? id) (str id " must carry legal-basis, provenance URL and verbatim text")))
  (is (nil? (facts/spec-basis "XX")))
  (is (false? (facts/cited? "XX"))))

(deftest hold-time-matches-the-thirty-minute-pairing
  (testing "63 °C pairs with 30 MINUTES; the old value was 30 SECONDS"
    (doseq [id ["US" "JP" "EU"]]
      (is (= 1800 (:pasteurization-hold-time-sec (facts/jurisdiction-by-id id)))
          (str id " must require 1800 s, not 30 s, at its low-temperature band")))))

(deftest equivalence-table-matches-21-cfr-1240-61
  (let [rows facts/pasteurization-equivalents]
    (is (= 7 (count rows)))
    (is (= [63.0 72.0 89.0 90.0 94.0 96.0 100.0] (mapv :temp-c rows)))
    (is (= [1800.0 15.0 1.0 0.5 0.1 0.05 0.01] (mapv :min-hold-sec rows)))
    (is (= 3.0 facts/high-fat-or-sweetened-temp-bump-c))))

(deftest pasteurization-adequacy-follows-the-pairs-not-a-single-number
  (testing "LTLT: 63 °C needs the full 30 minutes"
    (is (true? (facts/pasteurization-adequate? 63.0 1800)))
    (is (false? (facts/pasteurization-adequate? 63.0 1799)))
    (is (false? (facts/pasteurization-adequate? 63.5 31))
        "the demo batch's old 31 s at 63.5 °C was not pasteurization"))

  (testing "HTST: 72 °C needs only 15 s -- a single required-seconds number would reject this"
    (is (true? (facts/pasteurization-adequate? 72.0 15)))
    (is (false? (facts/pasteurization-adequate? 71.9 15)))
    (is (false? (facts/pasteurization-adequate? 72.0 14))))

  (testing "higher temperatures need less time"
    (is (true? (facts/pasteurization-adequate? 100.0 0.01)))
    (is (true? (facts/pasteurization-adequate? 96.0 0.05))))

  (testing "the CFR footnote raises every temperature by 3 °C for high-fat/sweetened product"
    (is (true? (facts/pasteurization-adequate? 72.0 15 {})))
    (is (false? (facts/pasteurization-adequate? 72.0 15 {:high-fat-or-sweetened? true}))
        "72 °C is no longer enough once the 3 °C bump applies")
    (is (true? (facts/pasteurization-adequate? 75.0 15 {:high-fat-or-sweetened? true}))))

  (testing "unmeasured is not adequate"
    (is (false? (facts/pasteurization-adequate? nil 1800)))
    (is (false? (facts/pasteurization-adequate? 63.0 nil)))))

(deftest eu-pasteurization-figures-are-reported-as-uncited
  (testing "the fetched 853/2004 text has no pasteurisation temperature/time definition"
    (let [eu (facts/spec-basis "EU")]
      (is (= 6.0 (-> eu :statutory-limits :acceptance-cooling-max-temp-c))
          "what IS cited: cooling to not more than 6 °C on acceptance")
      (is (false? (-> eu :statutory-limits :pasteurization-figures-cited?))))
    (let [c (facts/citation-coverage)]
      (is (false? (get (:pasteurization-figures-cited c) "EU")))
      (is (true? (get (:pasteurization-figures-cited c) "US"))))))

(deftest jp-statutory-floor-is-recorded-and-the-operative-limit-is-stricter
  (let [jp (facts/jurisdiction-by-id "JP")]
    (is (= 63.0 (-> jp :statutory-limits :pasteurization-floor-temp-c)))
    (is (= 30 (-> jp :statutory-limits :pasteurization-hold-min)))
    (is (= 10.0 (-> jp :statutory-limits :storage-max-temp-c)))
    (is (>= (:pasteurization-temp-min-c jp)
            (-> jp :statutory-limits :pasteurization-floor-temp-c))
        "the operative floor must never sit below the statutory one")
    (is (<= (:cooling-temp-max-c jp)
            (-> jp :statutory-limits :storage-max-temp-c))
        "4 °C is stricter than the 10 °C 乳等省令 storage ceiling -- kept deliberately")))
