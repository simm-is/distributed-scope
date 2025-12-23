(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.deps :as t]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'is.simm/distributed-scope)
(def major 0)
(def minor 1)
(defn commit-count [] (b/git-count-revs nil))
(defn version [] (format "%d.%d.%s" major minor (commit-count)))
(def class-dir "target/classes")

(defn test "Run all the tests." [opts]
  (println "\nRunning tests...")
  (let [basis    (b/create-basis {:aliases [:test]})
        combined (t/combine-aliases basis [:test])
        cmds     (b/java-command
                  {:basis basis
                   :java-opts (:jvm-opts combined)
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- pom-template [version]
  [[:description "Distributed scope for transparent state synchronization in Clojure/ClojureScript"]
   [:url "https://github.com/simm-is/distributed-scope"]
   [:licenses
    [:license
     [:name "Eclipse Public License"]
     [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
   [:developers
    [:developer
     [:name "Christian Weilbach"]]]
   [:scm
    [:url "https://github.com/simm-is/distributed-scope"]
    [:connection "scm:git:https://github.com/simm-is/distributed-scope.git"]
    [:developerConnection "scm:git:ssh:git@github.com:simm-is/distributed-scope.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (let [v (version)]
    (assoc opts
           :lib lib
           :version v
           :jar-file  (format "target/%s-%s.jar" (name lib) v)
           :basis     (b/create-basis {})
           :class-dir class-dir
           :target    "target"
           :src-dirs  ["src"]
           :pom-data  (pom-template v))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar "Build the JAR." [_]
  (clean nil)
  (let [opts (jar-opts {})]
    (println "Building version:" (:version opts))
    (b/write-pom opts)
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (b/jar opts)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (test opts)
  (jar nil)
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
