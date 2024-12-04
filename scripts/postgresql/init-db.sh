#!/usr/bin/env bash

set -euo pipefail

# Number of gradle workers times 4, which was somewhat arbitrary but is sufficient in practice.
# We make extra dbs because a gradle worker ID can exceed the max number of workers.
NUM_OF_DATABASES_TO_CREATE=48

function initDB() {
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DROP DATABASE IF EXISTS uaa;
    CREATE DATABASE uaa;
    DROP USER IF EXISTS root;
    CREATE USER root WITH SUPERUSER PASSWORD '$POSTGRES_PASSWORD';
    SHOW max_connections;
EOSQL
}

function createDB() {
  DATABASE_NAME="uaa_${1}"
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE $DATABASE_NAME;
EOSQL
}

initDB

for db_id in `seq 1 $NUM_OF_DATABASES_TO_CREATE`; do
  createDB $db_id
done