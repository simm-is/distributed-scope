(ns is.simm.distributed-scope
  (:require #?(:clj [clojure.tools.analyzer.jvm :as ana.jvm])
            #?(:clj [cljs.analyzer :as ana.js])
            [hasch.core :refer [uuid]]
            [superv.async :refer [S put? #?@(:clj [go-super go-loop-super <? >? go-try go-loop-try on-abort])]]
            [clojure.core.async :refer [chan pub sub unsub close! put! take! <! #?(:clj go-loop)]]
            [taoensso.telemere :as tel :include-macros true]
            [clojure.walk :as walk]
            [missionary.core :as m]
            [kabel.peer :as peer]
            [clojure.set :as set])
  #?(:cljs (:require-macros [superv.async :refer [go-super go-loop-super go-try <? >? go-loop-try on-abort]]
                            [clojure.core.async :refer [go go-loop]])))

;; Global connection registry: {remote-peer-id -> {:peer peer-atom :out out-channel}}
;; This replaces the old system-peer global. When invoke-remote needs to call a remote peer,
;; it looks up which local peer has a connection to the target.
(def connections (atom {}))

;; Dynamic binding for the authenticated principal
;; Set by invoke-on-peer when executing remote functions that have :kabel/principal attached
;; User code should capture this immediately if needed in async contexts:
;;   (let [principal *principal*] ...)
(def ^:dynamic *principal*
  "The authenticated principal for the current remote invocation.
   Contains claims from the JWT token (e.g., :sub, :email, :name).
   Bound by invoke-on-peer when processing authenticated invocation requests.

   Note: In ClojureScript, dynamic bindings don't carry through core.async.
   Capture immediately if needed: (let [principal *principal*] ...)"
  nil)

(defn current-principal
  "Get the current principal from dynamic binding.
   Returns nil if not in an authenticated context."
  []
  *principal*)

(defn require-principal
  "Get the current principal or throw if not authenticated.
   Use this in remote functions that require authentication."
  []
  (or *principal*
      (throw (ex-info "Authentication required" {:type :authentication-required}))))

;; Promise channels for waiting on connection readiness: {remote-peer-id -> promise-chan}
;; Used by invoke-remote to wait for a connection to be established before giving up.
(def connection-promises (atom {}))

;; One stable invocation router and one active owner per local peer. Keeping
;; both in one atom makes replacement and teardown generation-safe.
(defonce ^:private invocation-owners (atom {}))

;; Read-only compatibility view used for detecting self-invocation. Deriving it
;; from invocation-owners avoids a second mutable registry drifting during a
;; concurrent session handoff.
(def local-peers
  #?(:clj (reify clojure.lang.IDeref
            (deref [_] (set (keys @invocation-owners))))
     :cljs (reify IDeref
             (-deref [_] (set (keys @invocation-owners))))))

#?(:clj
   (defn free-variables [env body]
     (let [free-variables (atom #{})]
       (if (:js-globals env)
         (binding [ana.js/*cljs-warning-handlers*
                   [(fn [warning-type _env extra]
                      (when (= warning-type :undeclared-var)
                        (swap! free-variables conj (:suffix extra))))]]
           (ana.js/analyze env body))
         (ana.jvm/analyze
          body
          (ana.jvm/empty-env)
          {:passes-opts
           {:validate/unresolvable-symbol-handler
            (fn [_a s _b]
              (swap! free-variables conj s)
              ;; replacing unresolved symbol with `nil` in order to keep AST valid
              {:op :const :env {} :type :nil :literal? true
               :val nil :form nil :top-level true :o-tag nil :tag nil})}}))
       (disj @free-variables 'clojure))))

(defn remote-middleware
  "A middleware that forwards invocation messages to the remote peer
   and receives invocation messages from the remote peer.

   Registers {remote-peer-id -> {:peer ...}} in the global `connections` atom for
   OUTBOUND routing (invoke-remote), and tags each inbound ::invoke with a
   ::reply-out channel so the processor can reply on the request's own connection."
  [[S peer [in out]]]
  (let [new-in (chan 1000)
        p-in (pub in (fn [{:keys [type]}]
                       (or ({::register-scope ::register-scope
                             ::invoke ::invoke
                             ::invoke-result ::invoke-result
                             ::never-happens ::never-happens} type)
                           :unrelated)))
        ;; pass through
        _ (sub p-in :unrelated new-in)
        on-close-ch (chan)
        _ (sub p-in ::never-happens on-close-ch)

        register-ch (chan)
        _ (sub p-in ::register-scope register-ch)

        [bus-in bus-out] (get-in @peer [:volatile :chans])
        ;; Forwarding wrapper to properly handle messages with superv.async
        ;; TODO improve this buffering mechanism
        ;; currently fixes reconnects
        bus-in-wrapper (chan 1000)
        _ (go-loop-super S []
                         (let [msg (<? S bus-in-wrapper)]
                           (when msg
                             ;; Carry the reply channel WITH the request. An inbound ::invoke
                             ;; arrived on THIS connection, so `out` is the route back to the
                             ;; requester. The invoke processor replies on this channel instead
                             ;; of re-deriving it from the `connections` registry — which removes
                             ;; the ::register-scope/::invoke ordering race (#2) by construction:
                             ;; every invoke that reaches the processor necessarily passed through
                             ;; here first, so its reply route is always present.
                             (>? S bus-in (if (= (:type msg) ::invoke)
                                            (assoc msg ::reply-out out)
                                            msg))
                             (recur))))
        _ (sub p-in ::invoke bus-in-wrapper)
        ;; Route incoming invoke-results directly to the local response handlers
        _ (sub p-in ::invoke-result bus-in-wrapper)
        peer-id (get-in @peer [:id])
        ;; handle outgoing invocations on the internal bus (to send to remote)
        invoke-ch (chan 1000)
        _ (sub bus-out ::invoke invoke-ch)]
    ;; send messages from bus-in that are scoped for other peer
    (go-super
     S
     (let [{remote-scope :scope} (<? S register-ch)
           _ (close! register-ch)]
       (when remote-scope  ;; Guard against nil scope (can happen during shutdown)
         (tel/log! {:level :info
                    :id ::remote-middleware-registered
                    :msg "registered remote scope"
                    :data {:peer-id peer-id
                           :remote-scope remote-scope}})
         ;; Store the connection in the global registry
         ;; This allows invoke-remote to find the right peer for any target
         ;; Send-side registry only: `invoke-remote` looks up `:peer` here to
         ;; route an OUTBOUND invoke (gated by connection-promises). Replies no
         ;; longer use this — they ride the ::reply-out channel on the request.
         (swap! connections assoc remote-scope {:peer peer})
         ;; Deliver on promise so invoke-remote knows connection is ready
         (when-let [promise-ch (get @connection-promises remote-scope)]
           (put! promise-ch :ready)
           (swap! connection-promises dissoc remote-scope))
         ;; Signal that middleware is ready
         (>? S bus-in {:type :distributed-scope/ready})
         ;; ensure go-loop below is terminated on close
         (go-super S
                   (<? S on-close-ch)
                   ;; Clean up the connection from global registry
                   (swap! connections dissoc remote-scope)
                   ;; Clean up any pending promise
                   (swap! connection-promises dissoc remote-scope)
                   (tel/log! {:level :debug
                              :id ::remote-middleware-disconnected
                              :msg "cleaned up connection mapping"
                              :data {:peer-id peer-id
                                     :remote-scope remote-scope}})
                   (unsub p-in ::invoke bus-in-wrapper)
                   (unsub p-in ::invoke-result bus-in-wrapper)
                   (unsub bus-out ::invoke invoke-ch)
                   (close! invoke-ch))
         ;; Only need to forward outgoing invokes to the remote peer
         ;; Responses are sent directly via the connection mapping
         (go-loop-super S []
                        (let [{:keys [scope] :as msg} (<? S invoke-ch)]
                          (tel/log! {:level :trace
                                     :id ::remote-middleware-invoke
                                     :msg "invoking message received"
                                     :data {:msg msg
                                            :scope scope
                                            :remote-scope remote-scope}})
                          (when msg
                            (when (= scope remote-scope)
                              (tel/log! {:level :trace
                                         :id ::remote-middleware-invoke-send
                                         :msg "sending invocation request to remote peer"
                                         :data {:msg msg
                                                :scope scope
                                                :remote-scope remote-scope}})
                              (>? S out msg))
                            (recur)))))))

    ;; register our scope with remote peer
    (put? S out {:type ::register-scope :scope peer-id})

    [S peer [new-in out]]))

(defn connect-distributed-scope
  "Connect to a peer and wait for distributed-scope middleware to be ready.
   This ensures that remote function invocations will work immediately after
   the connection is established."
  [S peer-atom url]
  (go-try S
          (let [[bus-in bus-out] (get-in @peer-atom [:volatile :chans])
                ready-ch (chan)
                _ (sub bus-out :distributed-scope/ready ready-ch)]
      ;; Connect first (this applies middleware)
            (<? S (peer/connect S peer-atom url))
      ;; Wait for the ready signal
            (<? S ready-ch)
            (unsub bus-out :distributed-scope/ready ready-ch)
            (close! ready-ch)
            :ready)))

(defn throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(def remote-fn-registry (atom {}))

(defn register-remote-fn! [fn-name fn-var]
  (swap! remote-fn-registry assoc fn-name fn-var))

(defn unregister-remote-fn! [fn-name]
  (swap! remote-fn-registry dissoc fn-name))

(defn- dispatch-invocation! [peer-id router-id msg]
  (when-let [{:keys [supervisor authorize-fn]} (get @invocation-owners peer-id)]
    (let [{:keys [fn-name arg-map scope request-id request-scope]} msg
          principal (:kabel/principal msg)]
      (tel/log! {:level :debug
                 :id ::invoke-on-peer-invoke
                 :msg "remote invocation request received"
                 :data {:msg (dissoc msg :kabel/principal)
                        :scope scope
                        :peer-id peer-id
                        :has-principal (some? principal)}})
      (when (and (= scope peer-id)
                 (= router-id (get-in @invocation-owners [peer-id :router-id])))
        (go-try supervisor
          ;; Bind *principal* for the duration of the function call. In CLJS,
          ;; user code should capture it immediately before crossing async APIs.
                (let [res (binding [*principal* principal]
                            (try
                              (if-not (authorize-fn principal fn-name arg-map)
                                (if principal
                                  (throw (ex-info "Not authorized"
                                                  {:type :not-authorized :fn-name fn-name}))
                                  (throw (ex-info "Authentication required"
                                                  {:type :authentication-required :fn-name fn-name})))
                                (if-let [f (get @remote-fn-registry fn-name)]
                                  (<? supervisor (f arg-map))
                                  (throw (ex-info "Remote function not found"
                                                  {:fn-name fn-name}))))
                              (catch #?(:clj Throwable :cljs :default) e e)))
                      response (cond-> {:type ::invoke-result
                                        :scope request-scope
                                        :request-id request-id}
                                 (throwable? res) (assoc :error (pr-str res))
                                 (not (throwable? res)) (assoc :result res))
                ;; Every inbound invoke carries its reply route, avoiding a
                ;; dependency on ::register-scope message ordering.
                      reply-out (::reply-out msg)]
                  (tel/log! {:level :debug
                             :id ::invoke-on-peer-invoke-result-send
                             :msg "sending invocation result to requesting peer"
                             :data {:response response
                                    :request-scope request-scope
                                    :peer-id peer-id}})
                  (when-not reply-out
                    (throw (ex-info "Cannot send response: invoke carried no reply channel"
                                    {:request-scope request-scope})))
                  (>? supervisor reply-out response)))))))

(defn- start-invocation-router! [peer-id router-id invoke-ch]
  ;; The router is stable across session replacement. It contains no session
  ;; policy or supervisor state; each delivery snapshots the current owner.
  (go-loop []
    (if-let [msg (<! invoke-ch)]
      (do
        (dispatch-invocation! peer-id router-id msg)
        (recur))
      ;; A closed peer bus closes its pub subscriptions. Retire this router only
      ;; if it is still current; a replacement already points elsewhere.
      (swap! invocation-owners
             (fn [owners]
               (if (= router-id (get-in owners [peer-id :router-id]))
                 (dissoc owners peer-id)
                 owners))))))

(defn invoke-on-peer
  "Sets up the system to be able to invoke remote functions on the given peer.

   Responses are sent directly back on the channel the request arrived on
   (carried as ::reply-out), so reply routing never depends on the requester's
   ::register-scope having been processed first.

   Also makes the peer visible through `local-peers` for self-invocation.

   When the invocation message contains :kabel/principal (set by kabel-auth
   websocket middleware), it is bound to *principal* during function execution.

   `opts` may carry:
   - `:supervisor` — lifecycle owner for this invocation generation. Defaults
     to the process supervisor for compatibility. Aborting the current owner
     closes the stable per-peer router and removes the peer from `local-peers`.
   - `:authorize-fn` — (fn [principal fn-name arg-map] -> truthy),
   the control-plane authorization gate consulted for every NETWORK-inbound
   invocation before the handler runs. A falsy result short-circuits to a
   :not-authorized error and the handler never executes. Default permits
   everything, so distributed-scope stays a generic transport and the app
   supplies policy (symmetric to kabel.pubsub's :authorize-fn). Self-invocation
   (`invoke-remote` on a local peer) is NOT gated — it is the process calling
   its own functions and carries no principal."
  ([peer] (invoke-on-peer peer {}))
  ([peer {:keys [authorize-fn supervisor]
          :or {authorize-fn (fn [_principal _fn-name _arg-map] true)
               supervisor S}}]
   (let [[_ bus-out] (get-in @peer [:volatile :chans])
         peer-id (get-in @peer [:id])
         token (random-uuid)
         router-id (random-uuid)
         candidate-ch (chan 1000)
         candidate-started? (atom false)
         candidate-cleaned? (atom false)
         candidate-router {:router-id router-id
                           :invoke-ch candidate-ch
                           :started? candidate-started?
                           :bus-out bus-out
                           :cleanup-router! (fn []
                                              (when (compare-and-set! candidate-cleaned? false true)
                                                (unsub bus-out ::invoke candidate-ch)
                                                (close! candidate-ch)))}
         ;; Subscribe the candidate before the atomic owner switch. Its bounded
         ;; channel buffers deliveries until it wins and its router starts.
         _ (sub bus-out ::invoke candidate-ch)
         [before after]
         (swap-vals! invocation-owners
                     (fn [owners]
                       (let [current (get owners peer-id)
                             router (if (and current (identical? bus-out (:bus-out current)))
                                      (select-keys current
                                                   [:router-id :invoke-ch :started? :bus-out :cleanup-router!])
                                      candidate-router)]
                         (assoc owners peer-id
                                (merge router
                                       {:token token
                                        :supervisor supervisor
                                        :authorize-fn authorize-fn})))))
         previous (get before peer-id)
         {:keys [router-id invoke-ch started? cleanup-router!]} (get after peer-id)
         candidate-active? (= router-id (:router-id candidate-router))
         _ (when-not candidate-active?
             ((:cleanup-router! candidate-router)))
         _ (when (compare-and-set! started? false true)
             (start-invocation-router! peer-id router-id invoke-ch))
         _ (when (and previous
                      (not= (:router-id previous) router-id))
             ((:cleanup-router! previous)))
         cleanup! (fn []
                    (let [[before _]
                          (swap-vals! invocation-owners
                                      (fn [owners]
                                        (if (= token (get-in owners [peer-id :token]))
                                          (dissoc owners peer-id)
                                          owners)))
                          owner (get before peer-id)]
                      (when (= token (:token owner))
                        (cleanup-router!))))]
     (on-abort supervisor
               (cleanup!)))))

(defn invoke-remote
  "Invoke a registered function in `remote-scope`.

   Local scopes normally execute directly. Pass `{:force-remote? true}` to the
   four-argument form when the network route itself must be exercised."
  ([remote-scope fn-name arg-map]
   (invoke-remote remote-scope fn-name arg-map {}))
  ([remote-scope fn-name arg-map {:keys [force-remote?]}]
   ;; Returns a go-channel that will contain the result
   (go-try S
    ;; Check for self-invocation (calling a function on this same process)
           (if (and (not force-remote?) (contains? @local-peers remote-scope))
      ;; Local call - execute directly without network round-trip
             (if-let [f (get @remote-fn-registry fn-name)]
               (<? S (f arg-map))
               (throw (ex-info "Remote function not found" {:fn-name fn-name})))
      ;; Remote call - send over the network
             (do
        ;; Wait for connection if not immediately available
               (when-not (get @connections remote-scope)
                 (tel/log! {:level :debug
                            :id ::invoke-remote-waiting
                            :msg "Connection not ready, waiting for handshake"
                            :data {:remote-scope remote-scope}})
          ;; Create promise channel if doesn't exist
                 (let [promise-ch (or (get @connection-promises remote-scope)
                                      (let [ch (chan 1)]
                                        (swap! connection-promises assoc remote-scope ch)
                                        ch))]
            ;; Wait for the ready signal
                   (<? S promise-ch)))
        ;; Now look up the connection
               (let [{:keys [peer]} (get @connections remote-scope)
                     _ (when-not peer
                         (throw (ex-info "Not connected to remote peer"
                                         {:remote-scope remote-scope
                                          :available-connections (keys @connections)})))
                     client-scope (:id @peer)
                     [bus-in bus-out] (get-in @peer [:volatile :chans])
                     response-ch (chan)
                     _ (sub bus-out ::invoke-result response-ch)
                     rid (uuid)
                     msg {:type ::invoke
                          :scope remote-scope
                          :request-scope client-scope
                          :fn-name fn-name
                          :arg-map arg-map
                          :request-id rid}]
                 (tel/log! {:level :debug
                            :id ::invoke-remote-send
                            :msg "Sending remote invocation"
                            :data {:scope remote-scope
                                   :fn-name fn-name
                                   :client-scope client-scope
                                   :request-id rid}})
          ;; send a request
                 (put? S bus-in msg)
          ;; wait for the result
                 (loop []
                   (let [{:keys [request-id result error] :as msg} (<? S response-ch)]
                     (tel/log! {:level :debug
                                :id ::invoke-remote-received
                                :msg "Received response"
                                :data {:msg msg
                                       :request-id request-id
                                       :rid rid
                                       :error error
                                       :result result}})
                     (when msg
                       (if (= request-id rid)
                         (do
                           (unsub bus-out ::invoke-result response-ch)
                           (close! response-ch)
                           (if error
                             (throw (ex-info "Remote invocation error" {:error error}))
                             result))
                         (recur)))))))))))

;; core.async API

(defn go-remote
  "Executes the body on remote with id and returns the result.

   Usage: (go-remote peer-id [arg1 arg2 ...] body...)

   The arg vector explicitly lists which variables from the current scope
   should be captured and sent to the remote peer. The macro will validate
   that all free variables in the body are listed (error) and warn about
   any listed variables that aren't used (warning)."
  [scope explicit-args & body]
  (throw (ex-info "The go-remote macro must be used inside a defn-go-remote macro" {:scope scope :explicit-args explicit-args :body body})))

(defmacro defn-go-remote
  {:style/indent [1 :form [1]]
   :arglists '([go-remote-name [params*] & body])}
  [go-remote-name args & body]
  {:pre [(symbol? go-remote-name) (vector? args)]}
  (let [;; Walk through the body to find all remote macro invocations
        macro-pos (select-keys (meta &form) [:line :column])
        _  (when (not= (:column macro-pos) 1)
             (tel/log! {:level :warn
                        :id ::defn-go-remote-must-be-top-level
                        :msg "defn-go-remote must be top-level for remote function to be properly registered"
                        :macro-pos macro-pos}))
        remote-forms (atom [])
        new-body (clojure.walk/postwalk
                  (fn [form]
                    (if (and (seq? form)
                             (= 'go-remote (first form)))
                      (let [[_ scope explicit-args & remote-body] form
                            _ (when-not (vector? explicit-args)
                                (throw (ex-info "go-remote requires explicit arg vector: (go-remote peer-id [arg1 arg2 ...] body...)"
                                                {:form form
                                                 :got explicit-args})))
                            combined-body# `(do ~@remote-body)
                            free-vars# (free-variables &env combined-body#)
                            declared-args# (set explicit-args)
                            missing# (set/difference free-vars# declared-args#)
                            extra# (set/difference declared-args# free-vars#)
                            _ (when (seq missing#)
                                (throw (ex-info (str "go-remote at " (select-keys (meta form) [:line :column])
                                                     ": variables used in body but not in arg list")
                                                {:missing missing#
                                                 :declared declared-args#
                                                 :used free-vars#
                                                 :form form})))
                            _ (when (seq extra#)
                                (tel/log! {:level :debug
                                           :id ::go-remote-extra-args
                                           :msg (str "go-remote at " (select-keys (meta form) [:line :column])
                                                     ": variables in arg list but not used in body")
                                           :data {:extra extra#
                                                  :declared declared-args#
                                                  :used free-vars#}}))
                            ns-sym# (symbol (str *ns*) (str "go-remote-" (name go-remote-name) "-" (count @remote-forms)))
                            msg# (into {} (map (fn [s] [(keyword (str s)) s]) explicit-args))]
                        (swap! remote-forms conj [form explicit-args])
                        `(invoke-remote ~scope '~ns-sym# ~msg#))
                      form))
                  `(do ~@body))
        ;; Generate the remote function definitions and registrations
        remote-defs (mapv (fn [[remote-form explicit-args] i]
                            (let [[_ _scope-expr _args & remote-body] remote-form
                                  cont-sym# (symbol (str "go-remote-" (name go-remote-name) "-" i))
                                  ns-qualified-sym# (symbol (str *ns*) (str cont-sym#))]
                              {:def `(defn ~cont-sym# [{:keys ~(vec explicit-args)}]
                                       (go-try S ~@remote-body))
                               :registration `(register-remote-fn! '~ns-qualified-sym# ~ns-qualified-sym#)}))
                          @remote-forms
                          (range))]
    ;; Return a do block that:
    `(do
       ;; 1. Defines all the remote functions
       ~@(map :def remote-defs)
       ;; 2. Registers them in the registry
       ~@(map :registration remote-defs)
       ;; 3. Defines the main distributed function
       (defn ~go-remote-name ~args
         ~new-body))))

;; missionary API (TODO unify macros)

(def ^:private nil-sentinel ::nil-value)

(defn task->chan [sp]
  (let [ch (chan)]
    ;; Run the Missionary task using its callback interface (works in both CLJ and CLJS)
    (sp
     (fn [result]
       (put! ch (if (nil? result) nil-sentinel result))
       (close! ch))
     (fn [error]
       (put! ch error)
       (close! ch)))
    ch))

(defn ?<! "Takes from given channel, returns a task completing with value when take is accepted, or nil if port was closed."
  [c] (doto (m/dfv) (->> (take! c))))

(defn chan->task "Takes from given channel, returns a task completing with value when take is accepted, or nil if port was closed. Rethrows throwables."
  [c]
  (m/sp
   (let [res (m/? (?<! c))
         res (if (= res nil-sentinel) nil res)]
     (if (throwable? res)
       (throw res)
       res))))

(defn sp-remote
  "Executes the body on remote with id and returns the result.

   Usage: (sp-remote peer-id [arg1 arg2 ...] body...)

   The arg vector explicitly lists which variables from the current scope
   should be captured and sent to the remote peer. The macro will validate
   that all free variables in the body are listed (error) and warn about
   any listed variables that aren't used (warning)."
  [scope explicit-args & body]
  (throw (ex-info "The sp-remote macro must be used inside a defn-sp-remote macro" {:scope scope :explicit-args explicit-args :body body})))

(defmacro defn-sp-remote
  {:style/indent [1 :form [1]]
   :arglists '([sp-remote-name [params*] & body])}
  [sp-remote-name args & body]
  {:pre [(symbol? sp-remote-name) (vector? args)]}
  (let [;; Walk through the body to find all remote macro invocations
        macro-pos (select-keys (meta &form) [:line :column])
        _  (when (not= (:column macro-pos) 1)
             (tel/log! {:level :warn
                        :id ::defn-sp-remote-must-be-top-level
                        :msg "defn-sp-remote must be top-level for remote function to be properly registered"
                        :macro-pos macro-pos}))
        remote-forms (atom [])
        new-body (clojure.walk/postwalk
                  (fn [form]
                    (if (and (seq? form)
                             (= 'sp-remote (first form)))
                      (let [[_ scope explicit-args & remote-body] form
                            _ (when-not (vector? explicit-args)
                                (throw (ex-info "sp-remote requires explicit arg vector: (sp-remote peer-id [arg1 arg2 ...] body...)"
                                                {:form form
                                                 :got explicit-args})))
                            combined-body# `(do ~@remote-body)
                            free-vars# (free-variables &env combined-body#)
                            declared-args# (set explicit-args)
                            missing# (clojure.set/difference free-vars# declared-args#)
                            extra# (clojure.set/difference declared-args# free-vars#)
                            _ (when (seq missing#)
                                (throw (ex-info (str "sp-remote at " (select-keys (meta form) [:line :column])
                                                     ": variables used in body but not in arg list")
                                                {:missing missing#
                                                 :declared declared-args#
                                                 :used free-vars#
                                                 :form form})))
                            _ (when (seq extra#)
                                (tel/log! {:level :debug
                                           :id ::sp-remote-extra-args
                                           :msg (str "sp-remote at " (select-keys (meta form) [:line :column])
                                                     ": variables in arg list but not used in body")
                                           :data {:extra extra#
                                                  :declared declared-args#
                                                  :used free-vars#}}))
                            ns-sym# (symbol (str *ns*) (str "sp-remote-" (name sp-remote-name) "-" (count @remote-forms)))
                            msg# (into {} (map (fn [s] [(keyword (str s)) s]) explicit-args))]
                        (swap! remote-forms conj [form explicit-args])
                        `(chan->task (invoke-remote ~scope '~ns-sym# ~msg#)))
                      form))
                  `(do ~@body))
        ;; Generate the remote function definitions and registrations
        remote-defs (mapv (fn [[remote-form explicit-args] i]
                            (let [[_ _scope-expr _args & remote-body] remote-form
                                  cont-sym# (symbol (str "sp-remote-" (name sp-remote-name) "-" i))
                                  ns-qualified-sym# (symbol (str *ns*) (str cont-sym#))]
                              {:def `(defn ~cont-sym# [{:keys ~(vec explicit-args)}]
                                       (task->chan (m/sp ~@remote-body)))
                               :registration `(register-remote-fn! '~ns-qualified-sym# ~ns-qualified-sym#)}))
                          @remote-forms
                          (range))]
    ;; Return a do block that:
    `(do
       ;; 1. Defines all the remote functions
       ~@(map :def remote-defs)
       ;; 2. Registers them in the registry
       ~@(map :registration remote-defs)
       ;; 3. Defines the main distributed function
       (defn ~sp-remote-name ~args
         ~new-body))))
