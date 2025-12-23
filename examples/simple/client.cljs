(ns simple.client
  (:require [superv.async :refer [S] :refer-macros [go-try <?]]
            [kabel.peer :as peer]
            [kabel.middleware.transit :refer [transit]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]
            [simple.demo :refer [demo]]))

(def url "ws://localhost:47291")

(def client-id #uuid "c14c628b-b151-4967-ae0a-7c83e5622d0f")

(def server-id #uuid "05a06e85-e7ca-4213-9fe5-04ae511e50a0")

(def client
  (peer/client-peer S client-id
                    remote-middleware
                    transit))

(invoke-on-peer client)

(defn init []
  (prn "ClojureScript client initialized!")
  (peer/connect S client url))

(comment
  (peer/connect S client url)
  (go-try S (prn (<? S (demo server-id client-id)))))
