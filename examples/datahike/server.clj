(ns datahike.server
  (:require [superv.async :refer [S <??]]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]
            [kabel.middleware.fressian :refer [fressian]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]
            [datahike.api :as d]
            [datahike.helpers :as helpers]))

(defn create-test-db! []
  (let [cfg {:store {:backend :file
                     :path "/tmp/datahike-distributed-test"}
             :schema-flexibility :write
             :keep-history? true
             :initial-tx [{:db/ident :name
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one}
                          {:db/ident :age
                           :db/valueType :db.type/long
                           :db/cardinality :db.cardinality/one}]}]
    ;; Delete and recreate for clean test
    (when (d/database-exists? cfg)
      (d/delete-database cfg))
    (d/create-database cfg)
    (let [conn (d/connect cfg)
          ;; Register store in registry
          wrapped-atom (:wrapped-atom conn)
          store (:store @wrapped-atom)
          ;; Get the actual store config from the DB, which includes :scope
          db-config (:config @wrapped-atom)
          store-config (:store db-config)]
      (helpers/register-store! store-config store)
      ;; Add test data
      (d/transact conn [{:name "Alice" :age 30}
                       {:name "Bob" :age 25}])
      conn)))

(defn start!
  [url server-id]
  (let [handler (http-kit/create-http-kit-handler! S url server-id)
        ;; Use Fressian middleware with custom handlers
        fressian-middleware (fn [peer-config]
                              (fressian (atom helpers/custom-read-handlers)
                                        (atom helpers/datahike-write-handlers)
                                        peer-config))
        server  (peer/server-peer S handler server-id remote-middleware fressian-middleware)]
    (invoke-on-peer server)
    (<?? S (peer/start server))
    ;; Create test database
    (let [conn (create-test-db!)]
      {:server server
       :handler handler
       :conn conn})))

(defn stop!
  [{:keys [server conn]}]
  (when conn
    (d/release conn))
  (when server
    (<?? S (peer/stop server))))
