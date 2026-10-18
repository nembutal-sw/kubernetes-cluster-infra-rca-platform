#!/usr/bin/env bash
# Reviewed destination: ci/scripts/image-security.sh. Must run before credentials.
source "$(dirname "$0")/common.sh"
[[ $(cat "$CI_STATE/images-gated.sha") == "$SOURCE_SHA" ]] || fail 'runtime image gates not passed'
[[ ! -e $CI_STATE/security-gated.sha ]] || fail 'security gate marker already exists'
trivy="$HOME/.local/share/rca-cicd/trivy-v0.76.0/trivy"
binary_sha=1f889576636db0623539cb339df7d06c40ebf39e7919489eafa1160e29808f19
[[ -f $trivy && ! -L $trivy && -O $trivy ]] || fail 'pinned user-owned Trivy is missing'
printf '%s  %s\n' "$binary_sha" "$trivy" | sha256sum --check --status || fail 'Trivy binary checksum differs from pin'
cache="$HOME/.cache/rca-cicd/trivy-v0.76.0"
mkdir -p -- "$cache"
/usr/bin/python3 - "$cache" <<'PY'
import os,pathlib,sys
p=pathlib.Path(sys.argv[1]); home=pathlib.Path.home().resolve()
assert p.resolve().is_relative_to(home)
for q in [p,*p.parents]:
    if q == home.parent: break
    assert not q.is_symlink() and q.stat().st_uid==os.getuid(), 'unsafe scanner cache path'
p.chmod(0o700)
PY
exec 8>"$cache/scan.lock"
flock 8
security="$CI_STATE/security"
mkdir -m 700 -- "$security"
printf '{}\n' > "$security/empty-config.yaml"
: > "$security/empty-ignore"
seq=0
run_trivy() {
  seq=$((seq+1))
  # Transient scope of this unprivileged user; does not change a service unit.
  # Resource controller failure is a hard failure, never a silent unlimited scan.
  systemd-run --user --scope --quiet \
    --unit "rca-trivy-b${BUILD_NUMBER}-${seq}-$$" \
    --property CPUQuota=100% --property MemoryMax=1G --property TasksMax=128 \
    env -i HOME="$HOME" PATH=/usr/bin:/bin LANG=C.UTF-8 \
      GOMAXPROCS=1 GOMEMLIMIT=768MiB \
      "$trivy" --cache-dir "$cache" --config "$security/empty-config.yaml" "$@"
}
# New downloads/updates are mandatory before frozen per-build scans. The databases
# are public official OCI artifacts; no Registry or GitHub credentials are passed.
run_trivy image --download-db-only --db-repository ghcr.io/aquasecurity/trivy-db
run_trivy image --download-java-db-only --java-db-repository ghcr.io/aquasecurity/trivy-java-db
/usr/bin/python3 "$(dirname "$0")/security-evidence.py" database "$cache" "$security/database-before.json"
archive=''
cleanup_archive() {
  if [[ -n $archive && -f $archive && ! -L $archive ]]; then rm -f -- "$archive"; fi
}
trap cleanup_archive EXIT
failed=0
for component in platform agent; do
  if [[ $component == platform ]]; then image=$PLATFORM_IMAGE; else image=$AGENT_IMAGE; fi
  id=$(podman image inspect --format '{{.Id}}' "$image")
  [[ $id =~ ^(sha256:)?[a-f0-9]{64}$ ]] || fail 'invalid local image ID'
  [[ $(podman image inspect --format '{{index .Labels "org.opencontainers.image.revision"}}' "$id") == "$SOURCE_SHA" ]] || fail 'image source revision changed before security scan'
  archive="$security/$component.image.tar"
  [[ ! -e $archive ]] || fail 'scan archive already exists'
  # Trivy0.76 accepts Docker archives or OCI directories, not OCI archive TARs.
  # This export transport does not change the built/published OCI image.
  podman save --format docker-archive --output "$archive" "$id"
  /usr/bin/python3 "$(dirname "$0")/security-evidence.py" image "$archive" "$image" "$id" "$SOURCE_SHA" "$security/$component-input.json"
  flags=(image --input "$archive" --scanners vuln --pkg-types os,library \
    --parallel 1 --timeout 15m --skip-db-update --skip-java-db-update \
    --ignorefile "$security/empty-ignore" --ignore-unfixed)
  # First retain complete inventory and all severities. This reporting exit code
  # is followed by the separate mandatory HIGH/CRITICAL exit-code-1 gate below.
  run_trivy "${flags[@]}" --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL \
    --list-all-pkgs --format json --output "$security/$component-vulnerabilities.json" --exit-code 0
  /usr/bin/python3 "$(dirname "$0")/security-evidence.py" report "$component" "$security/$component-vulnerabilities.json" true
  run_trivy convert --format cyclonedx --output "$security/$component-sbom.cdx.json" "$security/$component-vulnerabilities.json"
  if run_trivy "${flags[@]}" --severity HIGH,CRITICAL --format json \
      --output "$security/$component-gate.json" --exit-code 1; then
    printf '0\n' > "$security/$component-gate.exit"
  else
    code=$?
    printf '%s\n' "$code" > "$security/$component-gate.exit"
    failed=1
  fi
  # Also checks report coverage: no report/empty analysis cannot be a pass.
  /usr/bin/python3 "$(dirname "$0")/security-evidence.py" report "$component" "$security/$component-gate.json"
  cleanup_archive
  archive=''
done
/usr/bin/python3 "$(dirname "$0")/security-evidence.py" database "$cache" "$security/database-after.json"
cmp --silent "$security/database-before.json" "$security/database-after.json" || fail 'scanner database changed during this build'
/usr/bin/python3 "$(dirname "$0")/security-evidence.py" summarize "$security" "$SOURCE_SHA"
[[ $failed == 0 ]] || fail 'image vulnerability gate rejected publication; inspect security artifacts'
printf '%s\n' "$SOURCE_SHA" > "$CI_STATE/security-gated.sha"
