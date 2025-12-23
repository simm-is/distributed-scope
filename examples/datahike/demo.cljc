(ns datahike.demo
  (:require [is.simm.distributed-scope :refer [defn-go-remote go-remote] :include-macros true]
            [superv.async :as sa :refer [S]]
            #?(:clj [datahike.api :as d])))

;; Example 1: Server sends DB snapshot to client for querying
(defn-go-remote query-on-client [server-id client-id conn]
  (let [db @conn]
    (go-remote
     server-id [client-id db]
     (let [_ (prn "Server: Sending DB snapshot to client")

         ;; Send DB to client - will be transparently serialized/deserialized
         result (sa/<? S (go-remote
                          client-id [db]
                          (prn "Client: Received DB")
                          ;; The DB is automatically reconstructed by Fressian handlers
                          ;; Query the DB directly
                          (let [result (d/q '[:find ?n ?a
                                             :where
                                             [?e :name ?n]
                                             [?e :age ?a]]
                                           db)]
                            (prn "Client: Query result:" result)
                            result)))]
       (prn "Server: Got result from client:" result)
       result))))

;; Example 2: Bidirectional DB passing - server and client both query different aspects
(defn-go-remote bidirectional-query [server-id client-id conn]
  (let [db @conn
        ;; Query for names on server before going remote
        names (d/q '[:find [?n ...] :where [?e :name ?n]] db)]
    (go-remote
     server-id [client-id db names]
     (let [_ (prn "Server: Starting bidirectional query")
         _ (prn "Server: Found names:" names)

         ;; Send DB to client to query for ages
         ages (sa/<? S (go-remote
                        client-id [db]
                        (let [;; DB is automatically reconstructed
                              ;; Query for ages
                              ages (d/q '[:find [?a ...] :where [?e :age ?a]] db)]
                          (prn "Client: Found ages:" ages)
                          ages)))]
       (prn "Server: Combined results - names:" names "ages:" ages)
       {:names names :ages ages}))))

;; Example 3: Simple demonstration - just pass DB and verify it works
(defn-go-remote simple-demo [server-id client-id conn]
  (let [db @conn
        ;; Query on server first
        server-result (d/q '[:find ?n :where [?e :name ?n]] db)
        ;; Add new data AFTER taking the snapshot
        _ (d/transact conn [{:name "Charlie" :age 35}])
        server-result-after (d/q '[:find ?n :where [?e :name ?n]] @conn)]
    (go-remote
     server-id [client-id db server-result server-result-after]
     (let [_ (prn "=== Simple Datahike Distribution Demo ===")
         _ (prn "Server query result (before tx):" server-result)
         _ (prn "Server query result (after tx):" server-result-after)
         _ (prn "Sending DB snapshot to client...")

         ;; Send DB to client and query there - transparent serialization!
         client-result (sa/<? S (go-remote
                                 client-id [db]
                                 (prn "Client: Received DB")
                                 (d/q '[:find ?n :where [?e :name ?n]] db)))]
       (prn "Client sees snapshot (should match before-tx):" (= server-result client-result))
       (prn "Client does NOT see new data:" (not= server-result-after client-result))
       {:server-result-before server-result
        :server-result-after server-result-after
        :client-result client-result
        :snapshot-correct? (= server-result client-result)}))))
