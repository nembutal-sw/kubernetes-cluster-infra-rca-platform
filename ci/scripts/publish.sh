#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
[[ $(cat "$CI_STATE/images-gated.sha") == "$SOURCE_SHA" ]] || fail 'runtime image gates not passed'
[[ $(cat "$CI_STATE/agent-safe-mode.sha") == "$SOURCE_SHA" ]] || fail 'Agent safe observation mode gate not passed'
[[ $(cat "$CI_STATE/security-gated.sha") == "$SOURCE_SHA" ]] || fail 'image security gate not passed'
"$CI_STATE/venv/bin/python" "$CI_HELPERS/verify-delivery-evidence.py" tests
"$CI_STATE/venv/bin/python" "$CI_HELPERS/verify-delivery-evidence.py" gates
[[ -n ${REGISTRY_USERNAME:-} && -n ${REGISTRY_PASSWORD:-} ]] || fail 'Jenkins build credential is missing'
authfile="$CI_STATE/registry-auth.json"
[[ ! -L $authfile ]] || fail 'authentication path must not be a symlink'
cleanup() { unset REGISTRY_PASSWORD; rm -f -- "$authfile"; }
trap cleanup EXIT
umask 077
printf '%s' "$REGISTRY_PASSWORD" | podman login --authfile "$authfile" --username "$REGISTRY_USERNAME" --password-stdin 10.7.254.6:5000 >/dev/null
for image in "$PLATFORM_IMAGE" "$AGENT_IMAGE"; do
  # Fail closed on any already-existing immutable tag. TLS verification is default.
  if podman manifest inspect --authfile "$authfile" "$image" >"$CI_STATE/tag-lookup.json" 2>"$CI_STATE/tag-lookup-error.txt"; then
    fail 'registry immutable tag already exists'
  fi
  grep -Eiq 'manifest unknown|name unknown' "$CI_STATE/tag-lookup-error.txt" || fail 'could not prove registry tag is absent; no push attempted'
done
for item in platform agent; do
  gated_id=$("$CI_STATE/venv/bin/python" - "$CI_STATE/gated-images.json" "$item" <<'PY'
import json,re,sys
value=json.load(open(sys.argv[1]))['images'][sys.argv[2]]['local_image_id']
assert re.fullmatch(r'sha256:[0-9a-f]{64}',value)
print(value)
PY
)
  if [[ $item == platform ]]; then destination=$PLATFORM_IMAGE; else destination=$AGENT_IMAGE; fi
  # Push the scanned immutable local ID, even if a mutable local tag changes.
  podman push --tls-verify=true --authfile "$authfile" --digestfile "$CI_STATE/$item.digest" "$gated_id" "$destination" >/dev/null
done
for item in platform agent; do
  digest=$(cat "$CI_STATE/$item.digest")
  [[ $digest =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'invalid registry digest'
  if [[ $item == platform ]]; then repository=10.7.254.6:5000/rca-platform; else repository=10.7.254.6:5000/rca-node-agent; fi
  podman manifest inspect --authfile "$authfile" "$repository@$digest" >/dev/null || fail 'published digest verification failed'
done
printf '%s' "$REGISTRY_PASSWORD" | env -u REGISTRY_PASSWORD "$CI_STATE/venv/bin/python" "$CI_HELPERS/verify-delivery-evidence.py" registry
unset REGISTRY_PASSWORD
"$CI_STATE/venv/bin/python" - <<'PY'
import json,os,pathlib,re
p=pathlib.Path(os.environ['CI_STATE']);result={'source_revision':os.environ['SOURCE_SHA'],'pipeline_revision':os.environ['PIPELINE_SHA']}
result['test_counts']=json.loads((p/'test-evidence.json').read_text())['counts']
security=json.loads((p/'security/summary.json').read_text())
assert security['passed'] is True and security['source_revision']==result['source_revision']
result['security']={'passed':True,'source_revision':security['source_revision'],'trivy_version':security['trivy_version'],'blocking_severities':security['blocking_severities'],'ignore_unfixed':security['ignore_unfixed']}
remote=json.loads((p/'registry-verification.json').read_text())['images']
for name,ref in [('platform',os.environ['PLATFORM_IMAGE']),('agent',os.environ['AGENT_IMAGE'])]:
    digest=(p/(name+'.digest')).read_text().strip()
    assert re.fullmatch(r'sha256:[0-9a-f]{64}',digest),'invalid pushed digest'
    result[name]={'tag':ref,'digest':digest,'pinned_image':ref.rsplit(':',1)[0]+'@'+digest}
    result[name]['remote_verification']=remote[name]
(p/'published-images.json').write_text(json.dumps(result,indent=2)+'\n')
print('Published source '+result['source_revision'])
for name in ('platform','agent'):print(name+' '+result[name]['tag']+' '+result[name]['digest'])
PY
