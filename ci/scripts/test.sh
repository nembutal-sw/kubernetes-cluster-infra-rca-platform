#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd -- "$CI_SOURCE"
case ${1:-} in
  backend)
    socket_path="$CI_STATE/podman-test.sock"
    [[ ! -e $socket_path && ${#socket_path} -lt 104 ]] || fail 'test API socket path already exists or is too long'
    # Per-build child process only; no persistent service or host socket mount.
    podman system service --time=0 "unix://$socket_path" >"$CI_STATE/podman-api.log" 2>&1 &
    api_pid=$!
    cleanup_api() {
      kill "$api_pid" >/dev/null 2>&1 || true
      wait "$api_pid" >/dev/null 2>&1 || true
      rm -f -- "$socket_path"
    }
    trap cleanup_api EXIT
    for _ in {1..50}; do
      [[ -S $socket_path && -O $socket_path ]] && break
      kill -0 "$api_pid" 2>/dev/null || fail 'rootless per-build API process exited'
      sleep 0.2
    done
    [[ -S $socket_path && -O $socket_path ]] || fail 'rootless per-build API socket was not ready'
    export DOCKER_HOST="unix://$socket_path"
    export TESTCONTAINERS_RYUK_DISABLED=true
    # This one canonical verification also produces the integrated React+Spring JAR.
    # Frontend unit tests remain independent and mandatory in the next stage.
    mvn --batch-mode --no-transfer-progress -f web-console/pom.xml -Pfrontend clean verify
    "$CI_STATE/venv/bin/python" scripts/verify_database_compatibility_report.py
    "$CI_STATE/venv/bin/python" scripts/verify_agent_enrollment_migration_report.py
    "$CI_STATE/venv/bin/python" scripts/verify-build-lifecycle.py
    printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/backend-passed.sha"
    ;;
  frontend)
    (
      cd web-console/frontend
      npm ci --no-audit --no-fund
      npm test -- --maxWorkers=1 --reporter=default --reporter=junit --outputFile="$CI_STATE/frontend-junit.xml"
      npm run build
    )
    printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/frontend-passed.sha"
    ;;
  python)
    "$CI_STATE/venv/bin/python" -m compileall -q node_agent tests
    "$CI_STATE/venv/bin/python" -m pytest -q --junitxml="$CI_STATE/python-junit.xml"
    printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/python-passed.sha"
    ;;
  aggregate)
    for suite in backend frontend python; do
      [[ $(cat "$CI_STATE/$suite-passed.sha") == "$SOURCE_SHA" ]] || fail "$suite gate does not match current revision"
    done
    # Recheck existing report evidence, not Maven tests or packaging.
    "$CI_STATE/venv/bin/python" scripts/verify_database_compatibility_report.py
    "$CI_STATE/venv/bin/python" scripts/verify_agent_enrollment_migration_report.py
    "$CI_STATE/venv/bin/python" - <<'PY'
import os,pathlib,zipfile
jar=pathlib.Path(os.environ['CI_SOURCE'])/'web-console/target/cluster-infra-rca-platform-0.1.0.jar'
assert jar.is_file() and not jar.is_symlink(), 'integrated JAR missing'
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    assert 'BOOT-INF/classes/static/index.html' in names, 'React index missing'
    assert any(n.startswith('BOOT-INF/classes/static/assets/') and n.endswith('.js') for n in names), 'React JS missing'
print('Three suite markers and integrated JAR verified; Maven was not rerun')
PY
    if [[ ${RUN_BROWSER_TESTS:-false} == true ]]; then
      # User-cache browser install only; missing OS dependencies fail this opt-in.
      (cd web-console/frontend && npx --no-install playwright install chromium && \
        CONSOLE_BASE_URL=http://127.0.0.1:18087 CONSOLE_E2E_REUSE_SERVER=false \
        CONSOLE_E2E_SERVER_COMMAND='java -jar ../target/cluster-infra-rca-platform-0.1.0.jar' \
        CONSOLE_USERNAME=e2e-admin CONSOLE_PASSWORD=e2e-password-123 SERVER_ADDRESS=127.0.0.1 npm run e2e)
    fi
    printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/tests-passed.sha"
    ;;
  *) fail 'test mode must be backend, frontend, python or aggregate' ;;
esac
