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
