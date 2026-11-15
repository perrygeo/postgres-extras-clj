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

-- :name stats-collector-drift-raw
-- :command :query
-- :result :many
-- :doc Probability of drift between the planner estimates and the statistics collector. 0 = perfect agreement, 1 = total disagreement.
/* HUG_PSQL_EXTRAS: Probability of drift between the planner estimates and the statistics collector. 0 = perfect agreement, 1 = total disagreement. */
WITH drift AS (
    SELECT
        c.oid,
        n.nspname AS schema,
        c.relname AS relname,
        pg_total_relation_size(c.oid) AS total_size_bytes,
        GREATEST (c.reltuples, 0)::bigint AS rows_planner_estimate,
        s.n_live_tup AS rows_stats_estimate,
        s.n_mod_since_analyze AS modified_since_analyze,
        s.last_analyze,
        s.last_autoanalyze,
        s.last_vacuum,
        s.last_autovacuum,
        CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
            OR n.nspname = 'information_schema'::name THEN
            TRUE
        ELSE
            FALSE
        END AS system_object
    FROM
        pg_class c
        INNER JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
    WHERE
        c.relkind IN ('r', 'm'))
SELECT
    SCHEMA,
    relname AS table,
    total_size_bytes,
    rows_planner_estimate,
    rows_stats_estimate,
    modified_since_analyze,
    last_analyze,
    last_autoanalyze,
    last_vacuum,
    last_autovacuum,
    system_object,
    CASE WHEN rows_stats_estimate IS NULL THEN
        NULL
    WHEN rows_planner_estimate = 0
        AND rows_stats_estimate = 0 THEN
        0.0
    WHEN rows_planner_estimate = 0
        OR rows_stats_estimate = 0 THEN
        1.0
    ELSE
        1.0 - LEAST (rows_planner_estimate, rows_stats_estimate)::double precision / GREATEST (rows_planner_estimate, rows_stats_estimate)::double precision
    END AS drift_probability
FROM
    drift
ORDER BY
    total_size_bytes DESC;

-- :name obsolete-types
-- :command :query
-- :result :many
-- :doc Columns using obsolete or discouraged data types (money, serial, char,
-- length-limited varchar, time and timestamp without time zone).
/* HUG_PSQL_EXTRAS: Columns using obsolete or discouraged data types (money, serial, char, length-limited varchar, time and timestamp without time zone). */
WITH obsolete AS (
    SELECT
        n.nspname AS schema,
        c.relname AS relname,
        a.attname AS attname,
        format_type(a.atttypid, a.atttypmod) AS data_type,
        CASE WHEN t.typname = 'money' THEN
            'money'
        WHEN t.typname = 'bpchar' THEN
            'char'
        WHEN t.typname = 'varchar'
            AND a.atttypmod <> -1 THEN
            'varchar'
        WHEN t.typname = 'time' THEN
            'time'
        WHEN t.typname = 'timestamp' THEN
            'timestamp'
        WHEN t.typname IN ('int2', 'int4', 'int8')
            AND a.attidentity = ''
            AND ad.adbin IS NOT NULL
            AND pg_get_expr(ad.adbin, ad.adrelid) LIKE 'nextval(%' THEN
            'serial'
        END AS obsolete_type
    FROM
        pg_attribute a
        INNER JOIN pg_class c ON c.oid = a.attrelid
        INNER JOIN pg_namespace n ON n.oid = c.relnamespace
        INNER JOIN pg_type t ON t.oid = a.atttypid
        LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid
            AND ad.adnum = a.attnum
    WHERE
        a.attnum > 0
        AND NOT a.attisdropped
        AND c.relkind IN ('r', 'p', 'm')
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
        AND n.nspname !~ '^pg_toast'
        AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)')
SELECT
    schema,
    relname AS table,
    attname AS column,
    data_type,
    obsolete_type,
    CASE obsolete_type
        WHEN 'money' THEN
            'use numeric'
        WHEN 'char' THEN
            'use text or varchar'
        WHEN 'varchar' THEN
            'use text with a length CHECK'
        WHEN 'time' THEN
            'use timetz or timestamptz'
        WHEN 'timestamp' THEN
            'use timestamptz'
        WHEN 'serial' THEN
            'use GENERATED ... AS IDENTITY'
    END AS recommendation
FROM
    obsolete
WHERE
    obsolete_type IS NOT NULL
ORDER BY
    schema,
    relname,
    attname;

-- :name character-sets-pg14
-- :command :query
-- :result :many
-- :doc Per-database encoding, locale, and collation (PostgreSQL < 15, before
-- the ICU locale provider and database-level collation version tracking).
/* HUG_PSQL_EXTRAS: Per-database encoding, locale, and collation (PostgreSQL < 15, before the ICU locale provider and database-level collation version tracking). */
SELECT
    d.datname AS database,
    pg_encoding_to_char(d.encoding) AS encoding,
    d.datcollate AS lc_collate,
    d.datctype AS lc_ctype,
    NULL::text AS collation_provider,
    NULL::text AS collation_version
FROM
    pg_database d
ORDER BY
    d.datname;

-- :name character-sets-pg15
-- :command :query
-- :result :many
-- :doc Per-database encoding, locale, and collation (PostgreSQL >= 15).
/* HUG_PSQL_EXTRAS: Per-database encoding, locale, and collation (PostgreSQL >= 15). */
SELECT
    d.datname AS database,
    pg_encoding_to_char(d.encoding) AS encoding,
    d.datcollate AS lc_collate,
    d.datctype AS lc_ctype,
    CASE d.datlocprovider
        WHEN 'c' THEN
            'libc'
        WHEN 'i' THEN
            'icu'
        WHEN 'b' THEN
            'builtin'
    END AS collation_provider,
    d.datcollversion AS collation_version
FROM
    pg_database d
ORDER BY
    d.datname;

-- :name toast
-- :command :query
-- :result :many
-- :doc TOAST tables with their sizes and the columns eligible for out-of-line storage.
-- toast_size_bytes is the total TOAST relation (chunk data + index),
-- toast_data_size_bytes is just the out-of-line chunk storage, and
-- toast_columns lists every non-PLAIN (toastable) column with its storage
-- strategy: external (never compressed, moved out-of-line when oversized),
-- extended (compressed, moved out-of-line only if still too large), or main
-- (kept inline unless the row itself overflows the page).
/* HUG_PSQL_EXTRAS: TOAST tables with their sizes and the columns eligible for out-of-line storage. */
SELECT
    n.nspname AS schema,
    c.relname AS table,
    t.relname AS toast_table,
    pg_total_relation_size(t.oid) AS toast_size_bytes,
    pg_relation_size(t.oid) AS toast_data_size_bytes,
    pg_indexes_size(t.oid) AS toast_index_size_bytes,
    (
        SELECT
            string_agg(a.attname || ' (' || CASE a.attstorage
                WHEN 'e' THEN
                    'external'
                WHEN 'x' THEN
                    'extended'
                WHEN 'm' THEN
                    'main'
            END || ')', ', ' ORDER BY a.attnum)
        FROM
            pg_attribute a
        WHERE
            a.attrelid = c.oid
            AND a.attnum > 0
            AND NOT a.attisdropped
            AND a.attstorage <> 'p') AS toast_columns
FROM
    pg_class c
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
    INNER JOIN pg_class t ON t.oid = c.reltoastrelid
WHERE
    c.relkind IN ('r', 'm')
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    toast_size_bytes DESC;

-- :name row-level-security
-- :command :query
-- :result :many
-- :doc Row level security (RLS) policies with per-table enablement flags.
-- One row per policy; tables with RLS enabled but zero policies are not
-- represented (a locked-down table with no policies is also worth auditing,
-- but that is a separate signal from a defined policy).
/* HUG_PSQL_EXTRAS: Row level security (RLS) policies with per-table enablement flags. */
SELECT
    n.nspname AS schema,
    c.relname AS table,
    p.polname AS policy,
    CASE p.polcmd
        WHEN 'r' THEN
            'SELECT'
        WHEN 'a' THEN
            'INSERT'
        WHEN 'w' THEN
            'UPDATE'
        WHEN 'd' THEN
            'DELETE'
        WHEN '*' THEN
            'ALL'
    END AS command,
    CASE WHEN p.polpermissive THEN
        'PERMISSIVE'
    ELSE
        'RESTRICTIVE'
    END AS permissive,
    array_to_string((
        SELECT
            array_agg(CASE WHEN r.role_oid = 0 THEN
                'public'
            ELSE
                pg_get_userbyid(r.role_oid)
            END
            ORDER BY r.role_oid)
        FROM
            unnest(p.polroles) AS r(role_oid)), ', ') AS roles,
    pg_get_expr(p.polqual, p.polrelid) AS using_expression,
    pg_get_expr(p.polwithcheck, p.polrelid) AS with_check_expression,
    c.relrowsecurity AS rls_enabled,
    c.relforcerowsecurity AS rls_forced
FROM
    pg_policy p
    INNER JOIN pg_class c ON c.oid = p.polrelid
    INNER JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE
    n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND n.nspname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    n.nspname,
    c.relname,
    p.polname;

-- :name io-stats-legacy
-- :command :query
-- :result :many
-- :doc Lower-level I/O statistics from pg_stat_io (PostgreSQL 16 and 17).
-- read/write/extend/writeback byte volumes are derived from the op_bytes
-- multiplier, which is always the block size on these versions.
/* HUG_PSQL_EXTRAS: Lower-level I/O statistics from pg_stat_io (PostgreSQL 16 and 17). */
SELECT
    backend_type,
    object,
    context,
    reads,
    (reads * op_bytes)::bigint AS read_bytes,
    read_time,
    writes,
    (writes * op_bytes)::bigint AS write_bytes,
    write_time,
    writebacks,
    (writebacks * op_bytes)::bigint AS writeback_bytes,
    writeback_time,
    extends,
    (extends * op_bytes)::bigint AS extend_bytes,
    extend_time,
    hits,
    evictions,
    reuses,
    fsyncs,
    fsync_time,
    stats_reset
FROM
    pg_stat_io
ORDER BY
    backend_type,
    object,
    context;

-- :name io-stats-pg18
-- :command :query
-- :result :many
-- :doc Lower-level I/O statistics from pg_stat_io (PostgreSQL 18+).
-- reads/writes/extends report explicit byte-level columns; writebacks are
-- still counted in BLCKSZ units and are converted to bytes here.
/* HUG_PSQL_EXTRAS: Lower-level I/O statistics from pg_stat_io (PostgreSQL 18+). */
SELECT
    backend_type,
    object,
    context,
    reads,
    read_bytes::bigint AS read_bytes,
    read_time,
    writes,
    write_bytes::bigint AS write_bytes,
    write_time,
    writebacks,
    (writebacks * current_setting('block_size')::bigint)::bigint AS writeback_bytes,
    writeback_time,
    extends,
    extend_bytes::bigint AS extend_bytes,
    extend_time,
    hits,
    evictions,
    reuses,
    fsyncs,
    fsync_time,
    stats_reset
FROM
    pg_stat_io
ORDER BY
    backend_type,
    object,
    context;

-- :name hba-file-rules
-- :command :query
-- :result :many
-- :doc Parsed client-authentication (pg_hba.conf) rules from pg_hba_file_rules.
-- Only successfully parsed rules are returned (error IS NULL). The view is
-- readable only by superusers, so this query fails with SQLSTATE 42501 for
-- other roles.
/* HUG_PSQL_EXTRAS: Parsed client-authentication (pg_hba.conf) rules from pg_hba_file_rules. */
SELECT
    type,
    auth_method,
    address,
    netmask
FROM
    pg_hba_file_rules
WHERE
    error IS NULL
ORDER BY
    line_number;

-- :name tablespaces
-- :command :query
-- :result :many
-- :doc Tablespaces with owner, filesystem location, size, and options.
/* HUG_PSQL_EXTRAS: Tablespaces with owner, filesystem location, size, and options. */
SELECT
    t.spcname AS tablespace,
    pg_get_userbyid(t.spcowner) AS owner,
    pg_tablespace_location(t.oid) AS location,
    pg_tablespace_size(t.oid) AS size_bytes,
    array_to_string(t.spcoptions, ', ') AS options
FROM
    pg_tablespace t
ORDER BY
    t.spcname;

-- :name os-cpu-mem
-- :command :query
-- :result :one
-- :doc Best-effort OS CPU and memory details from Linux /proc files.
-- Reading files outside the data directory requires superuser (or the
-- pg_read_server_files role), so this query fails with SQLSTATE 42501 for
-- other roles and with SQLSTATE 58P01 on non-Linux hosts without /proc.
/* HUG_PSQL_EXTRAS: Best-effort OS CPU and memory details from Linux /proc files. */
SELECT
    pg_read_file('/proc/cpuinfo') AS cpuinfo,
    pg_read_file('/proc/meminfo') AS meminfo,
    version() AS version;

-- :name os-disks
-- :command :query
-- :result :many
-- :doc Best-effort OS block devices and sizes from Linux /sys/block.
-- loop devices are excluded (file-backed, not physical disks). The size
-- file is reported in 512-byte sectors, so it is converted to bytes.
/* HUG_PSQL_EXTRAS: Best-effort OS block devices and sizes from Linux /sys/block. */
SELECT
    b AS device,
    (pg_read_file('/sys/block/' || b || '/size'))::bigint * 512 AS size_bytes
FROM
    pg_ls_dir('/sys/block') AS b
WHERE
    b NOT LIKE 'loop%'
ORDER BY
    b;

-- :name hot-updates
-- :command :query
-- :result :many
-- :doc Heap-only tuple (HOT) update effectiveness per table.
-- A low hot_ratio means most updates are not HOT: either an indexed column
-- is being updated or there is insufficient free space on the page (fillfactor).
/* HUG_PSQL_EXTRAS: Heap-only tuple (HOT) update effectiveness per table. */
WITH table_opts AS (
    SELECT
        pg_class.oid,
        relname,
        nspname,
        array_to_string(reloptions, '') AS relopts
    FROM
        pg_class
        INNER JOIN pg_namespace ns ON relnamespace = ns.oid
)
SELECT
    table_opts.nspname AS schema,
    table_opts.relname AS table,
    psut.n_tup_upd AS updates,
    psut.n_tup_hot_upd AS hot_updates,
    CASE WHEN psut.n_tup_upd = 0 THEN
        NULL
    ELSE
        psut.n_tup_hot_upd::float / psut.n_tup_upd::float
    END AS hot_ratio,
    CASE WHEN relopts LIKE '%fillfactor=%' THEN
        substring(relopts, '.*fillfactor=([0-9]+).*')::integer
    ELSE
        100
    END AS fillfactor
FROM
    pg_stat_user_tables psut
    INNER JOIN table_opts ON table_opts.oid = psut.relid
WHERE
    psut.schemaname !~ '^(_timescaledb_|timescaledb_(information|experimental)$)'
ORDER BY
    (psut.n_tup_upd - psut.n_tup_hot_upd) DESC;
