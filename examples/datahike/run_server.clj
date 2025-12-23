(ns datahike.run-server
  (:require [datahike.server :as server]
            [datahike.demo :as demo]
            [hasch.core :refer [uuid]]))

(defn -main [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 9090)
        url (str "ws://localhost:" port)
        server-id (uuid :server)]
    (println "===========================================")
    (println "Starting Datahike Server")
    (println "===========================================")
    (println "Port:" port)
    (println "URL:" url)
    (println "Server ID:" server-id)
    (println "===========================================")
    (println)

    (let [{:keys [server conn] :as state} (server/start! url server-id)]
      (println "Server started successfully!")
      (println "Database created with test data (Alice, Bob)")
      (println)
      (println "The server is now waiting for client connections...")
      (println "Press Ctrl+C to stop the server")
      (println)

      ;; Keep the server running
      (-> (Runtime/getRuntime)
          (.addShutdownHook
           (Thread. #(do
                       (println "\nShutting down server...")
                       (server/stop! state)
                       (println "Server stopped.")))))

      ;; Block forever
      @(promise))))

;; For REPL usage
(comment
  (-main)
  (-main "9090"))
