# postgres-extras-clj

A Clojure toolbox for inspecting and diagnosing PostgreSQL databases.

```clojure
com.github.perrygeo/postgres-extras-clj {:mvn/version "0.2.22"}
```

* [Clojars](https://clojars.org/com.github.perrygeo/postgres-extras-clj)
* [Github](https://github.com/perrygeo/postgres-extras-clj)
* [Documentation](https://cljdoc.org/d/com.github.perrygeo/postgres-extras-clj/)
* [CI tests](https://github.com/perrygeo/postgres-extras-clj/actions/workflows/test.yml)
* [Example Notebook](https://perrygeo.github.io/postgres-extras-clj/)


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

| <span>Function</span>  | Scenario |
|---------|---------|
| `columns` | List all database column objects |
| `databases` | List all databases |
| `functions` | List all function objects in current database |
| `indexes` | List all index objects in current database |
| `schemas` | List all shemas in current database |
| `partition-children` | List all child partitions in current database |
| `partition-parents` | List all parent partition tables in current database |
| `tables` | List all table objects in current database |
| `triggers` | List all trigger objects in current database |
| `views` | List all view objects in current database |

To get a full map of data objects, use `(read-data-dictionary db)` which
returns a map, with keywords mirroring the above functions.

System objects are excluded by default.
Pass `(read-data-dictionary db {:include-system-objects? true})` to include them.

### 🧬 DDL Generation

Experimental! Not widely tested yet.

The `postgres-extras-clj.ddl` namespace provides fns to
generate `CREATE`/`ALTER` DDL from a live database.

Inspired by DBeaver's "DDL" window and `pg_dump`.
These functions reassemble the *effective* DDL statements using system catalogs
and `pg_get_*def` decompilers.

In Clojure, we assemble the `CREATE TABLE` strings via the public API.

```clojure
(require '[postgres-extras-clj.ddl :as ddl])

(ddl/ddl-for-table db "public" "users")    ;=> "CREATE TABLE ...;"
(ddl/ddl-for-view db "public" "user_view")  ;=> "CREATE VIEW ...;"
(ddl/generate-ddl db)                       ;=> full-database DDL string
```
Configuration:

```clojure
(ddl/generate-ddl db {:schemas ["public" "audit"]})   ; restrict to schemas
(ddl/generate-ddl db {:include-system-objects? true})  ; include pg_catalog etc.
```

One known limitation: I don't yet have a way to filter out extension-created objects.
Extensions like PostGIS and pg_stat_statements will create many functions, tables, and views directly in the `public` schema.
Their definition will get dumped to the DDL string just like your own tables.
In the future, this namespace may filter out extension-created objects by default.

### 🛠️ Operational Diagnostics

Diagnostic stats ported from
[ecto_psql_extras](https://github.com/pawurb/ecto_psql_extras/tree/main),
[pghero](https://github.com/ankane/pghero), and PostgreSQL's own system
catalogs. Each source lives in its own `resources/sql/*.sql` file with the
source license inlined. These are valuable for looking at your database
through an operations or DBA lens.

| <span>Function</span> | Scenario |
|---------|---------|
| `all-locks` | Queries with active locks |
| `bloat` | Table and index "bloat" in your database |
| `blocking` | Queries holding locks other queries are waiting to be released |
| `cache-hit` | Index and table hit rate |
| `calls` | Queries that have the highest frequency of execution |
| `connections` | Returns the list of all active database connections |
| `db-settings` | Values of selected PostgreSQL settings |
| `duplicate-indexes` | Multiple indexes that have the same set of columns, same opclass, expression and predicate |
| `extensions` | Available and installed extensions |
| `health-check` | Checks the db for liveliness |
| `index-cache-hit` | Calculates your cache hit rate for reading indexes |
| `index-size` | The size of indexes |
| `index-usage` | Index hit rate (effective databases are at 99% and up) |
| `kill-all!` | Kill all the active database connections |
| `locks` | Queries with active exclusive locks |
| `long-running-queries` | All queries longer than the threshold |
| `null-indexes` | Find indexes with a high ratio of NULL values |
| `outliers` | Queries that have longest execution time in aggregate. |
| `records-rank` | All tables with row counts from both the query planner (`rows_planner_estimate`) and the statistics collector (`rows_stats_estimate`) |
| `seq-scans` | Count of sequential scans by table |
| `table-cache-hit` | Calculates your cache hit rate for reading tables |
| `table-indexes-size` | Total size of all the indexes on each table |
| `table-size` | Size of the tables (excluding indexes) |
| `total-index-size` | Total size of all indexes in MB |
| `total-table-size` | Size of the tables (including indexes) |
| `unused-indexes` | Unused and almost unused indexes |
| `vacuum-stats` | Dead rows and whether an automatic vacuum is expected to be triggered |

#### pghero

Queries ported from [pghero](https://github.com/ankane/pghero) (MIT, Andrew Kane).

| <span>Function</span> | Scenario |
|---------|---------|
| `connection-states` | Connections grouped by state |
| `invalid-constraints` | Constraints that are NOT VALID |
| `invalid-indexes` | Indexes that are invalid and need REINDEX |
| `maintenance` | Last vacuum/analyze and their counts per table |
| `missing-indexes` | Tables with low index usage and many rows |
| `replication-lag` | Standby replication lag in seconds |
| `replication-slots` | Replication slots with lag and retention |
| `sequences` | Sequences near exhaustion or on small integer types |
| `transaction-id` | Transaction ID wraparound risk per database |
| `unused-tables` | Tables never accessed via an index |

#### PostgreSQL native catalogs

Read-only diagnostics over PostgreSQL system catalogs.

| <span>Function</span> | Scenario |
|---------|---------|
| `autovacuum-disabled` | Tables with autovacuum disabled |
| `bgwriter-stats` | Checkpoint and background writer activity |
| `database-stats` | Cumulative database-level counters |
| `idle-in-transaction` | Sessions idle in transaction (vacuum blockers) |
| `replication-stats` | Standby connections and per-standby lag from the primary |
| `roles` | Roles and their privilege/expiry attributes |
| `sequence-origin` | How each sequence was created: serial, identity, or explicit |
| `stats-collector-drift` | Drift between planner estimate and statistics collector |
| `tables-without-primary-key` | Tables without a primary key |
| `unindexed-foreign-keys` | Foreign keys without a leading index on the referencing columns |
| `unlogged-tables` | Unlogged tables (data lost on crash) |
| `wal-archiver` | WAL archiving health |
| `wal-stats` | WAL activity (PostgreSQL 14+) |

To get a full map of diagnostic stats, use `(read-stats db)` which
returns a map, with keywords mirroring the table above. The single-row
instance views — `replication-lag`, `wal-stats`, `wal-archiver`, and
`bgwriter-stats` — are standalone functions (not part of `read-stats`).

Diagnostic queries exclude postgres schemas
`pg_catalog`, `information_schema`, `pg_toast*`,
and TimescaleDB's internal schemas.
Pass `:include-system-objects? true` to `read-stats` (or the functions directly) to include them.

Use the `(diagnose (read-stats db))` and `(diagnose-warnings (read-stats db))` functions
to evaluate the stats according to a set of heuristics.



## Usage

Check out the [examples](./examples/pgbench_tutorial.clj) if you're looking to create a fresh namespace.

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
;  :indexes
;  :schemas
;  :tables
;  :views
;  :partition-children
;  :partition-parents)

(rand-nth (:tables dd))
; {:size_pretty "16 kB",
;  :description nil,
;  :owner "postgres",
;  :size_plus_indexes_pretty "48 kB",
;  :rows 1,
;  :oid 19789,
;  :data_type "table",
;  :size_plus_indexes_bytes 49152,
;  :schema "public",
;  :system_object false,
;  :table "users",
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
;  :owner "postgres",
;  :size_plus_indexes_pretty "48 kB",
;  :rows 1,
;  :oid 19789,
;  :data_type "table",
;  :size_plus_indexes_bytes 49152,
;  :schema "public",
;  :system_object false,
;  :table "users",
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
    :idfn :table}})

(doseq [w (pgex/diagnose-warnings
           (pgex/read-stats db)
           :diagnostic-fns unrealistic-expectations)]
  (println (:message w)))

; ! Warning :table-cache-hit, message_topics, The cache hit ratio is not as insanely high as I'd like.
; {:ratio 0.9806201550387597, :schema "public", :table "message_topics", :buffer_hits 253, :block_reads 5, :total_read 258}
; ... many more
```


## Development

You'll need to install

* `docker compose` (or some other means of running postgres)
* `babashka`
* `clj-kondo`

You'll use `bb` for most day-to-day tasks

```sh
$ bb tasks
The following tasks are available:

dev               Start the nREPL for local development
test              Run the test suite against all currently supported PostgreSQL versions
lint              Run clj-kondo linting over the project sources
db:up             Ensure the pgbench PostgreSQL container is up, ready, and has required extensions enabled
db:pgbench        Ensure the pgbench schema and at least one benchmark run are present (idempotent)
db:benchmark      Force a fresh pgbench benchmark run to refresh stats
notebook:render   Render examples/pgbench_tutorial.clj to target/clay with Clay + Quarto
notebook:copy     Copy the rendered notebook artifacts from target/clay into doc/
notebook:publish  Commit doc/ to the ghpages branch and push to origin
notebook          Full pipeline: db:up -> db:pgbench -> render -> copy -> publish to ghpages
release:bump      Bump version files only (README.md, pom.xml, CHANGELOG.md) — no commit, tag, or push. bb release:bump 0.2.22
release           Cut a release: bump version files, commit, tag v<version>, and push (bb release 0.2.22 [--no-push])
jar               Build the JAR. Version = git tag at HEAD (untagged => 0.0.0-SNAPSHOT)
publish           Deploy the JAR to Clojars (requires CLOJARS_USERNAME / CLOJARS_PASSWORD)
```


## License

Copyright © 2026 Matthew T. Perry.
Distributed under the MIT license.

The credit for the SQL query logic goes
to the fantastic work done by these three projects:

  * https://github.com/heroku/heroku-pg-extras
  * https://github.com/pawurb/ecto_psql_extras
  * https://github.com/rustprooflabs/pgdd

Their licenses (all MIT) are included in the SQL files.
