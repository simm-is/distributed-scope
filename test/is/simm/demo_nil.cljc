(ns is.simm.demo-nil
  (:require [is.simm.distributed-scope :refer [defn-go-remote go-remote]]
            [superv.async :refer [<? S]]))

(defn-go-remote demo-explicit-nil [server-id]
  "Test explicit nil return"
  (go-remote
   server-id []
   nil))

(defn-go-remote demo-conditional-nil [server-id condition]
  "Test conditional nil return"
  (go-remote
   server-id [condition]
   (when condition
     42)))

(defn-go-remote demo-multi-hop-nil [server-id client-id]
  "Test nil passing through multiple remote calls"
  (go-remote
   server-id [client-id]
   (let [result (<? S (go-remote
                       client-id []
                       nil))]
     ;; Pass the nil back
     result)))

(defn-go-remote demo-mixed-returns [server-id return-nil?]
  "Test that same function can return nil or non-nil values"
  (go-remote
   server-id [return-nil?]
   (if return-nil?
     nil
     {:success true})))
