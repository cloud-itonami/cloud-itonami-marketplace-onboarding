(ns onboarding.durable-store-test
  "The durable store must behave exactly as the memory one does -- the
  contract test that keeps a backend swap from being a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.persist :as persist]
            [onboarding.store :as store]))

(defn- durable []
  (store/kotobase-store {:db-api (persist/mem-db-api)}))

(deftest a-missing-host-database-fails-loudly
  (testing ":policy/fail-closed-without-host-injection"
    (is (thrown? clojure.lang.ExceptionInfo (store/kotobase-store {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/kotobase-store {:db-api {:transact! identity}})))))

(deftest the-memory-backend-reports-itself-as-not-durable
  (is (false? (store/durable? (durable))))
  (is (false? (store/durable? (store/seed-db)))))

(deftest applicants-round-trip
  (let [st (durable)
        d (store/demo-data)]
    (store/with-applicant-records st (:applicants d))
    (is (= 4 (count (store/all-applicant-records st))))
    (is (= "merchant.riverside" (:seller-id (store/applicant-record st "app-1"))))
    (is (= :company (:kind (store/applicant-record st "app-1")))
        "the nested eKYC session and evidence survive the blob round trip")
    (is (= 2 (count (:ekyc-evidence (store/applicant-record st "app-1")))))))

(deftest only-an-approved-credential-proposal-writes-the-directory
  (testing "issuing an identity is never a side effect of a lesser op"
    (let [st (durable)
          cred {:seller/id "merchant.riverside" :seller/status :issued}]
      (store/commit-record! st {:op :log-application :value {:credential cred}})
      (is (nil? (store/credential-record st "merchant.riverside")))
      (store/commit-record! st {:op :propose-credential :value {:credential cred}})
      (is (= :issued (:seller/status (store/credential-record st "merchant.riverside")))))))

(deftest the-ledger-is-append-only-and-ordered
  (let [st (durable)]
    (is (empty? (store/ledger st)))
    (store/append-ledger! st {:t :governor-hold})
    (store/append-ledger! st {:t :committed})
    (is (= [:governor-hold :committed] (mapv :t (store/ledger st))))))

(deftest two-hosts-counting-from-one-would-lose-ledger-facts
  (testing "why the Worker passes marketplace.edge/ordinal-fn rather than
            letting the store default to a counter"
    (let [api (persist/mem-db-api)
          a (store/kotobase-store {:db-api api})
          b (store/kotobase-store {:db-api api})]
      (store/append-ledger! a {:t :from-a})
      (store/append-ledger! b {:t :from-b})
      (is (= 1 (count (store/ledger a))) "one fact where two were appended")
      (let [uniq (fn [tag] (let [n (atom 0)]
                             #(str "000000000001700-" (swap! n inc) "-" tag)))
            api2 (persist/mem-db-api)
            c (store/kotobase-store {:db-api api2 :seq-fn (uniq "ccc")})
            d (store/kotobase-store {:db-api api2 :seq-fn (uniq "ddd")})]
        (store/append-ledger! c {:t :from-c})
        (store/append-ledger! d {:t :from-d})
        (is (= 2 (count (store/ledger c))) "nothing dropped once ordinals are unique")))))
