(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; MAJOR.MINOR.COMMIT versioning
(def lib 'com.github.perrygeo/postgres-extras-clj)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")

(defn- jar-opts [opts]
  (assoc opts
         :lib lib :version version
         :jar-file (format "target/%s-%s.jar" lib version)
         :scm {:tag (str "v" version)}
         :basis (b/create-basis {})
         :class-dir class-dir
         :target "target"
         :src-dirs ["src"]))

(defn jar "Build the JAR." [opts]
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
  "Deploy the JAR to clojars.org, 
  relies on `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` env vars."
  [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
