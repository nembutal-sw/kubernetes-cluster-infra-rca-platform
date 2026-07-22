#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---check}"
TOOLS_DIR="${RCA_DEV_TOOLS_DIR:-$HOME/.local/cluster-infra-rca-tools}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
UV_DOWNLOAD_URL="${UV_DOWNLOAD_URL:-https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-unknown-linux-gnu.tar.gz}"

log() {
  printf '[linux-dev] %s\n' "$*"
}

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

download() {
  local url="$1"
  local output="$2"

  if has_cmd curl; then
    curl -L --fail --retry 3 -o "${output}" "${url}"
  elif has_cmd wget; then
    wget -O "${output}" "${url}"
  else
    log "curl or wget is required for user-local tool bootstrap"
    exit 2
  fi
}

ensure_tools_dir_safe() {
  mkdir -p "${TOOLS_DIR}"

  local home_real
  local tools_real
  home_real="$(cd "${HOME}" && pwd -P)"
  tools_real="$(cd "${TOOLS_DIR}" && pwd -P)"

  case "${tools_real}" in
    "${home_real}"/*)
      ;;
    *)
      log "Refusing to manage tools outside the current user's home: ${tools_real}"
      exit 2
      ;;
  esac
}

activate_user_tools() {
  if [ -x "${TOOLS_DIR}/jdk-21/bin/java" ]; then
    export JAVA_HOME="${TOOLS_DIR}/jdk-21"
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi

  if [ -x "${TOOLS_DIR}/apache-maven-3.9.9/bin/mvn" ]; then
    export PATH="${TOOLS_DIR}/apache-maven-3.9.9/bin:${PATH}"
  fi
}

activate_user_python() {
  if [ -x "${TOOLS_DIR}/python-3.11/bin/python" ] && [ "${PYTHON_BIN}" = "python3" ]; then
    PYTHON_BIN="${TOOLS_DIR}/python-3.11/bin/python"
  fi
}

java_major_version() {
  activate_user_tools
  if ! has_cmd java; then
    printf '0'
    return
  fi

  java -XshowSettings:properties -version 2>&1 \
    | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); split($2, v, "."); if (v[1] == "1") print v[2]; else print v[1]; exit}'
}

python_minor_version() {
  activate_user_python
  if ! has_cmd "${PYTHON_BIN}"; then
    printf '0.0'
    return
  fi

  "${PYTHON_BIN}" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")'
}

bootstrap_user_tools() {
  ensure_tools_dir_safe
  mkdir -p "${TOOLS_DIR}"

  if [ ! -x "${TOOLS_DIR}/jdk-21/bin/java" ]; then
    log "Installing user-local JDK 21 under ${TOOLS_DIR}"
    rm -rf "${TOOLS_DIR}/jdk-21" "${TOOLS_DIR}/jdk21.tar.gz"
    download "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" "${TOOLS_DIR}/jdk21.tar.gz"
    mkdir -p "${TOOLS_DIR}/jdk-21"
    tar -xzf "${TOOLS_DIR}/jdk21.tar.gz" -C "${TOOLS_DIR}/jdk-21" --strip-components=1
  fi

  if [ ! -x "${TOOLS_DIR}/apache-maven-3.9.9/bin/mvn" ]; then
    log "Installing user-local Maven 3.9.9 under ${TOOLS_DIR}"
    rm -rf "${TOOLS_DIR}/apache-maven-3.9.9" "${TOOLS_DIR}/maven.tar.gz"
    download "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" "${TOOLS_DIR}/maven.tar.gz"
    tar -xzf "${TOOLS_DIR}/maven.tar.gz" -C "${TOOLS_DIR}"
  fi

  activate_user_tools
}

bootstrap_uv() {
  ensure_tools_dir_safe

  if [ -x "${TOOLS_DIR}/uv/uv" ]; then
    return
  fi

  log "Installing user-local uv under ${TOOLS_DIR}"
  rm -rf "${TOOLS_DIR}/uv" "${TOOLS_DIR}/uv.tar.gz" "${TOOLS_DIR}/uv-extract"
  mkdir -p "${TOOLS_DIR}/uv" "${TOOLS_DIR}/uv-extract"
  download "${UV_DOWNLOAD_URL}" "${TOOLS_DIR}/uv.tar.gz"
  tar -xzf "${TOOLS_DIR}/uv.tar.gz" -C "${TOOLS_DIR}/uv-extract"
  find "${TOOLS_DIR}/uv-extract" -type f -name uv -exec cp {} "${TOOLS_DIR}/uv/uv" \;
  find "${TOOLS_DIR}/uv-extract" -type f -name uvx -exec cp {} "${TOOLS_DIR}/uv/uvx" \; || true
  chmod +x "${TOOLS_DIR}/uv/uv" "${TOOLS_DIR}/uv/uvx" 2>/dev/null || chmod +x "${TOOLS_DIR}/uv/uv"
  rm -rf "${TOOLS_DIR}/uv-extract"
}

bootstrap_user_python() {
  ensure_tools_dir_safe
  bootstrap_uv

  if [ -x "${TOOLS_DIR}/python-3.11/bin/python" ]; then
    activate_user_python
    return
  fi

  log "Installing user-local Python 3.11 under ${TOOLS_DIR}"
  "${TOOLS_DIR}/uv/uv" python install 3.11
  "${TOOLS_DIR}/uv/uv" venv --python 3.11 "${TOOLS_DIR}/python-3.11"
  activate_user_python
}

check_tooling() {
  activate_user_tools
  activate_user_python

  local failed=0
  local java_major
  local python_version
  java_major="$(java_major_version)"
  java_major="${java_major:-0}"
  python_version="$(python_minor_version)"

  if [ "${java_major}" -lt 21 ]; then
    log "Java 21+ is required. Detected: ${java_major}"
    failed=1
  else
    log "Java OK: $(java -version 2>&1 | head -n 1)"
  fi

  if has_cmd mvn; then
    log "Maven OK: $(mvn -version | head -n 1)"
  else
    log "Maven is missing"
    failed=1
  fi

  case "${python_version}" in
    3.11|3.12|3.13|3.14|3.15)
      log "Python OK: $("${PYTHON_BIN}" --version)"
      ;;
    *)
      log "Python 3.11+ is required for node agent validation. Detected: ${python_version}"
      failed=1
      ;;
  esac

  if has_cmd node; then
    log "Node OK: $(node --version)"
  else
    log "System Node.js is missing. The Frontend Maven profile will install its pinned Node.js runtime."
  fi

  return "${failed}"
}

bootstrap_python_env() {
  activate_user_tools
  activate_user_python

  if [ ! -d "${ROOT_DIR}/.venv" ]; then
    log "Creating Python virtual environment"
    if [ -x "${TOOLS_DIR}/uv/uv" ]; then
      "${TOOLS_DIR}/uv/uv" venv --python "${PYTHON_BIN}" "${ROOT_DIR}/.venv"
    else
      "${PYTHON_BIN}" -m venv "${ROOT_DIR}/.venv"
    fi
  fi

  log "Installing Python dependencies"
  if [ -x "${TOOLS_DIR}/uv/uv" ]; then
    "${TOOLS_DIR}/uv/uv" pip install --python "${ROOT_DIR}/.venv/bin/python" \
      -r "${ROOT_DIR}/requirements.txt" \
      -r "${ROOT_DIR}/requirements-dev.txt"
  else
    "${ROOT_DIR}/.venv/bin/python" -m pip install --upgrade pip
    "${ROOT_DIR}/.venv/bin/python" -m pip install -r "${ROOT_DIR}/requirements.txt" -r "${ROOT_DIR}/requirements-dev.txt"
  fi
}

run_validation() {
  activate_user_tools
  activate_user_python

  log "Running node agent Python tests"
  "${ROOT_DIR}/.venv/bin/python" -m pytest

  log "Running Python compile check"
  "${ROOT_DIR}/.venv/bin/python" -m compileall -q "${ROOT_DIR}/node_agent" "${ROOT_DIR}/tests"

  log "Running integrated Spring Boot and Frontend build"
  (cd "${ROOT_DIR}/web-console" && mvn -Pfrontend verify)

  log "Running Frontend unit tests"
  (cd "${ROOT_DIR}/web-console/frontend" && PATH="${ROOT_DIR}/web-console/frontend/node:${PATH}" "${ROOT_DIR}/web-console/frontend/node/npm" test)
}

case "${MODE}" in
  --check)
    check_tooling
    ;;
  --bootstrap-tools)
    bootstrap_user_tools
    check_tooling
    ;;
  --bootstrap-python)
    bootstrap_user_python
    check_tooling
    ;;
  --bootstrap)
    check_tooling
    bootstrap_python_env
    ;;
  --validate)
    check_tooling
    run_validation
    ;;
  --validate-web)
    bootstrap_user_tools
    log "Running integrated Spring Boot and Frontend build"
    (cd "${ROOT_DIR}/web-console" && mvn -Pfrontend verify)
    log "Running Frontend unit tests"
    (cd "${ROOT_DIR}/web-console/frontend" && PATH="${ROOT_DIR}/web-console/frontend/node:${PATH}" "${ROOT_DIR}/web-console/frontend/node/npm" test)
    ;;
  --full)
    bootstrap_user_tools
    bootstrap_user_python
    check_tooling
    bootstrap_python_env
    run_validation
    ;;
  *)
    cat <<'USAGE'
Usage: scripts/linux-dev-check.sh [--check|--bootstrap-tools|--bootstrap-python|--bootstrap|--validate|--validate-web|--full]

  --check            Check Java, Maven, Python, and Node.js. No system changes.
  --bootstrap-tools  Install user-local JDK 21 and Maven under $HOME/.local.
  --bootstrap-python Install user-local uv and managed Python 3.11 under $HOME/.local.
  --bootstrap        Create .venv and install Python dependencies.
  --validate         Run Python, JavaScript, and Spring Boot checks.
  --validate-web     Bootstrap user-local Java tools and run the integrated Spring Boot/Frontend checks.
  --full             Bootstrap user-local Java tools, Python env, and run validation.

The script does not use sudo and does not modify OS packages, Docker, CNI,
firewall, routing, or reboot settings.
USAGE
    exit 2
    ;;
esac
