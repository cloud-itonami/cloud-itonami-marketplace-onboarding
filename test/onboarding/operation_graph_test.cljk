(ns onboarding.operation-graph-test
  "Integration tests for `onboarding.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes.

  The headline test here is
  `credential-can-only-reach-the-store-through-a-human`: it asserts the
  structural claim ADR-2607264000 makes about this marketplace, namely
  that self-serve onboarding still cannot mint a seller identity without
  a named human signing it."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [onboarding.operation :as operation]
            [onboarding.store :as store]))

(def ^:private op-context {:actor-id "onboarding-01" :phase 3})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(def ^:private cred-patch
  {:issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"})

(deftest intake-auto-commits-in-phase-3
  (testing ":log-application is in phase-3's :auto set -- a clean proposal
            for a known applicant commits straight through the REAL
            compiled graph with no interrupt"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-commit"
                         {:op :log-application :applicant-id "app-1"
                          :patch {:channel "web"}})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-application (:op (first ledger)))))
        (is (= 1 (count (store/onboarding-log s))))))))

(deftest intake-works-for-a-not-yet-verified-applicant
  (testing "app-4 has incomplete evidence and no screening — intake must
            still flow, because handling unverified parties IS the job"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-unverified"
                       {:op :request-evidence :applicant-id "app-4"
                        :patch {:check :liveness}})]
      (is (= :done (:status result)))
      (is (= :commit (:disposition (:state result)))))))

(deftest unknown-applicant-hard-holds-without-pausing
  (let [s (store/seed-db)
        actor (operation/build s)
        result (exec actor "t-hold"
                     {:op :log-application :applicant-id "app-nope" :patch {}})]
    (is (= :done (:status result)) "HARD holds never pause for approval")
    (is (= :hold (:disposition (:state result))))
    (let [ledger (store/ledger s)]
      (is (= 1 (count ledger)))
      (is (= :governor-hold (:t (first ledger))))
      (is (some #{:applicant-unknown} (map :rule (:violations (first ledger))))))
    (is (empty? (store/onboarding-log s)) "never committed to the SSoT")))

(deftest credential-can-only-reach-the-store-through-a-human
  (testing "THE structural claim of this actor: :propose-credential is
            never in any phase's :auto set and the governor marks it
            high-stakes independently, so even a fully clean applicant
            GENUINELY interrupts. The credential directory stays EMPTY
            until a named human resumes the run"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/all-credential-records s)))
      (let [held (exec actor "t-cred"
                       {:op :propose-credential :applicant-id "app-1"
                        :patch cred-patch})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/all-credential-records s))
            "no credential exists yet — awaiting human sign-off")
        (is (empty? (store/ledger s)))

        (let [approved (g/run* actor {:approval {:status :approved :by "compliance-01"}}
                               {:thread-id "t-cred" :resume? true})]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition (:state approved))))
          (let [creds (store/all-credential-records s)]
            (is (= 1 (count creds)))
            (is (= "merchant.riverside" (:seller/id (first creds))))
            (is (= :issued (:seller/status (first creds)))))
          (let [[record] (store/onboarding-log s)]
            (is (= "compliance-01" (:approved-by (:payload record)))
                "the committed record names the human who signed it")))))))

(deftest rejected-credential-never-reaches-the-store
  (let [s (store/seed-db)
        actor (operation/build s)
        _held (exec actor "t-cred-reject"
                    {:op :propose-credential :applicant-id "app-1" :patch cred-patch})
        rejected (g/run* actor {:approval {:status :rejected :by "compliance-01"}}
                         {:thread-id "t-cred-reject" :resume? true})]
    (is (= :done (:status rejected)))
    (is (= :hold (:disposition (:state rejected))))
    (is (empty? (store/all-credential-records s)))
    (is (= :approval-rejected (:t (first (store/ledger s)))))))

(deftest credential-for-an-applicant-failing-the-evidence-floor-hard-holds
  (testing "app-2 is missing :sanctions — the graph must route to :hold
            and never pause for a human, because no human approval can
            override a HARD evidence-floor violation"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-floor"
                       {:op :propose-credential :applicant-id "app-2" :patch cred-patch})]
      (is (= :done (:status result)) "not :interrupted — a human is never even asked")
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/all-credential-records s)))
      (is (some #{:insufficient-evidence}
                (map :rule (:violations (first (store/ledger s)))))))))

(deftest credential-under-aml-hold-hard-holds
  (let [s (store/seed-db)
        actor (operation/build s)
        result (exec actor "t-aml"
                     {:op :propose-credential :applicant-id "app-3" :patch cred-patch})]
    (is (= :done (:status result)))
    (is (= :hold (:disposition (:state result))))
    (is (some #{:aml-hold} (map :rule (:violations (first (store/ledger s))))))))

(deftest identity-concern-escalates-and-threads-the-real-proposal
  (testing "a randomly generated single-use concern string proves the graph
            threads the Advisor's REAL proposal through
            :advise -> :govern -> :decide -> :request-approval -> :commit
            rather than hardcoding a pass-string"
    (let [distinctive (str "TEST-CONCERN-" (rand-int 1000000000))
          s (store/seed-db)
          actor (operation/build s)
          held (exec actor "t-concern"
                     {:op :flag-identity-concern :applicant-id "app-1"
                      :patch {:concern distinctive}})]
      (is (= :interrupted (:status held)))
      (is (empty? (store/ledger s)))
      (let [approved (g/run* actor {:approval {:status :approved :by "compliance-01"}}
                             {:thread-id "t-concern" :resume? true})]
        (is (= :done (:status approved)))
        (let [[record] (store/onboarding-log s)]
          (is (= distinctive (:concern (:payload record)))))))))

(deftest phase-1-disables-evidence-and-screening-ops
  (testing "the phase gate is genuinely wired into the compiled graph, not
            merely unit-tested in isolation"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-phase1"
                       {:op :record-screening :applicant-id "app-1" :patch {}}
                       {:actor-id "onboarding-01" :phase 1})]
      (is (= :done (:status result)))
      (is (= :hold (:disposition (:state result))))
      (is (= :phase-disabled (:phase-reason (first (store/ledger s))))))))

(deftest phase-0-is-read-only
  (let [s (store/seed-db)
        actor (operation/build s)
        result (exec actor "t-phase0"
                     {:op :log-application :applicant-id "app-1" :patch {}}
                     {:actor-id "onboarding-01" :phase 0})]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/onboarding-log s)))))
