(ns simple.client
  (:require [superv.async :refer [S <??]]
            [kabel.peer :as peer]
            [kabel.middleware.transit :refer [transit]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]))

(defn start!
  [url client-id]
  (let [client (peer/client-peer S client-id remote-middleware transit)]
    (invoke-on-peer client)
    ;; invoke-remote will wait for connection to be ready
    (<?? S (peer/connect S client url))
    client))
