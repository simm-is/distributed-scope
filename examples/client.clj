(ns examples.client
  (:require [superv.async :refer [S <??]]
            [kabel.peer :as peer]
            [kabel.middleware.transit :refer [transit]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]))

(defn start-client!
  "Start a demo client peer and connect to the server URL. Returns the client peer."
  [url client-id]
  (let [client (peer/client-peer S client-id remote-middleware transit)]
    (invoke-on-peer client)
    (<?? S (peer/connect S client url))
    client))
