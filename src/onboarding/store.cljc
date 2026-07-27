(ns onboarding.store
  "SSoT for the marketplace seller-onboarding actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every `cloud-itonami-*` actor in this fleet uses.

  This actor's primary gate is an APPLICANT record: a party that has
  actually applied to sell, with an eKYC session and (once run) an AML
  screening attached. Unlike ISIC 4791 -- whose gate is an
  already-verified merchant account -- this actor exists precisely to
  handle parties that are NOT yet verified, so 'unverified' cannot be
  the hard block here. What IS hard is proposing to ISSUE a credential
  for an applicant whose evidence does not meet the protocol floor, or
  for an applicant who does not exist at all.

  Directories are keyed by STRING ids (never keywords) -- consistent
  keying from the start, avoiding the silent-miss bug that has plagued
  earlier sibling actors.

  The ledger stays append-only: which applicant a proposal targeted,
  which operation, on what basis, committed/held/escalated and approved
  by whom is always a query over an immutable log."
  (:require [marketplace.persist :as persist]))

(defprotocol Store
  (applicant-record [s applicant-id]
    "Seller-applicant record, or nil.
     {:applicant-id .. :legal-name .. :kind :individual|:company
      :country \"JPN\" :ekyc-session {..} :ekyc-evidence [..]
      :aml-results [..] :payout-bound? bool}")
  (all-applicant-records [s])
  (credential-record [s seller-id] "An already-issued credential, or nil.")
  (all-credential-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (onboarding-log [s] "the append-only committed-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-applicant-records [s applicants])
  (durable? [s] "False for the test-only memory backend.")
  (with-credential-records [s credentials]))

;; ----------------------------- demo data -----------------------------

(defn- session [id required]
  {:ekyc/id id :ekyc/subject id :ekyc/status :submitted
   :ekyc/provider :demo :ekyc/required-checks (set required)})

(defn- ev [id check status]
  {:ekyc/id id :ekyc/check check :ekyc/status status
   :ekyc/provider :demo :ekyc/evidence-ref (str "ref-" (name check))
   :ekyc/non-adjudicating true})

(defn- aml [level]
  [{:aml/route :yabai :aml/level level :aml/non-adjudicating true}])

(defn demo-data
  "A self-contained applicant directory covering the happy path and each
  of the governor's own hard checks, so the actor + tests run offline.

    app-1  company, full evidence, AML clear      -> credential proposable
    app-2  company, MISSING :sanctions evidence   -> evidence floor blocks
    app-3  individual, full evidence, AML deny    -> AML hold blocks
    app-4  individual, evidence still incomplete  -> intake ops fine,
                                                      credential blocked"
  []
  {:applicants
   {"app-1" {:applicant-id "app-1" :seller-id "merchant.riverside"
             :legal-name "Riverside Trading K.K." :kind :company :country "JPN"
             :ekyc-session (session "app-1" [:document-authenticity :sanctions])
             :ekyc-evidence [(ev "app-1" :document-authenticity :verified)
                             (ev "app-1" :sanctions :verified)]
             :aml-results (aml :clear)
             :payout-bound? true}
    "app-2" {:applicant-id "app-2" :seller-id "merchant.sunset"
             :legal-name "Sunset Direct GmbH" :kind :company :country "DEU"
             :ekyc-session (session "app-2" [:document-authenticity :sanctions])
             :ekyc-evidence [(ev "app-2" :document-authenticity :verified)]
             :aml-results (aml :clear)
             :payout-bound? true}
    "app-3" {:applicant-id "app-3" :seller-id "merchant.downtown"
             :legal-name "Downtown Popup" :kind :individual :country "USA"
             :ekyc-session (session "app-3" [:document-authenticity :liveness :sanctions])
             :ekyc-evidence [(ev "app-3" :document-authenticity :verified)
                             (ev "app-3" :liveness :verified)
                             (ev "app-3" :sanctions :verified)]
             :aml-results (aml :deny)
             :payout-bound? true}
    "app-4" {:applicant-id "app-4" :seller-id "merchant.newcomer"
             :legal-name "Newcomer Studio" :kind :individual :country "JPN"
             :ekyc-session (session "app-4" [:document-authenticity :liveness :sanctions])
             :ekyc-evidence [(ev "app-4" :document-authenticity :verified)]
             :aml-results []
             :payout-bound? false}}
   :credentials {}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (applicant-record [_ id] (get-in @a [:applicants id]))
  (all-applicant-records [_] (sort-by :applicant-id (vals (:applicants @a))))
  (credential-record [_ id] (get-in @a [:credentials id]))
  (all-credential-records [_] (sort-by :seller/id (vals (:credentials @a))))
  (durable? [_] false)
  (ledger [_] (:ledger @a))
  (onboarding-log [_] (:onboarding-log @a))
  (commit-record! [_ record]
    (swap! a update :onboarding-log conj record)
    ;; An approved :propose-credential commit is the only thing that ever
    ;; writes the credential directory -- issuing an identity is never a
    ;; side effect of a lesser op.
    (when-let [c (and (= :propose-credential (:op record))
                      (get-in record [:value :credential]))]
      (swap! a assoc-in [:credentials (:seller/id c)] c))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-applicant-records [s applicants]
    (when (seq applicants) (swap! a assoc :applicants applicants)) s)
  (with-credential-records [s credentials]
    (when (seq credentials) (swap! a assoc :credentials credentials)) s))

(defn seed-db
  "A MemStore seeded with the demo applicant directory."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :onboarding-log []))))

(defn mem-store
  "A MemStore seeded with explicit maps -- the primary test/dev entry."
  ([applicants] (mem-store applicants {}))
  ([applicants credentials]
   (->MemStore (atom {:applicants (or applicants {})
                      :credentials (or credentials {})
                      :ledger [] :onboarding-log []}))))

;; ----------------------------- durable store -----------------------------

(defrecord KotobaseStore [st seed]
  Store
  (applicant-record [_ id] (persist/get-doc (persist/ctx st :applicant :applicant-id) id))
  (all-applicant-records [_] (persist/all-docs (persist/ctx st :applicant :applicant-id)))
  (credential-record [_ id] (persist/get-doc (persist/ctx st :credential :seller/id) id))
  (all-credential-records [_] (persist/all-docs (persist/ctx st :credential :seller/id)))
  (durable? [_] (not (:persist/memory? st)))
  (ledger [_] (persist/read-events (persist/stream-ctx st :ledger)))
  (onboarding-log [_] (persist/read-events (persist/stream-ctx st :onboarding-log)))
  (commit-record! [_ record]
    (persist/append-event! (persist/stream-ctx st :onboarding-log) seed record)
    ;; Same invariant as MemStore: issuing an identity is never a side
    ;; effect of a lesser op, so only an approved :propose-credential
    ;; writes the credential directory.
    (when-let [c (and (= :propose-credential (:op record))
                      (get-in record [:value :credential]))]
      (persist/put-doc! (persist/ctx st :credential :seller/id) c))
    record)
  (append-ledger! [_ fact]
    (persist/append-event! (persist/stream-ctx st :ledger) seed fact))
  (with-applicant-records [this applicants]
    (doseq [a (vals applicants)]
      (persist/put-doc! (persist/ctx st :applicant :applicant-id) a))
    this)
  (with-credential-records [this credentials]
    (doseq [c (vals credentials)]
      (persist/put-doc! (persist/ctx st :credential :seller/id) c))
    this))

(defn kotobase-store
  "A durable store over a HOST-INJECTED database API.

  `marketplace.persist/store` throws when `db-api` is missing or
  partial, per the policy's
  `:policy/fail-closed-without-host-injection` — this actor cannot come
  up durable-looking but writing to nothing.

  `seq-fn` is the host's because a count would be a read-modify-write
  two concurrent appends collide on. `marketplace.edge/ordinal-fn`
  supplies one that survives concurrent isolates."
  [{:keys [db-api seq-fn]}]
  (->KotobaseStore (persist/store {:db-api db-api :actor "onboarding"})
                   (or seq-fn (let [n (atom 0)] #(swap! n inc)))))
