(ns notebook
  "Babashka helpers for regenerating the pgbench example notebook and
  publishing the rendered docs to the ghpages branch.

  The full pipeline is exposed as `bb notebook`; the individual steps are
  also available as composable tasks: `bb db:up`, `bb db:pgbench`,
  `bb db:benchmark`, `bb notebook:render`, `bb notebook:copy`,
  `bb notebook:publish`."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;; --- Configuration ---------------------------------------------------------

(def ^:private db-host "localhost")
(def ^:private db-port "5432")
(def ^:private db-user "postgres")
(def ^:private db-pass "password")
(def ^:private db-name "main")
(def ^:private db-uri (str "postgresql://" db-user ":" db-pass "@" db-host ":" db-port "/" db-name))

(def ^:private compose-file "dev/infra/docker-compose.yml")
(def ^:private notebook-source "examples/pgbench_tutorial.clj")
(def ^:private render-dir "target/clay")

;; Rendered artifacts (Clay + Quarto output) and where they belong in doc/.
;; The HTML is renamed to index.html so GitHub Pages serves it at the root.
(def ^:private artifacts
  [{:from (str render-dir "/pgbench_tutorial.html")  :to "doc/index.html"}
   {:from (str render-dir "/pgbench_tutorial.qmd")   :to "doc/pgbench_tutorial.qmd"}
   {:from (str render-dir "/pgbench_tutorial_files") :to "doc/pgbench_tutorial_files"}])

(def ^:private publish-defaults
  {:branch  "ghpages"
   :worktree ".ghpages"
   :push?   true
   :message "chore: regenerate pgbench example notebook"})

;; --- Low-level helpers -----------------------------------------------------

(defn- shell-inherit
  "Run a command, streaming stdout/stderr to the terminal. Throws on non-zero exit.
  An optional leading map is merged into the process options."
  [& args]
  (let [[opts argv] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])]
    (apply p/shell (merge {:out :inherit :err :inherit} opts) argv)))

(defn- run
  "Run a command, capturing trimmed stdout/stderr. Never throws on non-zero exit."
  [& argv]
  (let [{:keys [out err] :as res}
        @(p/process (vec argv) {:out :string :err :string})]
    (-> res
        (assoc :out (some-> out str/trim)
               :err (some-> err str/trim)))))

(defn- psql-q [query]
  (run "psql" db-uri "-X" "-q" "-Atc" query))

(defn- db-ready? []
  (zero? (:exit (run "pg_isready" "-h" db-host "-p" db-port "-U" db-user "-d" db-name))))

(defn- pgbench-ready? []
  (let [{:keys [exit out]} (psql-q "select to_regclass('public.pgbench_accounts') is not null")]
    (and (zero? exit) (= "t" out))))

(defn- benchmark-ready?
  "True once a pgbench benchmark run has populated pgbench_history."
  []
  (let [{:keys [exit out]} (psql-q "select count(*) > 0 from pgbench_history")]
    (and (zero? exit) (= "t" out))))

(defn- ensure-extensions!
  "Enable extensions required by the notebook (pg_stat_statements)."
  []
  (let [{:keys [exit err]} (psql-q "CREATE EXTENSION IF NOT EXISTS pg_stat_statements")]
    (if (zero? exit)
      (println "• pg_stat_statements extension enabled.")
      (throw (ex-info (str "Could not enable pg_stat_statements extension: " err) {})))))

;; --- Pipeline steps --------------------------------------------------------

(defn db-up!
  "Ensure the pgbench PostgreSQL container is up and accepting connections."
  []
  (println "• Starting PostgreSQL via docker compose...")
  (shell-inherit "docker" "compose" "-f" compose-file "up" "-d")
  (print "• Waiting for PostgreSQL")
  (flush)
  (loop [tries 60]
    (cond
      (db-ready?) (println " — ready.")
      (pos? tries) (do (print ".") (flush) (Thread/sleep 1000) (recur (dec tries)))
      :else (throw (ex-info (str "PostgreSQL did not become ready within 60s. "
                                 "Is port " db-port " free? Run `docker compose -f " compose-file " ps`.")
                            {}))))
  (ensure-extensions!))

(defn db-pgbench!
  "Ensure the pgbench schema and at least one benchmark run are present.
  Idempotent: init is skipped when the schema exists, and the benchmark is
  skipped when pgbench_history is already populated.

  Options (optional map):
    :force?  re-run the benchmark even if it has already run (default false)"
  ([] (db-pgbench! {}))
  ([{:keys [force?] :or {force? false}}]
   (when-not (db-ready?)
     (throw (ex-info "Database is not ready. Run `bb db:up` first." {})))
   (if (pgbench-ready?)
     (println "• pgbench schema already present; skipping init.")
     (do (println "• Initializing pgbench (scale 10, fillfactor 100)...")
         (shell-inherit {:extra-env {"PGPASSWORD" db-pass}}
                        "pgbench" "-h" db-host "-p" db-port "-U" db-user
                        "-s" "10" "-F" "100" "-i" "-I" "dtgvpf" db-name)))
   (if (and (benchmark-ready?) (not force?))
     (println "• Benchmark already run; skipping (use `bb db:benchmark` to re-run).")
     (do (println "• Running pgbench benchmark (32 clients × 1000 txns)...")
         (shell-inherit {:extra-env {"PGPASSWORD" db-pass}}
                        "pgbench" "-h" db-host "-p" db-port "-U" db-user
                        "-c" "32" "-t" "1000" "-j" "8" db-name)))
   (println "• pgbench ready.")))

(defn render!
  "Render the Clay notebook to target/clay."
  []
  (println "• Rendering" notebook-source "with Clay...")
  (shell-inherit "clojure" "-M:notebook"
                 "--render"
                 "--base-target-path" render-dir
                 "--config-map" "{:format [:quarto :html]}"
                 notebook-source)
  (println "• Rendered to" render-dir))

(defn- copy-artifact!
  "Copy a rendered artifact (file or directory) to its destination."
  [from to]
  (fs/delete-tree to)
  (if (fs/directory? from)
    (fs/copy-tree from to {:replace-existing true})
    (do (fs/create-dirs (fs/parent to))
        (fs/copy from to {:replace-existing true}))))

(defn copy!
  "Copy the rendered notebook artifacts into doc/."
  []
  (println "• Copying rendered artifacts into doc/...")
  (doseq [{:keys [from to]} artifacts]
    (when-not (fs/exists? from)
      (throw (ex-info (str "Missing rendered artifact: " from ". Run `bb notebook:render` first.") {})))
    (copy-artifact! from to)
    (println "   " from "→" to))
  (println "• doc/ updated."))

(defn- ensure-worktree! [{:keys [branch worktree]}]
  ;; Always start from a clean slate: drop any stale worktree first.
  (when (fs/exists? (fs/path worktree ".git"))
    (println "• Removing existing worktree" worktree)
    (shell-inherit "git" "worktree" "remove" "--force" worktree))
  ;; Fast-forward the local branch to origin (tolerant if it has no upstream).
  (let [{:keys [exit err]} (run "git" "fetch" "origin" (str branch ":" branch))]
    (when-not (zero? exit)
      (println "   (warning: could not fast-forward" branch "from origin:" err ")")))
  (println "• Checking out" branch "in fresh worktree" worktree)
  (shell-inherit "git" "worktree" "add" worktree branch))

(defn publish!
  "Commit doc/ to the ghpages branch (via a git worktree) and push to origin.

  Options (optional map):
    :branch   branch to publish to (default \"ghpages\")
    :worktree local worktree dir (default \".ghpages\")
    :push?    push to origin after committing (default true)
    :message  commit message (default \"chore: regenerate pgbench example notebook\")"
  ([] (publish! {}))
  ([opts]
   (let [{:keys [branch worktree push? message]} (merge publish-defaults opts)]
     (doseq [{:keys [to]} artifacts]
       (when-not (fs/exists? to)
         (throw (ex-info (str "Missing doc artifact: " to ". Run `bb notebook:copy` first.") {}))))
     (ensure-worktree! {:branch branch :worktree worktree})
     (fs/create-dirs (str worktree "/doc"))
     (doseq [{:keys [to]} artifacts]
       (copy-artifact! to (str worktree "/" to)))
     (let [{:keys [out]} (run "git" "-C" worktree "status" "--porcelain" "doc")]
       (if (str/blank? out)
         (println "• No changes in doc/; nothing to commit.")
         (do (shell-inherit "git" "-C" worktree "add" "-f" "doc")
             (shell-inherit "git" "-C" worktree "commit" "-m" message)
             (if push?
               (do (shell-inherit "git" "-C" worktree "push" "origin" branch)
                   (println "• Pushed doc/ to origin/" branch))
               (println "• Committed to" branch "(push skipped)."))))))))
