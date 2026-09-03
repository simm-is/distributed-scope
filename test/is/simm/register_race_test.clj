(ns is.simm.register-race-test
  "Regression for #2 (github.com/simm-is/distributed-scope/issues/2): an
   ::invoke that reaches a peer BEFORE the requester's ::register-scope must
   still get its reply. The reply now rides the request's own connection
   (::reply-out), so it no longer depends on register-scope ordering.

   Mirrors nathell's repro (github.com/nathell/distributed-scope-race): the
   client holds its outbound ::register-scope while letting the ::invoke through,
   forcing the server to answer an invoke for a not-yet-registered scope."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [chan close! go timeout alts!! <!!]]
            [is.simm.distributed-scope :as ds]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [hasch.core :refer [uuid]]
            [superv.async :refer [S <? >? go-loop-super]])
  (:import (java.net ServerSocket)))

(defn- free-port []
  (with-open [^ServerSocket ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(defn- delay-register-middleware
  "Hold this peer's outbound ::register-scope for delay-ms so the far side sees
   the ::invoke first; everything else passes immediately."
  [delay-ms]
  (fn [[S peer [in out]]]
    (let [in' (chan 100)
          out' (chan 100)]
      (go-loop-super S []
                     (if-let [msg (<? S in)] (do (>? S in' msg) (recur)) (close! in')))
      (go-loop-super S []
                     (if-let [msg (<? S out')]
                       (do (if (contains? #{:kabel.remote/register :is.simm.distributed-scope/register-scope}
                                          (:type msg))
                             (go (<? S (timeout delay-ms)) (>? S out msg))
                             (>? S out msg))
                           (recur))
                       (close! out)))
      [S peer [in' out']])))

(deftest invoke-before-register-scope-still-replies
  (testing "reply routes back even when ::invoke beats the requester's ::register-scope (#2)"
    (let [port (free-port)
          url (str "ws://localhost:" port)
          server-id (uuid :race-server)
          client-id (uuid :race-client)]
      (ds/register-remote-fn! 'race/ping (fn [_] (go {:ok true})))
      (let [server (peer/server-peer S (http-kit/create-http-kit-handler! S url server-id)
                                     server-id ds/remote-middleware)
            client (peer/client-peer S client-id
                                     (comp ds/remote-middleware (delay-register-middleware 500)))]
        (try
          (<!! (peer/start server))
          (ds/invoke-on-peer server)
          (<!! (ds/connect-distributed-scope S client url))
          (let [result-ch (ds/invoke-remote server-id 'race/ping {} {:force-remote? true})
                [value chosen] (alts!! [result-ch (timeout 3000)])]
            (is (= result-ch chosen) "invoke replied before the 3s timeout (reply not lost)")
            (is (= {:ok true} value) "reply routed back despite the delayed ::register-scope"))
          (finally
            (ds/unregister-remote-fn! 'race/ping)
            (<!! (peer/stop client))
            (<!! (peer/stop server))))))))
