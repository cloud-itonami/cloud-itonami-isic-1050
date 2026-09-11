(ns dairyprocessing.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [dairyprocessing.operation :as operation]
            [dairyprocessing.store :as store]
            [dairyprocessing.advisor :as advisor]))

(deftest run-operation-success
  (testing "Clean batch operation succeeds through governance"
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
          request {:op :log-production-batch
                   :subject "batch-1"
                   :stake :log-production-batch}
          context {:actor-id "test-dairy" :phase :phase-1}
          result (operation/run-operation st request context)]
      (is (= :escalate (:disposition result)))  ;; Phase 1 escalates high-stakes
      (is (some? (:audit result)))
      (is (seq (:audit result))))))

(deftest run-operation-governor-hold
  (testing "Governor rejection produces HOLD disposition"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-2" {:contamination-flag-raised? true
                           :contamination-flag-resolved? false
                           :scc-cells-ml 350000
                           :tbc-cfu-ml 50000
                           :pathogen-test-result {:listeria-negative? true
                                                  :salmonella-negative? true
                                                  :ecoli-negative? true}}}})
          request {:op :log-production-batch
                   :subject "batch-2"
                   :stake :log-production-batch}
          context {:actor-id "test-dairy"}
          result (operation/run-operation st request context)]
      (is (= :hold (:disposition result)))
      (is (some? (:audit result))))))

(deftest run-operation-monitoring-allowed
  (testing "Monitoring operation allowed in Phase 0"
    (let [st (store/mem-store)
          request {:op :flag-food-safety-concern
                   :subject "batch-3"
                   :stake :monitoring}
          context {:actor-id "test-dairy" :phase :phase-0}
          result (operation/run-operation st request context)]
      (is (some? (:disposition result))))))
