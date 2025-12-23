(ns is.simm.browser-integration-test
  "Browser integration tests for distributed-scope."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.core.async :refer [<! timeout alts!] :refer-macros [go]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer
                                               connect-distributed-scope
                                               connections]]
            [simple.demo :refer [demo]]
            [simple.demo-sp :refer [demo-sp]]
            [kabel.peer :as peer]
            [kabel.middleware.transit :refer [transit]]
            [hasch.core :refer [uuid]]
            [superv.async :refer [S] :refer-macros [go-try <?]]
            [missionary.core :as m]))

;; =============================================================================
;; Configuration - must match browser_test_server.clj
;; =============================================================================

(def test-server-url "ws://localhost:47300")
(def test-server-id #uuid "d5000000-0000-0000-0000-000000000001")
(def test-client-id (uuid :browser-test-client))

;; =============================================================================
;; Client State
;; =============================================================================

(defonce client-atom (atom nil))
(defonce connected? (atom false))

;; =============================================================================
;; Test Setup
;; =============================================================================

(defn setup-client! []
  (when-not @client-atom
    (js/console.log "[TEST] Creating client peer...")
    (let [client (peer/client-peer S test-client-id remote-middleware transit)]
      (invoke-on-peer client)
      (reset! client-atom client)
      (js/console.log "[TEST] Client peer created"))))

(defn ensure-connected! []
  (go
    (setup-client!)
    (when-not @connected?
      (js/console.log "[TEST] Connecting to server at" test-server-url)
      (<! (connect-distributed-scope S @client-atom test-server-url))
      (reset! connected? true)
      (js/console.log "[TEST] Connected! Connections count:" (count @connections)))
    :ready))

;; =============================================================================
;; Tests
;; =============================================================================

;; Simple single-hop test function defined inline
(defn simple-add []
  (go-try S
          (js/console.log "[TEST] simple-add called on client")
          (+ 1 2 3)))

(deftest ^:async test-connection
  (testing "basic connection works"
    (async done
           (go
             (try
               (<! (ensure-connected!))
               (js/console.log "[TEST] Connection test passed")
               (is true "Connected successfully")
               (catch js/Error e
                 (js/console.error "[TEST] Error:" e)
                 (is false (str "Error: " (.-message e))))
               (finally
                 (done)))))))

(deftest ^:async test-go-remote-demo
  (testing "go-remote demo roundtrip"
    (async done
           (go
             (try
               (<! (ensure-connected!))
               (js/console.log "[TEST] Running demo (go-remote)")
               (let [result (<! (demo test-server-id test-client-id))]
                 (js/console.log "[TEST] demo result:" (pr-str result))
            ;; demo returns [b c] where b is (repeat 43 42) and c is 44
                 (is (= 44 (second result)) "c should be 44")
                 (is (= 42 (first (first result))) "b should start with 42"))
               (catch js/Error e
                 (js/console.error "[TEST] Error:" e)
                 (is false (str "Error: " (.-message e))))
               (finally
                 (done)))))))

(deftest ^:async test-sp-remote-demo
  (testing "sp-remote demo roundtrip"
    (async done
           (go
             (<! (ensure-connected!))
             (js/console.log "[TEST] Running demo-sp (sp-remote)")
        ;; demo-sp returns a Missionary task - run it with success/failure callbacks
             ((demo-sp test-server-id test-client-id)
              (fn [result]
                (js/console.log "[TEST] demo-sp result:" (pr-str result))
                (is (= [43 44] result) "demo-sp should return [43 44]")
                (done))
              (fn [error]
                (js/console.error "[TEST] Error:" error)
                (is false (str "Error: " error))
                (done)))))))
