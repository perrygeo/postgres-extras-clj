-----------------------------------------------------------------
-- postgres_native.sql
-- Copyright 2025 perrygeo
--
-- Read-only diagnostics over PostgreSQL system catalogs. Catalog and
-- column semantics derive from the PostgreSQL documentation.
--
-- PostgreSQL Database Management System
-- (formerly known as Postgres, then as Postgres95)
--
-- Portions Copyright (c) 1996-2025, The PostgreSQL Global Development Group
--
-- Portions Copyright (c) 1994, The Regents of the University of California
--
-- Permission to use, copy, modify, and distribute this software and its
-- documentation for any purpose, without fee, and without a written agreement
-- is hereby granted, provided that the above copyright notice and this
-- paragraph and the following two paragraphs appear in all copies.
--
-- IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR
-- DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING
-- LOST PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS
-- DOCUMENTATION, EVEN IF THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE
-- POSSIBILITY OF SUCH DAMAGE.
--
-- THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
-- INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
-- AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE PROVIDED HEREUNDER IS
-- ON AN "AS IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATIONS TO
-- PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
-----------------------------------------------------------------
--
-- :name wal-stats
-- :command :query
-- :result :one
-- :doc WAL activity (PostgreSQL 14+)
/* HUG_PSQL_EXTRAS: WAL activity (PostgreSQL 14+) */
SELECT
    wal_records,
    wal_fpi,
    wal_bytes,
    wal_buffers_full
FROM
    pg_stat_wal;

-- :name wal-archiver
-- :command :query
-- :result :one
-- :doc WAL archiving health
/* HUG_PSQL_EXTRAS: WAL archiving health */
SELECT
    archived_count,
    failed_count,
    last_archived_wal,
    last_archived_time,
    last_failed_wal,
    last_failed_time,
    current_setting('archive_mode') AS archive_mode
FROM
    pg_stat_archiver;

-- :name roles
-- :command :query
-- :result :many
-- :doc Roles and their privilege/expiry attributes
/* HUG_PSQL_EXTRAS: Roles and their privilege/expiry attributes */
SELECT
    rolname AS role,
    rolsuper AS super,
    rolinherit AS inherit,
    rolcreaterole AS create_role,
    rolcreatedb AS create_db,
    rolcanlogin AS can_login,
    rolreplication AS replication,
    rolconnlimit AS conn_limit,
    rolvaliduntil AS valid_until,
    rolbypassrls AS bypass_rls
FROM
    pg_roles
ORDER BY
    rolname;

-- :name database-stats
-- :command :query
-- :result :many
-- :doc Cumulative database-level counters
/* HUG_PSQL_EXTRAS: Cumulative database-level counters */
SELECT
    datname AS database,
    numbackends,
    xact_commit,
    xact_rollback,
    blks_read,
    blks_hit,
    tup_returned,
    tup_fetched,
    tup_inserted,
    tup_updated,
    tup_deleted,
    conflicts,
    temp_files,
    temp_bytes,
    deadlocks,
    checksum_failures,
    stats_reset,
    CASE WHEN (blks_hit + blks_read) = 0 THEN
        0
    ELSE
        blks_hit::float / (blks_hit + blks_read)
    END AS cache_hit_ratio,
    CASE WHEN (xact_commit + xact_rollback) = 0 THEN
        0
    ELSE
        xact_rollback::float / (xact_commit + xact_rollback)
    END AS rollback_ratio
FROM
    pg_stat_database
WHERE
    datname IS NOT NULL
ORDER BY
    datname;

-- :name replication-stats
-- :command :query
-- :result :many
-- :doc Standby connections and per-standby lag from the primary
/* HUG_PSQL_EXTRAS: Standby connections and per-standby lag from the primary */
SELECT
    usename,
    application_name,
    client_addr::text AS client_addr,
    state,
    sync_state,
    sent_lsn::text AS sent_lsn,
    write_lsn::text AS write_lsn,
    flush_lsn::text AS flush_lsn,
    replay_lsn::text AS replay_lsn,
    EXTRACT(EPOCH FROM write_lag) AS write_lag_seconds,
    EXTRACT(EPOCH FROM flush_lag) AS flush_lag_seconds,
    EXTRACT(EPOCH FROM replay_lag) AS replay_lag_seconds,
    reply_time
FROM
    pg_stat_replication
ORDER BY
    application_name NULLS LAST;

-- :name bgwriter-stats-legacy
-- :command :query
-- :result :one
-- :doc Checkpoint and background writer activity (PostgreSQL < 17)
/* HUG_PSQL_EXTRAS: Checkpoint and background writer activity (PostgreSQL < 17) */
SELECT
    checkpoints_timed,
    checkpoints_req,
    checkpoint_write_time,
    checkpoint_sync_time,
    buffers_checkpoint,
    buffers_clean,
    maxwritten_clean,
    buffers_alloc,
    CASE WHEN (checkpoints_timed + checkpoints_req) = 0 THEN
        0
    ELSE
        checkpoints_req::float / (checkpoints_timed + checkpoints_req)
    END AS checkpoint_req_ratio
FROM
    pg_stat_bgwriter;

-- :name bgwriter-stats-pg17
-- :command :query
-- :result :one
-- :doc Checkpoint and background writer activity (PostgreSQL >= 17)
/* HUG_PSQL_EXTRAS: Checkpoint and background writer activity (PostgreSQL >= 17) */
SELECT
    c.num_timed AS checkpoints_timed,
    c.num_requested AS checkpoints_req,
    c.write_time AS checkpoint_write_time,
    c.sync_time AS checkpoint_sync_time,
    c.buffers_written AS buffers_checkpoint,
    b.buffers_clean AS buffers_clean,
    b.maxwritten_clean AS maxwritten_clean,
    b.buffers_alloc AS buffers_alloc,
    CASE WHEN (c.num_timed + c.num_requested) = 0 THEN
        0
    ELSE
        c.num_requested::float / (c.num_timed + c.num_requested)
    END AS checkpoint_req_ratio
FROM
    pg_stat_checkpointer c
    CROSS JOIN pg_stat_bgwriter b;

-- :name unindexed-foreign-keys
-- :command :query
-- :result :many
-- :doc Foreign keys without a leading index on the referencing columns
/* HUG_PSQL_EXTRAS: Foreign keys without a leading index on the referencing columns */
SELECT
    n.nspname AS schema,
    c.relname AS table,
    con.conname AS constraint,
    fn.nspname AS foreign_schema,
    fc.relname AS foreign_table
FROM
    pg_constraint con
    INNER JOIN pg_class c ON c.oid = con.conrelid
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
    INNER JOIN pg_class fc ON fc.oid = con.confrelid
    INNER JOIN pg_namespace fn ON fn.oid = fc.relnamespace
WHERE
    con.contype = 'f'
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
    AND NOT EXISTS (
        SELECT
            1
        FROM
            pg_index i
        WHERE
            i.indrelid = con.conrelid
            AND i.indisvalid
            AND i.indnkeyatts >= array_length(con.conkey, 1)
            AND NOT EXISTS (
                SELECT
                    1
                FROM
                    generate_subscripts(con.conkey, 1) AS gs (pos)
                WHERE
                    con.conkey[gs.pos] <> i.indkey[gs.pos - 1]))
    ORDER BY
        n.nspname,
        c.relname,
        con.conname;

-- :name tables-without-primary-key
-- :command :query
-- :result :many
-- :doc Tables without a primary key
/* HUG_PSQL_EXTRAS: Tables without a primary key */
SELECT
    n.nspname AS schema,
    c.relname AS table
FROM
    pg_class c
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE
    c.relkind = 'r'
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
    AND NOT EXISTS (
        SELECT
            1
        FROM
            pg_constraint con
        WHERE
            con.conrelid = c.oid
            AND con.contype = 'p')
ORDER BY
    n.nspname,
    c.relname;

-- :name autovacuum-disabled
-- :command :query
-- :result :many
-- :doc Tables with autovacuum disabled
/* HUG_PSQL_EXTRAS: Tables with autovacuum disabled */
SELECT
    n.nspname AS schema,
    c.relname AS table,
    c.reloptions
FROM
    pg_class c
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE
    c.relkind IN ('r', 'm')
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
    AND c.reloptions IS NOT NULL
    AND array_to_string(c.reloptions, ',')
    LIKE '%autovacuum_enabled=false%'
ORDER BY
    n.nspname,
    c.relname;

-- :name idle-in-transaction
-- :command :query
-- :result :many
-- :doc Sessions idle in transaction (vacuum blockers)
/* HUG_PSQL_EXTRAS: Sessions idle in transaction (vacuum blockers) */
SELECT
    pid,
    usename,
    application_name,
    xact_start,
    state_change,
    query
FROM
    pg_stat_activity
WHERE
    state = 'idle in transaction'
    AND pid <> pg_backend_pid()
ORDER BY
    xact_start;

-- :name unlogged-tables
-- :command :query
-- :result :many
-- :doc Unlogged tables (data lost on crash)
/* HUG_PSQL_EXTRAS: Unlogged tables (data lost on crash) */
SELECT
    n.nspname AS schema,
    c.relname AS table
FROM
    pg_class c
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE
    c.relkind IN ('r', 'm')
    AND c.relpersistence = 'u'
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    n.nspname,
    c.relname;

-- :name sequence-origin
-- :command :query
-- :result :many
-- :doc How each sequence was created: serial, identity, or explicit CREATE SEQUENCE
/* HUG_PSQL_EXTRAS: How each sequence was created: serial, identity, or explicit CREATE SEQUENCE */
SELECT
    n.nspname AS schema,
    c.relname AS sequence,
    tc.relname AS table,
    a.attname AS column,
    CASE WHEN a.attidentity = 'a' THEN
        'identity always'
    WHEN a.attidentity = 'd' THEN
        'identity by default'
    WHEN d.deptype = 'a' THEN
        'serial'
    ELSE
        'explicit'
    END AS origin
FROM
    pg_class c
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_depend d ON d.objid = c.oid
        AND d.classid = 'pg_class'::regclass
        AND d.refclassid = 'pg_class'::regclass
        AND d.deptype IN ('a', 'i')
    LEFT JOIN pg_class tc ON tc.oid = d.refobjid
    LEFT JOIN pg_attribute a ON a.attrelid = d.refobjid
        AND a.attnum = d.refobjsubid
WHERE
    c.relkind = 'S'
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    n.nspname,
    c.relname;
