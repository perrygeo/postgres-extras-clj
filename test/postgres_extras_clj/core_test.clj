(ns postgres-extras-clj.core-test
  "Test the core postgres-extras-clj functions"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]
   [next.jdbc :as jdbc]
   [postgres-extras-clj.core :as pgex])
  (:import
   (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

;;; Globals

(set! *warn-on-reflection* true)

(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic db)

;;; Use an embedded (but real!) postgres instance.

(defn embedded-pg
  "Fixture to start a PostgreSQL database in a temp dir with
  pg_stat_statements preloaded via shared_preload_libraries, then
  create the extension before running tests."
  [f]
  (let [datadir (str "/tmp/testdb-" (System/currentTimeMillis))
        test-pg (.start (-> (EmbeddedPostgres/builder)
                            (.setDataDirectory datadir)
                            (.setServerConfig "shared_preload_libraries" "pg_stat_statements")))
        ds      (jdbc/get-datasource
                 {:jdbcUrl (str (.getJdbcUrl ^EmbeddedPostgres test-pg "postgres" "postgres")
                                "&password=password")})]
    (with-open [conn (jdbc/get-connection ds)]
      (jdbc/execute-one! conn ["CREATE EXTENSION IF NOT EXISTS pg_stat_statements"]))
    (binding [db ds]
      (f)
      (.close test-pg))))

;; Register the postgres fixture to be called once, wrapping ALL tests in the namespace

(use-fixtures :once embedded-pg)

;; Utils

(def test-fns
  {:table-cache-hit
   {:pred    #(> (:ratio %) 0.985)
    :onfalse "Table sees high block IO relative to buffer cache hit"
    :idfn    :table}})

(defn mock-stats-buffered []
  {:table-cache-hit
   [{:block_reads 1
     :schema      "public"
     :table       "table-with-buffered-cache"
     :ratio       0.99}]})

(defn mock-stats-blockio []
  {:table-cache-hit
   [{:block_reads 100
     :schema      "public"
     :table       "table-with-much-block-io"
     :ratio       0.45}]})

(deftest smoketest
  (testing
   "Healthy?"
    (is (str/starts-with? (:version (pgex/health-check db)) "PostgreSQL"))))

(deftest diagnose-stats-test
  (testing
   "Shouldn't get warnings on an empty database using the defaults"
    (is (empty? (pgex/diagnose-warnings db))))

  (testing
   "empty stats should produce empty warnings"
    (is (empty? (pgex/diagnose-warnings {}))))

  (testing
   "diagnostic-fns with a healthy table produces no warnings"
    (is (empty? (pgex/diagnose-warnings (mock-stats-buffered)
                                        :diagnostic-fns test-fns))))

  (testing
   "diagnostic-fns with an IO-laden table fails the :ratio predicate"
    (is (some? (pgex/diagnose (mock-stats-blockio)
                              :diagnostic-fns test-fns)))))

; TODO requires pg_stat_statements extension, see above
#_(deftest pg_stat_statements_related
    (testing "functions that require pg_stat_statements"
      (is (not (nil? (pgex/calls db {:limit 10}))))))

(deftest postgres-extras

  (testing
   "read all stats."
    ; this just confirms that queries are syntactically
    ;   correct, and the hugsql macros are wired up correctly.
    ;   the contents may not be meaningful.
    (is (= 43 (count (keys (pgex/read-stats db))))))

  (testing
   "Even an 'empty' postgres database isn't really empty."
    (is (> (count (pgex/tables db)) 25)))

  (testing
   "active database connections, at least one since we're asking."
    (is (some? (pgex/connections db))))

  ;; The following calls are covered by the read-stats fn
  ; (pgex/all-locks db) | Queries with active locks |
  ; (pgex/blocking db) | Queries holding locks other queries are waiting to be released |
  ; (pgex/bloat db) | Table and index "bloat" in your database ordered by most wasteful |
  ; (pgex/cache-hit db) | Index and table hit rate |
  ; (pgex/calls db) | Queries that have the highest frequency of execution |
  ; (pgex/db-settings db) | Values of selected PostgreSQL settings |
  ; (pgex/duplicate-indexes db) | Multiple indexes that have the same set of columns, same opclass, expression and predicate |
  ; (pgex/extensions db) | Available and installed extensions |
  ; (pgex/index-cache-hit db) | Calculates your cache hit rate for reading indexes |
  ; (pgex/index-size db) | The size of indexes, descending by size |
  ; (pgex/index-usage db) | Index hit rate (effective databases are at 99% and up) |
  ; (pgex/locks db) | Queries with active exclusive locks |
  ; (pgex/long-running-queries db) | All queries longer than the threshold by descending duration |
  ; (pgex/mandelbrot db) | The mandelbrot set |
  ; (pgex/null-indexes db) | Find indexes with a high ratio of NULL values |
  ; (pgex/outliers db) | Queries that have longest execution time in aggregate. |
  ; (pgex/records-rank db) | All tables and the number of rows in each ordered by number of rows descending |
  ; (pgex/seq-scans db) | Count of sequential scans by table descending by order |
  ; (pgex/table-cache-hit db) | Calculates your cache hit rate for reading tables |
  ; (pgex/table-indexes-size db) | Total size of all the indexes on each table, descending by size |
  ; (pgex/table-size db) | Size of the tables (excluding indexes), descending by size |
  ; (pgex/total-index-size db) | Total size of all indexes in MB |
  ; (pgex/total-table-size db) | Size of the tables (including indexes), descending by size |
  ; (pgex/unused-indexes db) | Unused and almost unused indexes |
  ; (pgex/vacuum-stats db) | Dead rows and whether an automatic vacuum is expected to be triggered |

  ; Not sure how we'd test this, TODO
  ; (pgex/kill-all! db)
  )
(deftest data-dict

  (testing
   "A few of the default system tables have COMMENTs, aka descriptions"
    ;; for most everything else, you'll need to add your own comments
    (let [schemas-with-comments (filter
                                 #(:description %)
                                 (map
                                  #(let [d (:description %) n (:schema %)]
                                     {:description d :schema n})
                                  (pgex/schemas db)))]
      (is (>= (count schemas-with-comments) 3))))

  (testing
   "read the full data dictionary"
    ; this just confirms that they are syntactically
    ;   correct, and the hugsql macros are wired up correctly.
    ;   the contents may not be meaningful.
    (is (= 9 (count (keys (pgex/read-data-dictionary db))))))

  (testing
   "system objects are filtered by default and included with the flag"
    (let [dd-default (pgex/read-data-dictionary db)
          dd-all     (pgex/read-data-dictionary db {:include-system-objects? true})]
      (is (every? (complement :system_object) (:schemas dd-default)))
      (is (some :system_object (:schemas dd-all)))
      (is (every? (complement :system_object) (:tables dd-default)))
      (is (every? (complement :system_object) (:columns dd-default)))))

  ; The following calls are covered by the read-data-dictionary fn
  ; | (columns db) | List all database column objects |
  ; | (databases db) | List all databases |
  ; | (functions db) | List all function objects in current database |
  ; | (indexes db) | List all index objects in current database |
  ; | (schemas db) | List all shemas in current database |
  ; | (partition-children db) | List all child partitions in current database |
  ; | (partition-parents db) | List all parent partitions in current database |
  ; | (tables db) | List all table objects in current database |
  ; | (views db) | List all view objects in current database |
  )

(deftest pg-stat-statements-based
  (testing "outliers returns the expected columns"
    (let [rows (pgex/outliers db {:limit 10})]
      (is (seq rows))
      (is (every? (fn [row]
                    (and (string? (:query row))
                         (number? (:exec_time_ms row))
                         (number? (:prop_exec_time row))
                         (integer? (:calls row))
                         (number? (:sync_io_time_ms row))))
                  rows))))

  (testing "calls returns the expected columns"
    (let [rows (pgex/calls db {:limit 10})]
      (is (seq rows))
      (is (every? (fn [row]
                    (and (string? (:query row))
                         (number? (:exec_time_ms row))
                         (number? (:exec_time_ratio row))
                         (integer? (:calls row))
                         (number? (:sync_io_time_ms row))))
                  rows))))

  (testing "sync io time is non-negative"
    (is (every? #(>= (:sync_io_time_ms %) 0) (pgex/outliers db {:limit 10})))
    (is (every? #(>= (:sync_io_time_ms %) 0) (pgex/calls db {:limit 10})))))

(deftest pghero-diagnostics
  (testing "invalid-indexes returns a seq with string schema/index keys"
    (let [rows (pgex/invalid-indexes db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:index %))
                        (integer? (:index_size_bytes %)))
                  rows))))

  (testing "invalid-constraints returns a seq with string keys"
    (let [rows (pgex/invalid-constraints db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %))
                        (string? (:constraint %)))
                  rows))))

  (testing "sequences returns a seq with used_ratio numeric when present"
    (let [rows (pgex/sequences db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:sequence %))
                        (or (nil? (:used_ratio %))
                            (number? (:used_ratio %))))
                  rows))))

  (testing "replication-lag returns a single numeric lag_seconds row"
    (let [row (pgex/replication-lag db)]
      (is (map? row))
      (is (number? (:lag_seconds row)))))

  (testing "replication-slots returns a seq (possibly empty on embedded)"
    (is (vector? (pgex/replication-slots db))))

  (testing "transaction-id returns non-empty per-database rows"
    (let [rows (pgex/transaction-id db)]
      (is (seq rows))
      (is (every? #(and (string? (:datname %))
                        (number? (:xid_age %))
                        (number? (:wrap_risk_ratio %)))
                  rows))))

  (testing "maintenance returns a seq with string schema/table keys"
    (let [rows (pgex/maintenance db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %)))
                  rows))))

  (testing "unused-tables returns a seq with size_bytes"
    (let [rows (pgex/unused-tables db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %))
                        (integer? (:size_bytes %)))
                  rows))))

  (testing "missing-indexes returns a seq (possibly empty on fresh db)"
    (let [rows (pgex/missing-indexes db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %))
                        (number? (:index_usage_ratio %)))
                  rows))))

  (testing "connection-states returns non-empty grouped rows"
    (let [rows (pgex/connection-states db)]
      (is (seq rows))
      (is (every? #(integer? (:connections %)) rows)))))

(deftest postgres-native-diagnostics
  (testing "wal-stats returns a single row of WAL counters"
    (let [row (pgex/wal-stats db)]
      (is (map? row))
      (is (integer? (:wal_records row)))
      (is (number? (:wal_bytes row)))
      (is (integer? (:wal_buffers_full row)))))

  (testing "wal-archiver returns a single row with archive mode"
    (let [row (pgex/wal-archiver db)]
      (is (map? row))
      (is (integer? (:archived_count row)))
      (is (integer? (:failed_count row)))
      (is (string? (:archive_mode row)))))

  (testing "roles returns non-empty rows with privilege flags"
    (let [rows (pgex/roles db)]
      (is (seq rows))
      (is (every? #(and (string? (:role %))
                        (boolean? (:super %))
                        (boolean? (:can_login %)))
                  rows))))

  (testing "database-stats returns non-empty rows with ratios"
    (let [rows (pgex/database-stats db)]
      (is (seq rows))
      (is (every? #(and (string? (:database %))
                        (number? (:cache_hit_ratio %))
                        (number? (:rollback_ratio %)))
                  rows))))

  (testing "replication-stats returns a seq (possibly empty on embedded)"
    (is (vector? (pgex/replication-stats db))))

  (testing "bgwriter-stats returns a single row of checkpoint counters"
    (let [row (pgex/bgwriter-stats db)]
      (is (map? row))
      (is (integer? (:checkpoints_timed row)))
      (is (number? (:checkpoint_req_ratio row)))))

  (testing "unindexed-foreign-keys returns a seq with string keys"
    (let [rows (pgex/unindexed-foreign-keys db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %))
                        (string? (:constraint %)))
                  rows))))

  (testing "tables-without-primary-key returns a seq with string keys"
    (let [rows (pgex/tables-without-primary-key db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %)))
                  rows))))

  (testing "autovacuum-disabled returns a seq (possibly empty)"
    (is (vector? (pgex/autovacuum-disabled db))))

  (testing "idle-in-transaction returns a seq (possibly empty)"
    (is (vector? (pgex/idle-in-transaction db))))

  (testing "unlogged-tables returns a seq with string keys"
    (let [rows (pgex/unlogged-tables db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:table %)))
                  rows))))

  (testing "sequence-origin returns a seq with origin classifications"
    (let [rows (pgex/sequence-origin db)]
      (is (vector? rows))
      (is (every? #(and (string? (:schema %))
                        (string? (:sequence %))
                        (contains? #{"serial"
                                     "identity always"
                                     "identity by default"
                                     "explicit"}
                                   (:origin %)))
                  rows)))))

(deftest diagnostic-fixtures
  (jdbc/execute! db ["CREATE SCHEMA IF NOT EXISTS pgext_test"])
  (try
    (jdbc/execute! db ["CREATE TABLE pgext_test.no_pk (id integer)"])
    (jdbc/execute! db ["CREATE UNLOGGED TABLE pgext_test.unlogged_t (id integer)"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.parent (id integer PRIMARY KEY)"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.child_unindexed (parent_id integer REFERENCES pgext_test.parent (id))"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.child_indexed (parent_id integer REFERENCES pgext_test.parent (id))"])
    (jdbc/execute! db ["CREATE INDEX child_indexed_parent_id_idx ON pgext_test.child_indexed (parent_id)"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.no_autovac (id integer) WITH (autovacuum_enabled = false)"])
    (jdbc/execute! db ["CREATE SEQUENCE pgext_test.small_seq AS integer"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.serial_t (id serial PRIMARY KEY)"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.identity_t (id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY)"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.identity_default_t (id integer GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)"])
    (jdbc/execute! db ["CREATE TABLE pgext_test.not_valid (id integer)"])
    (jdbc/execute! db ["ALTER TABLE pgext_test.not_valid ADD CONSTRAINT positive_id CHECK (id > 0) NOT VALID"])

    (testing "tables-without-primary-key finds a table without a PK"
      (is (some #(= "no_pk" (:table %)) (pgex/tables-without-primary-key db))))

    (testing "unlogged-tables finds an unlogged table"
      (is (some #(= "unlogged_t" (:table %)) (pgex/unlogged-tables db))))

    (testing "unindexed-foreign-keys flags the unindexed FK but not the indexed one"
      (let [rows (pgex/unindexed-foreign-keys db)]
        (is (some #(and (= "child_unindexed" (:table %))
                        (= "child_unindexed_parent_id_fkey" (:constraint %)))
                  rows))
        (is (not-any? #(= "child_indexed" (:table %)) rows))))

    (testing "autovacuum-disabled finds a table with autovacuum disabled"
      (is (some #(= "no_autovac" (:table %)) (pgex/autovacuum-disabled db))))

    (testing "sequences finds the integer sequence"
      (let [rows (pgex/sequences db)]
        (is (some #(and (= "small_seq" (:sequence %))
                        (= "integer" (:data_type %)))
                  rows))))

    (testing "sequence-origin distinguishes serial from identity columns"
      (let [rows (pgex/sequence-origin db)
            by-seq (into {} (map (juxt :sequence :origin) rows))]
        (is (= "serial" (get by-seq "serial_t_id_seq")))
        (is (= "identity always" (get by-seq "identity_t_id_seq")))
        (is (= "identity by default" (get by-seq "identity_default_t_id_seq")))
        (is (= "explicit" (get by-seq "small_seq")))))

    (testing "invalid-constraints finds the NOT VALID check constraint"
      (is (some #(= "positive_id" (:constraint %)) (pgex/invalid-constraints db))))

    (finally
      (jdbc/execute! db ["DROP SCHEMA IF EXISTS pgext_test CASCADE"]))))

(deftest timescaledb-system-schemas
  (testing "TimescaleDB internal schemas are flagged as system objects and filtered by default"
    ;; Ensure a clean slate in case a previous run left these behind.
    (jdbc/execute! db ["DROP SCHEMA IF EXISTS _timescaledb_internal CASCADE"])
    (jdbc/execute! db ["DROP SCHEMA IF EXISTS timescaledb_information CASCADE"])
    (jdbc/execute! db ["DROP SCHEMA IF EXISTS timescaledb_experimental CASCADE"])
    (try
      (jdbc/execute! db ["CREATE SCHEMA _timescaledb_internal"])
      (jdbc/execute! db ["CREATE SCHEMA timescaledb_information"])
      (jdbc/execute! db ["CREATE SCHEMA timescaledb_experimental"])
      (let [ts-schemas (filter #(re-find #"timescaledb" (:schema %))
                               (pgex/schemas db))]
        (is (= 3 (count ts-schemas)))
        (is (every? :system_object ts-schemas))
        (is (not-any? #(re-find #"timescaledb" (:schema %))
                      (:schemas (pgex/read-data-dictionary db)))))
      (finally
        (jdbc/execute! db ["DROP SCHEMA IF EXISTS _timescaledb_internal CASCADE"])
        (jdbc/execute! db ["DROP SCHEMA IF EXISTS timescaledb_information CASCADE"])
        (jdbc/execute! db ["DROP SCHEMA IF EXISTS timescaledb_experimental CASCADE"])))))

(comment
  ;; for testing another database,
  ;; instead of the embedded zonky db
  (def db
    (jdbc/get-datasource
     {:dbtype   "postgresql"
      :dbname   "main"
      :host     "localhost"
      :port     5432
      :user     "postgres"
      :password "password"})))

(comment
  (require '[clojure.reflect :refer [reflect]])
  (.getMethods (.getClass embedded-pg))
  (bean embedded-pg)
  (:members (reflect embedded-pg))
  (.close embedded-pg)
  (reflect (.getPostgresDatabase embedded-pg))
  (.startPostmaster embedded-pg)
  (with-open [con (jdbc/get-connection db)]
    (jdbc/execute-one! con ["select * from  pg_stat_statements;"])))
