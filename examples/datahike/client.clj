(ns datahike.client
  (:require [superv.async :refer [S <??]]
            [kabel.peer :as peer]
            [kabel.middleware.fressian :refer [fressian]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]
            [datahike.api :as d]
            [datahike.helpers :as helpers]))

;; Connect to shared store and register it
(defn connect-to-shared-store! []
  (let [cfg {:store {:backend :file
                     :path "/tmp/datahike-distributed-test"}
             :schema-flexibility :write
             :keep-history? true}]
    (when-not (d/database-exists? cfg)
      (throw (ex-info "Database does not exist. Run server first." {:config cfg})))
    (let [conn (d/connect cfg)
          ;; Register store in registry
          wrapped-atom (:wrapped-atom conn)
          store (:store @wrapped-atom)
          ;; Get the actual store config from the DB, which includes :scope
          db-config (:config @wrapped-atom)
          store-config (:store db-config)]
      (helpers/register-store! store-config store)
      conn)))

(defn start!
  [url client-id]
  (let [;; Use Fressian middleware with custom handlers
        fressian-middleware (fn [peer-config]
                              (fressian (atom helpers/custom-read-handlers)
                                        (atom helpers/datahike-write-handlers)
                                        peer-config))
        client (peer/client-peer S client-id remote-middleware fressian-middleware)]
    (invoke-on-peer client)
    (<?? S (peer/connect S client url))
    ;; Connect to shared store
    (let [conn (connect-to-shared-store!)]
      {:client client
       :conn conn})))
