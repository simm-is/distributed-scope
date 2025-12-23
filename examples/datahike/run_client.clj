(ns datahike.run-client
  (:require [datahike.client :as client]
            [datahike.demo :as demo]
            [hasch.core :refer [uuid]]
            [datahike.api :as d]
            [superv.async :refer [S <??]]))

(defn -main [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 9090)
        url (str "ws://localhost:" port)
        server-id (uuid :server)
        client-id (uuid :client)]
    (println "===========================================")
    (println "Starting Datahike Client")
    (println "===========================================")
    (println "Connecting to:" url)
    (println "Server ID:" server-id)
    (println "Client ID:" client-id)
    (println "===========================================")
    (println)

    (let [{:keys [client conn] :as state} (client/start! url client-id)]
      (println "Client connected successfully!")
      (println "Connected to shared database")
      (println)

      ;; Run the simple demo
      (println "Running simple-demo...")
      (println "--------------------------------------")
      (let [result (<?? S (demo/simple-demo server-id client-id conn))]
        (println "--------------------------------------")
        (println "Demo completed!")
        (println "Result:" result))
      (println)

      ;; Run the query-on-client demo
      (println "Running query-on-client...")
      (println "--------------------------------------")
      (let [result (<?? S (demo/query-on-client server-id client-id conn))]
        (println "--------------------------------------")
        (println "Query result:" result))
      (println)

      ;; Run the bidirectional-query demo
      (println "Running bidirectional-query...")
      (println "--------------------------------------")
      (let [result (<?? S (demo/bidirectional-query server-id client-id conn))]
        (println "--------------------------------------")
        (println "Bidirectional query result:" result))
      (println)

      (println "All demos completed!")
      (println "Disconnecting...")

      ;; Cleanup
      (try
        (when conn
          (d/release conn))
        (catch Exception e
          (println "Error during cleanup:" (.getMessage e))))

      (System/exit 0))))

;; For REPL usage
(comment
  (-main)
  (-main "9090"))
