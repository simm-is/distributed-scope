(ns is.simm.browser-test-server
  "JVM server for browser integration tests.

   Provides a Kabel server that:
   - Listens on fixed port 47300 for WebSocket connections
   - Enables distributed-scope for remote function invocation
   - Uses transit serialization for CLJS compatibility

   Usage:
   (start-test-server!)  ; Start server
   (stop-test-server!)   ; Stop server

   Or from command line:
   clj -M:browser-server"
  (:require [kabel.peer :as peer]
            [kabel.http-kit :refer [create-http-kit-handler!]]
            [kabel.middleware.transit :refer [transit]]
            [is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]]
            ;; Require demo functions so they're registered on the server
            [simple.demo]
            [simple.demo-sp]
            [superv.async :refer [S go-try <?]]
            [clojure.core.async :refer [<!!]])
  (:gen-class))

;; =============================================================================
;; Configuration
;; =============================================================================

(def test-server-port 47300)
(def test-server-url (str "ws://localhost:" test-server-port))
(def test-server-id #uuid "d5000000-0000-0000-0000-000000000001")

;; =============================================================================
;; Server State
;; =============================================================================

(defonce ^:private server-state (atom nil))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn start-test-server!
  "Start the test server for browser integration tests.

   Returns a map with:
   - :server - The kabel server peer
   - :url - WebSocket URL for clients"
  []
  (when @server-state
    (throw (ex-info "Test server already running" @server-state)))

  (println "[SERVER] Starting browser test server on port" test-server-port)

  (let [handler (create-http-kit-handler! S test-server-url test-server-id)
        server (peer/server-peer S handler test-server-id
                                 remote-middleware
                                 transit)]

    ;; Start the server
    (<!! (go-try S (<? S (peer/start server))))

    ;; Set up distributed-scope to process remote invocations
    (invoke-on-peer server)

    (println "[SERVER] Browser test server started successfully")
    (println "[SERVER] URL:" test-server-url)
    (println "[SERVER] Server ID:" test-server-id)

    (let [state {:server server
                 :url test-server-url}]
      (reset! server-state state)
      state)))

(defn stop-test-server!
  "Stop the test server and clean up resources."
  []
  (when-let [state @server-state]
    (println "[SERVER] Stopping browser test server")

    (when-let [server (:server state)]
      (<!! (go-try S (<? S (peer/stop server)))))

    (reset! server-state nil)
    (println "[SERVER] Browser test server stopped")))

(defn get-server-info
  "Get current server info (url, etc.) if running."
  []
  @server-state)

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Start the test server and wait for connections.
   Used by test-browser.sh script."
  [& _args]
  (start-test-server!)
  (println "[SERVER] Waiting for browser connections... (Ctrl+C to stop)")
  ;; Keep the process alive
  @(promise))

;; =============================================================================
;; REPL Helper
;; =============================================================================

(comment
  ;; Start server for manual browser testing
  (start-test-server!)

  ;; Check server status
  (get-server-info)

  ;; Stop server
  (stop-test-server!))
