#!/usr/bin/env bash
set -euo pipefail

suffix="${RANDOM}-$$"
network="rca-backup-test-${suffix}"
pg_source="rca-pg-source-${suffix}"
pg_target="rca-pg-target-${suffix}"
maria_source="rca-maria-source-${suffix}"
maria_target="rca-maria-target-${suffix}"
workdir="$(mktemp -d)"

cleanup() {
  docker rm -f "${pg_source}" "${pg_target}" "${maria_source}" "${maria_target}" >/dev/null 2>&1 || true
  docker network rm "${network}" >/dev/null 2>&1 || true
  rm -rf "${workdir}"
}
trap cleanup EXIT

docker network create "${network}" >/dev/null

wait_for() {
  local attempts="$1"
  shift
  for _ in $(seq 1 "${attempts}"); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

docker run -d --name "${pg_source}" --network "${network}" \
  -e POSTGRES_DB=rca -e POSTGRES_USER=rca -e POSTGRES_PASSWORD=backup-test postgres:16-alpine >/dev/null
docker run -d --name "${pg_target}" --network "${network}" \
  -e POSTGRES_DB=rca -e POSTGRES_USER=rca -e POSTGRES_PASSWORD=backup-test postgres:16-alpine >/dev/null
wait_for 30 docker exec "${pg_source}" pg_isready -U rca -d rca
wait_for 30 docker exec "${pg_target}" pg_isready -U rca -d rca
docker exec "${pg_source}" psql -U rca -d rca -v ON_ERROR_STOP=1 \
  -c "CREATE TABLE recovery_probe(id INTEGER PRIMARY KEY, value VARCHAR(64)); INSERT INTO recovery_probe VALUES (1, 'postgres-ok');" >/dev/null
docker exec "${pg_source}" pg_dump -U rca -d rca > "${workdir}/postgres.sql"
docker exec -i "${pg_target}" psql -U rca -d rca -v ON_ERROR_STOP=1 < "${workdir}/postgres.sql" >/dev/null
test "$(docker exec "${pg_target}" psql -U rca -d rca -Atc "SELECT value FROM recovery_probe WHERE id = 1")" = "postgres-ok"

docker run -d --name "${maria_source}" --network "${network}" \
  -e MARIADB_DATABASE=rca -e MARIADB_USER=rca -e MARIADB_PASSWORD=backup-test \
  -e MARIADB_ROOT_PASSWORD=root-test mariadb:11.4 >/dev/null
docker run -d --name "${maria_target}" --network "${network}" \
  -e MARIADB_DATABASE=rca -e MARIADB_USER=rca -e MARIADB_PASSWORD=backup-test \
  -e MARIADB_ROOT_PASSWORD=root-test mariadb:11.4 >/dev/null
wait_for 45 docker exec "${maria_source}" mariadb-admin ping -h 127.0.0.1 -urca -pbackup-test
wait_for 45 docker exec "${maria_target}" mariadb-admin ping -h 127.0.0.1 -urca -pbackup-test
docker exec "${maria_source}" mariadb -urca -pbackup-test rca \
  -e "CREATE TABLE recovery_probe(id INT PRIMARY KEY, value VARCHAR(64)); INSERT INTO recovery_probe VALUES (1, 'mariadb-ok');"
docker exec "${maria_source}" mariadb-dump -urca -pbackup-test rca > "${workdir}/mariadb.sql"
docker exec -i "${maria_target}" mariadb -urca -pbackup-test rca < "${workdir}/mariadb.sql"
test "$(docker exec "${maria_target}" mariadb -N -urca -pbackup-test rca -e "SELECT value FROM recovery_probe WHERE id = 1")" = "mariadb-ok"

echo "PostgreSQL and MariaDB backup/restore validation passed."
