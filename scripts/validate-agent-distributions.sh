#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_case() {
  local name="$1"
  local image="$2"
  local install_command="$3"
  local python_command="$4"

  echo "==> ${name} (${image})"
  docker run --rm \
    --volume "${ROOT_DIR}:/workspace:ro" \
    --workdir /workspace \
    "${image}" \
    /bin/sh -ec "
      ${install_command}
      ${python_command} -m node_agent.main --collect-local --output /tmp/evidence.json
      ${python_command} -c '
import json
from pathlib import Path

payload = json.loads(Path(\"/tmp/evidence.json\").read_text(encoding=\"utf-8\"))
assert payload.get(\"node_name\")
assert isinstance(payload.get(\"collectors\"), dict)
assert {\"node\", \"disk\", \"inode\", \"memory\", \"network\", \"kernel\"}.issubset(payload[\"collectors\"])
print(\"collector payload validated\")
'
    "
}

run_case "Ubuntu 22.04" "ubuntu:22.04" \
  "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3" \
  "python3"
run_case "Ubuntu 24.04" "ubuntu:24.04" \
  "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3" \
  "python3"
run_case "Debian 12" "debian:12-slim" \
  "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3" \
  "python3"
run_case "Rocky Linux 9" "rockylinux:9-minimal" \
  "microdnf install -y python3 && microdnf clean all" \
  "python3"
run_case "openSUSE Leap 15.6" "opensuse/leap:15.6" \
  "zypper --non-interactive modifyrepo --disable --all && \
   zypper --non-interactive modifyrepo --enable repo-oss && \
   zypper --non-interactive install --no-recommends -y python311" \
  "python3.11"
