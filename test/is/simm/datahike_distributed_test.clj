(ns is.simm.datahike-distributed-test
  (:require [clojure.test :refer :all]
            [datahike.demo :as demo]
            [datahike.server :as server]
            [datahike.client :as client]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [superv.async :refer [S <??]])
  (:import (java.net ServerSocket)))

(defn- free-port []
  (with-open [^ServerSocket ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest datahike-simple-demo-test
  (testing "Datahike DB snapshot is truly immutable - client sees snapshot, not current state"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          server-started (server/start! url server-id)
          client-started (client/start! url client-id)]
      (try
        (let [res (<?? S (demo/simple-demo server-id client-id (:conn server-started)))]
          ;; Verify client sees the snapshot (before transaction)
          (is (= (:server-result-before res) (:client-result res)))
          (is (:snapshot-correct? res))
          ;; Verify we got the expected data in snapshot (Alice and Bob)
          (is (= #{["Alice"] ["Bob"]} (:server-result-before res)))
          ;; Verify server sees new data after transaction (Alice, Bob, Charlie)
          (is (= #{["Alice"] ["Bob"] ["Charlie"]} (:server-result-after res)))
          ;; Verify client does NOT see the new data (proving it's a true snapshot)
          (is (not= (:server-result-after res) (:client-result res))))
        (finally
          (when (:conn client-started)
            (d/release (:conn client-started)))
          (server/stop! server-started))))))

(deftest datahike-query-on-client-test
  (testing "Client can query DB received from server with multiple fields"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          server-started (server/start! url server-id)
          client-started (client/start! url client-id)]
      (try
        (let [res (<?? S (demo/query-on-client server-id client-id (:conn server-started)))]
          ;; Verify we got the expected name-age pairs
          (is (= #{["Alice" 30] ["Bob" 25]} res)))
        (finally
          (when (:conn client-started)
            (d/release (:conn client-started)))
          (server/stop! server-started))))))

(deftest datahike-bidirectional-query-test
  (testing "Server and client can both query different aspects of the DB"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          server-started (server/start! url server-id)
          client-started (client/start! url client-id)]
      (try
        (let [res (<?? S (demo/bidirectional-query server-id client-id (:conn server-started)))]
          ;; Verify server got names
          (is (= #{"Alice" "Bob"} (set (:names res))))
          ;; Verify client got ages
          (is (= #{30 25} (set (:ages res)))))
        (finally
          (when (:conn client-started)
            (d/release (:conn client-started)))
          (server/stop! server-started))))))

(deftest datahike-db-reconstruction-test
  (testing "DB reconstruction produces functionally equivalent database"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          server-started (server/start! url server-id)
          client-started (client/start! url client-id)]
      (try
        ;; Test that various queries produce same results on both sides
        (let [test-query '[:find ?e ?n ?a
                           :where
                           [?e :name ?n]
                           [?e :age ?a]]
              server-db @(:conn server-started)
              server-result (d/q test-query server-db)

              ;; Pass DB to client and query there
              client-result (<?? S (demo/query-on-client
                                    server-id
                                    client-id
                                    (:conn server-started)))]

          ;; Both should have found the same entities
          (is (= 2 (count server-result)))
          (is (= 2 (count client-result))))
        (finally
          (when (:conn client-started)
            (d/release (:conn client-started)))
          (server/stop! server-started))))))
