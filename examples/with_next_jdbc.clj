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

;; TODO kondo doesn't know about hugsql macros
#_{:clj-kondo/ignore [:unresolved-var]}
(comment

  (pgex/health-check db)
  ; {:now #inst "2024-07-05T18:04:57.506678000-00:00",
  ;  :version "PostgreSQL 16.1 (Debian 16.1-1.pgdg110+1) on x86_64-pc-linux-gnu..."}

  ;; Full map of database objects, the "data dictionary"
  (keys
    (pgex/read-data-dictionary db))
  ; (:databases
  ;  :columns
  ;  :functions
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
  #_(kill-all! db)

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
      :desc "The cache hit ratio is not as insanely high as I'd like."
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
