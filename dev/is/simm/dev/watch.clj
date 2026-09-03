(ns is.simm.dev.watch
  "Development-only code watcher. By default, only reloads the namespaces
  corresponding to the files that changed (per-ns reload). You can switch to a
  full dependency-aware refresh using tools.namespace if needed.

  Quick start (in a REPL):

    (require 'is.simm.dev.watch)
    ;; optional: run a function after each successful reload/refresh
    (is.simm.dev.watch/set-after-refresh! 'user/reset)

  Modes:
    - :reload-ns (default): For each changed .clj/.cljc file, run (require ns :reload)
      Pros: fast, avoids global refresh conflicts. Cons: doesn't handle dependent
            namespaces automatically.
    - :refresh: Use clojure.tools.namespace.repl/refresh for a full graph reload.

  CLJS:
    - By default .cljs changes are ignored (shadow-cljs usually handles them).
      Enable with (set-include-cljs! true) if you really want to trigger actions on
      .cljs changes.
  "
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [hawk.core :as hawk]
            [clojure.tools.namespace.repl :as tns])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (java.io File)
           (java.nio.file Paths)))

;; Keep this namespace resident across refreshes so the watcher survives.
(tns/disable-unload!)
(tns/disable-reload!)

(def ^:private ^ScheduledExecutorService scheduler
  (Executors/newSingleThreadScheduledExecutor))

(def ^:private debounce-spin (atom nil))
(def ^:private started? (atom false))
(def ^:private watcher* (atom nil))
(def ^:private base-ns *ns*)

;; configuration
(def ^:private mode* (atom :reload-ns))          ;; :reload-ns | :refresh
(def ^:private include-cljs?* (atom false))      ;; .cljs events ignored by default
(def ^:private roots* (atom ["src" "resources"]))
(def ^:private after-refresh-sym (atom nil))

;; changed namespaces collected during debounce window
(def ^:private pending-ns* (atom #{}))

(defn set-after-refresh!
  "Configure a fully-qualified symbol to call after successful reload/refresh.
  Example: (set-after-refresh! 'user/reset)"
  [sym]
  (reset! after-refresh-sym sym))

(defn set-mode!
  "Set watcher mode: :reload-ns (default) or :refresh."
  [m]
  (when-not (#{:reload-ns :refresh} m)
    (throw (ex-info "Unsupported mode" {:mode m})))
  (reset! mode* m))

(defn set-include-cljs!
  "Enable/disable reacting to .cljs changes (default false)."
  [bool]
  (reset! include-cljs?* (boolean bool)))

(defn set-roots!
  "Set the list of root directories to watch and to compute namespaces from."
  [paths]
  (reset! roots* (vec paths)))

(defn- call-after-refresh []
  (when-let [sym @after-refresh-sym]
    (try
      (let [v (resolve sym)]
        (when (and v (fn? @v))
          (@v)))
      (catch Throwable t
        (println "[watch] after-refresh error" (pr-str sym) (.getMessage t))))))

(defn- interesting-file? [^File f]
  (let [p (.getPath f)]
    (or (str/ends-with? p ".clj")
        (str/ends-with? p ".cljc")
        (and @include-cljs?* (str/ends-with? p ".cljs")))))

(defn- canonical [^File f]
  (.getCanonicalPath f))

(defn- rel-to-root ^String [^String root ^String path]
  (let [r (Paths/get root (make-array String 0))
        p (Paths/get path (make-array String 0))]
    (try
      (str (.toString (.normalize (.relativize r p))))
      (catch Throwable _ nil))))

(defn- file->ns
  "Best-effort mapping from a file path to a namespace symbol based on watched roots."
  [^File f]
  (let [path (canonical f)
        roots @roots*]
    (when-let [rel (some (fn [root]
                           (let [root-file (io/file root)]
                             (when (.exists root-file)
                               (rel-to-root (canonical root-file) path))))
                         roots)]
      (when (and rel (not (str/ends-with? rel "/")))
        (let [no-ext (str/replace rel #"\.(clj|cljc|cljs)$" "")
              ;; split into path segments
              segs0 (str/split no-ext #"[\\/]+")
              ;; drop leading language dir if present (clj/cljs/cljc)
              segs1 (let [first-seg (first segs0)]
                      (if (#{"clj" "cljs" "cljc"} first-seg)
                        (rest segs0)
                        segs0))
              ;; map file-name underscores to ns hyphens per Clojure convention
              segs  (map #(str/replace % "_" "-") segs1)
              ns-name0 (str/join "." segs)
              ;; final guard: strip accidental leading language prefix
              ns-name (if (re-matches #"^(cljs|clj|cljc)\..+" ns-name0)
                        (subs ns-name0 (inc (.indexOf ns-name0 ".")))
                        ns-name0)]
          (when (seq ns-name)
            (symbol ns-name)))))))

(defn- schedule-spin [^Runnable r]
  (some-> @debounce-spin (.cancel false))
  (reset! debounce-spin (.schedule scheduler r 120 TimeUnit/MILLISECONDS)))

(defn- do-reload-pending! []
  (let [targets (set @pending-ns*)]
    (reset! pending-ns* #{})
    (when (seq targets)
      (with-bindings {#'*ns* base-ns}
        (println "[watch] reloading ns" (str/join ", " (map str targets)))
        (doseq [ns-sym targets]
          (try
            (locking clojure.lang.RT/REQUIRE_LOCK
              (require ns-sym :reload))
            (catch Throwable t
              (println "[watch] reload error" (str ns-sym) (.getMessage t)))))
        (call-after-refresh)))))

(defn- do-refresh! []
  (with-bindings {#'*ns* base-ns}
    (try
      (println "[watch] about to refresh...")
      (let [res (tns/refresh :after 'is.simm.dev.watch/call-after-refresh)]
        (when-not (#{:ok :noop} res)
          (println "[watch] refresh result:" res)))
      (catch Throwable t
        (println "[watch] refresh exception" (.getMessage t))))))

(defn- on-event [ctx {:keys [kind ^File file]}]
  (when (and file (#{:create :modify :delete} kind) (interesting-file? file))
    (case @mode*
      :reload-ns (let [p (.getPath file)]
                   (if (str/ends-with? p ".cljs")
                     ;; For CLJS changes, avoid JVM-side (require ...) which will fail.
                     ;; Just invoke the after-refresh hook if present.
                     (schedule-spin ^Runnable #(with-bindings {#'*ns* base-ns}
                                                 (println "[watch] cljs change" p)
                                                 (call-after-refresh)))
                     (when-let [ns-sym (file->ns file)]
                       (if (#{"cljs" "clj" "cljc"} (namespace ns-sym))
                         ;; extra safety: don't try to reload namespaces under a language prefix
                         (schedule-spin ^Runnable #(with-bindings {#'*ns* base-ns}
                                                     (println "[watch] skip reload for" (str ns-sym))
                                                     (call-after-refresh)))
                         (do (swap! pending-ns* conj ns-sym)
                             (schedule-spin ^Runnable do-reload-pending!))))))
      :refresh   (schedule-spin ^Runnable do-refresh!)
      nil))
  ctx)

(defn start!
  "Start the background file watcher if not already running.
  Optionally pass a vector of root directories to watch."
  ([] (start! @roots*))
  ([paths]
   (when-not @started?
     (reset! roots* (vec paths))
     (reset! watcher*
             (hawk/watch! [{:paths paths
                            :filter (fn [_ {:keys [^File file]}]
                                      (and file (.isFile file)))
                            :handler on-event}]))
     (reset! started? true)
     (println "[watch] started for" (str/join ", " paths)
              "mode" (name @mode*)
              "cljs?" @include-cljs?*)
     :started)))

(defn stop!
  "Stop the background file watcher."
  []
  (when-let [w @watcher*]
    (hawk/stop! w)
    (reset! watcher* nil)
    (reset! started? false)
    (println "[watch] stopped")
    :stopped))

;; Auto-start when required (only if not already started)
(when-not @started?
  (start!))
