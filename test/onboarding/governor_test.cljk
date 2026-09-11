(ns onboarding.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [onboarding.advisor :as advisor]
            [onboarding.governor :as governor]
            [onboarding.store :as store]))

(def ctx {:actor-id "onboarding-actor" :phase 3})

(defn- db [] (store/seed-db))

(defn- advise [st op applicant-id & [patch]]
  (advisor/-advise (advisor/mock-advisor) st
                   {:op op :applicant-id applicant-id :patch (or patch {})}))

(defn- check [st op applicant-id & [patch]]
  (let [req {:op op :applicant-id applicant-id :patch (or patch {})}]
    (governor/check req ctx (advise st op applicant-id patch) st)))

;; ───────────────────────── the applicant gate ─────────────────────────

(deftest unknown-applicant-is-a-hard-block
  (let [v (check (db) :log-application "app-nope")]
    (is (true? (:hard? v)))
    (is (= [:applicant-unknown] (mapv :rule (:violations v))))))

(deftest unverified-applicant-is-NOT-blocked-for-intake-ops
  (testing "handling not-yet-verified parties is this actor's entire job —
            inverting the fleet's usual 'verified counterparty' gate naively
            would make the actor useless"
    (doseq [op [:log-application :request-evidence :record-screening]]
      (let [v (check (db) op "app-4")]   ; app-4: incomplete evidence, no AML
        (is (false? (:hard? v)) (str op))))))

;; ───────────────────────── the evidence floor ─────────────────────────

(deftest credential-for-a-clean-applicant-passes-the-floor
  (let [v (check (db) :propose-credential "app-1"
                 {:issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"})]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (testing "but it still escalates — issuing an identity always needs a human"
      (is (true? (:high-stakes? v)))
      (is (true? (:escalate? v)))
      (is (false? (:ok? v))))))

(deftest credential-with-missing-evidence-is-a-hard-block
  (testing "app-2 never had :sanctions verified"
    (let [v (check (db) :propose-credential "app-2"
                   {:issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"})]
      (is (true? (:hard? v)))
      (is (some #{:insufficient-evidence} (mapv :rule (:violations v)))))))

(deftest credential-under-aml-hold-is-a-hard-block
  (testing "app-3 has full eKYC evidence but an AML :deny"
    (let [v (check (db) :propose-credential "app-3"
                   {:issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"})]
      (is (true? (:hard? v)))
      (is (some #{:aml-hold} (mapv :rule (:violations v)))))))

(deftest credential-with-no-screening-is-a-hard-block
  (testing "app-4 was never screened — :not-run must never be treated as clear"
    (let [v (check (db) :propose-credential "app-4"
                   {:issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"})]
      (is (true? (:hard? v)))
      (is (some #{:aml-not-run} (mapv :rule (:violations v)))))))

(deftest advisor-cannot-widen-its-own-evidence-base
  (testing "an advisor that attaches a flattering evidence summary to its own
            draft is overruled — the governor recomputes from the store"
    (let [st (db)
          ;; A hand-built proposal for app-2 (missing :sanctions) whose
          ;; drafted credential CLAIMS full evidence and a clear screening.
          forged {:op :propose-credential
                  :applicant-id "app-2"
                  :summary "forged" :rationale "forged" :cites []
                  :effect :propose :confidence 0.99
                  :value {:applicant-id "app-2"
                          :credential {:seller/id "merchant.sunset"
                                       :seller/kind :company
                                       :seller/legal-name "Sunset Direct GmbH"
                                       :seller/country "DEU"
                                       :seller/issuer "did:web:marketplace.example"
                                       :seller/issued-at "2026-01-01T00:00:00Z"
                                       :seller/expires-at "2027-01-01T00:00:00Z"
                                       :seller/status :issued
                                       :seller/payout-bound? true
                                       :seller/non-adjudicating true
                                       :seller/evidence
                                       {:evidence/verified-checks #{:document-authenticity :sanctions}
                                        :evidence/aml-status :clear}}}}
          v (governor/check {:op :propose-credential :applicant-id "app-2"} ctx forged st)]
      (is (true? (:hard? v)))
      (is (some #{:insufficient-evidence} (mapv :rule (:violations v)))
          "the store says :sanctions was never verified; the draft's claim is ignored"))))

(deftest credential-draft-must-be-present
  (let [st (db)
        v (governor/check {:op :propose-credential :applicant-id "app-1"} ctx
                          {:op :propose-credential :applicant-id "app-1"
                           :effect :propose :confidence 0.9 :value {}}
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:credential-draft-missing} (mapv :rule (:violations v))))))

;; ───────────────────────── effect + scope ─────────────────────────

(deftest effect-must-be-propose
  (let [st (db)
        v (governor/check {:op :log-application :applicant-id "app-1"} ctx
                          (assoc (advise st :log-application "app-1") :effect :commit)
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:effect-not-propose} (mapv :rule (:violations v))))))

(deftest op-outside-the-allowlist-is-a-scope-violation
  (let [st (db)
        v (governor/check {:op :issue-credential :applicant-id "app-1"} ctx
                          {:op :issue-credential :applicant-id "app-1"
                           :effect :propose :confidence 0.99}
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:op-not-allowed} (mapv :rule (:violations v))))))

(deftest scope-exclusion-blocks-finalization-claims
  (let [st (db)
        p (advisor/infer nil {:op :log-application :applicant-id "app-1"
                              :patch {} :out-of-scope? true})
        v (governor/check {:op :log-application :applicant-id "app-1"} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:scope-excluded} (mapv :rule (:violations v)))))
  (testing "a hard scope violation is never rescued by high confidence"
    (let [st (db)
          p (assoc (advisor/infer nil {:op :log-application :applicant-id "app-1"
                                       :patch {} :out-of-scope? true})
                   :confidence 1.0)
          v (governor/check {:op :log-application :applicant-id "app-1"} ctx p st)]
      (is (true? (:hard? v)))
      (is (false? (:ok? v))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every legitimate proposal talks about identity verification and
            sanctions screening — the excluded terms are phrased as the
            FINALIZATION action so the happy path never self-blocks"
    (let [st (db)]
      (doseq [op [:log-application :request-evidence :record-screening
                  :flag-identity-concern]]
        (let [v (check st op "app-1" {:check :sanctions :concern "velocity"})]
          (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))) (str op))))
      (let [v (check st :propose-credential "app-1"
                     {:issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"})]
        (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))))))))

;; ───────────────────────── escalation ─────────────────────────

(deftest identity-concern-always-escalates
  (let [v (check (db) :flag-identity-concern "app-1" {:concern "velocity" :confidence 0.99})]
    (is (false? (:hard? v)))
    (is (true? (:high-stakes? v)))
    (is (true? (:escalate? v)))
    (is (false? (:ok? v)) "confidence can never make this auto-committable")))

(deftest low-confidence-escalates
  (let [st (db)
        v (governor/check {:op :log-application :applicant-id "app-1"} ctx
                          (assoc (advise st :log-application "app-1") :confidence 0.4)
                          st)]
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))))

(deftest clean-intake-proposal-is-ok
  (let [v (check (db) :log-application "app-1" {:channel "web"})]
    (is (true? (:ok? v)))
    (is (false? (:escalate? v)))
    (is (empty? (:violations v)))))

(deftest hold-fact-carries-the-basis
  (let [st (db)
        v (check st :log-application "app-nope")
        f (governor/hold-fact {:op :log-application :applicant-id "app-nope"} ctx v)]
    (is (= :governor-hold (:t f)))
    (is (= :hold (:disposition f)))
    (is (= [:applicant-unknown] (:basis f)))))
