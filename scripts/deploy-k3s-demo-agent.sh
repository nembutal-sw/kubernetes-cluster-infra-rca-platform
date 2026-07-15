#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${RCA_DEPLOY_ENV_FILE:-${HOME}/.config/cluster-infra-rca-platform/demo.env}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: scripts/deploy-k3s-demo-agent.sh [--env-file PATH]"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

[[ -f "${env_file}" ]] || { echo "Deployment environment file not found: ${env_file}" >&2; exit 2; }
permissions="$(stat -c '%a' "${env_file}" 2>/dev/null || true)"
if [[ -n "${permissions}" ]] && (( (8#${permissions}) & 077 )); then
  echo "Deployment environment file must not be readable by group or others: ${env_file}" >&2
  exit 2
fi
set -a
# shellcheck disable=SC1090
source "${env_file}"
set +a

if [[ "${RCA_DEMO_K3S_AGENT_ENABLED:-false}" != "true" ]]; then
  echo "K3s demo Agent deployment is disabled."
  exit 0
fi

for command in docker helm kubectl k3s curl jq python3; do
  command -v "${command}" >/dev/null 2>&1 || { echo "${command} is required." >&2; exit 2; }
done

namespace="${RCA_DEMO_K3S_NAMESPACE:-rca-system}"
release_name="${RCA_DEMO_K3S_RELEASE_NAME:-cluster-infra-rca-agent}"
secret_name="${RCA_DEMO_K3S_AGENT_SECRET_NAME:-cluster-infra-rca-agent}"
backend_url="${RCA_PUBLIC_API_BASE_URL:-}"
image_tag="${RCA_DEMO_AGENT_IMAGE_TAG:-$(git -C "${repo_root}" rev-parse --short=12 HEAD)}"
image_ref="cluster-infra-rca-agent:${image_tag}"
chart="${repo_root}/charts/cluster-infra-rca-agent"

[[ -n "${backend_url}" ]] || { echo "RCA_PUBLIC_API_BASE_URL is required." >&2; exit 2; }
[[ "${namespace}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || { echo "Invalid K3s namespace." >&2; exit 2; }
[[ "${image_tag}" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "Invalid Agent image tag." >&2; exit 2; }
kubectl get namespace "${namespace}" >/dev/null
kubectl get secret "${secret_name}" -n "${namespace}" >/dev/null
[[ -n "$(kubectl get secret "${secret_name}" -n "${namespace}" -o jsonpath='{.data.cluster-id}')" ]] \
  || { echo "Agent Secret is missing cluster-id." >&2; exit 2; }
[[ -n "$(kubectl get secret "${secret_name}" -n "${namespace}" -o jsonpath='{.data.agent-token}')" ]] \
  || { echo "Agent Secret is missing agent-token." >&2; exit 2; }
curl --fail --silent --show-error --connect-timeout 3 --max-time 5 \
  "${backend_url%/}/health/ready" >/dev/null

previous_image="$(kubectl get daemonset "${release_name}" -n "${namespace}" \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="agent")].image}' 2>/dev/null || true)"
previous_backend="$(kubectl get configmap "${release_name}-config" -n "${namespace}" \
  -o jsonpath='{.data.BACKEND_URL}' 2>/dev/null || true)"
rendered="$(mktemp "${HOME}/.cache/rca-agent-rendered.XXXXXX.yaml")"
archive="$(mktemp "${HOME}/.cache/rca-agent-image.XXXXXX.tar")"
previous_daemonset="$(mktemp "${HOME}/.cache/rca-agent-previous.XXXXXX.json")"
applied=false
legacy_daemonset_recreated=false

cleanup() {
  rm -f "${rendered}" "${archive}" "${previous_daemonset}"
}
trap cleanup EXIT

rollback() {
  [[ "${applied}" == "true" ]] || return 0
  echo "Agent verification failed; restoring the previous deployment." >&2
  if [[ -n "${previous_backend}" ]]; then
    patch_payload="$(PREVIOUS_BACKEND="${previous_backend}" python3 -c \
      'import json, os; print(json.dumps({"data": {"BACKEND_URL": os.environ["PREVIOUS_BACKEND"]}}))')"
    kubectl patch configmap "${release_name}-config" -n "${namespace}" --type merge \
      -p "${patch_payload}" >/dev/null || true
  fi
  if [[ "${legacy_daemonset_recreated}" == "true" && -s "${previous_daemonset}" ]]; then
    kubectl delete daemonset "${release_name}" -n "${namespace}" --ignore-not-found --wait=true >/dev/null || true
    kubectl apply -f "${previous_daemonset}" >/dev/null || true
    kubectl rollout status daemonset/"${release_name}" -n "${namespace}" --timeout=180s || true
  elif [[ -n "${previous_image}" ]]; then
    kubectl set image daemonset/"${release_name}" -n "${namespace}" agent="${previous_image}" >/dev/null || true
    kubectl rollout status daemonset/"${release_name}" -n "${namespace}" --timeout=180s || true
  fi
}

echo "Building Agent image ${image_ref}."
docker build --pull -f "${repo_root}/Dockerfile.agent" -t "${image_ref}" "${repo_root}"
docker save -o "${archive}" "${image_ref}"
k3s ctr images import "${archive}" >/dev/null

helm lint "${chart}" \
  --set backendUrl="${backend_url}" \
  --set mode=node-diagnostics >/dev/null
helm template "${release_name}" "${chart}" \
  --namespace "${namespace}" \
  --set namespace.create=false \
  --set backendUrl="${backend_url}" \
  --set mode=node-diagnostics \
  --set image.repository=cluster-infra-rca-agent \
  --set image.tag="${image_tag}" \
  --set image.pullPolicy=Never \
  --set secret.create=false \
  --set secret.existingSecret.name="${secret_name}" >"${rendered}"

applied=true
existing_instance="$(kubectl get daemonset "${release_name}" -n "${namespace}" \
  -o jsonpath='{.spec.selector.matchLabels.app\.kubernetes\.io/instance}' 2>/dev/null || true)"
if kubectl get daemonset "${release_name}" -n "${namespace}" >/dev/null 2>&1 \
  && [[ "${existing_instance}" != "${release_name}" ]]; then
  kubectl get daemonset "${release_name}" -n "${namespace}" -o json | jq \
    'del(.metadata.annotations["kubectl.kubernetes.io/last-applied-configuration"],
         .metadata.creationTimestamp,
         .metadata.generation,
         .metadata.managedFields,
         .metadata.resourceVersion,
         .metadata.uid,
         .status)' >"${previous_daemonset}"
  kubectl delete daemonset "${release_name}" -n "${namespace}" --wait=true >/dev/null
  legacy_daemonset_recreated=true
fi
if ! kubectl apply -f "${rendered}" >/dev/null; then
  rollback
  exit 1
fi
if ! kubectl rollout status daemonset/"${release_name}" -n "${namespace}" --timeout=240s; then
  rollback
  exit 1
fi

connected=false
for _ in $(seq 1 30); do
  logs="$(kubectl logs -n "${namespace}" daemonset/"${release_name}" --since=3m --tail=100 2>/dev/null || true)"
  if grep -Eq 'registered node agent|poll cycle completed' <<<"${logs}"; then
    connected=true
    break
  fi
  sleep 2
done
if [[ "${connected}" != "true" ]]; then
  rollback
  exit 1
fi

echo "K3s demo Agent is connected with image ${image_ref}."
