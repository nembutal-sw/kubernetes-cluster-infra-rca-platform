#!/usr/bin/env bash
set -euo pipefail

apply=false
keep_resources=false
base_url="${RCA_E2E_BASE_URL:-}"
username="${RCA_E2E_USERNAME:-admin}"
password="${RCA_E2E_PASSWORD:-}"
node_name=""
namespace=""
release_name=""
output_dir=""
repo_root=""
mode="node-diagnostics"
image_repository="python"
image_tag="3.12-slim@sha256:423ed6ab25b1921a477529254bfeeabf5855151dc2c3141699a1bfc852199fbf"
timeout_seconds=300
collectors="node,kubernetes,systemd,runtime,kubelet,kernel,network,conntrack,disk,inode,memory,process,cni,dns"
run_id="rca-e2e-$(date -u +%Y%m%d%H%M%S)-${RANDOM}"

usage() {
  cat <<'EOF'
Usage: scripts/real-cluster-agent-e2e.sh [options]

Runs a read-only Node Agent canary lifecycle against a real Kubernetes cluster.
Without --apply it performs preflight and render checks only.

Required for --apply:
  --base-url URL          Platform URL reachable from the selected node
  RCA_E2E_PASSWORD        Platform administrator password (environment only)

Options:
  --apply                 Create the isolated canary resources
  --keep-resources        Keep the namespace and Platform test cluster
  --node NAME             Ready Kubernetes node; defaults to the first Ready node
  --namespace NAME        Must not already exist; defaults to rca-agent-e2e-<run>
  --release NAME          Helm release; defaults to rca-agent-e2e-<run>
  --username NAME         Platform login ID
  --mode MODE             safe or node-diagnostics (default: node-diagnostics)
  --image-repository REF  Canary runtime image repository (default: python)
  --image-tag TAG         Canary runtime image tag/digest
  --collectors CSV        Evidence collectors to request
  --timeout SECONDS       Wait timeout per lifecycle phase (default: 300)
  --output-dir PATH       Artifact directory
  --repo-root PATH        Repository root
  -h, --help              Show this help

Safety boundaries:
  - never restarts, reboots, cordons, or drains a node
  - selects one node and mounts host evidence paths read-only
  - uses emptyDir for Agent state and an isolated namespace
  - deletes only resources carrying this run's e2e-run-id label
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) apply=true; shift ;;
    --keep-resources) keep_resources=true; shift ;;
    --base-url) base_url="$2"; shift 2 ;;
    --username) username="$2"; shift 2 ;;
    --node) node_name="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --release) release_name="$2"; shift 2 ;;
    --mode) mode="$2"; shift 2 ;;
    --image-repository) image_repository="$2"; shift 2 ;;
    --image-tag) image_tag="$2"; shift 2 ;;
    --collectors) collectors="$2"; shift 2 ;;
    --timeout) timeout_seconds="$2"; shift 2 ;;
    --output-dir) output_dir="$2"; shift 2 ;;
    --repo-root) repo_root="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="${repo_root:-$(cd "${script_dir}/.." && pwd)}"
namespace="${namespace:-rca-agent-e2e-${run_id#rca-e2e-}}"
release_name="${release_name:-rca-agent-e2e-${run_id#rca-e2e-}}"
output_dir="${output_dir:-${repo_root}/validation-results/real-cluster-agent-e2e/${run_id}}"
chart="${repo_root}/charts/cluster-infra-rca-agent"
bundle_path="/tmp/${run_id}-node-agent.tar.gz"
bundle_configmap="${release_name}-source"
cluster_name="real-agent-e2e-${run_id#rca-e2e-}"
cluster_id=""
access_token=""
namespace_created=false
cluster_created=false
test_succeeded=false
cleanup_state="not_applicable"
cleanup_warning=""
namespace_cleanup_state="not_applicable"
platform_cluster_cleanup_state="not_applicable"
helm_cleanup_state="not_applicable"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

log() {
  printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

sanitize_url() {
  printf '%s' "${1%/}"
}

api_get() {
  local path="$1"
  curl -fsS --retry 3 --retry-delay 1 --connect-timeout 10 --max-time 45 \
    -H "Authorization: Bearer ${access_token}" \
    "${base_url}${path}"
}

api_mutate() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  local args=(
    -fsS --retry 3 --retry-delay 1 --connect-timeout 10 --max-time 45
    -X "${method}"
    -H "Authorization: Bearer ${access_token}"
    -H "Origin: ${base_url}"
  )
  if [[ -n "${payload}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "${payload}")
  fi
  curl "${args[@]}" "${base_url}${path}"
}

namespace_owned_by_run() {
  [[ "$(kubectl get namespace "${namespace}" -o jsonpath='{.metadata.labels.cluster-infra\.rca\.io/e2e-run-id}' 2>/dev/null || true)" == "${run_id}" ]]
}

collect_diagnostics() {
  [[ "${namespace_created}" == "true" ]] || return 0
  mkdir -p "${output_dir}/kubernetes"
  kubectl -n "${namespace}" get pods,daemonsets,configmaps,serviceaccounts -o wide \
    >"${output_dir}/kubernetes/resources.txt" 2>&1 || true
  kubectl -n "${namespace}" get events --sort-by=.lastTimestamp \
    >"${output_dir}/kubernetes/events.txt" 2>&1 || true
  kubectl -n "${namespace}" describe daemonset "${release_name}" \
    >"${output_dir}/kubernetes/daemonset-describe.txt" 2>&1 || true
  kubectl -n "${namespace}" logs daemonset/"${release_name}" --all-containers --tail=500 \
    >"${output_dir}/kubernetes/agent.log" 2>&1 || true
}

cleanup() {
  local exit_code=$?
  set +e
  collect_diagnostics
  rm -f "${bundle_path}"

  if [[ "${apply}" == "true" && "${keep_resources}" != "true" ]]; then
    cleanup_state="completed"
    if helm status "${release_name}" -n "${namespace}" >/dev/null 2>&1; then
      helm_cleanup_state="failed"
      if helm uninstall "${release_name}" -n "${namespace}" \
        >"${output_dir}/helm-uninstall.txt" 2>&1; then
        helm_cleanup_state="completed"
      else
        cleanup_warning="Helm canary release uninstall failed."
        printf '%s\n' "${cleanup_warning}" >"${output_dir}/cleanup-warning.txt"
      fi
    fi
    if [[ "${namespace_created}" == "true" ]] && namespace_owned_by_run; then
      namespace_cleanup_state="pending"
      kubectl delete namespace "${namespace}" --wait=false \
        >"${output_dir}/namespace-delete.txt" 2>&1 || true
      for _ in $(seq 1 30); do
        if ! kubectl get namespace "${namespace}" >/dev/null 2>&1; then
          namespace_cleanup_state="completed"
          break
        fi
        sleep 2
      done
      if [[ "${namespace_cleanup_state}" == "pending" ]]; then
        cleanup_warning="Namespace deletion is still pending; inspect namespace conditions before manual finalization."
        printf '%s\n' "${cleanup_warning}" >"${output_dir}/cleanup-warning.txt"
        kubectl get namespace "${namespace}" -o json \
          >"${output_dir}/namespace-pending.json" 2>/dev/null || true
      fi
    fi
    if [[ "${cluster_created}" == "true" && -n "${access_token}" && -n "${cluster_id}" ]]; then
      platform_cluster_cleanup_state="failed"
      if curl -fsS --connect-timeout 10 --max-time 45 -X DELETE \
        -H "Authorization: Bearer ${access_token}" \
        -H "Origin: ${base_url}" \
        --get --data-urlencode "confirm_name=${cluster_name}" \
        "${base_url}/api/clusters/${cluster_id}" \
        >"${output_dir}/cluster-delete.json" 2>"${output_dir}/cluster-delete.err"; then
        platform_cluster_cleanup_state="completed"
      else
        cleanup_warning="${cleanup_warning:+${cleanup_warning} }Platform test cluster deletion failed."
        printf '%s\n' "${cleanup_warning}" >"${output_dir}/cleanup-warning.txt"
      fi
    fi
    if [[ "${helm_cleanup_state}" == "failed" || "${namespace_cleanup_state}" == "pending" || "${platform_cluster_cleanup_state}" == "failed" ]]; then
      cleanup_state="failed"
      exit_code=1
    fi
  elif [[ "${keep_resources}" == "true" ]]; then
    cleanup_state="kept"
    namespace_cleanup_state="kept"
    platform_cluster_cleanup_state="kept"
    helm_cleanup_state="kept"
  fi

  jq -n \
    --arg run_id "${run_id}" \
    --arg status "$([[ ${exit_code} -eq 0 && ${test_succeeded} == true ]] && echo passed || echo failed)" \
    --arg started_at "${started_at}" \
    --arg completed_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg node "${node_name}" \
    --arg namespace "${namespace}" \
    --arg release "${release_name}" \
    --arg cluster_id "${cluster_id}" \
    --arg cleanup_state "${cleanup_state}" \
    --arg namespace_cleanup_state "${namespace_cleanup_state}" \
    --arg platform_cluster_cleanup_state "${platform_cluster_cleanup_state}" \
    --arg helm_cleanup_state "${helm_cleanup_state}" \
    --arg cleanup_warning "${cleanup_warning}" \
    --argjson kept "${keep_resources}" \
    '{run_id:$run_id,status:$status,started_at:$started_at,completed_at:$completed_at,node:$node,namespace:$namespace,release:$release,cluster_id:$cluster_id,resources_kept:$kept,cleanup:{state:$cleanup_state,helm_state:$helm_cleanup_state,namespace_state:$namespace_cleanup_state,platform_cluster_state:$platform_cluster_cleanup_state,warning:(if $cleanup_warning == "" then null else $cleanup_warning end)}}' \
    >"${output_dir}/summary.json" 2>/dev/null || true
  exit "${exit_code}"
}
trap cleanup EXIT

mkdir -p "${output_dir}"
base_url="$(sanitize_url "${base_url}")"

for command in kubectl helm curl jq tar python3; do
  require_command "${command}"
done
[[ -d "${chart}" ]] || fail "Agent chart not found: ${chart}"
[[ -d "${repo_root}/node_agent" ]] || fail "node_agent source not found"
[[ "${mode}" == "safe" || "${mode}" == "node-diagnostics" ]] || fail "mode must be safe or node-diagnostics"
[[ "${timeout_seconds}" =~ ^[0-9]+$ ]] || fail "timeout must be an integer"
(( timeout_seconds >= 30 )) || fail "timeout must be at least 30 seconds"
[[ -n "${base_url}" ]] || fail "--base-url or RCA_E2E_BASE_URL is required"

log "Checking Platform readiness at ${base_url}"
curl -fsS --connect-timeout 10 --max-time 30 "${base_url}/health/ready" \
  | tee "${output_dir}/platform-readiness.json" >/dev/null

kubectl get nodes -o json >"${output_dir}/nodes-before.json"
if [[ -z "${node_name}" ]]; then
  node_name="$(jq -r '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True")) | select((.metadata.labels["eks.amazonaws.com/compute-type"] // "") != "fargate")][0].metadata.name // empty' "${output_dir}/nodes-before.json")"
fi
[[ -n "${node_name}" ]] || fail "No Ready DaemonSet-compatible Kubernetes node found"
jq -e --arg node "${node_name}" '.items[] | select(.metadata.name == $node) | any(.status.conditions[]?; .type == "Ready" and .status == "True")' \
  "${output_dir}/nodes-before.json" >/dev/null || fail "Selected node is missing or not Ready: ${node_name}"
selected_compute_type="$(jq -r --arg node "${node_name}" '.items[] | select(.metadata.name == $node) | .metadata.labels["eks.amazonaws.com/compute-type"] // ""' "${output_dir}/nodes-before.json")"
[[ "${selected_compute_type}" != "fargate" ]] || fail "EKS Fargate does not support the Agent DaemonSet"
if [[ "${selected_compute_type}" == "auto" && "${mode}" != "safe" ]]; then
  fail "EKS Auto Mode requires a safe-mode canary until host evidence access is verified"
fi

for permission in \
  'create namespaces' \
  'create daemonsets.apps' \
  'create configmaps' \
  'create secrets' \
  'create serviceaccounts' \
  'create clusterroles.rbac.authorization.k8s.io' \
  'create clusterrolebindings.rbac.authorization.k8s.io'; do
  read -r verb resource <<<"${permission}"
  [[ "$(kubectl auth can-i "${verb}" "${resource}")" == "yes" ]] || fail "kubectl cannot ${verb} ${resource}"
done

helm lint "${chart}" \
  --set backendUrl="${base_url}" \
  --set secret.create=true \
  --set secret.clusterId=preflight-cluster \
  --set secret.agentToken=preflight-token \
  --set developmentSourceBundle.enabled=true \
  --set developmentSourceBundle.configMapName=preflight-source \
  >"${output_dir}/helm-lint.txt"

helm template "${release_name}" "${chart}" \
  --namespace default \
  --set namespace.create=false \
  --set namespace.name=default \
  --set fullnameOverride="${release_name}" \
  --set image.repository="${image_repository}" \
  --set-string image.tag="${image_tag}" \
  --set backendUrl="${base_url}" \
  --set mode="${mode}" \
  --set secret.create=true \
  --set secret.clusterId=preflight-cluster \
  --set secret.agentToken=preflight-token \
  --set-string "nodeSelector.kubernetes\\.io/hostname=${node_name}" \
  --set statePersistence.enabled=false \
  --set developmentSourceBundle.enabled=true \
  --set developmentSourceBundle.configMapName=preflight-source \
  >"${output_dir}/agent-rendered.yaml"

kubectl apply --dry-run=server -f "${output_dir}/agent-rendered.yaml" \
  >"${output_dir}/server-dry-run.txt"

jq -n \
  --arg run_id "${run_id}" \
  --arg node "${node_name}" \
  --arg namespace "${namespace}" \
  --arg release "${release_name}" \
  --arg mode "${mode}" \
  --arg base_url "${base_url}" \
  --argjson apply "${apply}" \
  '{run_id:$run_id,node:$node,namespace:$namespace,release:$release,mode:$mode,base_url:$base_url,apply:$apply,mutations:["isolated namespace","source ConfigMap","single-node DaemonSet","Platform test cluster"]}' \
  >"${output_dir}/plan.json"

if [[ "${apply}" != "true" ]]; then
  test_succeeded=true
  log "Preflight passed. Re-run with --apply to execute the isolated lifecycle."
  exit 0
fi

[[ -n "${password}" ]] || fail "RCA_E2E_PASSWORD is required with --apply"
if kubectl get namespace "${namespace}" >/dev/null 2>&1; then
  fail "Namespace already exists; choose a unique --namespace: ${namespace}"
fi

log "Authenticating to the Platform"
login_payload="$(jq -nc --arg username "${username}" --arg password "${password}" '{username:$username,password:$password}')"
access_token="$(curl -fsS --connect-timeout 10 --max-time 45 \
  -H "Origin: ${base_url}" -H 'Content-Type: application/json' \
  --data "${login_payload}" "${base_url}/api/auth/login" | jq -r '.access_token // empty')"
[[ -n "${access_token}" ]] || fail "Platform login did not return an access token"

log "Creating isolated Platform cluster ${cluster_name}"
cluster_payload="$(jq -nc --arg name "${cluster_name}" '{name:$name,environment:"e2e",description:"Real cluster read-only Agent lifecycle canary"}')"
cluster_response="$(api_mutate POST /api/clusters "${cluster_payload}")"
cluster_id="$(jq -r '.cluster_id // empty' <<<"${cluster_response}")"
agent_token="$(jq -r '.bootstrap_token // empty' <<<"${cluster_response}")"
[[ -n "${cluster_id}" && -n "${agent_token}" ]] || fail "Cluster create response is missing credentials"
cluster_created=true
jq 'del(.bootstrap_token)' <<<"${cluster_response}" >"${output_dir}/cluster.json"

log "Creating isolated namespace ${namespace}"
kubectl create namespace "${namespace}" >/dev/null
namespace_created=true
kubectl label namespace "${namespace}" \
  "cluster-infra.rca.io/e2e-run-id=${run_id}" \
  "app.kubernetes.io/managed-by=cluster-rca-e2e" --overwrite >/dev/null

tar -C "${repo_root}" -czf "${bundle_path}" node_agent
bundle_bytes="$(wc -c <"${bundle_path}")"
(( bundle_bytes < 750000 )) || fail "Agent source bundle exceeds safe ConfigMap size: ${bundle_bytes} bytes"
kubectl -n "${namespace}" create configmap "${bundle_configmap}" \
  --from-file="node-agent.tar.gz=${bundle_path}" >/dev/null
kubectl -n "${namespace}" label configmap "${bundle_configmap}" \
  "cluster-infra.rca.io/e2e-run-id=${run_id}" --overwrite >/dev/null

log "Installing single-node read-only Agent canary on ${node_name}"
helm upgrade --install "${release_name}" "${chart}" \
  --namespace "${namespace}" \
  --set namespace.create=false \
  --set namespace.name="${namespace}" \
  --set fullnameOverride="${release_name}" \
  --set image.repository="${image_repository}" \
  --set-string image.tag="${image_tag}" \
  --set image.pullPolicy=IfNotPresent \
  --set backendUrl="${base_url}" \
  --set mode="${mode}" \
  --set secret.create=true \
  --set-string secret.clusterId="${cluster_id}" \
  --set-string secret.agentToken="${agent_token}" \
  --set-string "nodeSelector.kubernetes\\.io/hostname=${node_name}" \
  --set statePersistence.enabled=false \
  --set systemdCollectorMode=file \
  --set developmentSourceBundle.enabled=true \
  --set developmentSourceBundle.configMapName="${bundle_configmap}" \
  --set podLabels.cluster-infra-rca-e2e="${run_id}" \
  >"${output_dir}/helm-install.txt"

kubectl -n "${namespace}" rollout status daemonset/"${release_name}" \
  --timeout="${timeout_seconds}s" | tee "${output_dir}/rollout.txt"

deadline=$((SECONDS + timeout_seconds))
agent_json='[]'
while (( SECONDS < deadline )); do
  agent_json="$(api_get "/api/clusters/${cluster_id}/agents")"
  if jq -e --arg node "${node_name}" 'any(.[]; .node_name == $node)' <<<"${agent_json}" >/dev/null; then
    break
  fi
  sleep 2
done
jq -e --arg node "${node_name}" 'any(.[]; .node_name == $node)' <<<"${agent_json}" >/dev/null \
  || fail "Agent did not register from ${node_name}"
jq 'map(del(.node_token, .agent_token))' <<<"${agent_json}" >"${output_dir}/agents.json"
api_get "/api/clusters/${cluster_id}/agent-health" >"${output_dir}/agent-health.json"

log "Requesting real node evidence"
collectors_json="$(jq -R 'split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0))' <<<"${collectors}")"
request_payload="$(jq -nc \
  --arg cluster_id "${cluster_id}" \
  --arg node_name "${node_name}" \
  --argjson collectors "${collectors_json}" \
  '{cluster_id:$cluster_id,node_name:$node_name,alert_name:"RealClusterCanary",requested_collectors:$collectors,context:{source:"real_cluster_e2e",read_only:true}}')"
request_response="$(api_mutate POST /api/evidence/requests "${request_payload}")"
request_id="$(jq -r '.request_id // empty' <<<"${request_response}")"
[[ -n "${request_id}" ]] || fail "Evidence request did not return request_id"
jq . <<<"${request_response}" >"${output_dir}/evidence-request-created.json"

deadline=$((SECONDS + timeout_seconds))
request_status=""
while (( SECONDS < deadline )); do
  request_response="$(api_get "/api/evidence/requests/${request_id}")"
  request_status="$(jq -r '.status // empty' <<<"${request_response}")"
  [[ "${request_status}" == "completed" || "${request_status}" == "failed" ]] && break
  sleep 2
done
jq . <<<"${request_response}" >"${output_dir}/evidence-request-final.json"
[[ "${request_status}" == "completed" ]] || fail "Evidence request ended with status: ${request_status:-timeout}"

deadline=$((SECONDS + timeout_seconds))
report_json=''
while (( SECONDS < deadline )); do
  reports="$(api_get /api/rca/reports)"
  report_json="$(jq -c --arg cluster_id "${cluster_id}" '[.[] | select(.cluster_id == $cluster_id)] | first // empty' <<<"${reports}")"
  [[ -n "${report_json}" ]] && break
  sleep 2
done
[[ -n "${report_json}" ]] || fail "RCA report was not generated"
report_id="$(jq -r '.report_id' <<<"${report_json}")"
jq . <<<"${report_json}" >"${output_dir}/report.json"
root_cause_candidate_count="$(jq '(.root_cause_candidates // []) | length' <<<"${report_json}")"
if (( root_cause_candidate_count == 0 )); then
  log "RCA report completed without root cause candidates; the healthy canary produced no anomaly signal"
fi

api_get /api/rca/analysis-tasks >"${output_dir}/analysis-tasks.json"
api_get /api/rca/incidents >"${output_dir}/incidents.json"
incident_count="$(jq --arg cluster_id "${cluster_id}" '[.[] | select(.cluster_id == $cluster_id)] | length' "${output_dir}/incidents.json")"
(( incident_count > 0 )) || fail "No incident was correlated for the canary report"

api_get "/api/rca/reports/${report_id}/bundle/manifest" >"${output_dir}/bundle-manifest.json"
curl -fsS --connect-timeout 10 --max-time 60 \
  -H "Authorization: Bearer ${access_token}" \
  "${base_url}/api/rca/reports/${report_id}/bundle" \
  -o "${output_dir}/evidence-bundle.zip"
python3 "${repo_root}/scripts/verify_evidence_bundle.py" --json "${output_dir}/evidence-bundle.zip" \
  >"${output_dir}/bundle-verification.json"

api_get "/api/clusters/${cluster_id}/topology" >"${output_dir}/topology.json"
test_succeeded=true
log "Real-cluster Agent lifecycle passed for ${node_name}; artifacts: ${output_dir}"
