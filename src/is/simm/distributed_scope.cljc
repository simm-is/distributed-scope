(ns is.simm.distributed-scope
  "Write distributed code that looks like local code: `defn-go-remote` and
   `defn-sp-remote` lift each `go-remote` / `sp-remote` hop into a named
   function registered on the peer that runs it, and rewrite the call into a
   remote invocation carrying exactly the declared variables.

   The runtime underneath — connection middleware, function registry,
   authorization gate, request/response over a kabel connection — is
   `kabel.remote` since kabel 0.3.127. The functions this namespace used to
   define for it (`remote-middleware`, `invoke-on-peer`, `invoke-remote`,
   `register-remote-fn!`, `connect-distributed-scope`, `*principal*`) remain
   here as thin wrappers so existing code keeps working; new code may use
   `kabel.remote` directly."
  (:require #?(:clj [clojure.tools.analyzer.jvm :as ana.jvm])
            [kabel.peer]
            [kabel.remote :as remote]
            [superv.async :refer [S -abort #?@(:clj [go-try <?])]]
            [clojure.core.async :refer [chan close! put! take! promise-chan #?(:clj go) <!]]
            [taoensso.telemere :as tel :include-macros true]
            [clojure.walk :as walk]
            [missionary.core :as m]
            [clojure.set :as set])
  #?(:cljs (:require-macros [superv.async :refer [go-try <?]]
                            [clojure.core.async :refer [go]])))

;; =============================================================================
;; Principal
;; =============================================================================

(def ^:dynamic *principal*
  "The authenticated principal for the current remote invocation, bound around
   functions registered through `register-remote-fn!`. It is the caller's
   `:kabel/principal`, also present in the argument map under that key.

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

;; =============================================================================
;; Runtime, delegated to kabel.remote
;; =============================================================================

(def remote-middleware
  "The connection middleware; `kabel.remote/middleware`."
  remote/middleware)

(def remote-fn-registry
  "The function registry; `kabel.remote/functions`."
  remote/functions)

(defn register-remote-fn!
  "Register `f` under `fn-name`, with `*principal*` bound to the caller's
   principal while it runs."
  [fn-name f]
  (remote/register! fn-name
                    (fn [arg-map]
                      (binding [*principal* (:kabel/principal arg-map)]
                        (f arg-map)))))

(defn unregister-remote-fn! [fn-name]
  (remote/unregister! fn-name))

(defonce ^{:doc "Peer ids served by this process through `invoke-on-peer`. A remote
                 invocation of one of them runs locally."}
  local-peers
  (atom #{}))

(defonce ^:private invocation-owners (atom {}))

(defn invoke-on-peer
  "Serve registered functions on `peer`; `kabel.remote/serve` underneath.

   `opts` may carry:
   - `:supervisor` — lifecycle owner for this invocation generation (default:
     the process supervisor). Aborting it stops serving and removes the peer
     from `local-peers`, unless a later `invoke-on-peer` on the same peer has
     replaced this generation, which stops the earlier one at once.
   - `:authorize-fn` (fn [principal fn-name arg-map]) or `:authorize` in the
     `kabel.authorize` map shape, the gate for every network-inbound
     invocation. Self-invocation is not gated.

   Returns a channel that closes once this generation has ended."
  ([peer] (invoke-on-peer peer {}))
  ([peer {:keys [supervisor] :or {supervisor S} :as opts}]
   (let [peer-id (:id @peer)
         token (random-uuid)
         run (promise-chan)
         handle (remote/serve peer (dissoc opts :supervisor))
         [before _] (swap-vals! invocation-owners assoc peer-id {:token token :handle handle})
         cleanup! (fn []
                    (let [[before _] (swap-vals! invocation-owners
                                                 (fn [owners]
                                                   (if (= token (get-in owners [peer-id :token]))
                                                     (dissoc owners peer-id)
                                                     owners)))]
                      (when (= token (get-in before [peer-id :token]))
                        ((:stop! handle))
                        (swap! local-peers disj peer-id)))
                    (close! run))]
     (when-let [previous (get before peer-id)]
       ((:stop! (:handle previous))))
     (swap! local-peers conj peer-id)
     (go (<! (-abort supervisor))
         (cleanup!))
     run)))

(defn invoke-remote
  "Invoke `fn-name` on the peer `remote-scope`; `kabel.remote/invoke`. A scope
   served by this process runs locally unless `{:force-remote? true}`."
  ([remote-scope fn-name arg-map]
   (invoke-remote remote-scope fn-name arg-map {}))
  ([remote-scope fn-name arg-map {:keys [force-remote?]}]
   (if (and (not force-remote?) (contains? @local-peers remote-scope))
     (remote/invoke (get @remote/routes remote-scope (kabel.peer/get-peer remote-scope))
                    remote-scope fn-name arg-map)
     (remote/invoke remote-scope fn-name arg-map))))

(defn connect-distributed-scope
  "Connect and wait until remote invocations work; `kabel.remote/connect`."
  [S peer-atom url]
  (remote/connect S peer-atom url))

(defn throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

;; =============================================================================
;; Free-variable analysis (macro time)
;; =============================================================================

#?(:clj
   (defn free-variables [env body]
     (let [free-variables (atom #{})]
       (if (:js-globals env)
         ;; ClojureScript is a compile-time dependency of the consumer's cljs
         ;; build, not of this library: resolve the analyzer when a cljs macro
         ;; expansion asks for it.
         (let [analyze (requiring-resolve 'cljs.analyzer/analyze)
               handlers (requiring-resolve 'cljs.analyzer/*cljs-warning-handlers*)]
           (with-bindings {handlers [(fn [warning-type _env extra]
                                       (when (= warning-type :undeclared-var)
                                         (swap! free-variables conj (:suffix extra))))]}
             (analyze env body)))
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