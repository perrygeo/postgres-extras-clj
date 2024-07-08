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
;;; 1300 lines of SQL + 2 lines of hugsql macro => 35+ fns 
;;;

(hugsql/def-db-fns "sql/postgres_extras.sql")
(hugsql/def-db-fns "sql/data_dictionary.sql")
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
         calls
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
         outliers
         records-rank
         seq-scans
         table-cache-hit
         table-indexes-size
         table-size
         total-index-size
         total-table-size
         unused-indexes
         vacuum-stats)

;;;
;;; Stats
;;;

(defn read-stats
  "Query postgres instance for all available diagnostic information.
  This will invoke all of the public, read-only diagnostic queries in the namespace.

  The :outliers and :calls stats require enabling the pg-stat-statements extension,
  otherwise they will log a warning and return nil."
  [db & {:keys [limit] :or {limit 10}}]
  (let [stats {:all-locks (all-locks db)
               :bloat (bloat db)
               :blocking (blocking db)
               :cache-hit (cache-hit db)
               :calls (try
                        (calls db {:limit limit})
                        (catch PSQLException e (log/warn (format-psql-exc e))))
               :connections (connections db)
               :db-settings (db-settings db)
               :duplicate-indexes (duplicate-indexes db)
               :extensions (extensions db)
               :index-cache-hit (index-cache-hit db)
               :index-size (index-size db)
               :index-usage (index-usage db)
               :locks (locks db)
               :long-running-queries (long-running-queries db)
               :null-indexes (null-indexes db)
               :outliers (try
                           (outliers db {:limit limit})
                           (catch PSQLException e (log/warn (format-psql-exc e))))
               :records-rank (records-rank db)
               :seq-scans (seq-scans db)
               :table-cache-hit (table-cache-hit db)
               :table-indexes-size (table-indexes-size db)
               :table-size (table-size db)
               :total-index-size (total-index-size db)
               :total-table-size (total-table-size db)
               :unused-indexes (unused-indexes db {:min_scans 50})
               :vacuum-stats (vacuum-stats db)}]
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
  in your PostgreSQL database. Respects SQL COMMMENTS,
  thus serves as a human-readable description of your data model."
  [db]
  {:databases (databases db)
   :columns   (columns db)
   :functions (functions db)
   :indexes   (indexes db)
   :schemas   (schemas db)
   :tables    (tables db)
   :views     (views db)
   :partition-children (partition-children db)
   :partition-parents (partition-parents db)})

;;;
;;; Diagnostics
;;;

(def default-diagnostic-fns
  "Defines the critical queries and their cutoffs for the `diagnose`
  function. The value is a map containing a predicate fn (true = pass)
  to test against each result, and a description of the problem if false."
  {:duplicate-indexes {:pred  #(identity %)
                       :onfalse "Duplicate indexes detected"
                       :idfn #(identity %)}
   :bloat             {:pred #(< (:bloat %) 10)
                       :onfalse "Bloated tables"
                       :idfn #(str (:schemaname %) "." (:object_name %))}
   :index-cache-hit   {:pred #(or (> (:ratio %) 0.985) (< (:block_reads %) 10))
                                                       ;; excluding rarely used tables
                       :onfalse "Index sees too much block IO relative to buffer cache hit"
                       :idfn #(str (:schema %) "." (:name %))}
   :null-indexes      {:pred  #(or (< (:null_frac_percent %) 50) (< (:size_mb %) 1))
                       :onfalse "Index has too many nulls"          ;; excluding small tables
                       :idfn :index}
   :outliers          {:pred #(< (:prop_exec_time %) 0.50)
                       :onfalse "Detected slow query"
                       :idfn :query}
   :table-cache-hit   {:pred #(or (> (:ratio %) 0.985) (< (:block_reads %) 10))
                                                       ;; excluding rarely used tables
                       :onfalse "Table sees too much block IO relative to buffer cache hit"
                       :idfn #(str (:schema %) "." (:name %))}
   :unused-indexes    {:pred #(or (< (:size_bytes %) 10000000) (> (:scans %) 20))
                       :onfalse "Large but unused index"
                       :idfn #(str (:schemaname %) "." (:object_name %))}})

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
                   :message    (do
                                 (println k ident)
                                 (if ret
                                   (str "✓  Passed " k " " (tidy-report ident))
                                   (str "! Warning " k " " (tidy-report ident)
                                        " " onfalse "\n" %)))
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

;; see examples/with_next_jdbc.clj
