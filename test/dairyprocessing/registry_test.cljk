(ns dairyprocessing.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [dairyprocessing.registry :as registry]))

(deftest pasteurization-temp-validation
  (testing "Temperature within range -> false (no violation)"
    (let [violation? (registry/pasteurization-temp-out-of-range? 63.5 63.0 65.0)]
      (is (false? violation?))))

  (testing "Temperature below minimum -> true (violation)"
    (let [violation? (registry/pasteurization-temp-out-of-range? 62.0 63.0 65.0)]
      (is (true? violation?))))

  (testing "Temperature above maximum -> true (violation)"
    (let [violation? (registry/pasteurization-temp-out-of-range? 66.0 63.0 65.0)]
      (is (true? violation?)))))

(deftest cooling-temp-validation
  (testing "Cooling temperature within limit -> false (no violation)"
    (let [violation? (registry/cooling-temp-out-of-range? 3.5 4.0)]
      (is (false? violation?))))

  (testing "Cooling temperature exceeds limit -> true (violation)"
    (let [violation? (registry/cooling-temp-out-of-range? 5.0 4.0)]
      (is (true? violation?)))))

(deftest holding-time-validation
  (testing "Holding time within limit -> false (no violation)"
    (let [violation? (registry/holding-time-exceeded? 12.0 24.0)]
      (is (false? violation?))))

  (testing "Holding time exceeds limit -> true (violation)"
    (let [violation? (registry/holding-time-exceeded? 30.0 24.0)]
      (is (true? violation?)))))

(deftest scc-tbc-validation
  (testing "Both SCC and TBC acceptable -> true"
    (let [acceptable? (registry/scc-tbc-acceptable?
                       {:scc-cells-ml 350000 :tbc-cfu-ml 50000})]
      (is (true? acceptable?))))

  (testing "SCC exceeds limit -> false"
    (let [acceptable? (registry/scc-tbc-acceptable?
                       {:scc-cells-ml 500000 :tbc-cfu-ml 50000})]
      (is (false? acceptable?))))

  (testing "TBC exceeds limit -> false"
    (let [acceptable? (registry/scc-tbc-acceptable?
                       {:scc-cells-ml 350000 :tbc-cfu-ml 150000})]
      (is (false? acceptable?)))))

(deftest pathogen-test-validation
  (testing "All pathogens negative -> true"
    (let [pass? (registry/pathogen-test-passed?
                 {:listeria-negative? true
                  :salmonella-negative? true
                  :ecoli-negative? true})]
      (is (true? pass?))))

  (testing "Listeria positive -> false"
    (let [pass? (registry/pathogen-test-passed?
                 {:listeria-negative? false
                  :salmonella-negative? true
                  :ecoli-negative? true})]
      (is (false? pass?))))

  (testing "Salmonella positive -> false"
    (let [pass? (registry/pathogen-test-passed?
                 {:listeria-negative? true
                  :salmonella-negative? false
                  :ecoli-negative? true})]
      (is (false? pass?)))))
