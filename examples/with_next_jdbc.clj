(ns with-next-jdbc
  "This is a demonstration of postgres-extras-clj with the next.jdbc adapter."
  (:require
   [hugsql.core :as hugsql]
   [hugsql.adapter.next-jdbc :as next-adapter]
   [next.jdbc :as jdbc]
   [postgres-extras-clj.core :as pgex]))

; {:deps {com.layerware/hugsql {:mvn/version "0.5.3"}
;         com.layerware/hugsql-adapter-next-jdbc {:mvn/version "0.5.3"}
;         org.clojure/clojure {:mvn/version "1.11.2"}
;         org.postgresql/postgresql {:mvn/version "42.7.3"}
;         seancorfield/next.jdbc {:mvn/version "1.2.659"}}}

;; define a JDBC datasource
(def db
  (jdbc/get-datasource
   {:dbtype "postgresql"
    :dbname "main"
    :host "localhost"
    :port 5432
    :user "postgres"
    :password "password"}))

;; tell hugsql about it
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

(defn single-value [[k v]]
  [k (if (or (nil? v) (empty? v)) nil (rand-nth v))])

(comment

; ## Database (s) 

  (pgex/databases db)
; [{:oid 19699,
;   :db_name "main",
;   :db_size "1530 MB",
;   :table_count 72,
;   :size_in_tables "1531 MB",
;   :extension_count 2}]

  (pgex/health-check db)
  ; {:now #inst "2024-07-05T18:04:57.506678000-00:00",
  ;  :version "PostgreSQL 16.1 (Debian 16.1-1.pgdg110+1) on x86_64-pc-linux-gnu..."}

;; ## Full map of database objects, the "data dictionary"
  (keys
    (pgex/read-data-dictionary db))
  ; (:databases
  ;  :columns
  ;  :functions
  ;  :indexes 
  ;  :schemas
  ;  :tables
  ;  :views 
  ;  :partition-children
  ;  :partition-parents)

  ;; Or one object, one item at time
  (rand-nth (pgex/tables db))
  ; {:size_pretty "16 kB",
  ;  :description nil,
  ;  :owned_by "postgres",
  ;  :size_plus_indexes "48 kB",
  ;  :rows 1,
  ;  :oid 19789,
  ;  :data_type "table",
  ;  :size_plus_indexes_bytes 49152,
  ;  :s_name "public",
  ;  :system_object false,
  ;  :t_name "users",
  ;  :size_bytes 16384,
  ;  :bytes_per_row 16384}

  ;; Full map of diagnostic statistics
  (def stats (pgex/read-stats db))
  (keys stats)
  ; (:duplicate-indexes
  ;  :db-settings
  ;  :locks
  ;  :vacuum-stats
  ;  :index-usage
  ;  :total-index-size
  ;  :cache-hit
  ;  :health-check
  ;  :records-rank
  ;  :null-indexes
  ;  :index-cache-hit
  ;  :all-locks
  ;  :outliers
  ;  :long-running-queries
  ;  :extensions
  ;  :total-table-size
  ;  :unused-indexes
  ;  :bloat
  ;  :calls
  ;  :table-size
  ;  :connections
  ;  :table-cache-hit
  ;  :table-indexes-size
  ;  :blocking
  ;  :seq-scans
  ;  :index-size)

  ;; Or use them individually, 
  ;; e.g. to check current connections
  (pgex/connections db)
  ; [{:username "postgres",
  ;   :client_address "172.22.0.1/32",
  ;   :application_name "psql"}
  ;  {:username "postgres",
  ;   :client_address "172.22.0.1/32",
  ;   :application_name "PostgreSQL JDBC Driver"}]

  ;; Most of the functions are zero-argument but a few
  ;;   take additional args for performance reasons;
  ;;   filtering at the database level avoids potentially
  ;;   expensive IO operations.

  ;; Find slow queries with `outliers`
  ;; requires creating the pg_stat_statements 
  ;;   extension, which you should almost certainly do anyway.
  ;;
  ;; Usually we're looking for the worst offenders,
  ;; so we can :limit the top x, sorted desceding by time.
  ;;
  (pgex/outliers db {:limit 1})

  ;; The only mutating function in the entire library
  ;; is a kill switch for closing all connections,
  ;; vital if you need to take a heavily-used database
  ;; down for maintenance or emergency. Needless to say,
  ;; use with caution!
  (pgex/kill-all! db)

  ;; Query and and print all default diagnostics
  (doseq [d (pgex/diagnose (pgex/read-stats db))]
    (println (:message d)))

  ;; Query and and print all default diagnostics (warnings only)
  (doseq [w (pgex/diagnose-warnings (pgex/read-stats db))]
    (println (:message w)))

  ;; Create your own diagnostics
  (def unrealistic-expectations
    {:table-cache-hit
     {:pred #(> (:ratio %) 0.999)
      :onfalse "The cache hit ratio is not as insanely high as I'd like."
      :idfn :name}})

  (doseq [w (pgex/diagnose-warnings
             (pgex/read-stats db)
             :diagnostic-fns unrealistic-expectations)]
    (println (:message w)))
  ; ! Warning :table-cache-hit, message_topics, The cache hit ratio is not as insanely high as I'd like.
  ; {:ratio 0.9806201550387597, :schema "public", :name "message_topics", :buffer_hits 253, :block_reads 5, :total_read 258}
  ; ...

  ;; just for fun :-P
  (doseq [m (pgex/mandelbrot db)]
    (println (:art m))))

#_{:clj-kondo/ignore [:duplicate-require]}
(comment
  (require '[postgres-extras-clj.core :as pgex])
  (require '[hugsql.core :as hugsql])
  (require '[hugsql.adapter.next-jdbc :as next-adapter])
  (require '[next.jdbc :as jdbc])
  (hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

  (def db
    (jdbc/get-datasource
     "jdbc:postgresql://localhost:5432/main?user=postgres&password=password"))

  (pgex/health-check db)
  ; {:now #inst "2024-07-07T21:56:59.013996000-00:00",
  ;  :version "PostgreSQL 16.1 (Debian 16.1-1.pgdg110+1)..."}

  ;; ## Settings

  ;; Check the database settings
  (rand-nth (pgex/db-settings db))
  ; {:name "max_connections",
  ;  :setting "400",
  ;  :unit nil,
  ;  :short_desc "Sets the maximum number of concurrent connections."}

  (let [settings (pgex/db-settings db)]
    (zipmap (map :name settings) (map :setting settings)))
  ; {"default_statistics_target" "100",
  ;  "shared_buffers" "263168",
  ;  "min_wal_size" "80",
  ;  "effective_io_concurrency" "1",
  ;  "maintenance_work_mem" "655360",
  ;  "work_mem" "40960",
  ;  "checkpoint_completion_target" "0.75",
  ;  "wal_buffers" "2048",
  ;  "effective_cache_size" "524288",
  ;  "max_connections" "400",
  ;  "random_page_cost" "4",
  ;  "max_wal_size" "1024"}

  ;; ## Locks

  (pgex/locks db) ; []

  ;; ## Vaccum stats
  (rand-nth
    (pgex/vacuum-stats db))
; {:schema "public",
;  :table "pgbench_accounts",
;  :last_vacuum "2024-07-07 15:42",
;  :last_autovacuum "2024-07-07 15:48",
;  :rowcount 9999472.0,
;  :dead_rowcount 157547,
;  :autovacuum_threshold 200039.44,
;  :expect_autovacuum nil}

  (zipmap
    (map :name (pgex/db-settings db))
    (map :setting (pgex/db-settings db)))

  ;; ## Tables 

  ;; aTable tuples alone
  (pgex/table-size db)
; [{:schema "public", :name "pgbench_accounts", :size 1364787200}
;  {:schema "public", :name "pgbench_history", :size 5341184}
;  {:schema "public", :name "pgbench_tellers", :size 1024000}
;  {:schema "public", :name "pgbench_branches", :size 876544}]

  ;; Indexes associated with 
  (pgex/table-indexes-size db)
; [{:schema "public", :table "pgbench_accounts", :index_size 224641024}
;  {:schema "public", :table "pgbench_tellers", :index_size 106496}
;  {:schema "public", :table "pgbench_branches", :index_size 16384}
;  {:schema "public", :table "pgbench_history", :index_size 0}]

  ;; Combined
  (pgex/total-table-size db)
; [{:schema "public", :name "pgbench_accounts", :size 1589428224}
;  {:schema "public", :name "pgbench_history", :size 5341184}
;  {:schema "public", :name "pgbench_tellers", :size 1130496}
;  {:schema "public", :name "pgbench_branches", :size 892928}]

  (rand-nth (pgex/table-cache-hit db))
; {:schema "public",
;  :name "pgbench_accounts",
;  :buffer_hits 6479787,
;  :block_reads 704260,
;  :total_read 7184047,
;  :ratio 0.9019689041566682}

  (filter #(= (:t_name %) "pgbench_accounts") (pgex/tables db))
; ({:size_pretty "1302 MB",
;   :description nil,
;   :owned_by "postgres",
;   :size_plus_indexes "1516 MB",
;   :rows 9999472,
;   :oid 19737,
;   :data_type "table",
;   :size_plus_indexes_bytes 1589428224,
;   :s_name "public",
;   :system_object false,
;   :t_name "pgbench_accounts",
;   :size_bytes 1364787200,
;   :bytes_per_row 136})

  ; ## Indexes

  (count (pgex/indexes db)) ; 167

  (pgex/total-index-size)

  (last (filter #(not (:system_object %)) (pgex/indexes db)))
; {:total_columns 1,
;  :unique_index true,
;  :rows_indexed 9999472.0,
;  :oid 19759,
;  :index_size_bytes 224641024,
;  :i_name "pgbench_accounts_pkey",
;  :partial_index false,
;  :index_size "214 MB",
;  :key_columns 1,
;  :valid_index true,
;  :s_name "public",
;  :system_object false,
;  :primary_key true,
;  :t_name "pgbench_accounts"}

  (pgex/index-size db)
; [{:schema "public", :name "pgbench_accounts_pkey", :size 224641024}
;  {:schema "public", :name "pgbench_tellers_pkey", :size 106496}
;  {:schema "public", :name "pgbench_branches_pkey", :size 16384}]

  (first (pgex/index-usage db))
; {:schema "public",
;  :name "pgbench_accounts",
;  :percent_of_times_index_used 99,
;  :rows_in_table 9999472}

  (last (pgex/index-cache-hit db))
; {:schema "public",
;  :name "pgbench_accounts",
;  :buffer_hits 6685792,
;  :block_reads 27422,
;  :total_read 6713214,
;  :ratio 0.9959152203400636}

  (pgex/duplicate-indexes db) ; []

  (into {} (map single-value (pgex/read-stats db :limit 100)))
  (into {} (map single-value (pgex/read-data-dictionary db)))) ; nil

