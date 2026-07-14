(ns dairyprocessing.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [dairyprocessing.phase :as phase]))

(deftest verdict-to-disposition
  (testing "ok? true -> commit"
    (let [disp (phase/verdict->disposition {:ok? true :escalate? false :hard? false})]
      (is (= :commit disp))))

  (testing "escalate? true -> escalate"
    (let [disp (phase/verdict->disposition {:ok? false :escalate? true :hard? false})]
      (is (= :escalate disp))))

  (testing "hard? true -> hold"
    (let [disp (phase/verdict->disposition {:ok? false :escalate? false :hard? true})]
      (is (= :hold disp)))))

(deftest phase-0-gate
  (testing "Phase 0 blocks high-stakes operations"
    (let [request {:stake :log-production-batch}
          result (phase/gate :phase-0 request :commit)]
      (is (= :hold (:disposition result)))
      (is (= :phase-0-no-production (:reason result)))))

  (testing "Phase 0 allows monitoring"
    (let [request {:stake :monitoring}
          result (phase/gate :phase-0 request :commit)]
      (is (= :commit (:disposition result))))))

(deftest phase-1-gate
  (testing "Phase 1 escalates high-stakes even if clean"
    (let [request {:stake :log-production-batch}
          result (phase/gate :phase-1 request :commit)]
      (is (= :escalate (:disposition result)))
      (is (= :phase-1-high-stakes (:reason result)))))

  (testing "Phase 1 allows routine operations"
    (let [request {:stake :monitoring}
          result (phase/gate :phase-1 request :commit)]
      (is (= :commit (:disposition result))))))

(deftest phase-2-3-gate
  (testing "Phase 2 passes through clean disposition"
    (let [request {:stake :log-production-batch}
          result (phase/gate :phase-2 request :commit)]
      (is (= :commit (:disposition result)))))

  (testing "Phase 3 passes through clean disposition"
    (let [request {:stake :log-production-batch}
          result (phase/gate :phase-3 request :commit)]
      (is (= :commit (:disposition result))))))

(deftest unknown-phase
  (testing "Unknown phase -> conservative hold"
    (let [request {:stake :monitoring}
          result (phase/gate :phase-99 request :commit)]
      (is (= :hold (:disposition result)))
      (is (= :unknown-phase (:reason result))))))
