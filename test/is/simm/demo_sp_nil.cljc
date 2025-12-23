(ns is.simm.demo-sp-nil
  (:require [is.simm.distributed-scope :refer [defn-sp-remote sp-remote]]
            [missionary.core :as m]))

(defn-sp-remote demo-sp-explicit-nil [server-id]
  "Test explicit nil return"
  (sp-remote
   server-id []
   nil))

(defn-sp-remote demo-sp-conditional-nil [server-id condition]
  "Test conditional nil return"
  (sp-remote
   server-id [condition]
   (when condition
     42)))

(defn-sp-remote demo-sp-multi-hop-nil [server-id client-id]
  "Test nil passing through multiple remote calls"
  (sp-remote
   server-id [client-id]
   (let [result (m/? (sp-remote
                      client-id []
                      nil))]
     ;; Pass the nil back
     result)))

(defn-sp-remote demo-sp-mixed-returns [server-id return-nil?]
  "Test that same function can return nil or non-nil values"
  (sp-remote
   server-id [return-nil?]
   (if return-nil?
     nil
     {:success true})))
