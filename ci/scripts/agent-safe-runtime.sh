#!/usr/bin/env bash
# Reviewed destination: ci/scripts/agent-safe-runtime.sh, before image publication.
source "$(dirname "$0")/common.sh"
[[ $(cat "$CI_STATE/images-gated.sha") == "$SOURCE_SHA" ]] || fail 'image runtime build gate not passed'
[[ ! -e $CI_STATE/agent-safe-mode-runtime.json && ! -e $CI_STATE/agent-safe-mode.sha ]] || fail 'Agent safe-mode gate already exists'
[[ $(podman image inspect --format '{{.Config.User}}' "$AGENT_IMAGE") == 65532:65532 ]] || fail 'Agent image default user differs'
[[ $(podman image inspect --format '{{index .Labels "org.opencontainers.image.revision"}}' "$AGENT_IMAGE") == "$SOURCE_SHA" ]] || fail 'Agent source revision differs'
id=$(podman image inspect --format '{{.Id}}' "$AGENT_IMAGE")
[[ $id =~ ^(sha256:)?[a-f0-9]{64}$ ]] || fail 'invalid Agent image ID'
cidfile="$CI_STATE/agent-safe-mode.cid"
[[ ! -e $cidfile && ! -L $cidfile ]] || fail 'Agent gate container ID path already exists'
cleanup() {
  if [[ -f $cidfile && ! -L $cidfile ]]; then
    cid=$(cat "$cidfile")
    if [[ $cid =~ ^[a-f0-9]{64}$ ]]; then
      podman rm --force -- "$cid" >/dev/null 2>&1 || true
    fi
    rm -f -- "$cidfile"
  fi
}
trap cleanup EXIT
# The harness is sent on stdin, not mounted from the host. Its only API server
# lives on loopback inside the network-none container and uses public fixtures.
timeout --signal TERM --kill-after 5s 90s podman run --rm --interactive --cidfile "$cidfile" \
  --network none --pid private --ipc private --uts private \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,nodev,size=32m \
  --user 65532:65532 --cap-drop ALL --security-opt no-new-privileges \
  --cpus 1 --memory 256m --pids-limit 128 \
  --env AGENT_MODE=safe --env EBPF_ENABLED=false --env SYSTEMD_COLLECTOR_MODE=file \
  --env KUBERNETES_TOPOLOGY_ENABLED=false --env AGENT_EVIDENCE_MAX_BYTES=65536 \
  --env "CI_EXPECTED_SOURCE_SHA=$SOURCE_SHA" --env "CI_EXPECTED_IMAGE_ID=$id" \
  --entrypoint python "$id" - \
  < "$(dirname "$0")/agent-safe-runtime.py" > "$CI_STATE/agent-safe-mode-runtime.json"
/usr/bin/python3 - "$CI_STATE/agent-safe-mode-runtime.json" "$SOURCE_SHA" "$id" <<'PY'
import json,pathlib,sys
p,source,image=sys.argv[1:]
data=json.loads(pathlib.Path(p).read_text())
assert data['passed'] is True
assert data['source_revision']==source and data['local_image_id']==image
assert data['uid']==65532 and data['mode']=='safe'
assert data['ebpf_enabled'] is False and data['forbidden_external_commands']==0
assert data['kubernetes_mutating_requests']==0
assert data['evidence_collectors_bytes']<=65536
assert data['privileged_collectors_disabled'] is True
PY
printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/agent-safe-mode.sha"
