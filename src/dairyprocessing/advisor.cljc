(ns dairyprocessing.advisor
  "DairyProcessingAdvisor -- the contained LLM/decision node. This actor's
  intelligence layer proposes batch operations based on plant state and
  incoming milk deliveries. The advisor is SEALED into the `:advise` node of
  the operation graph; every proposal is routed through the independent
  Governor before committing.

  The advisor makes proposals but has NO direct authority. Proposals are
  always censored by:
    1. Governor (food-safety, compliance checks)
    2. Phase gate (rollout stage)
    3. Human operator (for high-stakes actions)

  Current implementation is a mock advisor for testing. Production should
  use langchain/Claude or similar LLM backend (same seam point as
  `meatprocessing.advisor`)."
  (:require [dairyprocessing.facts :as facts]))

;; Protocol for swappable advisor implementations
(defprotocol Advisor
  (-advise [advisor store request]
    "Given store and request, return a proposal map with
    :op, :stake, :value, :cites, :summary, :confidence"))

;; Mock advisor for testing
(defrecord MockAdvisor []
  Advisor
  (-advise [_advisor _store request]
    (let [{:keys [op subject]} request]
      (case op
        :log-production-batch
        {:op :log-production-batch
         :stake :log-production-batch
         :value {:jurisdiction "US"
                 :batch-id subject
                 :action "Log batch into production records"}
         :cites ["FDA-Pasteurized-Milk-Ordinance"]
         :summary "Raw milk quality verified; pasteurization and cooling complete; ready for production logging"
         :confidence 0.85}

        :coordinate-shipment
        {:op :coordinate-shipment
         :stake :coordinate-shipment
         :value {:batch-id subject
                 :destination "Distribution center"
                 :transport-mode "refrigerated-truck"}
         :cites ["FDA-Dairy-Requirements"]
         :summary "Final product ready for shipment; cold-chain maintained throughout"
         :confidence 0.80}

        :flag-food-safety-concern
        {:op :flag-food-safety-concern
         :stake :monitoring
         :value {:batch-id subject
                 :concern-type "temperature-excursion"
                 :description "Temperature logger showed > 4C for 45 minutes during storage"
                 :recommended-action "hold-for-sensory-evaluation"}
         :cites ["FDA-Critical-Control-Points"]
         :summary "Detected potential cold-chain breach; escalating for evaluation"
         :confidence 0.65}

        :schedule-maintenance
        {:op :schedule-maintenance
         :stake :operational
         :value {:equipment-id "PASTEURIZER-001"
                 :maintenance-type "pasteurizer-calibration"
                 :proposed-date "2026-07-20"}
         :cites ["Equipment-Manual-Pasteurizer-2024"]
         :summary "Pasteurizer due for scheduled calibration"
         :confidence 0.90}

        ;; fallback
        {:op op
         :stake :unknown
         :value {}
         :cites []
         :summary "Operation not recognized"
         :confidence 0.0}))))

(defn mock-advisor []
  (MockAdvisor.))

(defn trace
  "Audit trail entry for an advisor proposal. Recorded whenever a proposal
  is generated, regardless of whether it's approved."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :proposal-summary (:summary proposal)
   :confidence (:confidence proposal)})
