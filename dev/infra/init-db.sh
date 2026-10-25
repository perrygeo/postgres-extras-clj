#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<- EOSQL
	CREATE DATABASE main;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname main <<- EOSQL
	CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
EOSQL
