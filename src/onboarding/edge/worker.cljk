(ns onboarding.edge.worker
  "The seller-onboarding actor's Worker.

  This is the actor that decides who may sell, and it is the reason the
  order actor can stop pretending: `-marketplace-order` used to seed its
  own seller credentials from a demo fixture, because nothing was
  writing real ones. Now this host writes them into the shared
  `marketplace` ref and the order actor simply reads them. That is the
  whole cross-actor wiring — a join, not an API call.

  ## What a credential means here

  `:propose-credential` DRAFTS. It never issues. The governor re-derives
  the evidence from the store and re-validates it, so nothing the
  advisor drafted carries authority, and the phase gate sends the draft
  to a human. A credential appearing in the ref therefore means a person
  approved it, which is exactly what the order actor is entitled to
  assume when it reads one.

  ## Why this endpoint is gated and the buyer surface is not

  Everything here touches applicant records: eKYC sessions, AML results,
  legal names. `-marketplace-listing`'s public surface deliberately
  contains none of that. Reads here are gated for the same reason
  writes are."
  (:require [marketplace.edge :as edge]
            [onboarding.advisor :as advisor]
            [onboarding.governor :as governor]
            [onboarding.phase :as phase]
            [onboarding.store :as store]))

(def ^:private ops
  {:advise      (fn [st req] (advisor/-advise (advisor/mock-advisor) st req))
   :check       governor/check
   :disposition phase/verdict->disposition
   :gate        phase/gate
   :commit!     (fn [st proposal req]
                  (store/commit-record! st {:op (:op proposal)
                                            :applicant-id (:applicant-id req)
                                            :value (:value proposal)
                                            :payload (:value proposal)}))
   :ledger!     store/append-ledger!
   :hold-fact   governor/hold-fact})

(def ^:private default-issuer
  "Who this deployment issues credentials AS.

  A credential is only worth what its issuer is worth —
  `marketplace.seller/sellable?` checks a credential against an expected
  issuer, so a deployment issuing under someone else's DID would be
  minting credentials nobody should honour. Overridable per request
  because a fork is a different issuer."
  "did:web:marketplace.cloud-itonami.example")

(defn- plus-year
  "Default credential validity: one year.

  A default, not a rule. How long an identity check stays good is a
  jurisdiction- and risk-specific operator decision, so the request may
  name any window; what the host must not do is leave the field blank
  and let a credential with no expiry into the ref."
  [iso]
  (let [d (js/Date. iso)]
    (.setUTCFullYear d (inc (.getUTCFullYear d)))
    (.toISOString d)))

(defn- ctx [body]
  {:actor-id "onboarding-edge"
   :phase (get body "phase" 3)
   :now (get body "now" "2026-06-01T00:00:00Z")})

;; ───────────────────────── operations ─────────────────────────

(defn- ->applicant
  "Translate what an eKYC/AML provider reported into the records the
  governor re-derives its answer from.

  This is a TRANSLATION and nothing else. The host does not get to say
  the evidence is sufficient — `onboarding.governor` rebuilds the
  evidence summary from these records and re-runs
  `marketplace.seller/credential-errors`, which re-derives the REQUIRED
  check set from `:seller/kind`. An individual therefore needs
  `:liveness` and a company does not, and no amount of shaping here
  changes that.

  `:ekyc/non-adjudicating` and `:aml/non-adjudicating` are carried
  through because that is what they are: a provider reporting a signal,
  never deciding admission."
  [aid body]
  (let [a (get body "applicant" {})
        kind (keyword (get a "kind" "company"))
        required (mapv keyword (get body "required-checks"
                                    (if (= :individual kind)
                                      ["document-authenticity" "liveness" "sanctions"]
                                      ["document-authenticity" "sanctions"])))
        verified (mapv keyword (get body "verified" []))
        aml (get body "aml")]
    {:applicant-id aid
     :seller-id (get a "seller-id")
     :legal-name (get a "legal-name")
     :kind kind
     :country (get a "country")
     :payout-bound? (boolean (get a "payout-bound?" false))
     :ekyc-session {:ekyc/id aid :ekyc/subject aid :ekyc/status :submitted
                    :ekyc/provider (keyword (get body "provider" "operator"))
                    :ekyc/required-checks (set required)}
     :ekyc-evidence (mapv (fn [c]
                            {:ekyc/id aid :ekyc/check c :ekyc/status :verified
                             :ekyc/provider (keyword (get body "provider" "operator"))
                             :ekyc/evidence-ref (str "ref-" (name c))
                             :ekyc/non-adjudicating true})
                          verified)
     ;; No AML result at all is NOT the same as a clean one. Absent
     ;; means absent, and the governor holds on :aml-not-run.
     :aml-results (if aml
                    [{:aml/route :yabai :aml/level (keyword aml)
                      :aml/non-adjudicating true}]
                    [])}))

(defn- register-applicant
  "Record an applicant. No judgement is made here beyond the governor's:
  applying to sell is not being admitted to sell."
  [client body]
  (let [aid (get body "applicant-id")
        applicant (->applicant aid body)]
    (edge/with-store
      {:client client :wants {:applicant [aid]} :store-fn store/kotobase-store}
      (fn [st]
        (store/with-applicant-records st {aid applicant})
        (edge/outcome aid (edge/run ops st (ctx body)
                                    {:op :log-application :applicant-id aid :ref aid
                                     :patch {:note (get body "note" "applicant registered")}}))))))

(defn- admit
  "Run the credential decision for one applicant.

  Prefetches only that applicant. The evidence floor and the AML hold
  are recomputed from the stored record by the governor — the request
  cannot supply them, which is what stops an applicant from admitting
  themselves by decorating a payload."
  [client body]
  (let [aid (get body "applicant-id")]
    (edge/with-store
      {:client client :wants {:applicant [aid] :credential :all}
       :store-fn store/kotobase-store}
      (fn [st]
        (let [a (store/applicant-record st aid)]
          (if-not a
            {:ref aid :disposition "hold" :violations ["applicant-unknown"]}
            (edge/outcome aid (edge/run ops st (ctx body)
                                        {:op :propose-credential :applicant-id aid :ref aid
                                         :patch {:applicant a
                                                 :seller-id (:seller-id a)
                                                 :issuer (get body "issuer" default-issuer)
                                                 :issued-at (get body "issued-at"
                                                                 (get body "now" "2026-06-01T00:00:00Z"))
                                                 :expires-at (or (get body "expires-at")
                                                                 (plus-year (get body "now" "2026-06-01T00:00:00Z")))
                                                 :confidence (get body "confidence" 0.9)}}))))))))

(defn- approve
  "A named human approves the drafted credential, and only then is one
  issued.

  This mirrors `onboarding.operation`'s `:request-approval` node rather
  than inventing a second path: the governor is re-run against the
  CURRENT stored applicant, so an approval cannot be replayed against a
  record that has since changed, and the approver's name is written into
  the committed payload where the ledger keeps it.

  A blank approver is refused: an approval attributed to nobody is how
  an audit trail becomes decorative."
  [client body]
  (let [aid (get body "applicant-id")
        by (get body "approved-by")]
    (if (or (nil? by) (= "" (str by)))
      (js/Promise.resolve {:ref aid :disposition "hold"
                           :violations ["no-named-approver"]})
      (edge/with-store
        {:client client :wants {:applicant [aid] :credential :all}
         :store-fn store/kotobase-store}
        (fn [st]
          (let [a (store/applicant-record st aid)]
            (if-not a
              {:ref aid :disposition "hold" :violations ["applicant-unknown"]}
              (let [req {:op :propose-credential :applicant-id aid :ref aid
                         :patch {:applicant a
                                 :seller-id (:seller-id a)
                                 :issuer (get body "issuer" default-issuer)
                                 :issued-at (get body "issued-at"
                                                 (get body "now" "2026-06-01T00:00:00Z"))
                                 :expires-at (or (get body "expires-at")
                                                 (plus-year (get body "now" "2026-06-01T00:00:00Z")))
                                 :confidence (get body "confidence" 0.9)}}
                    c (ctx body)
                    proposal (advisor/-advise (advisor/mock-advisor) st req)
                    verdict (governor/check req c proposal st)]
                (if (:hard? verdict)
                  ;; The governor still wins. An approver may release
                  ;; something the phase gate held; they may never
                  ;; release something compliance refused.
                  (do (store/append-ledger! st (governor/hold-fact req c verdict))
                      {:ref aid :disposition "hold"
                       :violations (mapv (comp name :rule) (:violations verdict))})
                  (do (store/commit-record!
                       st {:op :propose-credential :applicant-id aid
                           :value (:value proposal)
                           :payload (assoc (:value proposal) :approved-by by)})
                      (store/append-ledger! st {:t :approval-granted
                                                :op :propose-credential
                                                :applicant-id aid :by by})
                      {:ref aid :disposition "commit" :violations []
                       :approved-by by
                       :seller-id (get-in proposal [:value :credential :seller/id])}))))))))))

;; ───────────────────────── routes ─────────────────────────

(defn- routes [client request env method path _url]
  (cond
    (and (= method "POST") (= path "/applicants"))
    (if-not (edge/authorised? request env)
      (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
      (-> (.json request)
          (.then #(register-applicant client (js->clj %)))
          (.then #(edge/json % 200))))

    (and (= method "POST") (= path "/approve"))
    (if-not (edge/authorised? request env)
      (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
      (-> (.json request)
          (.then #(approve client (js->clj %)))
          (.then #(edge/json % 200))))

    (and (= method "POST") (= path "/admit"))
    (if-not (edge/authorised? request env)
      (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
      (-> (.json request)
          (.then #(admit client (js->clj %)))
          (.then #(edge/json % 200))))

    ;; Applicant records carry eKYC and AML. Gated, always.
    (and (= method "GET") (= path "/applicants"))
    (if-not (edge/authorised? request env)
      (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
      (-> (edge/read-all client :applicant)
          (.then (fn [as] (edge/json {:applicants (mapv :applicant-id as)} 200)))))

    ;; A credential is what the OTHER actors read; it carries an evidence
    ;; SUMMARY, not the evidence. Still gated: who sells where is not
    ;; public information this actor gets to publish.
    (and (= method "GET") (= path "/credentials"))
    (if-not (edge/authorised? request env)
      (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
      (-> (edge/read-all client :credential)
          (.then (fn [cs]
                   (edge/json {:credentials
                               (mapv (fn [c] {:seller-id (:seller/id c)
                                              :status (str (:seller/status c))
                                              :payout-bound? (:seller/payout-bound? c)
                                              :expires-at (:seller/expires-at c)})
                                     cs)}
                              200)))))

    ;; /escalations and /ledger, implemented once in marketplace.edge.
    ;; Every high-stakes move in this actor escalates rather than committing
    ;; on a machine's say-so; without a way to READ those, each of those gates
    ;; is a black hole.
    :else (edge/ledger-routes client request env method path :onboarding)))

(def app
  (clj->js
   {:fetch (fn [request env _ctx]
             (edge/serve "cloud-itonami-marketplace-onboarding" request env routes))}))
