-----------------------------------------------------------------
-- postgres_extras.sql
-- Copyright 2024 perrygeo
--
-- A toolbox for diagnosing PostgreSQL databases in Clojure.
--
-- The SQL query logic is based entirely on
-- the great work done by these two projects:
--   * https://github.com/heroku/heroku-pg-extras/tree/main
--   * https://github.com/pawurb/ecto_psql_extras/tree/main
--
-- MIT Licensed
--
-- ----
-- Copyright (c) 2020 Pawel Urbanek.
--
-- Permission is hereby granted, free of charge, to any person obtaining
-- a copy of this software and associated documentation files (the
-- "Software"), to deal in the Software without restriction, including
-- without limitation the rights to use, copy, modify, merge, publish,
-- distribute, sublicense, and/or sell copies of the Software, and to
-- permit persons to whom the Software is furnished to do so, subject to
-- the following conditions:
--
-- The above copyright notice and this permission notice shall be
-- included in all copies or substantial portions of the Software.
--
-- ----
-- Copyright © Heroku 2008 - 2023
--
-- Permission is hereby granted, free of charge, to any person obtaining
-- a copy of this software and associated documentation files (the
-- "Software"), to deal in the Software without restriction, including
-- without limitation the rights to use, copy, modify, merge, publish,
-- distribute, sublicense, and/or sell copies of the Software, and to
-- permit persons to whom the Software is furnished to do so, subject to
-- the following conditions:
--
-- The above copyright notice and this permission notice shall be
-- included in all copies or substantial portions of the Software.
-- ----
--
-- CHANGES:
-- * ported the queries to HugSQL syntax, https://www.hugsql.org/
-- * formatted the SQL code with pgFormat
-----------------------------------------------------------------
--
-- :name all-locks
-- :command :query
-- :result :many
-- :doc Queries with active locks
/* HUG_PSQL_EXTRAS: Queries with active locks */
SELECT
    pg_stat_activity.pid,
    pg_class.relname,
    pg_locks.transactionid,
    pg_locks.granted,
    pg_locks.mode,
    pg_stat_activity.query AS query_snippet,
    age(now(), pg_stat_activity.query_start) AS "age"
FROM
    pg_stat_activity,
    pg_locks
    LEFT OUTER JOIN pg_class ON (pg_locks.relation = pg_class.oid)
WHERE
    pg_stat_activity.query <> '<insufficient privilege>'
    AND pg_locks.pid = pg_stat_activity.pid
    AND pg_stat_activity.pid <> pg_backend_pid()
ORDER BY
    query_start;

-- :name bloat
-- :command :query
-- :result :many
-- :doc Table and index bloat in your database ordered by most wasteful
/* HUG_PSQL_EXTRAS: Table and index bloat in your database ordered by most wasteful */
WITH constants AS (
    SELECT
        current_setting('block_size')::numeric AS bs,
        23 AS hdr,
        4 AS ma
),
bloat_info AS (
    SELECT
        ma,
        bs,
        schemaname,
        tablename,
        (datawidth + (hdr + ma - (
                    CASE WHEN hdr % ma = 0 THEN
                        ma
                    ELSE
                        hdr % ma
                    END)))::numeric AS datahdr,
        (maxfracsum * (nullhdr + ma - (
                    CASE WHEN nullhdr % ma = 0 THEN
                        ma
                    ELSE
                        nullhdr % ma
                    END))) AS nullhdr2
FROM (
    SELECT
        schemaname,
        tablename,
        hdr,
        ma,
        bs,
        SUM((1 - null_frac) * avg_width) AS datawidth,
    MAX(null_frac) AS maxfracsum,
    hdr + (
        SELECT
            1 + count(*) / 8
        FROM
            pg_stats s2
        WHERE
            null_frac <> 0
            AND s2.schemaname = s.schemaname
            AND s2.tablename = s.tablename) AS nullhdr
    FROM
        pg_stats s,
        constants
    GROUP BY
        1,
        2,
        3,
        4,
        5) AS foo
),
table_bloat AS (
    SELECT
        schemaname,
        tablename,
        cc.relpages,
        bs,
        CEIL((cc.reltuples * ((datahdr + ma - (
                    CASE WHEN datahdr % ma = 0 THEN
                        ma
                    ELSE
                        datahdr % ma
                    END)) + nullhdr2 + 4)) / (bs - 20::float)) AS otta
    FROM
        bloat_info
        JOIN pg_class cc ON cc.relname = bloat_info.tablename
        JOIN pg_namespace nn ON cc.relnamespace = nn.oid
            AND nn.nspname = bloat_info.schemaname
            AND nn.nspname <> 'information_schema'
),
index_bloat AS (
    SELECT
        schemaname,
        tablename,
        bs,
        COALESCE(c2.relname, '?') AS iname,
        COALESCE(c2.reltuples, 0) AS ituples,
        COALESCE(c2.relpages, 0) AS ipages,
        COALESCE(CEIL((c2.reltuples * (datahdr - 12)) / (bs - 20::float)), 0) AS iotta -- very rough approximation, assumes all cols
    FROM
        bloat_info
    JOIN pg_class cc ON cc.relname = bloat_info.tablename
    JOIN pg_namespace nn ON cc.relnamespace = nn.oid
        AND nn.nspname = bloat_info.schemaname
        AND nn.nspname <> 'information_schema'
    JOIN pg_index i ON indrelid = cc.oid
    JOIN pg_class c2 ON c2.oid = i.indexrelid
)
SELECT
    type,
    schemaname,
    object_name,
    bloat,
    waste
FROM (
    SELECT
        'table' AS type,
        schemaname,
        tablename AS object_name,
        ROUND(
            CASE WHEN otta = 0 THEN
                0.0
            ELSE
                table_bloat.relpages / otta::numeric
            END, 1) AS bloat,
        CASE WHEN relpages < otta THEN
            0
        ELSE
            (bs * (table_bloat.relpages - otta)::bigint)::bigint
        END AS waste
    FROM
        table_bloat
UNION
SELECT
    'index' AS type,
    schemaname,
    tablename || '::' || iname AS object_name,
    ROUND(
        CASE WHEN iotta = 0
            OR ipages = 0 THEN
            0.0
        ELSE
            ipages / iotta::numeric
        END, 1) AS bloat,
    CASE WHEN ipages < iotta THEN
        0
    ELSE
        (bs * (ipages - iotta))::bigint
    END AS waste
FROM
    index_bloat) bloat_summary
ORDER BY
    waste DESC,
    bloat DESC;

-- :name blocking
-- :command :query
-- :result :many
-- :doc Queries holding locks other queries are waiting to be released
/* HUG_PSQL_EXTRAS: Queries holding locks other queries are waiting to be released */
SELECT
    bl.pid AS blocked_pid,
    ka.query AS blocking_statement,
    now() - ka.query_start AS blocking_duration,
    kl.pid AS blocking_pid,
    a.query AS blocked_statement,
    now() - a.query_start AS blocked_duration
FROM
    pg_catalog.pg_locks bl
    JOIN pg_catalog.pg_stat_activity a ON bl.pid = a.pid
    JOIN pg_catalog.pg_locks kl
    JOIN pg_catalog.pg_stat_activity ka ON kl.pid = ka.pid ON bl.transactionid = kl.transactionid
        AND bl.pid != kl.pid
WHERE
    NOT bl.granted;

-- :name cache-hit
-- :command :query
-- :result :many
-- :doc Index and table hit rate
SELECT
    'index hit rate' AS name,
    (sum(idx_blks_hit)) / nullif (sum(idx_blks_hit + idx_blks_read), 0) AS ratio
FROM
    pg_statio_user_indexes
UNION ALL
SELECT
    'table hit rate' AS name,
    sum(heap_blks_hit) / nullif (sum(heap_blks_hit) + sum(heap_blks_read), 0) AS ratio
FROM
    pg_statio_user_tables;

-- :name calls
-- :command :query
-- :result :many
-- :doc Queries that have the highest frequency of execution
/* HUG_PSQL_EXTRAS: Queries that have the highest frequency of execution */
SELECT
    query AS query,
    interval '1 millisecond' * total_exec_time AS exec_time,
    (total_exec_time / sum(total_exec_time) OVER ()) AS exec_time_ratio,
    calls,
    interval '1 millisecond' * (blk_read_time + blk_write_time) AS sync_io_time
FROM
    pg_stat_statements
WHERE
    userid = (
        SELECT
            usesysid
        FROM
            pg_user
        WHERE
            usename = CURRENT_USER
        LIMIT 1)
    AND query NOT LIKE '/* HUG_PSQL_EXTRAS:%'
ORDER BY
    calls DESC
LIMIT :v:limit;

-- :name calls-legacy
-- :command :query
-- :result :many
-- :doc Queries that have the highest frequency of execution (legacy)
/* HUG_PSQL_EXTRAS: Queries that have the highest frequency of execution */
SELECT
    query AS query,
    interval '1 millisecond' * total_time AS exec_time,
    (total_time / sum(total_time) OVER ()) AS exec_time_ratio,
    calls,
    interval '1 millisecond' * (blk_read_time + blk_write_time) AS sync_io_time
FROM
    pg_stat_statements
WHERE
    userid = (
        SELECT
            usesysid
        FROM
            pg_user
        WHERE
            usename = CURRENT_USER
        LIMIT 1)
    AND query NOT LIKE '/* HUG_PSQL_EXTRAS:%'
ORDER BY
    calls DESC
LIMIT 10;

-- :name connections
-- :command :query
-- :result :many
-- :doc Returns the list of all active database connections
/* HUG_PSQL_EXTRAS: Returns the list of all active database connections */
SELECT
    usename AS username,
    client_addr::text AS client_address,
    application_name
FROM
    pg_stat_activity
WHERE
    datname = current_database();

-- :name db-settings
-- :command :query
-- :result :many
-- :doc Values of selected PostgreSQL settings
/* HUG_PSQL_EXTRAS: Values of selected PostgreSQL settings */
SELECT
    name,
    setting,
    unit,
    short_desc
FROM
    pg_settings
WHERE
    name IN ('max_connections', 'shared_buffers', 'effective_cache_size', 'maintenance_work_mem', 'checkpoint_completion_target', 'wal_buffers', 'default_statistics_target', 'random_page_cost', 'effective_io_concurrency', 'work_mem', 'min_wal_size', 'max_wal_size');

-- :name duplicate-indexes
-- :command :query
-- :result :many
-- :doc Multiple indexes that have the same set of columns, same opclass, expression and predicate
/* HUG_PSQL_EXTRAS: Multiple indexes that have the same set of columns, same opclass, expression and predicate */
SELECT
    pg_size_pretty(sum(pg_relation_size(idx))::bigint) AS size,
    (array_agg(idx))[1]::text AS idx1,
    (array_agg(idx))[2]::text AS idx2,
    (array_agg(idx))[3]::text AS idx3,
    (array_agg(idx))[4]::text AS idx4
FROM (
    SELECT
        indexrelid::regclass AS idx,
        (indrelid::text || E'\n' || indclass::text || E'\n' || indkey::text || E'\n' || coalesce(indexprs::text, '') || E'\n' || coalesce(indpred::text, '')) AS key
    FROM
        pg_index) sub
GROUP BY
    key
HAVING
    count(*) > 1
ORDER BY
    sum(pg_relation_size(idx)) DESC;

-- :name extensions
-- :command :query
-- :result :many
-- :doc Available and installed extensions
/* HUG_PSQL_EXTRAS: Available and installed extensions */
SELECT
    name,
    default_version,
    installed_version,
    comment
FROM
    pg_available_extensions
ORDER BY
    installed_version;

-- :name index-cache-hit
-- :command :query
-- :result :many
-- :doc Calculates your cache hit rate for reading indexes
/* HUG_PSQL_EXTRAS: Calculates your cache hit rate for reading indexes */
SELECT
    schemaname AS schema,
    relname AS name,
    idx_blks_hit AS buffer_hits,
    idx_blks_read AS block_reads,
    idx_blks_hit + idx_blks_read AS total_read,
    CASE (idx_blks_hit + idx_blks_read)::float
    WHEN 0 THEN
        NULL
    ELSE
        (idx_blks_hit / (idx_blks_hit + idx_blks_read)::float)
    END ratio
FROM
    pg_statio_user_tables
ORDER BY
    idx_blks_hit / (idx_blks_hit + idx_blks_read + 1)::float DESC;

-- :name index-size
-- :command :query
-- :result :many
-- :doc The size of indexes, descending by size
/* HUG_PSQL_EXTRAS: The size of indexes, descending by size */
SELECT
    n.nspname AS schema,
    c.relname AS name,
    sum(c.relpages::bigint * 8192)::bigint AS size
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON (n.oid = c.relnamespace)
WHERE
    n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND c.relkind = 'i'
GROUP BY
    (n.nspname,
        c.relname)
ORDER BY
    sum(c.relpages) DESC;

-- :name index-usage
-- :command :query
-- :result :many
-- :doc Index hit rate (effective databases are at 99% and up)
/* HUG_PSQL_EXTRAS: Index hit rate (effective databases are at 99% and up) */
SELECT
    schemaname AS schema,
    relname AS name,
    CASE idx_scan
    WHEN 0 THEN
        NULL
    ELSE
        (100 * idx_scan / (seq_scan + idx_scan))
    END percent_of_times_index_used,
    n_live_tup rows_in_table
FROM
    pg_stat_user_tables
ORDER BY
    n_live_tup DESC;

-- :name kill-all!
-- :command :execute
-- :result raw
-- :doc Kill all the active database connections
/* HUG_PSQL_EXTRAS: Kill all the active database connections */
SELECT
    pg_terminate_backend(pid) AS killed
FROM
    pg_stat_activity
WHERE
    pid <> pg_backend_pid()
    AND query <> '<insufficient privilege>'
    AND datname = current_database();

-- :name locks
-- :command :query
-- :result :many
-- :doc Queries with active exclusive locks
/* HUG_PSQL_EXTRAS: Queries with active exclusive locks */
SELECT
    pg_stat_activity.pid,
    pg_class.relname,
    pg_locks.transactionid,
    pg_locks.granted,
    pg_locks.mode,
    pg_stat_activity.query AS query_snippet,
    age(now(), pg_stat_activity.query_start) AS "age"
FROM
    pg_stat_activity,
    pg_locks
    LEFT OUTER JOIN pg_class ON (pg_locks.relation = pg_class.oid)
WHERE
    pg_stat_activity.query <> '<insufficient privilege>'
    AND pg_locks.pid = pg_stat_activity.pid
    AND pg_locks.mode IN ('ExclusiveLock', 'AccessExclusiveLock', 'RowExclusiveLock')
    AND pg_stat_activity.pid <> pg_backend_pid()
ORDER BY
    query_start;

-- :name long-running-queries
-- :command :query
-- :result :many
-- :doc All queries longer than the threshold by descending duration
/* HUG_PSQL_EXTRAS: All queries longer than the threshold by descending duration */
SELECT
    pid,
    now() - pg_stat_activity.query_start AS duration,
    query AS query
FROM
    pg_stat_activity
WHERE
    pg_stat_activity.query <> ''::text
    AND state <> 'idle'
    AND now() - pg_stat_activity.query_start > interval '10ms'
ORDER BY
    now() - pg_stat_activity.query_start DESC;

-- :name mandelbrot
-- :command :query
-- :result :many
-- :doc The mandelbrot set
/* HUG_PSQL_EXTRAS: The mandelbrot set */
WITH RECURSIVE Z (
    IX,
    IY,
    CX,
    CY,
    X,
    Y,
    I
) AS (
    SELECT
        IX,
        IY,
        X::float,
        Y::float,
        X::float,
        Y::float,
        0
    FROM (
        SELECT
            -2.2 + 0.031 * i,
            i
        FROM
            generate_series(0, 101) AS i) AS xgen (x,
        ix),
    (
        SELECT
            -1.5 + 0.031 * i,
            i
        FROM
            generate_series(0, 101) AS i) AS ygen (y,
            iy)
    UNION ALL
    SELECT
        IX,
        IY,
        CX,
        CY,
        X * X - Y * Y + CX AS X,
        Y * X * 2 + CY,
        I + 1
    FROM
        Z
    WHERE
        X * X + Y * Y < 16::float
        AND I < 100
)
SELECT
    array_to_string(array_agg(SUBSTRING(' .,,,-----++++%%%%@@@@#### ', LEAST (GREATEST (I, 1), 27), 1)), '') AS art
FROM (
    SELECT
        IX,
        IY,
        MAX(I) AS I
    FROM
        Z
    GROUP BY
        IY,
        IX
    ORDER BY
        IY,
        IX) AS ZT
GROUP BY
    IY
ORDER BY
    IY;

-- :name null-indexes
-- :command :query
-- :result :many
-- :doc Find indexes with a high ratio of NULL values
/* HUG_PSQL_EXTRAS: Find indexes with a high ratio of NULL values */
SELECT
    c.oid,
    c.relname AS index, pg_size_pretty(pg_relation_size(c.oid)) AS index_size,
        i.indisunique AS unique,
        a.attname AS indexed_column,
        CASE s.null_frac
        WHEN 0 THEN
            ''
        ELSE
            to_char(s.null_frac * 100, '999.00%')
        END AS null_frac,
        pg_size_pretty((pg_relation_size(c.oid) * s.null_frac)::bigint) AS expected_saving
    FROM
        pg_class c
        JOIN pg_index i ON i.indexrelid = c.oid
        JOIN pg_attribute a ON a.attrelid = c.oid
        JOIN pg_class c_table ON c_table.oid = i.indrelid
        JOIN pg_indexes ixs ON c.relname = ixs.indexname
        LEFT JOIN pg_stats s ON s.tablename = c_table.relname
            AND a.attname = s.attname
    WHERE
        -- Primary key cannot be partial
        NOT i.indisprimary
        -- Exclude already partial indexes
        AND i.indpred IS NULL
        -- Exclude composite indexes
        AND array_length(i.indkey, 1) = 1
        -- Exclude indexes without null_frac ratio
        AND coalesce(s.null_frac, 0) != 0
        -- Larger than threshold
        /* min_relation_size_mb = 10 */
        AND pg_relation_size(c.oid) > 10 * 1024 ^ 2
    ORDER BY
        pg_relation_size(c.oid) * s.null_frac DESC;

-- :name outliers
-- :command :query
-- :result :many
-- :doc Queries that have longest execution time in aggregate.
/* HUG_PSQL_EXTRAS: Queries that have longest execution time in aggregate */
SELECT
    query AS query,
    interval '1 millisecond' * total_exec_time AS exec_time,
    (total_exec_time / sum(total_exec_time) OVER ()) AS prop_exec_time,
    calls,
    interval '1 millisecond' * (blk_read_time + blk_write_time) AS sync_io_time
FROM
    pg_stat_statements
WHERE
    userid = (
        SELECT
            usesysid
        FROM
            pg_user
        WHERE
            usename = CURRENT_USER
        LIMIT 1)
    AND query NOT LIKE '/* HUG_PSQL_EXTRAS:%'
ORDER BY
    total_exec_time DESC
LIMIT :v:limit;

-- :name outliers-legacy
-- :command :query
-- :result :many
-- :doc Queries that have longest execution time in aggregate
/* HUG_PSQL_EXTRAS: Queries that have longest execution time in aggregate */
SELECT
    query AS query,
    interval '1 millisecond' * total_time AS exec_time,
    (total_time / sum(total_time) OVER ()) AS prop_exec_time,
    calls,
    interval '1 millisecond' * (blk_read_time + blk_write_time) AS sync_io_time
FROM
    pg_stat_statements
WHERE
    userid = (
        SELECT
            usesysid
        FROM
            pg_user
        WHERE
            usename = CURRENT_USER
        LIMIT 1)
    AND query NOT LIKE '/* HUG_PSQL_EXTRAS:%'
ORDER BY
    total_time DESC
LIMIT 10;

-- :name records-rank
-- :command :query
-- :result :many
-- :doc All tables and the number of rows in each ordered by number of rows descending
/* HUG_PSQL_EXTRAS: All tables and the number of rows in each ordered by number of rows descending */
SELECT
    schemaname AS schema,
    relname AS name,
    n_live_tup AS estimated_count
FROM
    pg_stat_user_tables
ORDER BY
    n_live_tup DESC;

-- :name seq-scans
-- :command :query
-- :result :many
-- :doc Count of sequential scans by table descending by order
/* HUG_PSQL_EXTRAS: Count of sequential scans by table descending by order */
SELECT
    schemaname AS schema,
    relname AS name,
    seq_scan AS count
FROM
    pg_stat_user_tables
ORDER BY
    seq_scan DESC;

-- :name table-cache-hit
-- :command :query
-- :result :many
-- :doc Calculates your cache hit rate for reading tables
/* HUG_PSQL_EXTRAS: Calculates your cache hit rate for reading tables */
SELECT
    schemaname AS schema,
    relname AS name,
    heap_blks_hit AS buffer_hits,
    heap_blks_read AS block_reads,
    heap_blks_hit + heap_blks_read AS total_read,
    CASE (heap_blks_hit + heap_blks_read)::float
    WHEN 0 THEN
        NULL
    ELSE
        (heap_blks_hit / (heap_blks_hit + heap_blks_read)::float)
    END ratio
FROM
    pg_statio_user_tables
ORDER BY
    heap_blks_hit / (heap_blks_hit + heap_blks_read + 1)::float DESC;

-- :name table-indexes-size
-- :command :query
-- :result :many
-- :doc Total size of all the indexes on each table, descending by size
/* HUG_PSQL_EXTRAS: Total size of all the indexes on each table, descending by size */
SELECT
    n.nspname AS schema,
    c.relname AS table,
    pg_indexes_size(c.oid) AS index_size
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON (n.oid = c.relnamespace)
WHERE
    n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND c.relkind IN ('r', 'm')
ORDER BY
    pg_indexes_size(c.oid) DESC;

-- :name table-size
-- :command :query
-- :result :many
-- :doc Size of the tables (excluding indexes), descending by size
/* HUG_PSQL_EXTRAS: Size of the tables (excluding indexes), descending by size */
SELECT
    n.nspname AS schema,
    c.relname AS name,
    pg_table_size(c.oid) AS size
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON (n.oid = c.relnamespace)
WHERE
    n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND c.relkind IN ('r', 'm')
ORDER BY
    pg_table_size(c.oid) DESC;

-- :name total-index-size
-- :command :query
-- :result :many
-- :doc Total size of all indexes in MB
/* HUG_PSQL_EXTRAS: Total size of all indexes in MB */
SELECT
    sum(c.relpages::bigint * 8192)::bigint AS size
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON (n.oid = c.relnamespace)
WHERE
    n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND c.relkind = 'i';

-- :name total-table-size
-- :command :query
-- :result :many
-- :doc Size of the tables (including indexes), descending by size
/* HUG_PSQL_EXTRAS: Size of the tables (including indexes), descending by size */
SELECT
    n.nspname AS schema,
    c.relname AS name,
    pg_total_relation_size(c.oid) AS size
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON (n.oid = c.relnamespace)
WHERE
    n.nspname NOT IN ('pg_catalog', 'information_schema')
    AND n.nspname !~ '^pg_toast'
    AND c.relkind IN ('r', 'm')
ORDER BY
    pg_total_relation_size(c.oid) DESC;

-- :name unused-indexes
-- :command :query
-- :result :many
-- :doc Unused and almost unused indexes
/* HUG_PSQL_EXTRAS: Unused and almost unused indexes */
/* Ordered by their size relative to the number of index scans.
 Exclude indexes of very small tables (less than 5 pages),
 where the planner will almost invariably select a sequential scan,
 but may not in the future as the table grows */
SELECT
    schemaname AS schema,
    relname AS table,
    indexrelname AS index, pg_relation_size(i.indexrelid) AS index_size,
        idx_scan AS index_scans
    FROM
        pg_stat_user_indexes ui
        JOIN pg_index i ON ui.indexrelid = i.indexrelid
    WHERE
        NOT indisunique
        AND idx_scan < :v:min_scans
        AND pg_relation_size(relid) > 5 * 8192
    ORDER BY
        pg_relation_size(i.indexrelid) / nullif (idx_scan, 0) DESC NULLS FIRST,
        pg_relation_size(i.indexrelid) DESC;

-- :name vacuum-stats
-- :command :query
-- :result :many
-- :doc Dead rows and whether an automatic vacuum is expected to be triggered
/* HUG_PSQL_EXTRAS: Dead rows and whether an automatic vacuum is expected to be triggered */
WITH table_opts AS (
    SELECT
        pg_class.oid,
        relname,
        nspname,
        array_to_string(reloptions, '') AS relopts
    FROM
        pg_class
        INNER JOIN pg_namespace ns ON relnamespace = ns.oid
),
vacuum_settings AS (
    SELECT
        oid,
        relname,
        nspname,
        CASE WHEN relopts LIKE '%autovacuum_vacuum_threshold%' THEN
            substring(relopts, '.*autovacuum_vacuum_threshold=([0-9.]+).*')::integer
        ELSE
            current_setting('autovacuum_vacuum_threshold')::integer
        END AS autovacuum_vacuum_threshold,
        CASE WHEN relopts LIKE '%autovacuum_vacuum_scale_factor%' THEN
            substring(relopts, '.*autovacuum_vacuum_scale_factor=([0-9.]+).*')::real
        ELSE
            current_setting('autovacuum_vacuum_scale_factor')::real
        END AS autovacuum_vacuum_scale_factor
    FROM
        table_opts
)
SELECT
    vacuum_settings.nspname AS schema,
    vacuum_settings.relname AS table,
    to_char(psut.last_vacuum, 'YYYY-MM-DD HH24:MI') AS last_vacuum,
    to_char(psut.last_autovacuum, 'YYYY-MM-DD HH24:MI') AS last_autovacuum,
    to_char(pg_class.reltuples, '9G999G999G999') AS rowcount,
    to_char(psut.n_dead_tup, '9G999G999G999') AS dead_rowcount,
    to_char(autovacuum_vacuum_threshold + (autovacuum_vacuum_scale_factor::numeric * pg_class.reltuples), '9G999G999G999') AS autovacuum_threshold,
    CASE WHEN autovacuum_vacuum_threshold + (autovacuum_vacuum_scale_factor::numeric * pg_class.reltuples) < psut.n_dead_tup THEN
        'yes'
    END AS expect_autovacuum
FROM
    pg_stat_user_tables psut
    INNER JOIN pg_class ON psut.relid = pg_class.oid
    INNER JOIN vacuum_settings ON pg_class.oid = vacuum_settings.oid
ORDER BY
    1;

-- :name health-check
-- :command :query
-- :result :one
-- :doc Checks the db for liveliness
/* HUG_PSQL_EXTRAS: Checks the db for liveliness */
SELECT
    now() AS now,
    version() AS version;

