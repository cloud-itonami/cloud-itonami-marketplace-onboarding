(ns onboarding.governor
  "SellerOnboardingGovernor -- the independent compliance layer that
  earns the SellerOnboardingAdvisor the right to commit.

  The advisor has no notion of whether an applicant actually exists,
  whether the evidence behind a drafted credential meets the PROTOCOL's
  floor (as opposed to whatever floor the advisor believed applied),
  whether an AML screening actually came back clear, or whether its own
  `:effect` secretly claims a direct actuation instead of a mere
  proposal. So this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  ## Why the gate here is NOT 'the seller is verified'

  Every sibling actor in this fleet hard-blocks on an unverified
  counterparty. This actor cannot: handling not-yet-verified parties is
  its entire job. Inverting that gate naively would make the actor
  useless. So the gate is displaced to the ONE op that has lasting
  consequence -- `:propose-credential`, the draft that (once a human
  approves it) becomes a seller's admission to trade. Intake ops may
  touch an unverified applicant freely; issuing an identity for one
  cannot.

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Applicant unknown       -- the target applicant must exist in the
                                  store. Never trusts the proposal's own
                                  `:applicant-id` claim without a lookup
                                  -- the 'ground truth, not self-report'
                                  discipline every sibling governor uses.
    2. Evidence floor          -- for `:propose-credential` ONLY, the
                                  drafted credential is re-validated with
                                  `marketplace.seller/credential-errors`,
                                  which RE-DERIVES the required eKYC
                                  check set from the applicant's kind
                                  rather than believing any
                                  `complete?` flag. An AML `:hold`, an
                                  unrun screening, or a missing required
                                  check is a HARD block. Critically the
                                  evidence is recomputed FROM THE STORE's
                                  own session/evidence/screening records,
                                  not from the proposal's drafted
                                  credential -- an advisor cannot widen
                                  its own evidence base by asserting one.
    3. Effect not :propose     -- every proposal's `:effect` MUST be
                                  `:propose`. Any other value is, by
                                  construction, a claim to directly
                                  actuate outside governance.
    4. Scope exclusion         -- ANY proposal whose op, summary,
                                  rationale, cites or draft value claims
                                  to have FINALIZED an identity
                                  determination, a sanctions
                                  determination, or the issuance of a
                                  credential is a HARD, PERMANENT block.
                                  This actor coordinates onboarding; it
                                  never concludes it. An op outside the
                                  closed allowlist is the same failure
                                  mode and folds into this check.

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-identity-concern` or `:propose-credential` --
      ALWAYS escalates, regardless of confidence. Issuing a seller
      identity is the highest-stakes act in this actor's charter and
      may NEVER become auto-commit-eligible; `onboarding.phase` keeps
      both out of every phase's `:auto` set independently -- two layers,
      not one."
  (:require [clojure.string :as str]
            [marketplace.seller :as seller]
            [onboarding.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: no op that finalizes an
  identity or sanctions determination, or that issues a credential
  directly, is EVER a member -- such an op would be a permanent scope
  violation, not merely un-implemented. `:propose-credential` DRAFTS a
  credential for a human to approve; it does not issue one."
  #{:log-application :request-evidence :record-screening
    :flag-identity-concern :propose-credential})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-identity-concern :propose-credential})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming an
  authority this actor permanently lacks.

  CRITICAL: every term is phrased as the FINALIZATION action ('completed
  the identity verification'), never a bare noun like 'identity
  verification' or 'sanctions' -- a bare noun would match inside this
  actor's own legitimate intake proposals (whose whole job is to talk
  about identity verification and sanctions screening) and self-block
  the happy path. See
  `onboarding.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["completed the identity verification" "completes the identity verification"
   "finalized the identity verification" "finalize the identity verification"
   "concluded the identity verification" "concludes the identity verification"
   "determined the applicant is who they claim" "determined the applicant's identity"
   "confirmed the applicant is sanction-free" "confirms the applicant is sanction-free"
   "cleared the applicant of sanctions" "clears the applicant of sanctions"
   "finalized the sanctions determination" "finalize the sanctions determination"
   "issued the credential" "issues the credential" "issuing the credential"
   "granted the seller credential" "grants the seller credential"
   "activated the seller account" "activates the seller account"
   "approved the application" "approves the application" "approving the application"
   "本人確認を完了した" "本人確認を完了とした" "本人確認を確定した"
   "制裁該当なしと確定した" "制裁スクリーニングを完了と判断した"
   "資格情報を発行した" "クレデンシャルを発行した" "出品者資格を付与した"
   "出店申請を承認した" "アカウントを有効化した"])

;; ----------------------------- checks -----------------------------

(defn- applicant-unknown-violations
  "The target applicant must exist in the store. Never trust the
  proposal's own `:applicant-id` without a lookup."
  [{:keys [applicant-id]} st]
  (when-not (store/applicant-record st applicant-id)
    [{:rule :applicant-unknown
      :detail (str (or applicant-id "(applicant-id missing)")
                   " は登録されていない出店申請者 -- いかなる提案も進められない")}]))

(defn- evidence-floor-violations
  "For `:propose-credential` ONLY: rebuild the evidence summary FROM THE
  STORE and re-validate the drafted credential against
  `marketplace.seller/credential-errors`.

  The summary is recomputed from the applicant's own eKYC session,
  evidence log and AML results -- deliberately NOT taken from the
  proposal's drafted credential. An advisor that attached a flattering
  evidence summary to its own draft cannot widen its evidence base that
  way; the governor always re-derives from ground truth.

  `credential-errors` itself re-derives the REQUIRED check set from the
  credential's `:seller/kind`, so an advisor cannot lower the floor by
  claiming fewer checks were needed either."
  [proposal st]
  (when (= :propose-credential (:op proposal))
    (let [applicant (store/applicant-record st (:applicant-id proposal))
          draft     (get-in proposal [:value :credential])]
      (cond
        (not (map? draft))
        [{:rule :credential-draft-missing
          :detail ":propose-credential の :value に :credential がない"}]

        :else
        (let [truth (seller/evidence-summary
                     {:ekyc-session  (:ekyc-session applicant)
                      :ekyc-evidence (:ekyc-evidence applicant)
                      :aml-results   (:aml-results applicant)})
              ;; Re-key the draft onto the store-derived evidence before
              ;; validating -- validate the CLAIM against the TRUTH.
              checked (assoc draft :seller/evidence truth)
              errs (seller/credential-errors checked)]
          (when (seq errs)
            (mapv (fn [e]
                    {:rule (:seller.error/code e)
                     :detail (str "資格情報の発行要件を満たさない: "
                                  (or (:seller.error/detail e)
                                      (name (:seller.error/code e))))})
                  errs)))))))

(defn- effect-not-propose-violations
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field into one lower-cased blob the
  scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block, evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "本人確認の完了・制裁判定の確定・資格情報の発行など確定行為に触れる提案は永久に禁止"}])))

(defn check
  "Censors a SellerOnboardingAdvisor proposal. Returns
  {:ok? bool :violations [..] :confidence c :escalate? bool
   :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [applicant-id (or (:applicant-id proposal) (:applicant-id request))
        hard (into []
                   (concat (applicant-unknown-violations {:applicant-id applicant-id} store)
                           (evidence-floor-violations (assoc proposal :applicant-id applicant-id) store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :applicant-id (:applicant-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
