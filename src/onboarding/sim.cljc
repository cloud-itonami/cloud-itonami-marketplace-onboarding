(ns onboarding.sim
  "Offline demo: drive one clean intake, one HARD evidence-floor hold, and
  one credential proposal that pauses for a human, through the REAL
  compiled actor graph. `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [onboarding.operation :as operation]
            [onboarding.store :as store]))

(def ^:private ctx {:actor-id "onboarding-demo" :phase 3})

(defn- run-req! [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn -main [& _]
  (let [s (store/seed-db)
        actor (operation/build s)]

    (println "\n=== 1. 出店申請の記録（app-1、自動コミット）===")
    (let [r (run-req! actor "sim-1" {:op :log-application :applicant-id "app-1"
                                 :patch {:channel "web"}})]
      (println "  status     :" (:status r))
      (println "  disposition:" (:disposition (:state r))))

    (println "\n=== 2. 証跡不足での資格情報草案（app-2、HARD hold）===")
    (let [r (run-req! actor "sim-2" {:op :propose-credential :applicant-id "app-2"
                                 :patch {:issued-at "2026-01-01T00:00:00Z"
                                         :expires-at "2027-01-01T00:00:00Z"}})]
      (println "  status     :" (:status r) "(人間に承認を求めることすらしない)")
      (println "  disposition:" (:disposition (:state r)))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 3. 資格情報の草案（app-1、人間の承認待ちで停止）===")
    (let [held (run-req! actor "sim-3" {:op :propose-credential :applicant-id "app-1"
                                    :patch {:issued-at "2026-01-01T00:00:00Z"
                                            :expires-at "2027-01-01T00:00:00Z"}})]
      (println "  status     :" (:status held))
      (println "  frontier   :" (:frontier held))
      (println "  発行済み資格情報:" (count (store/all-credential-records s)) "件（承認前）")
      (let [ok (g/run* actor {:approval {:status :approved :by "compliance-01"}}
                       {:thread-id "sim-3" :resume? true})]
        (println "  --- 人間 compliance-01 が承認 ---")
        (println "  status     :" (:status ok))
        (println "  発行済み資格情報:" (count (store/all-credential-records s)) "件")
        (println "  seller-id  :" (:seller/id (first (store/all-credential-records s))))))

    (println "\n=== 監査台帳 ===")
    (doseq [f (store/ledger s)]
      (println " " (:t f) (:op f) (:applicant-id f)))))
