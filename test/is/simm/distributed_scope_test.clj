(ns is.simm.distributed-scope-test
  (:require [clojure.test :refer :all]
            [simple.demo :as demo]
            [simple.demo-sp :as demo-sp]
            [is.simm.demo-nil :as demo-nil]
            [is.simm.demo-sp-nil :as demo-sp-nil]
            [simple.server :as server]
            [simple.client :as client]
            [is.simm.distributed-scope :as ds]
            [kabel.peer :as peer]
            [hasch.core :refer [uuid]]
            [superv.async :refer [S <?? simple-supervisor]]
            [clojure.core.async :refer [put! close! promise-chan timeout alt!!]]
            [missionary.core :as m])
  (:import (java.net ServerSocket)))

(defn- abort! [supervisor]
  (doseq [abort-ch (:aborts supervisor)]
    (put! abort-ch :abort)
    (close! abort-ch)))

(defn- await-predicate [pred]
  (loop [attempts 100]
    (cond
      (pred) true
      (zero? attempts) false
      :else (do (Thread/sleep 10)
                (recur (dec attempts))))))

(deftest invocation-session-lifecycle-test
  (let [errors (atom [])
        supervisor (simple-supervisor :error-fn #(swap! errors conj %))
        peer-id (uuid :session-peer)
        client (peer/client-peer supervisor peer-id identity identity)
        run (ds/invoke-on-peer client {:supervisor supervisor})]
    (is (contains? @ds/local-peers peer-id))
    (abort! supervisor)
    (is (not= :timeout (alt!! (timeout 1000) :timeout run ([v] v))))
    (is (await-predicate #(not (contains? @ds/local-peers peer-id))))
    (Thread/sleep 50)
    (is (empty? @errors))))

(deftest invocation-session-replacement-test
  (let [old-errors (atom [])
        new-errors (atom [])
        old-supervisor (simple-supervisor :error-fn #(swap! old-errors conj %))
        new-supervisor (simple-supervisor :error-fn #(swap! new-errors conj %))
        peer-id (uuid :replacement-peer)
        client (peer/client-peer old-supervisor peer-id identity identity)
        old-run (ds/invoke-on-peer client {:supervisor old-supervisor})
        new-run (ds/invoke-on-peer client {:supervisor new-supervisor})
        calls (atom 0)
        fn-name 'test/invocation-owner
        reply-ch (promise-chan)
        [bus-in _] (get-in @client [:volatile :chans])]
    (ds/register-remote-fn!
     fn-name
     (fn [_]
       (swap! calls inc)
       (doto (promise-chan) (put! :ok))))
    (try
      (put! bus-in {:type ::ds/invoke
                    :scope peer-id
                    :fn-name fn-name
                    :arg-map {}
                    :request-id (random-uuid)
                    :request-scope (random-uuid)
                    ::ds/reply-out reply-ch})
      (is (= :ok (:result (alt!! (timeout 1000) :timeout reply-ch ([v] v)))))
      (is (= 1 @calls))
      (abort! old-supervisor)
      (is (not= :timeout (alt!! (timeout 1000) :timeout old-run ([v] v))))
      (is (contains? @ds/local-peers peer-id))
      (abort! new-supervisor)
      (is (not= :timeout (alt!! (timeout 1000) :timeout new-run ([v] v))))
      (is (await-predicate #(not (contains? @ds/local-peers peer-id))))
      (Thread/sleep 50)
      (is (empty? @old-errors))
      (is (empty? @new-errors))
      (finally
        (ds/unregister-remote-fn! fn-name)
        (abort! old-supervisor)
        (abort! new-supervisor)))))

(defn- free-port []
  (with-open [^ServerSocket ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest demo-roundtrip
  (testing "defn-go-remote cross-peer roundtrip works"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          started (server/start! url server-id)
          _client (client/start! url client-id)]
      (try
        (let [res (<?? S (demo/demo server-id client-id))]
          ;; demo returns [b c], where b is a (repeat (inc 42) 42) and c is 44
          (is (= 44 (second res)))
          (is (every? #(= 42 %) (take 3 (first res)))))
        (finally
          (server/stop! started))))))

(deftest demo-sp-roundtrip
  (testing "defn-sp-remote missionary cross-peer roundtrip works"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          started (server/start! url server-id)
          _client (client/start! url client-id)]
      (try
        (let [res (m/? (demo-sp/demo-sp server-id client-id))]
          ;; demo-sp returns [b c], where b is 43 and c is 44
          (is (= [43 44] res)))
        (finally
          (server/stop! started))))))

(deftest demo-sp-nil-handling
  (testing "defn-sp-remote handles nil returns correctly"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          started (server/start! url server-id)
          _client (client/start! url client-id)]
      (try
        (testing "explicit nil return"
          (let [res (m/? (demo-sp-nil/demo-sp-explicit-nil server-id))]
            (is (nil? res))))

        (testing "conditional nil return - true branch"
          (let [res (m/? (demo-sp-nil/demo-sp-conditional-nil server-id true))]
            (is (= 42 res))))

        (testing "conditional nil return - false branch"
          (let [res (m/? (demo-sp-nil/demo-sp-conditional-nil server-id false))]
            (is (nil? res))))

        (testing "multi-hop nil passing"
          (let [res (m/? (demo-sp-nil/demo-sp-multi-hop-nil server-id client-id))]
            (is (nil? res))))

        (testing "mixed returns - nil case"
          (let [res (m/? (demo-sp-nil/demo-sp-mixed-returns server-id true))]
            (is (nil? res))))

        (testing "mixed returns - non-nil case"
          (let [res (m/? (demo-sp-nil/demo-sp-mixed-returns server-id false))]
            (is (= {:success true} res))))
        (finally
          (server/stop! started))))))

(deftest demo-go-nil-handling
  (testing "defn-go-remote handles nil returns correctly"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :server)
          client-id (uuid :client)
          started (server/start! url server-id)
          _client (client/start! url client-id)]
      (try
        (testing "explicit nil return"
          (let [res (<?? S (demo-nil/demo-explicit-nil server-id))]
            (is (nil? res))))

        (testing "conditional nil return - true branch"
          (let [res (<?? S (demo-nil/demo-conditional-nil server-id true))]
            (is (= 42 res))))

        (testing "conditional nil return - false branch"
          (let [res (<?? S (demo-nil/demo-conditional-nil server-id false))]
            (is (nil? res))))

        (testing "multi-hop nil passing"
          (let [res (<?? S (demo-nil/demo-multi-hop-nil server-id client-id))]
            (is (nil? res))))

        (testing "mixed returns - nil case"
          (let [res (<?? S (demo-nil/demo-mixed-returns server-id true))]
            (is (nil? res))))

        (testing "mixed returns - non-nil case"
          (let [res (<?? S (demo-nil/demo-mixed-returns server-id false))]
            (is (= {:success true} res))))
        (finally
          (server/stop! started))))))

;; Principal binding tests

(deftest principal-helpers-test
  (testing "current-principal returns nil by default"
    (is (nil? (ds/current-principal))))

  (testing "require-principal throws when not authenticated"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Authentication required"
                          (ds/require-principal))))

  (testing "binding *principal* makes it available"
    (binding [ds/*principal* {:sub "test-user" :email "test@example.com"}]
      (is (= "test@example.com" (:email (ds/current-principal))))
      (is (= "test-user" (:sub (ds/require-principal))))))

  (testing "*principal* is nil outside binding"
    (is (nil? (ds/current-principal)))))
