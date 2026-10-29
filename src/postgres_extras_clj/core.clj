(ns postgres-extras-clj.core
  "A namespace of useful functions for inspecting
  PostgreSQL databases in Clojure.

   The SQL query logic is based on

     * https://github.com/heroku/heroku-pg-extras/tree/main
     * https://github.com/pawurb/ecto_psql_extras/tree/main
     * https://github.com/rustprooflabs/pgdd

   Queries ported to HugSQL syntax to access them
   as Clojure data structures."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hugsql.core :as hugsql])
  (:import [org.postgresql.util PSQLException]))

;;;
;;; Utils
;;;

(defn- format-psql-exc [e]
  (first (str/split (str e) #"\n")))

(defn- tidy-report
  "Limit string to 200 chars and strip all contiguous whitespace.
  This might mangle some code printed to stdout,
  so don't rely on the output and stay brief."
  [w]
  (->> (-> (subs w 0 (min (count w) 200))
           (str/split  #"\s"))
       (filter #(not (str/blank? %)))
       (str/join " ")))

;;;
;;; Here's where the hugsql magic happens
;;; SQL files get converted into their respective clojure fns
;;;
(declare columns
         databases
         functions
         indexes
         schemas
         partition-children
         partition-parents
         tables
         views
         all-locks
         bloat
         blocking
         cache-hit
         calls-legacy
         calls-pg17
         connections
         db-settings
         duplicate-indexes
         extensions
         health-check
         index-cache-hit
         index-size
         index-usage
         kill-all!
         locks
         long-running-queries
         mandelbrot
         null-indexes
         outliers-legacy
         outliers-pg17
         records-rank
         seq-scans
         server-version-num
         table-cache-hit
         table-indexes-size
         table-size
         total-index-size
         total-table-size
         unused-indexes
         vacuum-stats

         ;; pghero.sql
         connection-states
         invalid-constraints
         invalid-indexes
         maintenance
         missing-indexes
         replication-lag
         replication-slots
         sequences
         transaction-id
         unused-tables

         ;; postgres_native.sql
         autovacuum-disabled
         bgwriter-stats-legacy
         bgwriter-stats-pg17
         database-stats
         idle-in-transaction
         replication-stats
         roles
         sequence-origin
         tables-without-primary-key
         unindexed-foreign-keys
         unlogged-tables
         wal-archiver
         wal-stats

         ;; ddl.sql
         ddl-table-columns
         ddl-table-options
         ddl-table-constraints
         ddl-index-defs
         ddl-view-defs
         ddl-function-defs
         ddl-trigger-defs
         ddl-rule-defs
         ddl-comments)

(hugsql/def-db-fns "sql/postgres_extras.sql")

(hugsql/def-db-fns "sql/data_dictionary.sql")

(hugsql/def-db-fns "sql/pghero.sql")

(hugsql/def-db-fns "sql/postgres_native.sql")

(hugsql/def-db-fns "sql/ddl.sql")

(defn- pg-version-num
  "Numeric PostgreSQL server version, e.g. 170005 for 17.5."
  [db]
  (:version_num (server-version-num db)))

(defn calls
  "Queries that have the highest frequency of execution."
  [db {:keys [limit] :or {limit 10}}]
  (if (>= (pg-version-num db) 170000)
    (calls-pg17 db {:limit limit})
    (calls-legacy db {:limit limit})))

(defn outliers
  "Queries that have longest execution time in aggregate."
  [db {:keys [limit] :or {limit 10}}]
  (if (>= (pg-version-num db) 170000)
    (outliers-pg17 db {:limit limit})
    (outliers-legacy db {:limit limit})))

(defn bgwriter-stats
  "Checkpoint and background writer activity."
  [db]
  (if (>= (pg-version-num db) 170000)
    (bgwriter-stats-pg17 db)
    (bgwriter-stats-legacy db)))

;;;
;;; Stats
;;;

(defn read-stats
  "Query postgres instance for all available diagnostic information.
  This will invoke all of the public, read-only diagnostic queries in the namespace.

  The :outliers and :calls stats require enabling the pg-stat-statements extension,
  otherwise they will log a warning and return nil."
  [db & {:keys [limit] :or {limit 10}}]
  (let [stats {:all-locks                  (all-locks db)
               :autovacuum-disabled        (autovacuum-disabled db)
               :bloat                      (bloat db)
               :blocking                   (blocking db)
               :cache-hit                  (cache-hit db)
               :calls                      (try
                                             (calls db {:limit limit})
                                             (catch PSQLException e (log/warn (format-psql-exc e))))
               :connection-states          (connection-states db)
               :connections                (connections db)
               :database-stats             (database-stats db)
               :db-settings                (db-settings db)
               :duplicate-indexes          (duplicate-indexes db)
               :extensions                 (extensions db)
               :idle-in-transaction        (idle-in-transaction db)
               :index-cache-hit            (index-cache-hit db)
               :index-size                 (index-size db)
               :index-usage                (index-usage db)
               :invalid-constraints        (invalid-constraints db)
               :invalid-indexes            (invalid-indexes db)
               :locks                      (locks db)
               :long-running-queries       (long-running-queries db)
               :maintenance                (maintenance db)
               :missing-indexes            (missing-indexes db)
               :null-indexes               (null-indexes db)
               :outliers                   (try
                                             (outliers db {:limit limit})
                                             (catch PSQLException e (log/warn (format-psql-exc e))))
               :records-rank               (records-rank db)
               :replication-slots          (replication-slots db)
               :replication-stats          (replication-stats db)
               :roles                      (roles db)
               :seq-scans                  (seq-scans db)
               :sequence-origin            (sequence-origin db)
               :sequences                  (sequences db)
               :table-cache-hit            (table-cache-hit db)
               :table-indexes-size         (table-indexes-size db)
               :table-size                 (table-size db)
               :tables-without-primary-key (tables-without-primary-key db)
               :total-index-size           (total-index-size db)
               :total-table-size           (total-table-size db)
               :transaction-id             (transaction-id db)
               :unindexed-foreign-keys     (unindexed-foreign-keys db)
               :unlogged-tables            (unlogged-tables db)
               :unused-indexes             (unused-indexes db {:min_scans 50})
               :unused-tables              (unused-tables db)
               :vacuum-stats               (vacuum-stats db)}]
    (into {} (map (fn [[k v]]
                    [k (if (nil? v)
                         nil
                         (take limit v))])
                  stats))))

;;;
;;; Data Dictionary
;;;

(defn read-data-dictionary
  "Create a data dictionary summarizing all major objects
  in your PostgreSQL database. Respects SQL COMMENTS,
  thus serves as a human-readable description of your data model.

  By default, system objects (schemas in pg_catalog,
  information_schema, pg_*, and TimescaleDB's _timescaledb_*,
  timescaledb_information, and timescaledb_experimental schemas)
  are excluded. Pass :include-system-objects? true to include them."
  [db & {:keys [include-system-objects?] :or {include-system-objects? false}}]
  (let [dd {:databases          (databases db)
            :columns            (columns db)
            :functions          (functions db)
            :indexes            (indexes db)
            :schemas            (schemas db)
            :tables             (tables db)
            :views              (views db)
            :partition-children (partition-children db)
            :partition-parents  (partition-parents db)}]
    (if include-system-objects?
      dd
      (into {} (map (fn [[k v]] [k (when v (remove :system_object v))]) dd)))))

;;;
;;; Diagnostics
;;;

(def default-diagnostic-fns
  "Defines the critical queries and their cutoffs for the `diagnose`
  function. The value is a map containing a predicate fn (true = pass)
  to test against each result, and a description of the problem if false."
  {:duplicate-indexes {:pred    #(identity %)
                       :onfalse "Duplicate indexes detected"
                       :idfn    #(identity %)}
   :bloat             {:pred    #(< (:bloat %) 10)
                       :onfalse "Bloated tables"
                       :idfn    #(str (:schema %) "." (:object_name %))}
   :index-cache-hit   {:pred    #(or (> (:ratio %) 0.985) (< (:block_reads %) 10))
                       ;; excluding rarely used tables
                       :onfalse "Index sees too much block IO relative to buffer cache hit"
                       :idfn    #(str (:schema %) "." (:index %))}
   :null-indexes      {:pred    #(or (< (:null_frac %) 0.5) (< (:index_size %) 1048576))
                       :onfalse "Index has too many nulls"          ;; excluding small tables
                       :idfn    :index}
   :outliers          {:pred    #(< (:prop_exec_time %) 0.50)
                       :onfalse "Detected slow query"
                       :idfn    :query}
   :table-cache-hit   {:pred    #(or (> (:ratio %) 0.985) (< (:block_reads %) 10))
                       ;; excluding rarely used tables
                       :onfalse "Table sees too much block IO relative to buffer cache hit"
                       :idfn    #(str (:schema %) "." (:table %))}
   :unused-indexes    {:pred    #(or (< (:index_size %) 10000000) (> (:index_scans %) 20))
                       :onfalse "Large but unused index."
                       :idfn    :index}})

(defn diagnose
  "Run assertions on stats and return a seq of result maps.
  Iterate through the diagnostic functions, run them,
  then iterate through each record, applying the predicate.
  Returns a flat list of result maps."
  [stats & {:keys [diagnostic-fns] :or {diagnostic-fns default-diagnostic-fns}}]
  (flatten
   (map (fn [[k {:keys [pred onfalse idfn]}]]
          (map #(let [ret (pred %)
                      ident (idfn %)]
                  {:diagnostic k
                   :pass       ret
                   :message    (if ret
                                 (str "Passed " k " " (tidy-report ident))
                                 (str "Warning " k " " (tidy-report ident)
                                      " " onfalse))
                   :data       (when (not ret) %)})
               (get stats k)))
        diagnostic-fns)))

(defn diagnose-warnings
  "Run assertions on stats and return a seq of result maps.
  Iterate through the diagnostic functions,
  then through the results, applying the predicate.
  Returns a flat list of result maps, filtered to contain
  only actionable warnings. An empty return sequence indicates
  all your dianostics passed."
  [stats & {:keys [diagnostic-fns] :or {diagnostic-fns default-diagnostic-fns}}]
  (filter #(not (:pass %)) (diagnose stats :diagnostic-fns diagnostic-fns)))

;; see examples/pgbench_tutorial.clj
