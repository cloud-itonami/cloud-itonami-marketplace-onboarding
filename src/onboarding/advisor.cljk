(ns onboarding.advisor
  "SellerOnboardingAdvisor -- the *contained intelligence node* for the
  marketplace seller-onboarding actor.

  It drafts exactly five kinds of proposal from a closed allowlist:
  logging an application, requesting a specific missing eKYC evidence
  item, recording a screening result received from a provider, flagging
  an identity concern for human triage, and DRAFTING a seller credential
  for human approval.

  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record and
  NEVER a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by
  `onboarding.governor` before anything touches the SSoT.

  This advisor NEVER drafts a completion of identity verification, a
  sanctions determination, or the issuance of a credential -- those are
  permanently out of scope, not merely un-implemented.
  `onboarding.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode and HARD-holds
  it, regardless of confidence or op.

  Note what the advisor does NOT do on `:propose-credential`: it drafts
  the credential's identifying fields but the governor recomputes the
  EVIDENCE from the store and validates against that, so a drafted
  evidence summary here has no authority (see
  `onboarding.governor/evidence-floor-violations`).

  Like every sibling actor's advisor this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM with the same shape."
  (:require [marketplace.seller :as seller]
            [onboarding.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-application
  [_db {:keys [applicant-id patch]}]
  {:op         :log-application
   :applicant-id applicant-id
   :summary    (str applicant-id " の出店申請を記録: " (pr-str (keys patch)))
   :rationale  "出店申請の受付事実の記録のみ。審査結果や本人確認の可否は含まない。"
   :cites      [applicant-id]
   :effect     :propose
   :value      (merge {:applicant-id applicant-id} patch)
   :confidence 0.93})

(defn- propose-evidence-request
  [_db {:keys [applicant-id patch]}]
  {:op         :request-evidence
   :applicant-id applicant-id
   :summary    (str applicant-id " に不足している本人確認書類の提出を依頼: "
                    (pr-str (:check patch "unknown")))
   :rationale  "不足している本人確認資料の提出依頼のみ。提出物の真正性判断は行わない。"
   :cites      [applicant-id]
   :effect     :propose
   :value      (merge {:applicant-id applicant-id} patch)
   :confidence 0.9})

(defn- propose-screening-record
  [_db {:keys [applicant-id patch]}]
  {:op         :record-screening
   :applicant-id applicant-id
   :summary    (str applicant-id " のスクリーニング結果を外部プロバイダから受領し記録")
   :rationale  "外部AMLプロバイダから受領した結果の転記のみ。該当/非該当の判断はプロバイダと人間が行う。"
   :cites      [applicant-id]
   :effect     :propose
   :value      (merge {:applicant-id applicant-id} patch)
   :confidence 0.88})

(defn- propose-identity-concern
  "Surface an observed identity/eligibility concern for HUMAN triage.
  ALWAYS escalates in `onboarding.governor` -- never auto-committed at
  any phase. Deliberately reports the OBSERVATION only, never a
  determination, so the default rationale never trips
  `scope-excluded-terms`."
  [_db {:keys [applicant-id patch]}]
  {:op         :flag-identity-concern
   :applicant-id applicant-id
   :summary    (str applicant-id " の本人性・適格性に関する懸念フラグ: "
                    (pr-str (:concern patch "unknown")))
   :rationale  "観察された懸念事実の報告のみ。本人確認の可否や制裁該当性の判断は行わず、常に人間の確認を要する。"
   :cites      [applicant-id]
   :effect     :propose
   :value      (merge {:applicant-id applicant-id} patch)
   :confidence (or (:confidence patch) 0.82)})

(defn- propose-credential
  "Draft a seller credential for HUMAN approval. This DRAFTS; it never
  issues. The governor re-derives the evidence from the store and
  re-validates, so nothing in the drafted `:seller/evidence` here has
  any authority -- it is filled in only so the reviewing human sees the
  shape they are approving."
  [_db {:keys [applicant-id patch]}]
  (let [a (:applicant patch)
        cred (seller/credential
              {:id (or (:seller-id a) (:seller-id patch))
               :kind (:kind a)
               :legal-name (:legal-name a)
               :country (:country a)
               :issuer (or (:issuer patch) "did:web:marketplace.example")
               :issued-at (:issued-at patch)
               :expires-at (:expires-at patch)
               :status :issued
               :payout-bound? (:payout-bound? a)
               :evidence (when (:ekyc-session a)
                           (seller/evidence-summary
                            {:ekyc-session (:ekyc-session a)
                             :ekyc-evidence (:ekyc-evidence a)
                             :aml-results (:aml-results a)}))})]
    {:op         :propose-credential
     :applicant-id applicant-id
     :summary    (str applicant-id " の出品者資格情報の草案を作成（人間の承認待ち）")
     :rationale  "収集済みの証跡に基づく資格情報の草案提示のみ。発行の可否は人間が判断する。"
     :cites      [applicant-id (str (:seller-id a))]
     :effect     :propose
     :value      {:applicant-id applicant-id :credential cred}
     :confidence (or (:confidence patch) 0.86)}))

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-application       (propose-application db request)
                   :request-evidence      (propose-evidence-request db request)
                   :record-screening      (propose-screening-record db request)
                   :flag-identity-concern (propose-identity-concern db request)
                   :propose-credential    (propose-credential db request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared
    ;; before production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually completed the identity verification and issued the credential")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :applicant-id (:applicant-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn- with-applicant-context
  "Hand the advisor the applicant record it is drafting about -- the
  same place a real LLM would be given context. Only `:propose-credential`
  needs it, and an explicitly supplied `:applicant` in the request wins
  so tests can drive a draft the store does not contain."
  [store {:keys [op applicant-id] :as request}]
  (if (and (= :propose-credential op)
           (nil? (get-in request [:patch :applicant]))
           store)
    (assoc-in request [:patch :applicant] (store/applicant-record store applicant-id))
    request))

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ store request]
      (infer nil (with-applicant-context store request)))))
