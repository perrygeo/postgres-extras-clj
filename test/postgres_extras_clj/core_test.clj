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
    (is (= 44 (count (keys (pgex/read-stats db))))))

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
  ; (pgex/records-rank db) | All tables with row counts from both the planner and the stats collector |
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
    (is (= 12 (count (keys (pgex/read-data-dictionary db))))))

  (testing
   "system objects are filtered by default and included with the flag"
    (let [dd-default (pgex/read-data-dictionary db)
          dd-all     (pgex/read-data-dictionary db {:include-system-objects? true})]
      (is (every? (complement :system_object) (:schemas dd-default)))
      (is (some :system_object (:schemas dd-all)))
      (is (every? (complement :system_object) (:tables dd-default)))
      (is (every? (complement :system_object) (:columns dd-default)))
      (is (every? (complement :system_object) (:constraints dd-default)))
      (is (some :system_object (:constraints dd-all)))))

  ; The following calls are covered by the read-data-dictionary fn
  ; | (columns db) | List all database column objects |
  ; | (constraints db) | List all constraint objects in current database |
  ; | (databases db) | List all databases |
  ; | (functions db) | List all function objects in current database |
  ; | (indexes db) | List all index objects in current database |
  ; | (schemas db) | List all shemas in current database |
  ; | (partition-children db) | List all child partitions in current database |
  ; | (partition-parents db) | List all parent partitions in current database |
  ; | (tables db) | List all table objects in current database |
  ; | (views db) | List all view objects in current database |
  )

(deftest triggers-data-dictionary
  (jdbc/execute! db ["CREATE SCHEMA IF NOT EXISTS pgext_trig"])
  (try
    (jdbc/execute! db ["CREATE TABLE pgext_trig.widgets (id integer PRIMARY KEY, updated_at timestamptz)"])
    (jdbc/execute! db ["CREATE OR REPLACE FUNCTION pgext_trig.set_updated_at() RETURNS trigger AS $$ BEGIN NEW.updated_at := now(); RETURN NEW; END; $$ LANGUAGE plpgsql"])
    (jdbc/execute! db ["CREATE TRIGGER widgets_before_iu BEFORE INSERT OR UPDATE ON pgext_trig.widgets FOR EACH ROW WHEN (NEW.id IS NOT NULL) EXECUTE FUNCTION pgext_trig.set_updated_at()"])

    (jdbc/execute! db ["CREATE VIEW pgext_trig.widgets_v AS SELECT id, updated_at FROM pgext_trig.widgets"])
    (jdbc/execute! db ["CREATE OR REPLACE FUNCTION pgext_trig.noop() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql"])
    (jdbc/execute! db ["CREATE TRIGGER widgets_v_io INSTEAD OF INSERT OR UPDATE ON pgext_trig.widgets_v FOR EACH ROW EXECUTE FUNCTION pgext_trig.noop()"])

    (jdbc/execute! db ["CREATE TABLE pgext_trig.parent_fk (id integer PRIMARY KEY)"])
    (jdbc/execute! db ["CREATE TABLE pgext_trig.child_fk (parent_id integer REFERENCES pgext_trig.parent_fk (id))"])

    (jdbc/execute! db ["CREATE TABLE pgext_trig.cchild (parent_id integer)"])
    (jdbc/execute! db ["CREATE CONSTRAINT TRIGGER cchild_deferred_fk AFTER INSERT ON pgext_trig.cchild DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pgext_trig.noop()"])

    (testing "one row per trigger with correct shape"
      (let [row (some #(when (= "widgets_before_iu" (:trigger %)) %) (pgex/triggers db))]
        (is row)
        (is (= "BEFORE" (:timing row)))
        (is (= "ROW" (:orientation row)))
        (is (= "INSERT, UPDATE" (:events row)))
        (is (= "enabled" (:enabled row)))
        (is (= "pgext_trig.set_updated_at" (:trigger_function row)))
        (is (false? (:system_object row)))))

    (testing "INSTEAD OF trigger on a view is returned"
      (let [row (some #(when (= "widgets_v_io" (:trigger %)) %) (pgex/triggers db))]
        (is row)
        (is (= "INSTEAD OF" (:timing row)))
        (is (= "widgets_v" (:table row)))))

    (testing "FK internal triggers are excluded"
      (let [rows (pgex/triggers db)]
        (is (not-any? #(re-find #"RI_Constraint" (:trigger %)) rows))
        (is (not-any? #(= "child_fk" (:table %)) rows))))

    (testing "constraint trigger is deferrable"
      (let [row (some #(when (= "cchild_deferred_fk" (:trigger %)) %) (pgex/triggers db))]
        (is row)
        (is (true? (:deferrable row)))
        (is (true? (:initially_deferred row)))))

    (testing "read-data-dictionary includes user triggers and filters system objects"
      (let [dd (pgex/read-data-dictionary db)]
        (is (some #(= "widgets_before_iu" (:trigger %)) (:triggers dd)))
        (is (every? (complement :system_object) (:triggers dd)))))

    (finally
      (jdbc/execute! db ["DROP SCHEMA IF EXISTS pgext_trig CASCADE"]))))

(deftest constraints-data-dictionary
  (jdbc/execute! db ["CREATE SCHEMA IF NOT EXISTS pgext_con"])
  (try
    (jdbc/execute! db ["CREATE TABLE pgext_con.parent (id integer PRIMARY KEY, label text UNIQUE)"])
    (jdbc/execute! db ["CREATE TABLE pgext_con.child (id integer PRIMARY KEY, parent_id integer REFERENCES pgext_con.parent (id), amount integer CHECK (amount > 0))"])
    (jdbc/execute! db ["CREATE OR REPLACE FUNCTION pgext_con.noop() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql"])
    (jdbc/execute! db ["CREATE CONSTRAINT TRIGGER child_constr_trig AFTER INSERT ON pgext_con.child DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pgext_con.noop()"])

    (testing "constraints returns one row per constraint with correct shape"
      (let [rows (pgex/constraints db)
            pk   (some #(when (and (= "pgext_con" (:schema %))
                                   (= "parent" (:table %))
                                   (= "primary key" (:type %)))
                          %)
                       rows)
            fk   (some #(when (and (= "pgext_con" (:schema %))
                                   (= "child" (:table %))
                                   (= "foreign key" (:type %)))
                          %)
                       rows)
            chk  (some #(when (and (= "pgext_con" (:schema %))
                                   (= "child" (:table %))
                                   (= "check" (:type %)))
                          %)
                       rows)]
        (is pk)
        (is (string? (:definition pk)))
        (is (true? (:validated pk)))
        (is (false? (:system_object pk)))
        (is fk)
        (is (= "pgext_con" (:foreign_schema fk)))
        (is (= "parent" (:foreign_table fk)))
        (is chk)
        (is (re-find #"amount" (:definition chk)))))

    (testing "constraint trigger is deferrable"
      (let [row (some #(when (= "child_constr_trig" (:constraint %)) %) (pgex/constraints db))]
        (is row)
        (is (= "constraint trigger" (:type row)))
        (is (true? (:deferrable row)))
        (is (true? (:initially_deferred row)))))

    (testing "constraints filters system objects by default and includes them with the flag"
      (is (every? (complement :system_object) (pgex/constraints db)))
      (is (some :system_object (pgex/constraints db {:include-system-objects? true}))))

    (testing "read-data-dictionary includes user constraints and filters system objects"
      (let [dd (pgex/read-data-dictionary db)]
        (is (some #(= "pgext_con" (:schema %)) (:constraints dd)))
        (is (every? (complement :system_object) (:constraints dd)))))

    (finally
      (jdbc/execute! db ["DROP SCHEMA IF EXISTS pgext_con CASCADE"]))))

(deftest key-relationships-test
  (jdbc/execute! db ["CREATE SCHEMA IF NOT EXISTS pgext_keys"])
  (try
    (jdbc/execute! db ["CREATE TABLE pgext_keys.parent (id integer PRIMARY KEY, a integer, b integer, UNIQUE (a, b))"])
    (jdbc/execute! db ["CREATE TABLE pgext_keys.child (id integer PRIMARY KEY, a integer, b integer, CONSTRAINT child_parent_fk FOREIGN KEY (a, b) REFERENCES pgext_keys.parent (a, b) ON DELETE CASCADE ON UPDATE RESTRICT DEFERRABLE INITIALLY DEFERRED)"])
    (jdbc/execute! db ["CREATE TABLE pgext_keys.simple (parent_id integer REFERENCES pgext_keys.parent (id))"])

    (testing "returns one map per FK constraint with resolved columns"
      (let [rows      (pgex/key-relationships db)
            composite (some #(when (= "child_parent_fk" (:constraint %)) %) rows)
            simple    (some #(when (= "simple" (:table %)) %) rows)]
        (is composite)
        (is (= "pgext_keys" (:schema composite)))
        (is (= "child" (:table composite)))
        (is (= ["a" "b"] (:columns composite)))
        (is (= "pgext_keys" (:foreign_schema composite)))
        (is (= "parent" (:foreign_table composite)))
        (is (= ["a" "b"] (:foreign_columns composite)))
        (is (= "CASCADE" (:delete_action composite)))
        (is (= "RESTRICT" (:update_action composite)))
        (is (= "SIMPLE" (:match_type composite)))
        (is (true? (:deferrable composite)))
        (is (true? (:initially_deferred composite)))
        (is (true? (:validated composite)))
        (is (false? (:system_object composite)))
        (is simple)
        (is (= ["parent_id"] (:columns simple)))
        (is (= ["id"] (:foreign_columns simple)))))

    (testing "system objects are filtered by default and included with the flag"
      (let [all  (pgex/key-relationships db {:include-system-objects? true})
            user (pgex/key-relationships db)]
        (is (seq user))
        (is (every? (complement :system_object) user))
        ;; the include flag returns at least the user relationships
        (is (every? (fn [u] (some #(= (:constraint %) (:constraint u)) all)) user))
        ;; default == include result with system objects removed
        (is (= (vec (remove :system_object all)) user))))

    (finally
      (jdbc/execute! db ["DROP SCHEMA IF EXISTS pgext_keys CASCADE"]))))

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
                  rows))))

  (testing "stats-collector-drift tags system objects and filters them by default"
    (jdbc/execute! db ["CREATE TABLE public.drift_test (id integer)"])
    (jdbc/execute! db ["INSERT INTO public.drift_test SELECT generate_series(1, 1000)"])
    (jdbc/execute! db ["ANALYZE public.drift_test"])
    (try
      (let [rows     (pgex/stats-collector-drift db)
            all-rows (pgex/stats-collector-drift db {:include-system-objects? true})]
        (is (vector? rows))
        (is (seq rows))
        (is (every? (complement :system_object) rows))
        (is (some :system_object all-rows))
        (is (some #(= "drift_test" (:table %)) rows))
        (is (every? #(and (string? (:schema %))
                          (string? (:table %))
                          (or (nil? (:drift_probability %))
                              (number? (:drift_probability %))))
                    rows))

        ;; after ANALYZE the planner and the stats collector agree
        (let [agree (some #(when (= "drift_test" (:table %)) %) rows)]
          (is (number? (:drift_probability agree)))
          (is (< (:drift_probability agree) 0.01)))

        ;; force a small disagreement: 1000 live rows vs 1009 planner estimate
        (jdbc/execute! db ["UPDATE pg_class SET reltuples = 1009 WHERE oid = 'public.drift_test'::regclass"])
        (let [small (some #(when (= "drift_test" (:table %)) %) (pgex/stats-collector-drift db))]
          (is (< 0.008 (:drift_probability small) 0.01)))

        ;; force total disagreement: planner says zero, stats say 1000 live rows
        (jdbc/execute! db ["UPDATE pg_class SET reltuples = 0 WHERE oid = 'public.drift_test'::regclass"])
        (let [total (some #(when (= "drift_test" (:table %)) %) (pgex/stats-collector-drift db))]
          (is (= 1.0 (:drift_probability total)))))
      (finally
        (jdbc/execute! db ["DROP TABLE IF EXISTS public.drift_test"])))))

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
