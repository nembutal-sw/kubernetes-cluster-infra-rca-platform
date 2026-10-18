#!/usr/bin/env bash
set -euo pipefail
set +x
umask 022
export PATH="${RCA_NODE_BIN:-$HOME/.local/share/rca-cicd/node-v22.23.3-linux-x64/bin}:$PATH"
fail() { printf 'BLOCKED: %s\n' "$*" >&2; exit 1; }
[[ ${CI_SOURCE:-} == "${WORKSPACE:?}/source" ]] || fail 'unexpected source directory'
[[ ${CI_STATE:-} == "$WORKSPACE/.rca-ci/build-${BUILD_NUMBER:-}" ]] || fail 'unexpected state directory'
[[ ${SOURCE_SHA:-} =~ ^[a-f0-9]{40}$ ]] || fail 'invalid source revision'
[[ ${PIPELINE_SHA:-} =~ ^[a-f0-9]{40}$ && $PIPELINE_SHA == "$SOURCE_SHA" ]] || fail 'pipeline and source revision differ'
[[ ${BUILD_NUMBER:-} =~ ^[0-9]{1,12}$ ]] || fail 'invalid build number'
[[ $(id -un) == jenkins-agent ]] || fail 'run only as jenkins-agent'
[[ $(hostname -s) == ci-agent-01 ]] || fail 'run only on ci-agent-01'
[[ -z ${CONTAINER_HOST:-} && -z ${CONTAINER_CONNECTION:-} ]] || fail 'remote Podman environment is not allowed'
[[ $(podman info --format '{{.Host.Security.Rootless}}') == true ]] || fail 'rootless Podman required'
[[ $(git -C "$CI_SOURCE" rev-parse HEAD) == "$SOURCE_SHA" ]] || fail 'source revision changed'
git -C "$CI_SOURCE" diff --quiet || fail 'tracked source was modified'
git -C "$CI_SOURCE" diff --cached --quiet || fail 'staged source was modified'
mkdir -p -- "$CI_STATE"
[[ ! -L $CI_STATE && -O $CI_STATE ]] || fail 'state directory must be owned and not a symlink'
chmod 700 -- "$CI_STATE"
export IMAGE_TAG="git-${SOURCE_SHA}-b${BUILD_NUMBER}"
export PLATFORM_IMAGE="10.7.254.6:5000/rca-platform:$IMAGE_TAG"
export AGENT_IMAGE="10.7.254.6:5000/rca-node-agent:$IMAGE_TAG"
export PYTHONDONTWRITEBYTECODE=1
export MAVEN_OPTS='-Xmx1024m'
export NODE_OPTIONS='--max-old-space-size=1536'
