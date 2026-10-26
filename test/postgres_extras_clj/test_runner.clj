(ns postgres-extras-clj.test-runner
  "Matrix test driver for `clj -X:test`.

  Runs the Kaocha suite once per supported PostgreSQL version, then runs
  code coverage once on the latest version.

  Each version needs its own JVM process because Zonky embedded-postgres binaries
  artifact embed exactly one PostgreSQL build in a single classpath resource."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def supported-postgres-versions
  "Currently supported PostgreSQL versions."
  ["14.24.0" "15.19.0" "16.15.0" "17.11.0" "18.6.0"])

(def latest-postgres-version
  (last supported-postgres-versions))

(defn- platform-suffix
  "Maps the current OS/architecture to Zonky's binaries artifact suffix."
  []
  (let [os    (str/lower-case (System/getProperty "os.name"))
        arch  (str/lower-case (System/getProperty "os.arch"))
        os'   (cond
                (str/includes? os "linux") "linux"
                (str/includes? os "mac")   "darwin"
                (str/includes? os "win")   "windows"
                :else (throw (ex-info "Unsupported OS" {:os os})))
        arch' (cond
                (re-find #"amd64|x86_64|x64" arch) "amd64"
                (re-find #"aarch64|arm64" arch)    "arm64v8"
                :else (throw (ex-info "Unsupported architecture" {:arch arch})))]
    (str os' "-" arch')))

(defn- binaries-artifact []
  (symbol (str "io.zonky.test.postgres/embedded-postgres-binaries-" (platform-suffix))))

(defn- sdeps [version]
  (pr-str {:deps {(binaries-artifact) {:mvn/version version}}}))

(defn- project-dir []
  (io/file (System/getProperty "user.dir")))

(defn- run-command
  [args]
  (let [pb (doto (ProcessBuilder. ^java.util.List args)
             (.directory (project-dir))
             (.inheritIO))]
    (.waitFor (.start pb))))

(defn- run-test-version [version]
  (println (format "\n===== PostgreSQL %s =====" version))
  (run-command ["clojure" "-Sdeps" (sdeps version) "-X:test-run"]))

(defn- run-coverage []
  (println (format "\n===== Coverage (PostgreSQL %s) =====" latest-postgres-version))
  (run-command ["clojure" "-Sdeps" (sdeps latest-postgres-version)
                "-M:coverage" "--config-file" "tests.coverage.edn"]))

(defn run
  "Test all PostgreSQL versions and run coverage on the latest."
  [{:keys [postgres-versions]
    :or   {postgres-versions supported-postgres-versions}}]
  (println (format "Testing against PostgreSQL versions: %s"
                   (str/join ", " postgres-versions)))
  (flush)
  (let [test-results  (mapv (fn [v] [v (run-test-version v)]) postgres-versions)
        coverage-exit (run-coverage)
        failed        (filterv (fn [[_ exit]] (not (zero? exit))) test-results)]
    (println "\n===== Summary =====")
    (doseq [[v exit] test-results]
      (println (format "  PostgreSQL %-8s %s" v (if (zero? exit) "OK" (str "FAILED (exit " exit ")")))))
    (println (format "  coverage        %s" (if (zero? coverage-exit) "OK" (str "FAILED (exit " coverage-exit ")"))))
    (flush)
    (System/exit (if (or (seq failed) (not (zero? coverage-exit))) 1 0))))
