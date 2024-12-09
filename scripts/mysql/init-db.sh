#!/bin/bash

set -euo pipefail

# Number of gradle workers times 4, which was somewhat arbitrary but is sufficient in practice.
# We make extra dbs because a gradle worker ID can exceed the max number of workers.
NUM_OF_DATABASES_TO_CREATE=24


function initDB() {
  mysql -uroot -pchangeme <<-EOSQL
  SET GLOBAL max_connections = 250;
  DROP DATABASE IF EXISTS uaa;
  CREATE DATABASE uaa DEFAULT CHARACTER SET utf8mb4;
EOSQL
}

function createDB() {
  DATABASE_NAME="uaa_${1}"
  echo "Creating MySQL database with name ${DATABASE_NAME}"
  mysql  -uroot -pchangeme <<-EOSQL
    DROP DATABASE IF EXISTS $DATABASE_NAME;
    CREATE DATABASE $DATABASE_NAME DEFAULT CHARACTER SET utf8mb4;
EOSQL
}

initDB

for db_id in `seq 1 $NUM_OF_DATABASES_TO_CREATE`; do
  createDB $db_id
done
