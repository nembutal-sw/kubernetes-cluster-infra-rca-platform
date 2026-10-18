#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
for tool in git python3 java mvn node npm podman curl flock; do
  command -v "$tool" >/dev/null || fail "missing tool: $tool"
done
java_version=$(java -version 2>&1)
[[ $java_version == *'version "21.'* ]] || fail 'Java 21 required'
node -e 'if(process.versions.node.split(".").map(Number).slice(0,2).reduce((a,b,i)=>i===0?b*1000:a+b,0)<22022) process.exit(1)'
python3 -c 'import sys; assert sys.version_info >= (3,12)'
[[ $(podman info --format '{{.Host.CgroupsVersion}}') == v2 ]] || fail 'cgroup v2 required'
controllers=$(podman info --format '{{join .Host.CgroupControllers " "}}')
[[ " $controllers " == *' cpu '* && " $controllers " == *' memory '* ]] || fail 'delegated CPU/memory controllers required'
python3 -m venv "$CI_STATE/venv"
"$CI_STATE/venv/bin/python" -m pip install --disable-pip-version-check -r "$CI_SOURCE/requirements-dev.txt"
printf 'Prerequisites passed for source %s\n' "$SOURCE_SHA"
