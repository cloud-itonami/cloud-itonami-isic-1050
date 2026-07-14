(ns dairyprocessing.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [dairyprocessing.store :as store]))

(deftest mem-store-basic
  (testing "Create empty store"
    (let [st (store/mem-store)]
      (is (some? st))))

  (testing "Create store with initial batches"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:id "batch-1"
                          :product-type "whole-milk"}}})]
      (is (some? st))))

  (testing "Retrieve batch from store"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:id "batch-1"
                          :product-type "whole-milk"}}})
          batch (store/processing-batch st "batch-1")]
      (is (some? batch))
      (is (= "batch-1" (:id batch)))))

  (testing "Retrieve non-existent batch returns nil"
    (let [st (store/mem-store)
          batch (store/processing-batch st "no-such-batch")]
      (is (nil? batch)))))

(deftest mem-store-add-batch
  (testing "Add batch to store"
    (let [st (store/mem-store)
          data {:id "batch-2" :product-type "yogurt"}]
      (store/add-batch st "batch-2" data)
      (let [retrieved (store/processing-batch st "batch-2")]
        (is (= data retrieved))))))

(deftest mem-store-batch-quality
  (testing "Retrieve batch quality"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:scc-cells-ml 350000
                          :tbc-cfu-ml 50000
                          :holding-time-hours 12
                          :sanitation-score 85
                          :evidence-checklist [:raw-milk-assay :pathogen-test]}}})
          quality (store/batch-quality-of st "batch-1")]
      (is (some? quality))
      (is (= 350000 (:scc-cells-ml quality)))
      (is (= 50000 (:tbc-cfu-ml quality))))))

(deftest mem-store-processed-flag
  (testing "Mark batch as processed"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:id "batch-1"}}})
          _ (store/mark-processed st "batch-1")
          is-processed? (store/batch-already-processed? st "batch-1")]
      (is (true? is-processed?))))

  (testing "Non-processed batch returns false"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:id "batch-1"}}})
          is-processed? (store/batch-already-processed? st "batch-1")]
      (is (false? is-processed?)))))

(deftest mem-store-shipment-flag
  (testing "Mark shipment as finalized"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:id "batch-1"}}})
          _ (store/mark-shipment-finalized st "batch-1")
          finalized? (store/batch-shipment-finalized? st "batch-1")]
      (is (true? finalized?))))

  (testing "Non-finalized shipment returns false"
    (let [st (store/mem-store
              {:initial-batches
               {"batch-1" {:id "batch-1"}}})
          finalized? (store/batch-shipment-finalized? st "batch-1")]
      (is (false? finalized?)))))
