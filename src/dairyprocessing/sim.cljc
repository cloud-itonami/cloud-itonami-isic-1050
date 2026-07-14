(ns dairyprocessing.sim
  "Simple simulation/demo driver for the dairy processing actor.
  Exercises the basic flow: intake -> log batch -> coordinate shipment."
  (:require [dairyprocessing.store :as store]
            [dairyprocessing.operation :as operation]
            [dairyprocessing.advisor :as advisor]))

(defn -main []
  (println "Dairy Processing Actor Simulation")
  (println "=================================")

  ;; Set up an initial batch in the store
  (let [st (store/mem-store
            {:initial-batches
             {"batch-1050-001"
              {:id "batch-1050-001"
               :jurisdiction "US"
               :product-type "whole-milk"
               :received-at "2026-07-14T08:00:00Z"
               :raw-milk-temp-c 3.5
               :scc-cells-ml 350000
               :tbc-cfu-ml 50000
               :pasteurization-temp-c 63.5
               :pasteurization-hold-time-sec 31
               :cooling-temp-c 3.8
               :holding-time-hours 12
               :sanitation-score 85
               :pathogen-test-result {:listeria-negative? true
                                      :salmonella-negative? true
                                      :ecoli-negative? true}
               :contamination-flag-raised? false
               :contamination-flag-resolved? true
               :evidence-checklist [:raw-milk-assay :pasteurization-log
                                    :temperature-log :holding-time-record
                                    :sanitation-log :pathogen-test]}}})
        actor-context {:actor-id "dairy-processing-01" :phase :phase-1}]

    ;; Simulate logging a production batch
    (println "\n1. Logging production batch: batch-1050-001")
    (let [request {:op :log-production-batch
                   :subject "batch-1050-001"
                   :stake :log-production-batch}
          result (operation/run-operation st request actor-context)]
      (println "   Disposition:" (:disposition result))
      (println "   Audit trail:" (:audit result))
      (when-let [rec (:record result)]
        (println "   Record:" rec)))

    ;; Simulate coordinating shipment (only if prior logged successfully)
    (println "\n2. Coordinating shipment: batch-1050-001")
    (let [request {:op :coordinate-shipment
                   :subject "batch-1050-001"
                   :stake :coordinate-shipment}
          result (operation/run-operation st request actor-context)]
      (println "   Disposition:" (:disposition result))
      (println "   Audit trail:" (:audit result))
      (when-let [rec (:record result)]
        (println "   Record:" rec)))

    (println "\n✓ Simulation complete")))
