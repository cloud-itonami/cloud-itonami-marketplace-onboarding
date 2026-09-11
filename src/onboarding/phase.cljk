(ns onboarding.phase
  "Phase 0->3 staged rollout for the marketplace seller-onboarding actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- application logging allowed, every
                                   write needs human approval.
    Phase 2  assisted-evidence  -- adds evidence requests and screening-
                                   result recording, still approval-gated.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                   `:log-application`/`:request-evidence`/
                                   `:record-screening` may auto-commit.

  `:flag-identity-concern` and `:propose-credential` are deliberately
  ABSENT from every phase's `:auto` set, INCLUDING phase 3 -- a permanent
  structural fact, not a rollout milestone still to come.

  This is the difference between this marketplace and a fully self-serve
  one: a seller can complete their whole application without a human
  touching it, but a human ALWAYS signs the credential that lets them
  trade. ADR-2607264000 records that as a deliberate cost of operating
  in AML/sanctions jurisdictions, not an unfinished feature.

  `onboarding.governor`'s own `always-escalate-ops` enforces the same
  invariant independently -- two layers, not one, agree on this."
  (:require [onboarding.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-identity-concern` and `:propose-credential`
;; are members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"         :writes #{}                    :auto #{}}
   1 {:label "assisted-intake"   :writes #{:log-application}     :auto #{}}
   2 {:label "assisted-evidence" :writes #{:log-application :request-evidence
                                           :record-screening}    :auto #{}}
   3 {:label "supervised-auto"   :writes write-ops
      :auto #{:log-application :request-evidence :record-screening}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a SellerOnboardingGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
