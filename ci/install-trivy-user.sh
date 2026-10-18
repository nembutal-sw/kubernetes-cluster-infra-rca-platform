#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
[[ $(id -un) == jenkins-agent && $(hostname -s) == ci-agent-01 ]] || { echo 'Run only as jenkins-agent on ci-agent-01' >&2; exit 1; }
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Linux x86_64 required' >&2; exit 1; }
version=0.76.0
archive_sha=e24b1c51293c1b15e4ba8d45bbc7891ba053f90278ec2698f3562c993e145fe7
binary_sha=1f889576636db0623539cb339df7d06c40ebf39e7919489eafa1160e29808f19
parent="$HOME/.local/share/rca-cicd"
target="$parent/trivy-v$version"
mkdir -p -- "$parent"
/usr/bin/python3 - "$parent" <<'PY'
import os,pathlib,sys
p=pathlib.Path(sys.argv[1]); home=pathlib.Path.home().resolve()
assert p.is_absolute() and p.resolve().is_relative_to(home), 'tool directory leaves home'
for q in [p,*p.parents]:
    if q == home.parent: break
    assert not q.is_symlink(), 'symlink in tool path'
    assert q.stat().st_uid == os.getuid(), 'tool path not owned by current user'
PY
exec 9>"$parent/trivy-install.lock"
flock 9
if [[ -e $target ]]; then
  [[ -d $target && ! -L $target && -O $target && -f $target/trivy && ! -L $target/trivy && -O $target/trivy ]] || { echo 'Existing tool path is unsafe' >&2; exit 1; }
  printf '%s  %s\n' "$binary_sha" "$target/trivy" | sha256sum --check --status || { echo 'Existing Trivy binary differs from pin' >&2; exit 1; }
  printf 'Checksum-verified Trivy %s already installed for this user\n' "$version"
  exit 0
fi
temp=$(mktemp -d "$parent/trivy-download.XXXXXXXX")
cleanup() {
  rm -f -- "$temp/archive.tar.gz" "$temp/trivy"
  rmdir -- "$temp" 2>/dev/null || true
}
trap cleanup EXIT
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error --retry 3 \
  "https://github.com/aquasecurity/trivy/releases/download/v$version/trivy_${version}_Linux-64bit.tar.gz" \
  --output "$temp/archive.tar.gz"
printf '%s  %s\n' "$archive_sha" "$temp/archive.tar.gz" | sha256sum --check --status
# Extract only the exact regular file to stdout; no archive paths are installed.
/usr/bin/python3 - "$temp/archive.tar.gz" "$temp/trivy" "$binary_sha" <<'PY'
import hashlib,pathlib,sys,tarfile
archive,out,expected=sys.argv[1:]
with tarfile.open(archive,'r:gz') as t:
    member=t.getmember('trivy')
    assert member.isfile() and member.size < 300*1024*1024
    data=t.extractfile(member).read()
assert hashlib.sha256(data).hexdigest() == expected
with open(out,'xb') as f: f.write(data)
pathlib.Path(out).chmod(0o755)
PY
mkdir -m 700 -- "$target"
install -m 0755 -- "$temp/trivy" "$target/trivy"
printf '%s  %s\n' "$binary_sha" "$target/trivy" | sha256sum --check --status
printf 'Installed checksum-verified Trivy %s under the Jenkins agent account\n' "$version"
