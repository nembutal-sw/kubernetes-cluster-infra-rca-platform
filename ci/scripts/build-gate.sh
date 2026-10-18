#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
[[ $(cat "$CI_STATE/tests-passed.sha") == "$SOURCE_SHA" ]] || fail 'full test gate not passed'
exec 9>"$CI_STATE/build.lock"
flock -n 9 || fail 'this job build lock is held'
for image in "$PLATFORM_IMAGE" "$AGENT_IMAGE"; do
  ! podman image exists "$image" || fail 'immutable local tag exists; start a new Jenkins build'
done
export CI_SOURCE CI_STATE SOURCE_SHA
"$CI_STATE/venv/bin/python" - <<'PY'
import hashlib,json,os,pathlib,shutil,zipfile
source=pathlib.Path(os.environ['CI_SOURCE']); state=pathlib.Path(os.environ['CI_STATE'])
jar=source/'web-console/target/cluster-infra-rca-platform-0.1.0.jar'
assert jar.is_file() and not jar.is_symlink(), 'JAR must be a regular owned build output'
assert jar.resolve().is_relative_to((source/'web-console/target').resolve()), 'JAR leaves target'
for p in (source/'node_agent').rglob('*'):
    assert not p.is_symlink(), 'source symlink is not allowed'
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    assert 'BOOT-INF/classes/static/index.html' in names, 'React index missing from integrated JAR'
    assert any(n.startswith('BOOT-INF/classes/static/assets/') and n.endswith('.js') for n in names), 'React JS missing'
for name in ['platform-context','agent-context']:
    (state/name).mkdir(mode=0o755,exist_ok=False)
shutil.copyfile(jar,state/'platform-context/platform.jar')
os.chmod(state/'platform-context/platform.jar',0o644)
shutil.copytree(source/'node_agent',state/'agent-context/node_agent',ignore=shutil.ignore_patterns('__pycache__','*.pyc','*.pyo'))
for p in (state/'agent-context').rglob('*'):
    assert not p.is_symlink(), 'source symlink is not allowed'
    os.chmod(p,0o755 if p.is_dir() else 0o644)
(state/'jar-evidence.json').write_text(json.dumps({'source_revision':os.environ['SOURCE_SHA'],'pipeline_revision':os.environ['PIPELINE_SHA'],'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}))
PY
build_date=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
flags=(--format oci --isolation=oci --tls-verify=true --http-proxy=false --jobs 1 --cpu-period 100000 --cpu-quota 100000 --memory 2g --network slirp4netns:allow_host_loopback=false --build-arg "VERSION=0.1.0-ci.${BUILD_NUMBER}" --build-arg "VCS_REF=$SOURCE_SHA" --build-arg "BUILD_DATE=$build_date")
podman build "${flags[@]}" --file "$CI_HELPERS/../Dockerfile.platform-runtime" --tag "$PLATFORM_IMAGE" "$CI_STATE/platform-context"
podman build "${flags[@]}" --file "$CI_SOURCE/Dockerfile.agent" --tag "$AGENT_IMAGE" "$CI_STATE/agent-context"
for image in "$PLATFORM_IMAGE" "$AGENT_IMAGE"; do
  [[ $(podman image inspect --format '{{.Config.User}}' "$image") == 65532:65532 ]] || fail 'image default user is not 65532:65532'
  [[ $(podman image inspect --format '{{index .Labels "org.opencontainers.image.revision"}}' "$image") == "$SOURCE_SHA" ]] || fail 'OCI revision label mismatch'
done
podman run --rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=32m --user 65532:65532 --cap-drop ALL --security-opt no-new-privileges --cpus 1 --memory 256m --pids-limit 128 --entrypoint python "$AGENT_IMAGE" -c 'import os,node_agent.main,node_agent.client,node_agent.collectors; assert os.getuid()==65532; print("Agent non-root import passed")'
[[ $(podman run --rm --network none --read-only --user 65532:65532 --cap-drop ALL --security-opt no-new-privileges --cpus 1 --memory 256m --entrypoint id "$PLATFORM_IMAGE" -u) == 65532 ]] || fail 'Platform non-root gate failed'
# Isolated H2 fixture; no production configuration, credentials or cluster API.
name="rca-ci-smoke-${BUILD_NUMBER}-$$"
smoke_id=''
cleanup() {
  if [[ $smoke_id =~ ^[a-f0-9]{64}$ ]]; then
    podman rm -f -- "$smoke_id" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
"$CI_STATE/venv/bin/python" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",18088)); s.close()'
smoke_id=$(podman run --detach --name "$name" --network slirp4netns:allow_host_loopback=false --publish 127.0.0.1:18088:8080 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=256m --user 65532:65532 --cap-drop ALL --security-opt no-new-privileges --cpus 1 --memory 1g --pids-limit 256 \
  --env 'RCA_JDBC_URL=jdbc:h2:mem:rca_ci;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1' \
  --env RCA_DB_USERNAME=sa --env RCA_DB_PASSWORD= --env RCA_DEFAULT_ADMIN_USERNAME=ci-runtime-fixture --env RCA_DEFAULT_ADMIN_PASSWORD=ci-runtime-fixture-password \
  --env RCA_PUBLIC_API_BASE_URL=http://127.0.0.1:18088 --env RCA_DEMO_ENABLED=true --env RCA_PIPELINE_ENABLED=true --env RCA_OBSERVABILITY_ENABLED=false --env RCA_MAINTENANCE_ENABLED=false --env RCA_MONITORING_ENABLED=false "$PLATFORM_IMAGE")
[[ $smoke_id =~ ^[a-f0-9]{64}$ ]] || fail 'invalid created smoke container ID'
ready=false
for _ in {1..90}; do
  if curl --fail --silent --output /dev/null http://127.0.0.1:18088/health/ready; then ready=true; break; fi
  [[ $(podman inspect --format '{{.State.Running}}' "$smoke_id") == true ]] || fail 'isolated smoke container exited'
  sleep 2
done
[[ $ready == true ]] || fail 'isolated image readiness timed out'
if [[ ${RUN_BROWSER_TESTS:-false} == true ]]; then
  (cd "$CI_SOURCE/web-console/frontend" && CONSOLE_BASE_URL=http://127.0.0.1:18088 CONSOLE_USERNAME=ci-runtime-fixture CONSOLE_PASSWORD=ci-runtime-fixture-password npm run smoke:routes)
fi
podman rm -f -- "$smoke_id" >/dev/null || fail 'own smoke container cleanup failed'
smoke_id=''
"$CI_STATE/venv/bin/python" - <<'PY'
import json,os,pathlib
p=pathlib.Path(os.environ['CI_STATE']); evidence=json.loads((p/'jar-evidence.json').read_text())
evidence.update({'platform_image':os.environ['PLATFORM_IMAGE'],'agent_image':os.environ['AGENT_IMAGE'],'tests':'passed including no-skip DB gates','nonroot_runtime':'passed','image_readiness':'passed','browser_tests':'passed' if os.environ.get('RUN_BROWSER_TESTS')=='true' else 'not requested'})
(p/'build-evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
PY
printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/images-gated.sha"
