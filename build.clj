(ns build
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; Tag-is-truth versioning: the version is the `v<major>.<minor>.<patch>` git
;; tag pointing at HEAD, e.g. tag `v0.2.22` => version "0.2.22".
;;
;;   - A tagged commit builds a release jar with that version.
;;   - An untagged commit builds a local `0.0.0-SNAPSHOT` jar (smoke-test only).
;;   - `deploy` refuses to run unless HEAD is tagged.
;;
;; Cut a release with `bb release <version>` (see bb/release.clj); pushing the
;; tag triggers CI to build and deploy that exact version.

(def lib 'com.github.perrygeo/postgres-extras-clj)
(def class-dir "target/classes")

(defn- git-out
  "Run `git` with `args`, returning trimmed stdout. Throws on non-zero exit."
  [& args]
  (let [{:keys [exit out err]} (apply sh "git" args)]
    (if (zero? exit)
      (str/trim out)
      (throw (ex-info (str "git " (str/join " " args) " failed: " (str/trim err)) {})))))

(defn current-tag
  "Version string of the `v<major>.<minor>.<patch>` tag pointing at HEAD,
  or nil when HEAD is untagged."
  []
  (let [tag (first (filter #(re-matches #"v\d+\.\d+\.\d+" %)
                           (str/split-lines (git-out "tag" "--points-at" "HEAD"))))]
    (some-> tag (str/replace #"^v" ""))))

(def tag     (current-tag))
(def version (or tag "0.0.0-SNAPSHOT"))

(defn- jar-opts [opts]
  (cond-> (assoc opts
                 :lib       lib
                 :version   version
                 :jar-file  (format "target/%s-%s.jar" lib version)
                 :basis     (b/create-basis {})
                 :class-dir class-dir
                 :target    "target"
                 :src-dirs  ["src"])
    tag (assoc :scm {:tag (str "v" tag)})))

(defn jar
  "Build the JAR. Version is the git tag at HEAD (see ns docstring)."
  [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "Writing pom.xml...")
    (b/write-pom opts)
    (println "Copying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "Building JAR...")
    (b/jar opts)
    (println (str "✓ SUCCESS " (:jar-file opts))))
  opts)

(defn deploy
  "Deploy the JAR to clojars.org, relying on `CLOJARS_USERNAME` and
  `CLOJARS_PASSWORD` env vars. Refuses to deploy an untagged commit."
  [opts]
  (when-not tag
    (throw (ex-info (str "Refusing to deploy an untagged commit (version " version
                         "). Tag HEAD, e.g. `bb release 0.2.22`, and re-run.") {})))
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
