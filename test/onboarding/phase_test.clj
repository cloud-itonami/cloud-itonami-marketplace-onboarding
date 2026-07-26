(ns onboarding.phase-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [onboarding.governor :as governor]
            [onboarding.phase :as phase]))

(deftest high-stakes-ops-are-never-auto-eligible-at-any-phase
  (testing "the structural invariant, asserted across EVERY phase rather
            than only the current default — a future phase added below
            cannot quietly make credential issuance automatic"
    (doseq [[p {:keys [auto label]}] phase/phases
            op governor/always-escalate-ops]
      (is (not (contains? auto op))
          (str "phase " p " (" label ") must not auto-commit " op)))))

(deftest governor-and-phase-agree-independently
  (testing "two layers, not one: the governor's always-escalate set and the
            phase table's auto sets must never disagree about which ops can
            run unattended"
    (is (= #{:flag-identity-concern :propose-credential} governor/always-escalate-ops))
    (is (empty? (set/intersection
                 governor/always-escalate-ops
                 (:auto (get phase/phases phase/default-phase)))))))

(deftest phase-gate-transitions
  (testing "a governor HOLD always stays HOLD — compliance wins over phase"
    (is (= :hold (:disposition (phase/gate 3 {:op :log-application} :hold)))))
  (testing "an op not enabled in this phase holds with a reason"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :record-screening} :commit)]
      (is (= :hold disposition))
      (is (= :phase-disabled reason))))
  (testing "an enabled but non-auto op escalates even when the governor was clean"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-application} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason))))
  (testing "an auto-eligible op at phase 3 commits"
    (is (= :commit (:disposition (phase/gate 3 {:op :log-application} :commit)))))
  (testing "phase 0 writes nothing"
    (doseq [op governor/allowed-ops]
      (is (= :hold (:disposition (phase/gate 0 {:op op} :commit))) (str op)))))

(deftest unknown-phase-falls-back-to-default
  (is (= (phase/gate phase/default-phase {:op :log-application} :commit)
         (phase/gate 99 {:op :log-application} :commit))))

(deftest verdict-mapping
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? true})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
