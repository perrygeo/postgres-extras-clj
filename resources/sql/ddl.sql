-----------------------------------------------------------------
-- ddl.sql
-- Copyright 2026 perrygeo
--
-- Read-only queries that synthesize CREATE/ALTER DDL from the current
-- state of a live database, the same way DBeaver's "DDL" tab and
-- pg_dump do. PostgreSQL stores only a parsed representation of DDL;
-- these queries reverse-engineer it from the system catalogs via the
-- pg_get_*def decompiler functions plus manual CREATE TABLE assembly.
--
-- Catalog and column semantics derive from the PostgreSQL documentation.
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
-- :name ddl-table-columns
-- :command :query
-- :result :many
-- :doc Columns of tables (relkind r/p/f), with rendered type/default/identity
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    c.oid AS table_oid,
    a.attname::text AS column,
    a.attnum AS position,
    pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
    a.attnotnull AS not_null,
    CASE WHEN a.attidentity = 'a'::"char" THEN
        'always'::text
    WHEN a.attidentity = 'd'::"char" THEN
        'by default'::text
    ELSE
        NULL::text
    END AS identity_kind,
    CASE WHEN a.attgenerated = 's'::"char" THEN
        'stored'::text
    ELSE
        NULL::text
    END AS generated_kind,
    pg_catalog.pg_get_expr(d.adbin, d.adrelid, TRUE) AS default_value,
    CASE WHEN a.attcollation <> t.typcollation
        AND a.attcollation <> 0 THEN
        pg_catalog.quote_ident(cn.nspname) || '.' || pg_catalog.quote_ident(col.collname)
    ELSE
        NULL
    END AS collation,
    pg_catalog.col_description(c.oid, a.attnum::integer) AS column_comment,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_attribute a
    JOIN pg_catalog.pg_class c ON a.attrelid = c.oid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_catalog.pg_type t ON a.atttypid = t.oid
    LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid
        AND d.adnum = a.attnum
    LEFT JOIN pg_catalog.pg_collation col ON col.oid = a.attcollation
    LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = col.collnamespace
WHERE
    a.attnum > 0
    AND c.relkind IN ('r', 'p', 'f')
    AND NOT c.relispartition
ORDER BY
    n.nspname,
    c.relname,
    a.attnum;

-- :name ddl-table-options
-- :command :query
-- :result :many
-- :doc Persistence, storage params, tablespace and partition key of tables
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    c.relkind::text AS relkind,
    c.relpersistence::text AS persistence,
    array_to_string(c.reloptions, ', ') AS storage_params,
    ts.spcname::text AS tablespace,
    pg_catalog.pg_get_partkeydef (c.oid) AS partition_by,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_catalog.pg_tablespace ts ON ts.oid = c.reltablespace
WHERE
    c.relkind IN ('r', 'p', 'f')
    AND NOT c.relispartition
ORDER BY
    n.nspname,
    c.relname;

-- :name ddl-table-constraints
-- :command :query
-- :result :many
-- :doc Table constraints (PK/unique/check/FK), excluding NOT NULL (contype n)
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    con.conname::text AS constraint,
    con.contype::text AS type,
    pg_catalog.pg_get_constraintdef(con.oid, TRUE) AS definition,
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
WHERE
    con.contype IN ('p', 'u', 'f', 'c')
    AND c.relkind IN ('r', 'p', 'f')
    AND NOT c.relispartition
ORDER BY
    n.nspname,
    c.relname,
    con.conname;

-- :name ddl-index-defs
-- :command :query
-- :result :many
-- :doc Index definitions, excluding primary-key and constraint-owned indexes
--
SELECT
    n.nspname::text AS schema,
    t.relname::text AS table,
    c.relname::text AS index,
    pg_catalog.pg_get_indexdef(c.oid) AS definition,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_index i
    JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
    JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
    LEFT JOIN pg_catalog.pg_constraint con ON con.conindid = i.indexrelid
WHERE
    con.conindid IS NULL
    AND NOT i.indisprimary
    AND t.relkind IN ('r', 'p')
    AND NOT t.relispartition
ORDER BY
    n.nspname,
    t.relname,
    c.relname;

-- :name ddl-view-defs
-- :command :query
-- :result :many
-- :doc View and materialized view SELECT definitions
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS view,
    c.relkind::text AS kind,
    pg_catalog.pg_get_viewdef(c.oid, TRUE) AS definition,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE
    c.relkind IN ('v', 'm')
ORDER BY
    n.nspname,
    c.relname;

-- :name ddl-function-defs
-- :command :query
-- :result :many
-- :doc Function and procedure CREATE OR REPLACE definitions
--
SELECT
    n.nspname::text AS schema,
    p.proname::text AS function, p.prokind::text AS kind, pg_catalog.pg_get_functiondef(p.oid) AS definition, CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_proc p
    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
WHERE
    p.prokind IN ('f', 'p')
ORDER BY
    n.nspname, p.proname, p.oid;

-- :name ddl-trigger-defs
-- :command :query
-- :result :many
-- :doc Trigger definitions, excluding internal triggers
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    t.tgname::text AS trigger,
    pg_catalog.pg_get_triggerdef(t.oid, TRUE) AS definition,
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
WHERE
    NOT t.tgisinternal
    AND c.relkind IN ('r', 'p', 'f')
ORDER BY
    n.nspname,
    c.relname,
    t.tgname;

-- :name ddl-rule-defs
-- :command :query
-- :result :many
-- :doc Rule definitions, excluding the internal _RETURN rules of views
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS table,
    rw.rulename::text AS rule,
    pg_catalog.pg_get_ruledef(rw.oid, TRUE) AS definition,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_rewrite rw
    JOIN pg_catalog.pg_class c ON c.oid = rw.ev_class
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE
    rw.rulename <> '_RETURN'
ORDER BY
    n.nspname,
    c.relname,
    rw.rulename;

-- :name ddl-comments
-- :command :query
-- :result :many
-- :doc Table/view/column comments for COMMENT ON statements
--
SELECT
    n.nspname::text AS schema,
    c.relname::text AS object,
    CASE c.relkind
    WHEN 'r'::"char" THEN
        'table'::text
    WHEN 'p'::"char" THEN
        'table'::text
    WHEN 'f'::"char" THEN
        'foreign table'::text
    WHEN 'v'::"char" THEN
        'view'::text
    WHEN 'm'::"char" THEN
        'materialized view'::text
    ELSE
        NULL::text
    END AS object_type,
    NULL::text AS column,
    pg_catalog.obj_description(c.oid, 'pg_class'::name) AS comment,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE
    c.relkind IN ('r', 'p', 'f', 'v', 'm')
    AND pg_catalog.obj_description(c.oid, 'pg_class'::name) IS NOT NULL
UNION ALL
SELECT
    n.nspname::text AS schema,
    c.relname::text AS object,
    'column'::text AS object_type,
    a.attname::text AS column,
    pg_catalog.col_description(c.oid, a.attnum::integer) AS comment,
    CASE WHEN n.nspname ~ '^(pg_|_timescaledb_|timescaledb_(information|experimental)$)'::text
        OR n.nspname = 'information_schema'::name THEN
        TRUE
    ELSE
        FALSE
    END AS system_object
FROM
    pg_catalog.pg_attribute a
    JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE
    a.attnum > 0
    AND c.relkind IN ('r', 'p', 'f')
    AND NOT c.relispartition
    AND pg_catalog.col_description(c.oid, a.attnum::integer) IS NOT NULL
ORDER BY
    SCHEMA,
    object,
    "column" NULLS FIRST;
