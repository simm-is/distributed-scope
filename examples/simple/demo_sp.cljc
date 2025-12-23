(ns simple.demo-sp
  (:require [is.simm.distributed-scope :refer [defn-sp-remote sp-remote] :include-macros true]
            [missionary.core :as m]))

(defn-sp-remote demo-sp [server-id client-id]
  (sp-remote
   server-id [client-id server-id]
   (let [_ (prn "sp: starting on server")
         a 42
         b (m/? (sp-remote
                 client-id [a]
                 (prn "sp: continuing on client")
                 (inc a)))]
     (m/? (sp-remote
           server-id [a b]
           (let [_ (prn "sp: finishing on server")
                 c (+ a 2)]
             [b c]))))))
