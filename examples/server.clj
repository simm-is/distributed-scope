(ns examples.server
  (:require [superv.async :refer [S <??]]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]
            [kabel.middleware.transit :refer [transit]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]))

(defn start-server!
  "Start a demo server peer on a given ws URL and peer id. Returns {:server server :handler handler}."
  [url server-id]
  (let [handler (http-kit/create-http-kit-handler! S url server-id)
        server  (peer/server-peer S handler server-id remote-middleware transit)]
    (invoke-on-peer server)
    (<?? S (peer/start server))
    {:server server :handler handler}))

(defn stop-server!
  [{:keys [server]}]
  (when server
    (<?? S (peer/stop server))))
