#!/usr/bin/env bash
set -euo pipefail

cluster_name="${KIND_CLUSTER_NAME:-rca-smoke}"
port="${RCA_SMOKE_PORT:-18080}"
admin_password="${RCA_SMOKE_ADMIN_PASSWORD:-integration-password}"
agent_soak_profile="${RCA_AGENT_SOAK_PROFILE:-smoke}"
agent_soak_minimum_pods="${RCA_AGENT_SOAK_MINIMUM_PODS:-3}"
agent_soak_output_dir="${RCA_AGENT_SOAK_OUTPUT_DIR:-validation-results/kind-agent-runtime}"
port_forward_pid=""
test_succeeded="false"
curl_command=(curl -fsS --retry 5 --retry-delay 1 --retry-connrefused --retry-all-errors)
audience_api_status=""
audience_token_review_status=""

case "${agent_soak_profile}" in
  smoke|standard|extended) ;;
  *) echo "RCA_AGENT_SOAK_PROFILE must be smoke, standard, or extended" >&2; exit 2 ;;
esac
[[ "${agent_soak_minimum_pods}" =~ ^[2-9][0-9]*$ ]] \
  || { echo "RCA_AGENT_SOAK_MINIMUM_PODS must be an integer of at least two" >&2; exit 2; }

cleanup() {
  if [[ "${test_succeeded}" != "true" ]]; then
    echo "Kind smoke test failed. Collecting Kubernetes diagnostics." >&2
    kubectl get pods -A -o wide >&2 || true
    kubectl describe deployment/rca-platform >&2 || true
    kubectl logs deployment/rca-platform --all-containers --tail=200 >&2 || true
    kubectl -n rca-system logs daemonset/rca-agent-cluster-infra-rca-agent \
      --all-containers --tail=200 >&2 || true
  fi
  if [[ -n "${port_forward_pid}" ]]; then
    kill "${port_forward_pid}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_KIND_CLUSTER:-false}" != "true" ]]; then
    kind delete cluster --name "${cluster_name}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if ! kind get clusters | grep -Fxq "${cluster_name}"; then
  kind create cluster --name "${cluster_name}"
fi

validate_audience_boundary() {
  local namespace="rca-audience-boundary"
  local enrollment_audience="cluster-infra-rca-agent-enrollment"
  local ca_file
  local api_server
  local enrollment_token
  local reviewer_token
  local review_response
  local review_payload

  kubectl create namespace "${namespace}" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  cat <<'YAML' | kubectl -n "${namespace}" apply -f - >/dev/null
apiVersion: v1
kind: ServiceAccount
metadata:
  name: enrollment-subject
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: token-reviewer
YAML
  cat <<YAML | kubectl apply -f - >/dev/null
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: rca-audience-boundary-reviewer
rules:
  - apiGroups: ["authentication.k8s.io"]
    resources: ["tokenreviews"]
    verbs: ["create"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: rca-audience-boundary-reviewer
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: rca-audience-boundary-reviewer
subjects:
  - kind: ServiceAccount
    name: token-reviewer
    namespace: ${namespace}
YAML

  enrollment_token="$(kubectl -n "${namespace}" create token enrollment-subject \
    --audience="${enrollment_audience}" --duration=10m)"
  reviewer_token="$(kubectl -n "${namespace}" create token token-reviewer --duration=10m)"
  api_server="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"
  ca_file="$(mktemp)"
  kubectl config view --raw --minify \
    -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' \
    | base64 --decode > "${ca_file}"

  audience_api_status="$(curl -sS --cacert "${ca_file}" \
    -H "Authorization: Bearer ${enrollment_token}" \
    -o /tmp/rca-enrollment-api-response.json \
    -w '%{http_code}' \
    "${api_server}/api")"
  test "${audience_api_status}" = "401"

  review_payload="$(jq -nc \
    --arg token "${enrollment_token}" \
    --arg audience "${enrollment_audience}" \
    '{
      apiVersion:"authentication.k8s.io/v1",
      kind:"TokenReview",
      spec:{token:$token,audiences:[$audience]}
    }')"
  review_response="$(mktemp)"
  audience_token_review_status="$(curl -sS --cacert "${ca_file}" \
    -H "Authorization: Bearer ${reviewer_token}" \
    -H 'Content-Type: application/json' \
    -d "${review_payload}" \
    -o "${review_response}" \
    -w '%{http_code}' \
    "${api_server}/apis/authentication.k8s.io/v1/tokenreviews")"
  test "${audience_token_review_status}" = "201"
  jq -e \
    --arg audience "${enrollment_audience}" \
    '.status.authenticated == true and (.status.audiences | index($audience) != null)' \
    "${review_response}" >/dev/null

  rm -f "${ca_file}" "${review_response}" /tmp/rca-enrollment-api-response.json
}

validate_audience_boundary

docker build -q -f Dockerfile.web-console -t rca-platform:smoke .
docker build -q -f Dockerfile.agent -t rca-agent:smoke .
kind load docker-image --name "${cluster_name}" rca-platform:smoke rca-agent:smoke

helm upgrade --install rca charts/cluster-infra-rca-platform \
  --set fullnameOverride=rca \
  --set platform.image.repository=rca-platform \
  --set platform.image.tag=smoke \
  --set platform.image.pullPolicy=Never \
  --set-string platform.secret.defaultAdminUsername=admin \
  --set-string platform.secret.defaultAdminPassword="${admin_password}" \
  --set database.persistence.enabled=false
kubectl rollout status statefulset/rca-db --timeout=240s
kubectl rollout status deployment/rca-platform --timeout=480s

kubectl port-forward service/rca-platform "${port}:8080" > /tmp/rca-port-forward.log 2>&1 &
port_forward_pid="$!"
for _ in $(seq 1 60); do
  if "${curl_command[@]}" "http://127.0.0.1:${port}/health/ready" >/dev/null; then
    break
  fi
  sleep 2
done
"${curl_command[@]}" "http://127.0.0.1:${port}/health/ready" >/dev/null

access_token="$("${curl_command[@]}" "http://127.0.0.1:${port}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg password "${admin_password}" '{username:"admin",password:$password}')" \
  | jq -r .access_token)"
cluster_json="$("${curl_command[@]}" "http://127.0.0.1:${port}/api/clusters" \
  -H "Authorization: Bearer ${access_token}" \
  -H 'Content-Type: application/json' \
  -d '{"name":"kind-smoke","environment":"ci"}')"
cluster_id="$(jq -r .cluster_id <<<"${cluster_json}")"
agent_token="$(jq -r .bootstrap_token <<<"${cluster_json}")"

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --set image.repository=rca-agent \
  --set image.tag=smoke \
  --set image.pullPolicy=Never \
  --set mode=node-diagnostics \
  --set backendUrl=http://rca-platform.default.svc.cluster.local:8080 \
  --set secret.create=true \
  --set-string secret.clusterId="${cluster_id}" \
  --set-string secret.agentToken="${agent_token}"
kubectl -n rca-system rollout status daemonset/rca-agent-cluster-infra-rca-agent --timeout=240s

expected_agent_count="$(kubectl get nodes -o name | wc -l | tr -d '[:space:]')"
test "${expected_agent_count}" -ge "${agent_soak_minimum_pods}"
agent_count=0
for _ in $(seq 1 60); do
  agent_count="$("${curl_command[@]}" \
    -H "Authorization: Bearer ${access_token}" \
    "http://127.0.0.1:${port}/api/clusters/${cluster_id}/agents" | jq length)"
  if [[ "${agent_count}" -ge "${expected_agent_count}" ]]; then
    break
  fi
  sleep 2
done
test "${agent_count}" -ge "${expected_agent_count}"

node_name="$("${curl_command[@]}" \
  -H "Authorization: Bearer ${access_token}" \
  "http://127.0.0.1:${port}/api/clusters/${cluster_id}/agents" | jq -r '.[0].node_name')"
"${curl_command[@]}" "http://127.0.0.1:${port}/api/evidence/requests" \
  -H "Authorization: Bearer ${access_token}" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc \
    --arg cluster_id "${cluster_id}" \
    --arg node_name "${node_name}" \
    '{cluster_id:$cluster_id,node_name:$node_name,alert_name:"NodeNotReady",requested_collectors:["node","systemd","runtime","kubelet","kernel","network"]}')" \
  >/dev/null

report_count=0
for _ in $(seq 1 90); do
  report_count="$("${curl_command[@]}" \
    -H "Authorization: Bearer ${access_token}" \
    "http://127.0.0.1:${port}/api/rca/reports" | jq length)"
  if [[ "${report_count}" -gt 0 ]]; then
    break
  fi
  sleep 2
done
test "${report_count}" -gt 0
test "$("${curl_command[@]}" \
  -H "Authorization: Bearer ${access_token}" \
  "http://127.0.0.1:${port}/api/rca/incidents" | jq length)" -gt 0

export RCA_AGENT_SOAK_PLATFORM_URL="http://127.0.0.1:${port}"
export RCA_AGENT_SOAK_PLATFORM_CLUSTER_ID="${cluster_id}"
export RCA_AGENT_SOAK_PLATFORM_ACCESS_TOKEN="${access_token}"
export RCA_AGENT_SOAK_PLATFORM_FAMILY="${RCA_AGENT_SOAK_PLATFORM_FAMILY:-kind}"
export RCA_AGENT_SOAK_ARCHITECTURE="${RCA_AGENT_SOAK_ARCHITECTURE:-$(uname -m)}"
export RCA_AGENT_SOAK_AGENT_VERSION="${RCA_AGENT_SOAK_AGENT_VERSION:-smoke}"
python3 scripts/agent-soak-validation.py \
  --profile "${agent_soak_profile}" \
  --collectors node,disk,inode,memory,process \
  --discover-agent-pods \
  --platform-evidence-fleet \
  --minimum-agent-pods "${agent_soak_minimum_pods}" \
  --require-runtime-observation \
  --health-url "http://127.0.0.1:${port}/health/ready" \
  --output-dir "${agent_soak_output_dir}"
mkdir -p "${agent_soak_output_dir}"
jq -n \
  --arg enrollment_audience "cluster-infra-rca-agent-enrollment" \
  --arg kubernetes_api_status "${audience_api_status}" \
  --arg token_review_status "${audience_token_review_status}" \
  '{
    enrollment_audience:$enrollment_audience,
    kubernetes_api_access_status:($kubernetes_api_status | tonumber),
    token_review_status:($token_review_status | tonumber),
    enrollment_token_rejected_as_api_credential:($kubernetes_api_status == "401"),
    enrollment_token_authenticated_by_token_review:($token_review_status == "201")
  }' > "${agent_soak_output_dir}/audience-boundary.json"
unset RCA_AGENT_SOAK_PLATFORM_ACCESS_TOKEN

test_succeeded="true"
echo "Kind audience isolation, TokenReview, multi-node platform, DaemonSet Agent fleet ${agent_soak_profile} runtime, evidence, incident, and RCA report validation passed."
