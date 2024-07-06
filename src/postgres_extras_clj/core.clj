(ns postgres-extras-clj.core
  "A toolbox for inspecting PostgreSQL databases in Clojure.
   
   The SQL query logic is based entirely on the great work done by these three projects:
     * https://github.com/heroku/heroku-pg-extras/tree/main
     * https://github.com/pawurb/ecto_psql_extras/tree/main
     * https://github.com/rustprooflabs/pgdd
   
   Queries ported to HugSQL syntax to access them as Clojure data structures. 
   
   See also `sql/*.sql`, where the query logic is defined."
  ;; :-( TODO kondo doesn't know about hugsql macros
  {:clj-kondo/ignore [:unresolved-symbol]}
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hugsql.core :as hugsql])
  (:import [org.postgresql.util PSQLException]))

;; Here's where the magic happens;
;; SQL queries get converted into their respective clojure fns
(hugsql/def-db-fns "sql/postgres_extras.sql")
(hugsql/def-db-fns "sql/data_dictionary.sql")

(defn- format-psql-exc [e]
  (first (str/split (str e) #"\n")))

(comment
  (format-psql-exc (throw (Exception. "wat"))))

(defn read-stats
  "Query postgres instance for all available diagnostic information.
  This will invoke all of the public, read-only diagnostic queries in the namespace.
  Based on ecto_psql_extras.
  The :outliers and :calls stats require enabling the pg-stat-statements extension,
  otherwise they will log a warning and return nil."
  [db & {:keys [limit] :or {limit 10}}]
  {:all-locks (all-locks db)
   :bloat (take limit (bloat db))
   :blocking (blocking db)
   :cache-hit (cache-hit db)
   :calls (try
            (outliers db {:limit limit})
            (catch PSQLException e (log/warn (format-psql-exc e))))
   :connections (connections db)
   :db-settings (db-settings db)
   :duplicate-indexes (duplicate-indexes db)
   :extensions (extensions db)
   :health-check (health-check db)
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
   :vacuum-stats (vacuum-stats db)})

(defn read-data-dictionary
  "Create a data dictionary summarizing all major objects
  in your PostgreSQL database. Respects SQL COMMMENTS,
  thus serves as a human-readable description of your data model.
  Based on pgdd."
  [db]
  {:databases (databases db)
   :columns   (columns db)
   :functions (functions db)
   :schemas   (schemas db)
   :tables    (tables db)
   :views     (views db)
   :partition-children (partition-children db)
   :partition-parents (partition-parents db)})

(def default-diagnostic-fns
  "Defines the critical queries and their cutoffs for the `diagnose`
  function. The value is a map containing a predicate fn to test against
  each result, and a description of the problem if it fails."
  {:duplicate-indexes {:pred  #(identity %)
                       :desc "Duplicate indexes detected"
                       :idfn #(identity %)}
   :bloat             {:pred #(< (:bloat %) 10)
                       :desc "Bloated tables"
                       :idfn #(str (:schemaname %) "." (:object_name %))}
   :index-cache-hit   {:pred #(or (> (:ratio %) 0.985) (< (:block_reads %) 10))
                       :desc "Index sees high block IO relative to buffer cache hit"
                       :idfn #(str (:schema %) "." (:name %))}
   :null-indexes      {:pred  #(or (< (:size_mb %) 1) (< (:null_frac_percent %) 50))
                       :desc "Null indexes too high"
                       :idfn :index}
   :outliers          {:pred #(< (:prop_exec_time %) 0.50)
                       :desc "Detected slow query"
                       :idfn :query}
   :table-cache-hit   {:pred #(or (> (:ratio %) 0.985) (< (:block_reads %) 10))
                       :desc "Table sees high block IO relative to buffer cache hit"
                       :idfn #(str (:schema %) "." (:name %))}
   :unused-indexes    {:pred #(and (< (:scans %) 20) (< (:size_bytes %) 10000000))
                       :desc "Large unused index"
                       :idfn #(str (:schemaname %) "." (:object_name %))}})

(defn- tidy-report
  "Limit to 200 chars and strip all contiguous whitespace.
  This might mangle some SQL printed to stdout,
  so don't rely on the output and keep your :desc short."
  [w]
  (->> (-> (subs w 0 (min (count w) 200))
           (str/split  #"\s"))
       (filter #(not (str/blank? %)))
       (str/join " ")))

(defn diagnose
  "Run assertions on stats and return a seq of result maps.
  Iterate through the diagnostic functions, run them,
  then iterate through each record, applying the predicate. 
  Returns a flat list of result maps."
  [stats & {:keys [diagnostic-fns] :or {diagnostic-fns default-diagnostic-fns}}]
  (flatten
   (map (fn [[k {:keys [pred desc idfn]}]]
          (map #(let [ret (pred %)
                      ident (idfn %)]
                  {:diagnostic k
                   :pass       ret
                   :message    (do
                                 (println k ident)
                                 (if ret
                                   (str "✓  Passed " k " " (tidy-report ident))
                                   (str "! Warning " k " " (tidy-report ident)
                                        " " desc "\n" %)))
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
