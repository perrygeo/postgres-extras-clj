# Change Log
All notable changes to this project will be documented in this file.

This project's version numbers are MAJOR.MINOR.PR-NUMBER by convention.
Pre 1.0 releases will have breaking changes.

## [Unreleased]

* BREAKING: `pgex/records-rank`, To clarify that row counts are *estimates*, I replaced with `:rows` with `:rows_stats_estimate` and `rows_planner_estimate`. You've got to make a choice; pick your poison.
* Add DDL generation in `resources/sql/ddl.sql` + `postgres-extras-clj.ddl`. You can synthesize `CREATE`/`ALTER` DDL from live catalogs.
* Add pghero diagnostics (10 new queries) in `resources/sql/pghero.sql`
* Add PostgreSQL-native catalog diagnostics (13 new queries) in `resources/sql/postgres_native.sql`
* `bgwriter-stats` dispatches on PG17+ (`pg_stat_checkpointer`)
* `wal-stats` targets the stable `pg_stat_wal` columns (PG14+)
* `read-stats` returns all 44 diagnostic keys

## [0.2.22] - 2026-10-25
* New MAJOR.MINOR.PR-NUMBER versioning. start 0.2.x
* Upgrade clojars dependencies
* BREAKING: Make naming consistent (e.g. `s_name`, `schemaname`, `schema` are all now `schema`)
* BREAKING: Make units consistent (bytes for sizes, proportions not percentages)
* Babashka task runner
* Documentation automation, don't rely on repl workflow
* Better filtering of system objects, account for timescaledb
* Fix bug in PG17+, support new pg_stat_statements
* Test with all supported PostgreSQL versions

## [0.1.23] - 2024-07-30

- FIX: correct keys for unused-indexes diagnostic

## [0.1.21] - 2024-07-21

- FIX: For the index-cache-hit and table-cache-hit tables, COALESCE null values to zero for unused tables.
- CHANGES: Added pgbench example notebook.

## [0.1.17] - 2024-07-08

- Declare fns before hugsql macros to get proper docstrings

## [0.1.15] - 2024-07-08

### Changes

- Remove the outlier-legacy and calls-legacy functions, which served no purpose that I could tell.
- Changed the outliers and calls return maps to use :exec_time_ms (bigdecimal, explicit units) instead of :exec_time.
- Similarly, changed the outliers and calls return maps with :sync_io_time_ms

## [0.1.12] - 2024-07-08

### Fix

- vacuum cols should be numeric, not string

### Changes

- remove healthcheck from read-stats, doesn't fit the record structure.

## [0.1.10] - 2024-07-07

### Fix

- Add :indexes to data dictionary map
- Declare hugsql functions to make names explicit; clj_kondo is happy

## [0.1.8] - 2024-07-07

### Fix

- Respect :limits for all stats

### Changes

- Change diagnostic keyword from :desc to :onfalse for clarity

## [0.1.5] - 2024-07-06

### Fixed

- Correct :call
- Bug fix :null-indexes logic

## [0.1.4] - 2024-07-06

- Initial release.

