(ns examples.simple.demo
  (:require [is.simm.distributed-scope :refer [defn-go-remote go-remote] :include-macros true]
            [superv.async :as sa :refer [S]]))

(defn-go-remote demo [server-id client-id]
  (go-remote
   server-id
   (let [_ (prn "starting on server")
     a 42
     b (sa/<? S (go-remote
         client-id
         (prn "continuing on client")
         (repeat (inc a) 42)))]
     (sa/<? S (go-remote
       server-id
       (let [_ (prn "and finishing on server")
         c (+ a 2)]
         [b c]))))))
