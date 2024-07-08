(ns postgres-extras-clj.core-test
  "Test the core postgres-extras-clj functions"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]
   [next.jdbc :as jdbc]
   [postgres-extras-clj.core :as pgex])
  (:import
   (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

;;;
;;; Globals
;;;
(set! *warn-on-reflection* true)
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;;;
;;; Use an embedded (but real!) postgres instance.
;;;
(defonce embedded-pg (EmbeddedPostgres/start))

(def db
  (jdbc/get-datasource
   {:jdbcUrl
    (str  ;; TODO there's got to be a better way
     (.getJdbcUrl ^EmbeddedPostgres embedded-pg "postgres" "postgres")
     "&password=password")}))

; TODO Doesn't work, we'd need to restart thus lose changes (I think?)
; (err) ERROR: pg_stat_statements must be loaded via shared_preload_libraries
; (with-open [con (jdbc/get-connection db)]
;   (try
;     (jdbc/execute-one!
;      con ["CREATE EXTENSION if not exists pg_stat_statements;"])
;     (catch Throwable _)))

;;;
;;; Utils
;;;
(def test-fns
  {:table-cache-hit
   {:pred #(> (:ratio %) 0.985)
    :onfalse "Table sees high block IO relative to buffer cache hit"
    :idfn :name}})

(defn mock-stats-buffered []
  {:table-cache-hit
   [{:block_reads 1
     :schema "public"
     :name "table-with-buffered-cache"
     :ratio 0.99}]})

(defn mock-stats-blockio []
  {:table-cache-hit
   [{:block_reads 100
     :schema "public"
     :name "table-with-much-block-io"
     :ratio 0.45}]})

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

(deftest postgres-extras

  (testing
   "read all stats."
    ; this just confirms that queries are syntactically
    ;   correct, and the hugsql macros are wired up correctly.
    ;   the contents may not be meaningful.
    (is (= 25 (count (keys (pgex/read-stats db))))))

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
                                  #(let [d (:description %) n (:s_name %)]
                                     {:description d :name n})
                                  (pgex/schemas db)))]
      (is (>= (count schemas-with-comments) 3))))

  (testing
   "read the full data dictionary"
    ; this just confirms that they are syntactically
    ;   correct, and the hugsql macros are wired up correctly.
    ;   the contents may not be meaningful.
    (is (= 9 (count (keys (pgex/read-data-dictionary db))))))

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

(comment
  ; Some stats are skipped (nil) due to the lack of pg_stat_statements extension in test.
  ; Depending on whether your test database has pg_stat_statements or not,
  ; you'll want to run one of these:

  ; no extension, outliers and call will always be nil.
  ; (testing
  ;  "read all stats, filter for nils."
  ;   (is (= '([:outliers nil]
  ;            [:calls nil])
  ;          (filter (fn [[_ v]] (nil? v))
  ;                  (pgex/read-stats db)))))

  ; yes extension, outliers and call will always return times in decimal ms.
  (deftest pg-stat-statements-based
    (testing "outliers return exec time in ms"
      (is (> (-> (pgex/outliers db {:limit 10}) first :exec_time_ms) 0M)))
    (testing "outliers return sync io time in ms"
      (is (>= (-> (pgex/outliers db {:limit 10}) first :sync_io_time_ms) 0M)))
    (testing "calls return exec time in ms"
      (is (> (-> (pgex/calls db {:limit 10}) first :exec_time_ms) 0M)))
    (testing "calls return sync io time in ms"
      (is (>= (-> (pgex/calls db {:limit 10}) first :sync_io_time_ms) 0M)))))

(comment
  ;; for testing another database,
  ;; instead of the embedded zonky db
  (def db
    (jdbc/get-datasource
     {:dbtype "postgresql"
      :dbname "main"
      :host "localhost"
      :port 5432
      :user "postgres"
      :password "password"})))
