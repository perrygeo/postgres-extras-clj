# postgres-extras-clj

A Clojure toolbox for inspecting and diagnosing PostgreSQL databases.

```clojure
perrygeo/postgres-extras-clj {:mvn/version "0.1.6"}
```

* [Clojars](https://clojars.org/com.github.perrygeo/postgres-extras-clj)
* [Github](https://github.com/perrygeo/postgres-extras-clj)
* [Documentation](https://cljdoc.org/d/com.github.perrygeo/postgres-extras-clj/)
* [CI tests](https://github.com/perrygeo/postgres-extras-clj/actions/workflows/test.yml)

## 🐘 Motivation

PostgreSQL is a fantastic database but using it in production
requires some care, as do all databases.
The postgres [system catalogs](https://www.postgresql.org/docs/current/catalogs.html)
allow us to monitor things like query performance, connection management,
index efficiency, disk usage, and MVCC bloat. But accessing that information
requires some arcane knowledge and reasonably hardcore SQL skills.

This project was inspired by the rustprooflabs [pgdd](https://github.com/rustprooflabs/pgdd)
extension and by the phoenix web framework which ships with a developer-centric,
postgres-specific dashboard based on [ecto_psql_extras](https://github.com/pawurb/ecto_psql_extras/tree/main).
Both projects demonstrate that high-level database tooling
can, and probably should, be built on top of the system catalogs.
Understanding your database isn't optional.

`postgres-extras-clj` provides this missing toolkit for Clojure developers.

The SQL logic lives in [`resources/sql/*.sql`](resources/sql/) and is
formatted with pgFormat for consistency. SQL is 
annotated with [HugSQL](https://www.hugsql.org) comments to turn them
into clojure fns via macro magic. Instead of a web interface or postgres extension,
`postgres-extras-clj.core` provides a clojure namespace with a few dozen useful 
functions that query system tables and **return diagnostics as plain data structures**. 
The goal is to run with limited, SELECT-only privileges
of system schemas and tables (with a few noted exceptions).

All you need is a JDBC connection and a REPL.


### 📚 Data Dictionary
 
The data dictionary functionality is based on [pgdd](https://github.com/rustprooflabs/pgdd).
These include COMMENTS and are helpful for understanding the structure
of your database, from a data modeling lens.

| <span style="width:320px">Function</span>  | Scenario |
|---------|---------|
| `columns` | List all database column objects |
| `databases` | List all databases |
| `functions` | List all function objects in current database |
| `indexes` | List all index objects in current database |
| `schemas` | List all shemas in current database |
| `partition-children` | List all child partitions in current database |
| `partition-parents` | List all parent partition tables in current database |
| `tables` | List all table objects in current database |
| `views` | List all view objects in current database |

To get a full map of data objects, use `(read-data-dictionary db)` which
returns a map, with keywords mirroring the above functions.

### 🛠️ Operational Diagnostics

Diagnostic stats based on [ecto_psql_extras](https://github.com/pawurb/ecto_psql_extras/tree/main).
These are valuable for looking at your database through an operations or DBA lens.

| <span style="width:320px">Function</span> | Scenario |
|---------|---------|
| `all-locks` | Queries with active locks |
| `bloat` | Table and index "bloat" in your database ordered by most wasteful |
| `blocking` | Queries holding locks other queries are waiting to be released | 
| `cache-hit` | Index and table hit rate |
| `calls` | Queries that have the highest frequency of execution |
| `connections` | Returns the list of all active database connections |
| `db-settings` | Values of selected PostgreSQL settings |
| `duplicate-indexes` | Multiple indexes that have the same set of columns, same opclass, expression and predicate |
| `extensions` | Available and installed extensions |
| `health-check` | Checks the db for liveliness |
| `index-cache-hit` | Calculates your cache hit rate for reading indexes |
| `index-size` | The size of indexes, descending by size |
| `index-usage` | Index hit rate (effective databases are at 99% and up) |
| `kill-all!` | Kill all the active database connections |
| `locks` | Queries with active exclusive locks |
| `long-running-queries` | All queries longer than the threshold by descending duration |
| `null-indexes` | Find indexes with a high ratio of NULL values |
| `outliers` | Queries that have longest execution time in aggregate. |
| `outliers-legacy` | Queries that have longest execution time in aggregate |
| `records-rank` | All tables and the number of rows in each ordered by number of rows descending |
| `seq-scans` | Count of sequential scans by table descending by order |
| `table-cache-hit` | Calculates your cache hit rate for reading tables |
| `table-indexes-size` | Total size of all the indexes on each table, descending by size |
| `table-size` | Size of the tables (excluding indexes), descending by size |
| `total-index-size` | Total size of all indexes in MB |
| `total-table-size` | Size of the tables (including indexes), descending by size |
| `unused-indexes` | Unused and almost unused indexes |
| `vacuum-stats` | Dead rows and whether an automatic vacuum is expected to be triggered |

To get a full map of diagnostic stats, use `(read-stats db)` which
returns a map, with keywords mirroring the above functions.

Use the `(diagnose (read-stats db))` and `(diagnose-warnings (read-stats db))` functions
to evaluate the stats according to a set of heuristics. 


## Usage

Check out the [examples](./examples/with_next_jdbc.clj) if you're looking to create a fresh namespace. 

The following is a REPL demonstration of `postgres-extras-clj` with the `next.jdbc` adapter. 
Run `clj -M:dev` then evaluate the following forms


```clojure
(require '[postgres-extras-clj.core :as pgex] :reload-all)
(require '[hugsql.core :as hugsql])
(require '[hugsql.adapter.next-jdbc :as next-adapter])
(require '[next.jdbc :as jdbc])

(def db
  (jdbc/get-datasource
   "jdbc:postgresql://localhost:5432/main?user=postgres&password=password"))

(hugsql/set-adapter! (next-adapter/hugsql-adapter-next-jdbc))
```

For this example, wer'e using a `next.jdbc` datasource but there are other options,
see the [HugSQL Adapters](https://www.hugsql.org/hugsql-adapters/) documentation.

Do a quick health check

```clojure
(pgex/health-check db)
; {:now #inst "2024-07-05T18:04:57.506678000-00:00",
;  :version "PostgreSQL 16.1 (Debian 16.1-1.pgdg110+1) on x86_64-pc-linux-gnu..."}
```

Generate a data dictionary summarizing all major objects in your database.

```clojure
(def dd (pgex/read-data-dictionary db))
(keys dd)
; (:databases
;  :columns
;  :functions
;  :schemas
;  :tables
;  :views 
;  :partition-children
;  :partition-parents)

(rand-nth (:tables dd))
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
```

Create a full map of diagnostic stats. 

```clojure
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

(rand-nth (:connections stats))
; {:username "postgres"
;  :client_address "172.22.0.1/32"
;  :application_name "psql"}
```

All of the stats and data dictionary keywords mirror the name of a public function in the
`postgres-extras-clj.core` namespace so you can invoke them selectively,
instead of getting them from the full map.


```clojure
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

(rand-nth (pgex/connections db))
; {:username "postgres"
;  :client_address "172.22.0.1/32"
;  :application_name "psql"}
```


Read stats and print the default diagnostics:

```clojure
;; warnings only
(doseq [w (pgex/diagnose-warnings (pgex/read-stats db))]
  (println (:message w)))
```

The `default-diagnostic-fns` can be overridden.
To create your own diagnostics:

```clojure
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
; ... many more
```

## Development

Test runner with coverage

    clj -X:test

Run NREPL and interactive terminal REPL in one

    clj -M:dev


Build a jar. Output in `./target/com.github.perrygeo/postgres-extras-clj-*.jar`

    clj -T:build jar


Deploy to Clojars.
Set `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` env vars.
Assumes that `clj -T:build jar` has already been run.

    clj -T:build deploy

## License

Copyright © 2024 Matthew T. Perry (`perrygeo`). 
Distributed under the MIT license.

The credit for the SQL query logic goes
to the fantastic work done by these three projects:

  * https://github.com/heroku/heroku-pg-extras
  * https://github.com/pawurb/ecto_psql_extras
  * https://github.com/rustprooflabs/pgdd

Their licenses (all MIT) are included in the SQL files.
