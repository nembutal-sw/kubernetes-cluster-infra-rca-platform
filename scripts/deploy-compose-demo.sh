#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${RCA_DEPLOY_ENV_FILE:-${HOME}/.config/cluster-infra-rca-platform/demo.env}"
project_name="${RCA_COMPOSE_PROJECT_NAME:-cluster-infra-rca}"
backup_dir="${RCA_DEPLOY_BACKUP_DIR:-${HOME}/.local/state/cluster-infra-rca-platform/backups}"
health_timeout="${RCA_DEPLOY_HEALTH_TIMEOUT_SECONDS:-180}"
lock_dir="${RCA_DEPLOY_LOCK_DIR:-${HOME}/.local/state/cluster-infra-rca-platform/deploy.lock}"

usage() {
  cat <<'EOF'
Usage: scripts/deploy-compose-demo.sh [--env-file PATH] [--project NAME]

Builds and deploys the Docker Compose demo stack. The environment file must
contain deployment secrets and RCA_DEMO_ENABLED=true. Existing PostgreSQL data
is backed up before a running stack is replaced.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="$2"; shift 2 ;;
    --project) project_name="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for command in docker curl gzip; do
  command -v "${command}" >/dev/null 2>&1 || { echo "${command} is required." >&2; exit 2; }
done
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required." >&2; exit 2; }
[[ -f "${env_file}" ]] || { echo "Deployment environment file not found: ${env_file}" >&2; exit 2; }
[[ "${health_timeout}" =~ ^[0-9]+$ ]] || { echo "Health timeout must be an integer." >&2; exit 2; }

permissions="$(stat -c '%a' "${env_file}" 2>/dev/null || true)"
if [[ -n "${permissions}" ]] && (( (8#${permissions}) & 077 )); then
  echo "Deployment environment file must not be readable by group or others: ${env_file}" >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "${env_file}"
set +a

required=(
  POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD RCA_DB_USERNAME RCA_DB_PASSWORD
  RCA_DEFAULT_ADMIN_USERNAME RCA_DEFAULT_ADMIN_PASSWORD RCA_WEBHOOK_TOKEN
  RCA_ENCRYPTION_SECRET RCA_EXPORT_SIGNATURE_SECRET RCA_METRICS_TOKEN
  RCA_PUBLIC_API_BASE_URL RCA_BIND_ADDRESS RCA_HTTP_PORT
)
for name in "${required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Required deployment value is empty: ${name}" >&2; exit 2; }
done
[[ "${RCA_DEMO_ENABLED:-false}" == "true" ]] || { echo "RCA_DEMO_ENABLED must be true for this deployment." >&2; exit 2; }
[[ "${RCA_BIND_ADDRESS}" != "0.0.0.0" ]] || { echo "Demo deployment must bind to an explicit management address." >&2; exit 2; }

mkdir -p "$(dirname "${lock_dir}")" "${backup_dir}"
if ! mkdir "${lock_dir}" 2>/dev/null; then
  echo "Another deployment is already running: ${lock_dir}" >&2
  exit 1
fi
trap 'rmdir "${lock_dir}" 2>/dev/null || true' EXIT

compose=(docker compose --project-name "${project_name}" --env-file "${env_file}" -f "${repo_root}/docker-compose.yml")
"${compose[@]}" config --quiet

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
rollback_tag="cluster-infra-rca-platform:rollback-${timestamp}"
rollback_available=false
if docker image inspect cluster-infra-rca-platform:local >/dev/null 2>&1; then
  docker tag cluster-infra-rca-platform:local "${rollback_tag}"
  rollback_available=true
fi

postgres_state="$(docker inspect --format '{{.State.Status}}' rca-postgres 2>/dev/null || true)"
if [[ "${postgres_state}" == "running" ]]; then
  backup_path="${backup_dir}/rca-${timestamp}.sql.gz"
  echo "Creating PostgreSQL backup: ${backup_path}"
  docker exec rca-postgres pg_dump --no-owner --no-privileges \
    --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" | gzip -9 >"${backup_path}"
  chmod 600 "${backup_path}"
fi

echo "Building Platform image."
"${compose[@]}" build --pull platform
echo "Starting PostgreSQL."
"${compose[@]}" up -d postgres

for _ in $(seq 1 60); do
  [[ "$(docker inspect --format '{{.State.Health.Status}}' rca-postgres 2>/dev/null || true)" == "healthy" ]] && break
  sleep 2
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' rca-postgres 2>/dev/null || true)" == "healthy" ]] || {
  "${compose[@]}" logs --tail=100 postgres >&2
  echo "PostgreSQL did not become healthy." >&2
  exit 1
}

echo "Starting Platform."
"${compose[@]}" up -d --no-build --remove-orphans platform

ready=false
for _ in $(seq 1 "${health_timeout}"); do
  if curl --fail --silent --show-error --connect-timeout 3 --max-time 5 \
    "${RCA_PUBLIC_API_BASE_URL%/}/health/ready" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done

if [[ "${ready}" != "true" ]]; then
  "${compose[@]}" ps >&2 || true
  "${compose[@]}" logs --tail=200 platform >&2 || true
  if [[ "${rollback_available}" == "true" ]]; then
    echo "Health check failed; restoring the previous Platform image." >&2
    docker tag "${rollback_tag}" cluster-infra-rca-platform:local
    if "${compose[@]}" up -d --no-build --force-recreate platform; then
      docker image rm "${rollback_tag}" >/dev/null 2>&1 || true
    else
      echo "Rollback container recreation failed; retained image ${rollback_tag}." >&2
    fi
  fi
  exit 1
fi

"${compose[@]}" ps
if [[ "${rollback_available}" == "true" ]]; then
  docker image rm "${rollback_tag}" >/dev/null 2>&1 || true
fi
echo "Demo deployment is ready at ${RCA_PUBLIC_API_BASE_URL%/}."
