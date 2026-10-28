-----------------------------------------------------------------
-- pghero.sql
-- Copyright 2024 perrygeo
--
-- SQL queries ported to HugSQL syntax from pghero:
--   https://github.com/ankane/pghero
--
-- Portions of the query logic are Copyright (c) Andrew Kane and are
-- licensed under the MIT License, reproduced below.
--
-- MIT License
--
-- Copyright (c) Andrew Kane
--
-- Permission is hereby granted, free of charge, to any person obtaining a copy
-- of this software and associated documentation files (the "Software"), to deal
-- in the Software without restriction, including without limitation the rights
-- to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
-- copies of the Software, and to permit persons to whom the Software is
-- furnished to do so, subject to the following conditions:
--
-- The above copyright notice and this permission notice shall be included in
-- all copies or substantial portions of the Software.
--
-- THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
-- IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
-- FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
-- AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
-- LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
-- OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
-- THE SOFTWARE.
--
-- CHANGES:
-- * ported the queries to HugSQL syntax
-- * formatted the SQL code
-----------------------------------------------------------------
--
-- :name invalid-indexes
-- :command :query
-- :result :many
-- :doc Indexes that are invalid and need REINDEX
/* HUG_PSQL_EXTRAS: Indexes that are invalid and need REINDEX */
SELECT
    n.nspname AS schema,
    c.relname AS index,
    pg_relation_size(c.oid) AS index_size_bytes
FROM
    pg_index i
    INNER JOIN pg_class c ON c.oid = i.indexrelid
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE
    NOT i.indisvalid
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    index_size_bytes DESC;

-- :name invalid-constraints
-- :command :query
-- :result :many
-- :doc Constraints that are NOT VALID
/* HUG_PSQL_EXTRAS: Constraints that are NOT VALID */
SELECT
    nsp.nspname AS schema,
    rel.relname AS table,
    con.conname AS constraint,
    con.contype,
    fnsp.nspname AS referenced_schema,
    frel.relname AS referenced_table
FROM
    pg_catalog.pg_constraint con
    INNER JOIN pg_catalog.pg_class rel ON rel.oid = con.conrelid
    LEFT JOIN pg_catalog.pg_class frel ON frel.oid = con.confrelid
    LEFT JOIN pg_catalog.pg_namespace nsp ON nsp.oid = con.connamespace
    LEFT JOIN pg_catalog.pg_namespace fnsp ON fnsp.oid = frel.relnamespace
WHERE
    con.convalidated = FALSE
    AND nsp.nspname NOT IN ('pg_catalog', 'information_schema')
    AND nsp.nspname !~ '^pg_toast'
    AND nsp.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    nsp.nspname,
    rel.relname,
    con.conname;

-- :name sequences
-- :command :query
-- :result :many
-- :doc Sequences near exhaustion or on small integer types
/* HUG_PSQL_EXTRAS: Sequences near exhaustion or on small integer types */
SELECT
    schemaname AS schema,
    sequencename AS sequence,
    data_type::text AS data_type,
    start_value,
    increment_by,
    cycle,
    last_value,
    max_value,
    CASE WHEN max_value IS NULL THEN
        NULL
    ELSE
        COALESCE(last_value, start_value)::numeric / max_value
    END AS used_ratio
FROM
    pg_sequences
WHERE
    schemaname NOT IN ('pg_catalog', 'information_schema')
    AND schemaname !~ '^pg_toast'
    AND schemaname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    used_ratio DESC NULLS LAST,
    schema,
    sequence;

-- :name replication-lag
-- :command :query
-- :result :one
-- :doc Standby replication lag in seconds
/* HUG_PSQL_EXTRAS: Standby replication lag in seconds */
SELECT
    CASE WHEN NOT pg_is_in_recovery()
        OR pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN
        0
    ELSE
        EXTRACT(EPOCH FROM now() - pg_last_xact_replay_timestamp())
    END AS lag_seconds;

-- :name replication-slots
-- :command :query
-- :result :many
-- :doc Replication slots with lag and retention
/* HUG_PSQL_EXTRAS: Replication slots with lag and retention */
SELECT
    slot_name,
    slot_type,
    database,
    active,
    wal_status,
    restart_lsn::text AS restart_lsn,
    confirmed_flush_lsn::text AS confirmed_flush_lsn,
    pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS restart_lag_bytes,
    CASE WHEN slot_type = 'logical' THEN
        pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)
    ELSE
        NULL
    END AS lag_bytes
FROM
    pg_replication_slots
ORDER BY
    slot_name;

-- :name transaction-id
-- :command :query
-- :result :many
-- :doc Transaction ID wraparound risk per database
/* HUG_PSQL_EXTRAS: Transaction ID wraparound risk per database */
SELECT
    datname,
    age(datfrozenxid) AS xid_age,
    age(datfrozenxid)::numeric / 2000000000 AS wrap_risk_ratio
FROM
    pg_database
ORDER BY
    xid_age DESC;

-- :name maintenance
-- :command :query
-- :result :many
-- :doc Last vacuum/analyze and their counts per table
/* HUG_PSQL_EXTRAS: Last vacuum/analyze and their counts per table */
SELECT
    schemaname AS schema,
    relname AS table,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze,
    vacuum_count,
    autovacuum_count,
    analyze_count,
    autoanalyze_count
FROM
    pg_stat_user_tables
WHERE
    schemaname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    1,
    2;

-- :name unused-tables
-- :command :query
-- :result :many
-- :doc Tables never accessed via an index
/* HUG_PSQL_EXTRAS: Tables never accessed via an index */
SELECT
    schemaname AS schema,
    relname AS table,
    seq_scan,
    idx_scan,
    n_live_tup AS estimated_rows,
    pg_total_relation_size(relid) AS size_bytes
FROM
    pg_stat_user_tables
WHERE
    schemaname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
    AND idx_scan = 0
ORDER BY
    size_bytes DESC,
    1,
    2;

-- :name missing-indexes
-- :command :query
-- :result :many
-- :doc Tables with low index usage and many rows
/* HUG_PSQL_EXTRAS: Tables with low index usage and many rows */
SELECT
    schemaname AS schema,
    relname AS table,
    idx_scan,
    seq_scan,
    n_live_tup AS estimated_rows,
    CASE WHEN (seq_scan + idx_scan) = 0 THEN
        0
    ELSE
        idx_scan::float / (seq_scan + idx_scan)
    END AS index_usage_ratio
FROM
    pg_stat_user_tables
WHERE
    schemaname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
    AND idx_scan > 0
    AND idx_scan::float / (seq_scan + idx_scan) < 0.95
    AND n_live_tup >= 10000
ORDER BY
    n_live_tup DESC,
    relname;

-- :name connection-states
-- :command :query
-- :result :many
-- :doc Connections grouped by state
/* HUG_PSQL_EXTRAS: Connections grouped by state */
SELECT
    state,
    count(*) AS connections
FROM
    pg_stat_activity
GROUP BY
    state
ORDER BY
    connections DESC,
    state;
