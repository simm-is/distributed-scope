(ns simple.demo
  (:require [is.simm.distributed-scope :refer [defn-go-remote go-remote] :include-macros true]
            [superv.async :as sa :refer [S]]
            [clojure.core.async :refer [alts!] :include-macros true]))

(defn-go-remote demo [server-id client-id]
  (go-remote
   server-id [client-id server-id]
   (let [a 42
         b (sa/<? S (go-remote
                     client-id [a]
                     (repeat (inc a) 42)))]
     (sa/<? S (go-remote
               server-id [a b]
               (let [c (+ a 2)]
                 [b c]))))))
