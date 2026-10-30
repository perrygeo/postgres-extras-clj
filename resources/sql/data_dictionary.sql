--------------------------------------------------
-- Data Dictionary
--
-- Inspect the structure of your database objects.
--
-- Based on the pgdd project at rustprooflabs:
--   https://github.com/rustprooflabs/pgdd
--
-------
-- MIT License
--
-- Copyright (c) 2018 - 2023 Ryan Lambert
--
-- Permission is hereby granted, free of charge, to any person obtaining a copy
-- of this software and associated documentation files (the "Software"), to deal
-- in the Software without restriction, including without limitation the rights
-- to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
-- copies of the Software, and to permit persons to whom the Software is
-- furnished to do so, subject to the following conditions:
--
-- The above copyright notice and this permission notice shall be included in all
-- copies or substantial portions of the Software.
-------
--
-- Changes
-- * remove references to the internal `dd` tables.
-- * centralize system-object detection and treat TimescaleDB's
--   `_timescaledb_*`, `timescaledb_information`, and
--   `timescaledb_experimental` schemas as system objects.
--------------------------------------------------
--
-- :name columns
-- :command :query
-- :result :many
-- :doc List all database column objects
-- Handles generated column details for Pg12+
SELECT
    n.nspname::text AS schema,
    CASE c.relkind
    WHEN 'r'::"char" THEN
        'table'::text
    WHEN 'v'::"char" THEN
        'view'::text
    WHEN 'm'::"char" THEN
        'materialized view'::text
    WHEN 's'::"char" THEN
        'special'::text
    WHEN 'f'::"char" THEN
        'foreign table'::text
    WHEN 'p'::"char" THEN
        'table'::text
    ELSE
        NULL::text
    END AS source_type,
    c.relname::text AS table,
    a.attname::text AS column,
    t.typname::text AS data_type,
    a.attnum AS "position",
    col_description(c.oid, a.attnum::integer) AS description,
    -- mc.data_source,
    -- mc.sensitive,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object,
    (
        SELECT
            pg_catalog.pg_get_expr(d.adbin, d.adrelid, TRUE) AS default_expression
        FROM
            pg_catalog.pg_attrdef d
        WHERE
            d.adrelid = a.attrelid
            AND d.adnum = a.attnum
            AND a.atthasdef) AS default_value,
    CASE WHEN a.attgenerated = '' THEN
        FALSE
    ELSE
        TRUE
    END AS generated_column
FROM
    pg_attribute a
    JOIN pg_class c ON a.attrelid = c.oid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_type t ON a.atttypid = t.oid
    -- LEFT JOIN dd.meta_column mc ON n.nspname = mc.s_name
    --     AND c.relname = mc.t_name
    --     AND a.attname = mc.c_name
WHERE
    a.attnum > 0
    AND (c.relkind = ANY (ARRAY['r'::"char", 'p'::"char", 's'::"char", 'v'::"char", 'f'::"char", 'm'::"char"]));

--
-- :name constraints-raw
-- :command :query
-- :result :many
-- :doc List all constraint objects in current database
-- Named constraints only (PK/unique/FK/check/exclusion/constraint trigger),
-- excluding NOT NULL (contype 'n') which is only a pg_constraint row on
-- PG17+ for domains and PG18+ for table columns.
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    con.conname::text AS constraint,
    CASE con.contype
    WHEN 'p'::"char" THEN
        'primary key'::text
    WHEN 'u'::"char" THEN
        'unique'::text
    WHEN 'f'::"char" THEN
        'foreign key'::text
    WHEN 'c'::"char" THEN
        'check'::text
    WHEN 'x'::"char" THEN
        'exclusion'::text
    WHEN 't'::"char" THEN
        'constraint trigger'::text
    ELSE
        con.contype::text
    END AS type,
    pg_catalog.pg_get_constraintdef(con.oid, TRUE) AS definition,
    con.convalidated AS validated,
    con.condeferrable AS deferrable,
    con.condeferred AS initially_deferred,
    fn.nspname::text AS foreign_schema,
    fc.relname::text AS foreign_table,
    obj_description(con.oid, 'pg_constraint'::name)::text AS description,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_constraint con
    JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_catalog.pg_class fc ON fc.oid = con.confrelid
    LEFT JOIN pg_catalog.pg_namespace fn ON fn.oid = fc.relnamespace
WHERE
    con.contype IN ('p', 'u', 'f', 'c', 'x', 't')
    AND c.relkind IN ('r', 'p', 'f')
ORDER BY
    n.nspname,
    c.relname,
    con.conname;

--
-- :name key-relationships-raw
-- :command :query
-- :result :many
-- :doc List all foreign key relationships, one row per column pair
-- A foreign key may span multiple columns, so a single constraint
-- produces one row per (referencing column, referenced column) pair,
-- ordered by :position. Reassemble with postgres-extras-clj.core/key-relationships.
SELECT
    con.conname::text AS constraint,
    n.nspname::text AS schema,
    c.relname::text AS table,
    a.attname::text AS column,
    k.position::int AS "position",
    fn.nspname::text AS foreign_schema,
    fc.relname::text AS foreign_table,
    fa.attname::text AS foreign_column,
    CASE con.confdeltype
    WHEN 'a'::"char" THEN
        'NO ACTION'::text
    WHEN 'r'::"char" THEN
        'RESTRICT'::text
    WHEN 'c'::"char" THEN
        'CASCADE'::text
    WHEN 'n'::"char" THEN
        'SET NULL'::text
    WHEN 'd'::"char" THEN
        'SET DEFAULT'::text
    ELSE
        con.confdeltype::text
    END AS delete_action,
    CASE con.confupdtype
    WHEN 'a'::"char" THEN
        'NO ACTION'::text
    WHEN 'r'::"char" THEN
        'RESTRICT'::text
    WHEN 'c'::"char" THEN
        'CASCADE'::text
    WHEN 'n'::"char" THEN
        'SET NULL'::text
    WHEN 'd'::"char" THEN
        'SET DEFAULT'::text
    ELSE
        con.confupdtype::text
    END AS update_action,
    CASE con.confmatchtype
    WHEN 'f'::"char" THEN
        'FULL'::text
    WHEN 'p'::"char" THEN
        'PARTIAL'::text
    WHEN 's'::"char" THEN
        'SIMPLE'::text
    ELSE
        con.confmatchtype::text
    END AS match_type,
    con.condeferrable AS deferrable,
    con.condeferred AS initially_deferred,
    con.convalidated AS validated,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_constraint con
    JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_catalog.pg_class fc ON fc.oid = con.confrelid
    JOIN pg_catalog.pg_namespace fn ON fn.oid = fc.relnamespace
    JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(fk_attnum, pk_attnum, position) ON TRUE
    JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid
        AND a.attnum = k.fk_attnum
    JOIN pg_catalog.pg_attribute fa ON fa.attrelid = con.confrelid
        AND fa.attnum = k.pk_attnum
WHERE
    con.contype = 'f'::"char"
    AND c.relkind IN ('r'::"char", 'p'::"char", 'f'::"char")
ORDER BY
    n.nspname,
    c.relname,
    con.conname,
    k.position;

--
-- :name databases
-- :command :query
-- :result :many
-- :doc List all databases
--
WITH db_stats AS (
    SELECT
        d.oid,
        d.datname AS database,
        pg_database_size(d.datname) AS db_size_bytes,
        pg_size_pretty(pg_database_size(d.datname)) AS db_size_pretty
    FROM
        pg_catalog.pg_database d
    WHERE
        d.datname = current_database()
),
-- TODO
-- schema_stats AS (
--     SELECT
--         COUNT(s.s_name) AS schema_count
--     FROM
--         dd.schemas s
-- ),
dd_tablestats AS (
    SELECT
        pg_total_relation_size(c.oid::regclass)::bigint AS size_plus_indexes_bytes,
        c.oid
    FROM
        pg_class c
        LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE
        c.relkind = ANY (ARRAY['r'::"char",
            'p'::"char",
            's'::"char",
            'r'::"char"])
),
table_stats AS (
    SELECT
        COUNT(t.oid) AS table_count,
        SUM(t.size_plus_indexes_bytes) AS size_in_tables_bytes,
        pg_size_pretty(SUM(t.size_plus_indexes_bytes)) AS size_in_tables_pretty
    FROM
        dd_tablestats AS t
),
-- TODO
-- view_stats AS (
--     SELECT
--         COUNT(v.oid) AS view_count,
--         pg_size_pretty(SUM(v.size_plus_indexes_bytes)) AS size_in_views
--     FROM
--         dd.views v
-- ),
extension_stats AS (
    SELECT
        COUNT(e.oid) AS extension_count
    FROM
        pg_catalog.pg_extension e
)
SELECT
    d.oid,
    d.database::text,
    d.db_size_bytes,
    d.db_size_pretty,
    t.table_count,
    t.size_in_tables_bytes,
    t.size_in_tables_pretty,
    -- TODO
    -- s.schema_count,
    -- v.view_count,
    -- v.size_in_views,
    e.extension_count
FROM
    db_stats d
    INNER JOIN table_stats t ON TRUE
    -- INNER JOIN schema_stats s ON TRUE
    -- INNER JOIN view_stats v ON TRUE
    INNER JOIN extension_stats e ON TRUE;

--
-- :name functions
-- :command :query
-- :result :many
-- :doc List all function objects in current database
--
SELECT
    n.nspname::text AS schema,
    p.proname::text AS function, pg_get_function_result(p.oid)::text AS result_data_types, pg_get_function_arguments(p.oid)::text AS argument_data_types, pg_get_userbyid(p.proowner)::text AS owner, CASE WHEN p.prosecdef THEN
        'definer'::text
    ELSE
        'invoker'::text
    END AS proc_security, array_to_string(p.proacl, ''::text) AS access_privileges, l.lanname::text AS proc_language, p.prosrc::text AS source_code, obj_description(p.oid, 'pg_proc'::name)::text AS description, CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_proc p
    LEFT JOIN pg_namespace n ON n.oid = p.pronamespace
    LEFT JOIN pg_language l ON l.oid = p.prolang;

--
-- :name indexes
-- :command :query
-- :result :many
-- :doc List all index objects in current database
--
SELECT
    c.oid,
    n.nspname::text AS schema,
    t.relname::text AS table,
    c.relname::text AS index,
    i.indnkeyatts AS key_columns,
    i.indnatts AS total_columns,
    i.indisprimary AS primary_key,
    i.indisunique AS unique_index,
    i.indisvalid AS valid_index,
    CASE WHEN i.indpred IS NULL THEN
        FALSE
    ELSE
        TRUE
    END AS partial_index,
    c.reltuples AS rows_indexed,
    pg_size_pretty(pg_total_relation_size(c.oid::regclass)) AS index_size_pretty,
    pg_total_relation_size(c.oid::regclass) AS index_size_bytes,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_index i
    INNER JOIN pg_catalog.pg_class c ON c.relkind = 'i'
        AND i.indexrelid = c.oid
    INNER JOIN pg_catalog.pg_class t ON i.indrelid = t.oid
    INNER JOIN pg_catalog.pg_namespace t_n ON t_n.oid = t.relnamespace
    INNER JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace;

--
-- :name schemas
-- :command :query
-- :result :many
-- :doc List all shemas in current database
--
WITH s AS (
    SELECT
        n.oid,
        n.nspname AS schema,
        pg_get_userbyid(n.nspowner) AS owner,
        -- ms.data_source,
        -- ms.sensitive,
        obj_description(n.oid, 'pg_namespace'::name) AS description,
        CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
            OR n.nspname = 'information_schema'::name THEN
            TRUE
        ELSE
            FALSE
        END AS system_object
    FROM
        pg_namespace n
        -- LEFT JOIN dd.meta_schema ms ON n.nspname = ms.s_name
),
f AS (
    SELECT
        n.nspname AS schema,
        count(DISTINCT p.oid) AS function_count
    FROM
        pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
    GROUP BY
        n.nspname
),
v AS (
    SELECT
        n.nspname AS schema,
        count(DISTINCT c_1.oid) AS view_count
    FROM
        pg_class c_1
        JOIN pg_namespace n ON n.oid = c_1.relnamespace
    WHERE
        c_1.relkind = ANY (ARRAY['v'::"char",
            'm'::"char"])
    GROUP BY
        n.nspname
)
SELECT
    s.schema::text,
    s.owner::text,
    s.description::text,
    s.system_object,
    COALESCE(count(c.*), 0::bigint)::bigint AS table_count,
    COALESCE(v.view_count, 0::bigint)::bigint AS view_count,
    COALESCE(f.function_count, 0::bigint)::bigint AS function_count,
    pg_size_pretty(sum(pg_table_size(c.oid::regclass)))::text AS size_pretty,
    pg_size_pretty(sum(pg_total_relation_size(c.oid::regclass)))::text AS size_plus_indexes_pretty,
    sum(pg_table_size(c.oid::regclass))::bigint AS size_bytes,
    sum(pg_total_relation_size(c.oid::regclass))::bigint AS size_plus_indexes_bytes
FROM
    s
    LEFT JOIN pg_class c ON s.oid = c.relnamespace
        AND (c.relkind = ANY (ARRAY['r'::"char", 'p'::"char"]))
    LEFT JOIN f ON f.schema = s.schema
    LEFT JOIN v ON v.schema = s.schema
GROUP BY
    s.schema,
    s.owner,
    s.description,
    s.system_object,
    v.view_count,
    f.function_count;

--
-- :name tables
-- :command :query
-- :result :many
-- :doc List all table objects in current database
--
-- dd."tables" source
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    CASE WHEN c.relkind = ANY (ARRAY['r'::"char", 'p'::"char"]) THEN
        'table'::text
    WHEN c.relkind = 's'::"char" THEN
        'special'::text
    WHEN c.relkind = 'f'::"char" THEN
        'foreign table'::text
    ELSE
        NULL::text
    END AS data_type,
    pg_get_userbyid(c.relowner)::text AS owner,
    pg_size_pretty(pg_table_size(c.oid::regclass))::text AS size_pretty,
    pg_table_size(c.oid::regclass)::bigint AS size_bytes,
    c.reltuples::bigint AS rows,
    CASE WHEN c.reltuples > 0::bigint THEN
        (pg_table_size(c.oid::regclass)::double precision / c.reltuples)::bigint
    ELSE
        NULL::bigint
    END AS bytes_per_row,
    pg_total_relation_size(c.oid::regclass)::bigint AS size_plus_indexes_bytes,
    pg_size_pretty(pg_total_relation_size(c.oid::regclass))::text AS size_plus_indexes_pretty,
    obj_description(c.oid, 'pg_class'::name)::text AS description,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object,
    c.oid
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE
    c.relkind = ANY (ARRAY['r'::"char", 'p'::"char", 's'::"char", 'f'::"char"]);

--
-- :name views
-- :command :query
-- :result :many
-- :doc List all view objects in current database
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS view,
    CASE c.relkind
    WHEN 'v'::"char" THEN
        'view'::text
    WHEN 'm'::"char" THEN
        'materialized view'::text
    ELSE
        NULL::text
    END AS view_type,
    pg_get_userbyid(c.relowner)::text AS owner,
    c.reltuples::bigint AS rows,
    pg_size_pretty(pg_table_size(c.oid::regclass))::text AS size_pretty,
    pg_table_size(c.oid::regclass)::bigint AS size_bytes,
    pg_size_pretty(pg_total_relation_size(c.oid::regclass))::text AS size_plus_indexes_pretty,
    pg_total_relation_size(c.oid::regclass)::bigint AS size_plus_indexes_bytes,
    obj_description(c.oid, 'pg_class'::name)::text AS description,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object,
    c.oid
FROM
    pg_class c
    LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE (c.relkind = ANY (ARRAY['v'::"char", 'm'::"char"]));

-- :name partition-children
-- :command :query
-- :result :many
-- :doc List all child partitions in current database
--
SELECT
    c.oid,
    ns.nspname::text AS schema,
    c.relname::text AS table,
    i.inhparent AS parent_oid,
    i.inhparent::regclass::text AS parent_name,
    c.relispartition::boolean AS declarative_partition,
    pg_catalog.pg_get_expr(c.relpartbound, c.oid)::text AS partition_expression
FROM
    pg_catalog.pg_class c
    INNER JOIN pg_catalog.pg_namespace ns ON c.relnamespace = ns.oid
    INNER JOIN pg_catalog.pg_inherits i ON c.oid = i.inhrelid
    INNER JOIN pg_catalog.pg_class cp ON i.inhparent = cp.oid
WHERE
    c.relkind IN ('r', 'p');

-- :name partition-parents
-- :command :query
-- :result :many
-- :doc List all parent partitions in current database
--
-- Declarative is built-in Postgres partitioning per
--     https://www.postgresql.org/docs/current/ddl-partitioning.html
-- Inheritance includes partitions like Timescale hypertables,
--    but probably includes objects that are not partitions such as
--    https://www.postgresql.org/docs/current/tutorial-inheritance.html
WITH partition_parent AS (
    SELECT
        c.oid,
        n.nspname::text AS schema,
        c.relname::text AS table,
        CASE WHEN pt.partrelid IS NOT NULL THEN
            'declarative'
        WHEN c.relkind = 'r' THEN
            'inheritance'
        ELSE
            'unknown'
        END AS partition_type,
        COUNT(i.inhrelid) AS partitions
    FROM
        pg_catalog.pg_class c
        INNER JOIN pg_catalog.pg_inherits i ON c.oid = i.inhparent
        LEFT JOIN pg_catalog.pg_partitioned_table pt ON c.oid = pt.partrelid
        LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE
        c.relkind != 'I' -- Exclude partitioned indexes
    GROUP BY
        c.relkind,
        c.oid,
        pt.partrelid,
        n.nspname::text,
        c.relname::text
)
SELECT
    *
FROM
    partition_parent;

--
-- :name triggers
-- :command :query
-- :result :many
-- :doc List all trigger objects in current database
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    t.tgname::text AS trigger,
    pn.nspname::text || '.'::text || p.proname::text AS trigger_function,
    CASE WHEN ((t.tgtype::int & 2) <> 0) THEN
        'BEFORE'::text
    WHEN ((t.tgtype::int & 64) <> 0) THEN
        'INSTEAD OF'::text
    ELSE
        'AFTER'::text
    END AS timing,
    trim(trailing ', '::text FROM concat_ws(', '::text,
        CASE WHEN ((t.tgtype::int & 4) <> 0) THEN 'INSERT'::text END,
        CASE WHEN ((t.tgtype::int & 8) <> 0) THEN 'DELETE'::text END,
        CASE WHEN ((t.tgtype::int & 16) <> 0) THEN 'UPDATE'::text END,
        CASE WHEN ((t.tgtype::int & 32) <> 0) THEN 'TRUNCATE'::text END)) AS events,
    CASE WHEN ((t.tgtype::int & 1) <> 0) THEN
        'ROW'::text
    ELSE
        'STATEMENT'::text
    END AS orientation,
    CASE t.tgenabled
    WHEN 'O'::"char" THEN
        'enabled'::text
    WHEN 'D'::"char" THEN
        'disabled'::text
    WHEN 'R'::"char" THEN
        'replica'::text
    WHEN 'A'::"char" THEN
        'always'::text
    END AS enabled,
    t.tgdeferrable AS deferrable,
    t.tginitdeferred AS initially_deferred,
    pg_catalog.pg_get_triggerdef(t.oid, TRUE) AS definition,
    obj_description(t.oid, 'pg_trigger'::name)::text AS description,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_trigger t
    JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
    JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
WHERE
    NOT t.tgisinternal
    AND c.relkind IN ('r', 'p', 'f', 'v')
ORDER BY
    n.nspname,
    c.relname,
    t.tgname;
