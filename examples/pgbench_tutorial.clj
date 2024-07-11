; # `postgres-extras-clj` example with pgbench

; `postgres-extras-clj` is a Clojure toolbox for inspecting and diagnosing PostgreSQL databases.
;
; * [Clojars](https://clojars.org/com.github.perrygeo/postgres-extras-clj)
; * [Github](https://github.com/perrygeo/postgres-extras-clj)
; * [Documentation](https://cljdoc.org/d/com.github.perrygeo/postgres-extras-clj/)
; * [CI tests](https://github.com/perrygeo/postgres-extras-clj/actions/workflows/test.yml)
;
; In this notebook, we'll cover the majority of the high-level API,
; demonstrating some useful fns based on the postgres [system catalogs](https://www.postgresql.org/docs/current/catalogs.html).
;
; This notebook is written in a [Clojure namespace](https://github.com/perrygeo/postgres-extras-clj/blob/main/examples/pgbench_tutorial.clj)
; and converted to HTML using [Clay](https://scicloj.github.io/clay/#api).

(ns pgbench-tutorial
  "This is a demonstration of postgres-extras-clj using the next.jdbc adapter
  to access a pgbench database."
  (:require
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as hugsql]
   [next.jdbc :as jdbc]
   [postgres-extras-clj.core :as pgex]
   [scicloj.kindly.v4.kind :as kind]))

; ##  Utility functions
;
(defn show
  "Given a sequence of maps (records),
  Kindly show me a table with some nice UI elements."
  [f]
  (if (empty? f)
    '()
    (let [ncol (count (first f))
          nrow (count f)]
      (kind/table f
                  {:use-datatables true
                   :datatables {:paging (< 12 nrow)
                                :scrollY false
                                :scrollX (< 6 ncol)
                                :pageLength 12}}))))

(defn show-public
  "Given a sequence of maps (records), 
  filter for non-system objects, then show it."
  [f]
  (show
   (filter #(not (:system_object %)) f)))

^:kindly/hide-code
(defn meta-as-header [x]
  (kind/md (str "### " (:doc (meta x)))))

;;
;; ## Setup
;;

;; ### Dependencies

^:kindly/hide-code
(kind/md
 "```clojure
 {:deps {com.layerware/hugsql {:mvn/version \"0.5.3\"}
         com.layerware/hugsql-adapter-next-jdbc {:mvn/version \"0.5.3\"}
         org.scicloj/clay {:mvn/version \"2-beta11\"}
         org.postgresql/postgresql {:mvn/version \"42.7.3\"}
         seancorfield/next.jdbc {:mvn/version \"1.2.659\"}}}
  ```")

; ### Database benchmark

; We'll use [`pgbench`](https://www.postgresql.org/docs/current/pgbench.html) to get a database populated with interesting tables:

; > Pgbench is a simple program for running benchmark tests on PostgreSQL. 
; > It runs the same sequence of SQL commands over and over, possibly in multiple concurrent database sessions, and then calculates the average transaction rate (transactions per second) . By default, pgbench tests a scenario that is loosely based on TPC-B, involving five SELECT, UPDATE, and INSERT commands per transaction.

; To initialize

^:kindly/hide-code
(kind/md
 "```bash
 PGPASSWORD=password pgbench \\
  --host localhost --port 5432 --username postgres \\
  -s 10 -f 100 -i -i dtgvpf \\
  main
 ```")

; Then run the benchmarks

^:kindly/hide-code
(kind/md
 "```bash
 PGPASSWORD=password pgbench \\
  --host localhost --port 5432 --username postgres \\
  --client 32 --transactions 1000 --jobs 8 \\
  main
 ```")

;; ### Driver Setup 

;; We define a JDBC datasource from a map

(def db
  (jdbc/get-datasource
   {:dbtype "postgresql" :dbname "main" :host "localhost" :port 5432 :user "postgres" :password "password"}))

;; or from a JDBC URI.

(def db2
  (jdbc/get-datasource
   "jdbc:postgresql://localhost:5432/main?user=postgres&password=password"))

;; Independently, we need to tell hugsql to expect next-jdbc.
(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))

;; Do a health check to ensure connectivity

^:kindly/hide-code
(pgex/health-check db2)

(pgex/health-check db)


; ## Settings at a glance

; These settings are a subset of the total available via
; `postgresql.conf`, but these particular settings are worth
; paying attention to.

; How to tune each of these parameters is beyond the scope of this
; document. There are plenty of guides online, my recommnedation
; being [PGTune](https://pgtune.leopard.in.ua/). 
; It provides a simple, web-based tool to generate
; "optimal" settings depending on your hardware and application. 

; The default settings that ship with postgres are almost never optimal,
; they are far too conservative and make poor use of modern hardware.
; Here's my configuration tuned for test/dev on my laptop.

(show (pgex/db-settings db))
; example row
^:kindly/hide-code (first (pgex/db-settings db))

;;
;; ## Full map of database objects, the "data dictionary"
;;

(def dd (pgex/read-data-dictionary db))
(keys dd)

;; Or one object, one item at time

^:kindly/hide-code (meta-as-header #'pgex/databases)
(show (pgex/databases db))
; example row
^:kindly/hide-code (first (:databases dd))

^:kindly/hide-code (meta-as-header #'pgex/schemas)
(show-public (pgex/schemas db))
; example row
(comment
  (count (pgex/schemas db))
  (count (show (pgex/schemas db)))
  (count (show-public (pgex/schemas db))))
^:kindly/hide-code (first (:schemas dd))

^:kindly/hide-code (meta-as-header #'pgex/views)
(show-public (pgex/views db))
; example row
^:kindly/hide-code (first (:views dd))

^:kindly/hide-code (meta-as-header #'pgex/indexes)
(show-public (pgex/indexes db))
; example row
^:kindly/hide-code (first (:indexes dd))

^:kindly/hide-code (meta-as-header #'pgex/columns)
(show-public (pgex/columns db))
; example row
^:kindly/hide-code (first (:columns dd))

^:kindly/hide-code (meta-as-header #'pgex/tables)
(show-public (pgex/tables db))
; example row
^:kindly/hide-code (first (:tables dd))

^:kindly/hide-code (meta-as-header #'pgex/functions)
(show-public (pgex/functions db))
; example row
^:kindly/hide-code (first (:functions dd))

#_(filter #(= (:t_name %) "pgbench_accounts") (pgex/tables db))

;; 
;; ## Full map of diagnostic statistics
;;

(def stats (pgex/read-stats db {:limit 100}))
(keys stats)


^:kindly/hide-code (meta-as-header #'pgex/vacuum-stats)
(show (pgex/vacuum-stats db))
; example row
^:kindly/hide-code (first (:vacuum-stats stats))

^:kindly/hide-code (meta-as-header #'pgex/index-usage)
(show (pgex/index-usage db))
; example row
^:kindly/hide-code (first (:index-usage stats))

^:kindly/hide-code (meta-as-header #'pgex/total-index-size)
(pgex/total-index-size db)
; example row
^:kindly/hide-code (first (:total-index-size stats))

^:kindly/hide-code (meta-as-header #'pgex/cache-hit)
(show (pgex/cache-hit db))
; example row
^:kindly/hide-code (first (:cache-hit stats))

^:kindly/hide-code (meta-as-header #'pgex/records-rank)
(show (pgex/records-rank db))
; example row
^:kindly/hide-code (first (:records-rank stats))

^:kindly/hide-code (meta-as-header #'pgex/index-cache-hit)
(show (pgex/index-cache-hit db))
; example row
^:kindly/hide-code (first (:index-cache-hit stats))

^:kindly/hide-code (meta-as-header #'pgex/outliers)
(show (pgex/outliers db {:limit 10}))
; example row
^:kindly/hide-code (first (:outliers stats))


^:kindly/hide-code (meta-as-header #'pgex/extensions)
(show (pgex/extensions db))
; example row
^:kindly/hide-code (first (:extensions stats))

^:kindly/hide-code (meta-as-header #'pgex/total-table-size)
(show (pgex/total-table-size db))
; example row
^:kindly/hide-code (first (:total-table-size stats))

^:kindly/hide-code (meta-as-header #'pgex/bloat)
(show (pgex/bloat db))
; example row
^:kindly/hide-code (first (:bloat stats))

^:kindly/hide-code (meta-as-header #'pgex/calls)
(show (pgex/calls db {:limit 10}))
; example row
^:kindly/hide-code (first (:calls stats))

^:kindly/hide-code (meta-as-header #'pgex/table-size)
(show (pgex/table-size db))
; example row
^:kindly/hide-code (first (:table-size stats))

^:kindly/hide-code (meta-as-header #'pgex/connections)
(show (pgex/connections db))
; example row
^:kindly/hide-code (first (:connections stats))

^:kindly/hide-code (meta-as-header #'pgex/table-cache-hit)
(show (pgex/table-cache-hit db))
; example row
^:kindly/hide-code (first (:table-cache-hit stats))

^:kindly/hide-code (meta-as-header #'pgex/table-indexes-size)
(show (pgex/table-indexes-size db))
; example row
^:kindly/hide-code (first (:table-indexes-size stats))


^:kindly/hide-code (meta-as-header #'pgex/seq-scans)
(show (pgex/seq-scans db))
; example row
^:kindly/hide-code (first (:seq-scans stats))

^:kindly/hide-code (meta-as-header #'pgex/index-size)
(show (pgex/index-size db))
; example row
^:kindly/hide-code (first (:index-size stats))

^:kindly/hide-code
(comment
  ;;;
  ;;; These show zero results with the pgbench example
  ;;; show them once we can reproduce the required conditions
  ;;;
  ^:kindly/hide-code (meta-as-header #'pgex/partition-children)
  (show (pgex/partition-children db))

  ^:kindly/hide-code (meta-as-header #'pgex/partition-parents)
  (show (pgex/partition-parents db))

  ^:kindly/hide-code (meta-as-header #'pgex/duplicate-indexes)
  (show (pgex/duplicate-indexes db))

  ^:kindly/hide-code (meta-as-header #'pgex/locks)
  (show (pgex/locks db))

  ^:kindly/hide-code (meta-as-header #'pgex/null-indexes)
  (show (pgex/null-indexes db))

  ^:kindly/hide-code (meta-as-header #'pgex/all-locks)
  (show (pgex/all-locks db))

  ^:kindly/hide-code (meta-as-header #'pgex/blocking)
  (show (pgex/blocking db))

  ^:kindly/hide-code (meta-as-header #'pgex/unused-indexes)
  (show (pgex/unused-indexes db {:min_scans 50}))

  ^:kindly/hide-code (meta-as-header #'pgex/long-running-queries)
  (show (pgex/long-running-queries db)))


;; ## The Kill Switch

;; The only mutating function in the entire library
;; is a kill switch for closing all connections,
;; vital if you need to take a heavily-used database
;; down for maintenance or emergency.
;;
;;; ```clojure
;;; (pgex/kill-all! db)  ;; use with caution
;;; ```

;; ## Diagnostics

;; Query and and print all default diagnostics

(for [d (pgex/diagnose (pgex/read-stats db))]
  (:message d))

;; Or just the warnings

(for [w (pgex/diagnose-warnings (pgex/read-stats db))]
  (:message w))

;; Create your own diagnostics

(def unrealistic-expectations
  {:table-cache-hit
   {:pred #(> (:ratio %) 0.999)
    :onfalse "The cache hit ratio is not as insanely high as I'd like."
    :idfn :name}})

(for [w (pgex/diagnose-warnings
         (pgex/read-stats db)
         :diagnostic-fns unrealistic-expectations)]
  (:message w))

;; ## Just for fun

(for [m (pgex/mandelbrot db)]
  (:art m))

; ## To recreate this notebook
;
(comment
  (require '[scicloj.clay.v2.api :as clay])
  (clay/browse!)
  (clay/make! {:source-path "examples/pgbench_tutorial.clj"
               :format [:quarto :html]
               :base-target-path "target/clay"}))
