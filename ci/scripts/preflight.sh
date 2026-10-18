#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
for tool in git python3 java mvn node npm podman curl flock mktemp timeout systemd-run sha256sum; do
  command -v "$tool" >/dev/null || fail "missing tool: $tool"
done
java_version=$(java -version 2>&1)
[[ $java_version == *'version "21.'* ]] || fail 'Java 21 required'
node -e 'if(process.versions.node.split(".").map(Number).slice(0,2).reduce((a,b,i)=>i===0?b*1000:a+b,0)<22022) process.exit(1)'
python3 -c 'import sys; assert sys.version_info >= (3,12)'
[[ $(podman info --format '{{.Host.CgroupsVersion}}') == v2 ]] || fail 'cgroup v2 required'
controllers=$(podman info --format '{{join .Host.CgroupControllers " "}}')
[[ " $controllers " == *' cpu '* && " $controllers " == *' memory '* ]] || fail 'delegated CPU/memory controllers required'
pip_wheel=${RCA_PIP_WHEEL:-$HOME/.local/share/rca-cicd/pip-26.2.1-py3-none-any.whl}
[[ -f $pip_wheel && -O $pip_wheel && ! -L $pip_wheel ]] || fail 'owned pinned pip wheel prerequisite missing'
python3 - "$pip_wheel" <<'PY'
import hashlib,pathlib,sys
expected='71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e'
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()!=expected:
    raise SystemExit('BLOCKED: pip wheel SHA-256 differs from pinned official PyPI metadata')
PY
python3 -m venv --without-pip "$CI_STATE/venv"
PYTHONPATH="$pip_wheel" "$CI_STATE/venv/bin/python" -m pip --isolated install --no-index --no-deps "$pip_wheel"
unset PYTHONPATH
"$CI_STATE/venv/bin/python" -m pip --isolated install --disable-pip-version-check -r "$CI_SOURCE/requirements-dev.txt"
"$CI_STATE/venv/bin/python" -m unittest discover -s "$CI_HELPERS/../tests" -p 'test_*.py'
printf 'Prerequisites passed for source %s\n' "$SOURCE_SHA"
