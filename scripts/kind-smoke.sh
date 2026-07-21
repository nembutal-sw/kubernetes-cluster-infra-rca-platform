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

python3 scripts/agent-soak-validation.py \
  --profile "${agent_soak_profile}" \
  --collectors node,disk,inode,memory,process \
  --discover-agent-pods \
  --minimum-agent-pods "${agent_soak_minimum_pods}" \
  --require-runtime-observation \
  --health-url "http://127.0.0.1:${port}/health/ready" \
  --output-dir "${agent_soak_output_dir}"

test_succeeded="true"
echo "Kind multi-node platform, DaemonSet Agent fleet ${agent_soak_profile} runtime, evidence, incident, and RCA report validation passed."
