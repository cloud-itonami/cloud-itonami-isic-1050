(ns dairyprocessing.governor-test
  (:require [clojure.test :refer [deftest is are testing]]
            [dairyprocessing.governor :as governor]
            [dairyprocessing.store :as store]
            [dairyprocessing.facts :as facts]))

(deftest raw-milk-quality-violations
  (testing "Raw milk quality within range -> no violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:scc-cells-ml 350000
                           :tbc-cfu-ml 50000}}})
          request {:op :log-production-batch :subject "batch-1"}
          violations (#'governor/raw-milk-quality-violations request st)]
      (is (empty? violations))))

  (testing "Raw milk SCC too high -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-2" {:scc-cells-ml 500000
                           :tbc-cfu-ml 50000}}})
          request {:op :log-production-batch :subject "batch-2"}
          violations (#'governor/raw-milk-quality-violations request st)]
      (is (seq violations))
      (is (= :raw-milk-quality-insufficient (-> violations first :rule)))))

  (testing "Raw milk TBC too high -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-3" {:scc-cells-ml 350000
                           :tbc-cfu-ml 150000}}})
          request {:op :log-production-batch :subject "batch-3"}
          violations (#'governor/raw-milk-quality-violations request st)]
      (is (seq violations))
      (is (= :raw-milk-quality-insufficient (-> violations first :rule))))))

(deftest pasteurization-temp-out-of-range-violations
  (testing "Pasteurization temperature within range -> no violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:pasteurization-temp-c 63.5
                           :jurisdiction "US"}}})
          request {:op :log-production-batch :subject "batch-1"}
          violations (#'governor/pasteurization-temp-out-of-range-violations request st)]
      (is (empty? violations))))

  (testing "Pasteurization temperature too low -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-2" {:pasteurization-temp-c 62.0
                           :jurisdiction "US"}}})
          request {:op :log-production-batch :subject "batch-2"}
          violations (#'governor/pasteurization-temp-out-of-range-violations request st)]
      (is (seq violations))
      (is (= :pasteurization-temp-out-of-range (-> violations first :rule)))))

  (testing "Pasteurization temperature too high -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-3" {:pasteurization-temp-c 66.0
                           :jurisdiction "US"}}})
          request {:op :log-production-batch :subject "batch-3"}
          violations (#'governor/pasteurization-temp-out-of-range-violations request st)]
      (is (seq violations))
      (is (= :pasteurization-temp-out-of-range (-> violations first :rule))))))

(deftest cooling-temp-out-of-range-violations
  (testing "Cooling temperature within range -> no violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:cooling-temp-c 3.5
                           :product-type "whole-milk"}}})
          request {:op :log-production-batch :subject "batch-1"}
          violations (#'governor/cooling-temp-out-of-range-violations request st)]
      (is (empty? violations))))

  (testing "Cooling temperature too high -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-2" {:cooling-temp-c 5.0
                           :product-type "whole-milk"}}})
          request {:op :log-production-batch :subject "batch-2"}
          violations (#'governor/cooling-temp-out-of-range-violations request st)]
      (is (seq violations))
      (is (= :cooling-temp-out-of-range (-> violations first :rule))))))

(deftest pathogen-test-failed-violations
  (testing "All pathogens negative -> no violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:pathogen-test-result {:listeria-negative? true
                                                   :salmonella-negative? true
                                                   :ecoli-negative? true}}}})
          request {:op :log-production-batch :subject "batch-1"}
          violations (#'governor/pathogen-test-failed-violations request st)]
      (is (empty? violations))))

  (testing "Listeria detected -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-2" {:pathogen-test-result {:listeria-negative? false
                                                   :salmonella-negative? true
                                                   :ecoli-negative? true}}}})
          request {:op :log-production-batch :subject "batch-2"}
          violations (#'governor/pathogen-test-failed-violations request st)]
      (is (seq violations))
      (is (= :pathogen-test-failed (-> violations first :rule))))))

(deftest contamination-flag-unresolved-violations
  (testing "No contamination flag -> no violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:contamination-flag-raised? false}}})
          request {:op :log-production-batch :subject "batch-1"}
          violations (#'governor/contamination-flag-unresolved-violations request st)]
      (is (empty? violations))))

  (testing "Contamination raised but resolved -> no violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-2" {:contamination-flag-raised? true
                           :contamination-flag-resolved? true}}})
          request {:op :log-production-batch :subject "batch-2"}
          violations (#'governor/contamination-flag-unresolved-violations request st)]
      (is (empty? violations))))

  (testing "Contamination raised and NOT resolved -> violation"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-3" {:contamination-flag-raised? true
                           :contamination-flag-resolved? false}}})
          request {:op :log-production-batch :subject "batch-3"}
          violations (#'governor/contamination-flag-unresolved-violations request st)]
      (is (seq violations))
      (is (= :contamination-flag-unresolved (-> violations first :rule))))))

(deftest check-ok-verdict
  (testing "All checks pass -> ok? true"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:scc-cells-ml 350000
                           :tbc-cfu-ml 50000
                           :pasteurization-temp-c 63.5
                           :cooling-temp-c 3.8
                           :holding-time-hours 12
                           :jurisdiction "US"
                           :sanitation-score 85
                           :product-type "whole-milk"
                           :pathogen-test-result {:listeria-negative? true
                                                  :salmonella-negative? true
                                                  :ecoli-negative? true}
                           :contamination-flag-raised? false
                           :evidence-checklist [:raw-milk-assay :pasteurization-log
                                               :temperature-log :holding-time-record
                                               :sanitation-log :pathogen-test]}}})
          request {:op :log-production-batch :subject "batch-1" :stake :log-production-batch}
          proposal {:value {:jurisdiction "US"} :cites ["FDA-PMO"] :confidence 0.85}
          context {:actor-id "test"}
          verdict (governor/check request context proposal st)]
      (is (true? (:ok? verdict)))
      (is (empty? (:violations verdict))))))

(deftest already-processed-check
  (testing "Batch already processed -> violation"
    (let [st (store/mem-store
              {:initial-batches {"batch-1" {:processed? true}}})
          request {:op :log-production-batch :subject "batch-1"}
          violations (#'governor/already-processed-violations request st)]
      (is (seq violations))
      (is (= :already-processed (-> violations first :rule))))))
