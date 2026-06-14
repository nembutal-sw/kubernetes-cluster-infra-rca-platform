#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${RCA_DEV_TOOLS_DIR:-$HOME/.local/cluster-infra-rca-tools}"
BACKEND_PORT="${RCA_SMOKE_BACKEND_PORT:-18000}"
WEB_PORT="${RCA_SMOKE_WEB_PORT:-18080}"
BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
WEB_URL="http://127.0.0.1:${WEB_PORT}"
TMP_DIR="$(mktemp -d)"
BACKEND_PID=""
WEB_PID=""

log() {
  printf '[integration-smoke] %s\n' "$*"
}

cleanup() {
  local status=$?
  if [ -n "${WEB_PID}" ]; then
    kill "${WEB_PID}" 2>/dev/null || true
  fi
  if [ -n "${BACKEND_PID}" ]; then
    kill "${BACKEND_PID}" 2>/dev/null || true
  fi
  wait "${WEB_PID}" "${BACKEND_PID}" 2>/dev/null || true

  if [ "${status}" -ne 0 ]; then
    log "Backend log tail:"
    tail -n 80 "${TMP_DIR}/backend.log" 2>/dev/null || true
    log "Web console log tail:"
    tail -n 120 "${TMP_DIR}/web-console.log" 2>/dev/null || true
  fi

  rm -rf "${TMP_DIR}"
  exit "${status}"
}

trap cleanup EXIT

activate_user_tools() {
  if [ -x "${TOOLS_DIR}/jdk-17/bin/java" ]; then
    export JAVA_HOME="${TOOLS_DIR}/jdk-17"
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi

  if [ -x "${TOOLS_DIR}/apache-maven-3.9.9/bin/mvn" ]; then
    export PATH="${TOOLS_DIR}/apache-maven-3.9.9/bin:${PATH}"
  fi
}

wait_for_http() {
  local url="$1"
  local name="$2"

  for _ in $(seq 1 45); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      log "${name} is ready"
      return
    fi
    sleep 1
  done

  log "${name} did not become ready: ${url}"
  return 1
}

require_file() {
  if [ ! -f "$1" ]; then
    log "Required file is missing: $1"
    return 1
  fi
}

activate_user_tools

require_file "${ROOT_DIR}/.venv/bin/python"

if ! command -v java >/dev/null 2>&1; then
  log "Java is missing. Run scripts/linux-dev-check.sh --bootstrap-tools first."
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  log "Maven is missing. Run scripts/linux-dev-check.sh --bootstrap-tools first."
  exit 2
fi

if ! command -v curl >/dev/null 2>&1; then
  log "curl is required for smoke checks"
  exit 2
fi

log "Packaging web console"
(cd "${ROOT_DIR}/web-console" && mvn -q package -DskipTests)

DB_FILE="${TMP_DIR}/rca-smoke.db"
export RCA_DATABASE_URL="sqlite:///${DB_FILE}"
export RCA_AUTO_CREATE_TABLES="true"
export RCA_LLM_PROVIDER="disabled"

log "Starting backend on ${BACKEND_URL}"
(
  cd "${ROOT_DIR}"
  "${ROOT_DIR}/.venv/bin/python" -m uvicorn backend.app.main:app --host 127.0.0.1 --port "${BACKEND_PORT}"
) >"${TMP_DIR}/backend.log" 2>&1 &
BACKEND_PID=$!

wait_for_http "${BACKEND_URL}/health/ready" "backend"

log "Starting web console on ${WEB_URL}"
RCA_API_BASE_URL="${BACKEND_URL}" \
RCA_PUBLIC_API_BASE_URL="${BACKEND_URL}" \
java -jar "${ROOT_DIR}/web-console/target/cluster-infra-rca-web-console-0.1.0.war" \
  --server.port="${WEB_PORT}" >"${TMP_DIR}/web-console.log" 2>&1 &
WEB_PID=$!

wait_for_http "${WEB_URL}/" "web console"

curl -fsS -D "${TMP_DIR}/headers.txt" "${WEB_URL}/" -o "${TMP_DIR}/console.html"
curl -fsS "${WEB_URL}/console-api/health" -o "${TMP_DIR}/proxy-health.json"
curl -fsS "${WEB_URL}/console-api/health/ready" -o "${TMP_DIR}/proxy-ready.json"
curl -fsS \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' \
  "${WEB_URL}/console-api/api/auth/login" \
  -o "${TMP_DIR}/login.json"
SESSION_TOKEN="$("${ROOT_DIR}/.venv/bin/python" -c 'import json,sys; print(json.load(open(sys.argv[1]))["access_token"])' "${TMP_DIR}/login.json")"
curl -fsS \
  -H "Authorization: Bearer ${SESSION_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"smoke-cluster","environment":"dev"}' \
  "${WEB_URL}/console-api/api/clusters" \
  -o "${TMP_DIR}/cluster.json"

grep -q 'rca-console-root' "${TMP_DIR}/console.html"
grep -iq 'Content-Security-Policy' "${TMP_DIR}/headers.txt"
grep -iq 'X-Frame-Options: DENY' "${TMP_DIR}/headers.txt"
grep -q '"status":"ok"' "${TMP_DIR}/proxy-health.json"
grep -q '"database":"reachable"' "${TMP_DIR}/proxy-ready.json"
grep -q '"token_type":"bearer"' "${TMP_DIR}/login.json"
grep -q '"name":"smoke-cluster"' "${TMP_DIR}/cluster.json"
grep -q '"cluster_id"' "${TMP_DIR}/cluster.json"

log "Integration smoke check passed"
