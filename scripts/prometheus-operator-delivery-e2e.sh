#!/usr/bin/env bash
set -euo pipefail

apply=false
keep_resources=false
confirm_context=""
namespace=""
selector_label="release=monitoring"
timeout_seconds=240
output_dir=""
sink_image="python:3.12-slim@sha256:423ed6ab25b1921a477529254bfeeabf5855151dc2c3141699a1bfc852199fbf"
run_id="rca-operator-e2e-$(date -u +%Y%m%d%H%M%S)-${RANDOM}"

usage() {
  cat <<'EOF'
Usage: scripts/prometheus-operator-delivery-e2e.sh [options]

Validates Prometheus Operator -> Alertmanager -> authenticated webhook delivery.
Without --apply it prints a safety summary and does not access a cluster.

Required for --apply:
  --confirm-context NAME   Exact kubectl context that may be used

Options:
  --apply                  Create the isolated canary namespace and resources
  --keep-resources         Keep the canary namespace after validation
  --namespace NAME         Must not exist; defaults to a unique e2e namespace
  --selector-label K=V     Label selected by Prometheus and Alertmanager (default: release=monitoring)
  --timeout SECONDS        Timeout for firing and resolved delivery (default: 240)
  --output-dir PATH        Validation artifact directory
  --sink-image REF         Digest-pinned Python image for the webhook sink
  -h, --help               Show this help

Safety boundaries:
  - never modifies existing namespaces or monitoring resources
  - creates only one ownership-labeled namespace
  - deletes resources only when the namespace ownership label matches this run
  - never logs or stores the webhook token in validation artifacts
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) apply=true; shift ;;
    --keep-resources) keep_resources=true; shift ;;
    --confirm-context) confirm_context="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --selector-label) selector_label="$2"; shift 2 ;;
    --timeout) timeout_seconds="$2"; shift 2 ;;
    --output-dir) output_dir="$2"; shift 2 ;;
    --sink-image) sink_image="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
namespace="${namespace:-${run_id}}"
output_dir="${output_dir:-${repo_root}/validation-results/prometheus-operator-delivery/${run_id}}"
sink_name="rca-operator-webhook"
rule_name="rca-operator-delivery"
alert_config_name="rca-operator-delivery"
expected_alert="ClusterRcaOperatorDeliveryCanary"
namespace_created=false

[[ "${namespace}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || { echo "Invalid namespace: ${namespace}" >&2; exit 2; }
(( ${#namespace} <= 63 )) || { echo "Namespace is longer than 63 characters." >&2; exit 2; }
[[ "${selector_label}" == *=* ]] || { echo "--selector-label must use key=value." >&2; exit 2; }
[[ "${timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || { echo "--timeout must be a positive integer." >&2; exit 2; }
[[ "${sink_image}" == *@sha256:* ]] || { echo "--sink-image must be digest pinned." >&2; exit 2; }

selector_key="${selector_label%%=*}"
selector_value="${selector_label#*=}"
[[ -n "${selector_key}" && -n "${selector_value}" ]] || { echo "Selector key and value are required." >&2; exit 2; }

if [[ "${apply}" != "true" ]]; then
  cat <<EOF
Prometheus Operator delivery canary is ready.
  namespace: ${namespace}
  selector: ${selector_label}
  sink image: ${sink_image}
  mutation: disabled (use --apply with --confirm-context)
EOF
  exit 0
fi

for command in kubectl python3; do
  command -v "${command}" >/dev/null 2>&1 || { echo "${command} is required." >&2; exit 2; }
done
[[ -n "${confirm_context}" ]] || { echo "--confirm-context is required with --apply." >&2; exit 2; }
current_context="$(kubectl config current-context)"
[[ "${current_context}" == "${confirm_context}" ]] || {
  echo "kubectl context mismatch: current=${current_context} confirmed=${confirm_context}" >&2
  exit 2
}

kubectl get crd prometheusrules.monitoring.coreos.com >/dev/null
kubectl get crd alertmanagerconfigs.monitoring.coreos.com >/dev/null
if kubectl get namespace "${namespace}" >/dev/null 2>&1; then
  echo "Refusing to use existing namespace: ${namespace}" >&2
  exit 2
fi

mkdir -p "${output_dir}"

namespace_owned_by_run() {
  [[ "$(kubectl get namespace "${namespace}" -o jsonpath='{.metadata.labels.cluster-infra\.rca\.io/e2e-run-id}' 2>/dev/null || true)" == "${run_id}" ]]
}

collect_diagnostics() {
  if [[ "${namespace_created}" != "true" ]]; then
    return
  fi
  kubectl get prometheusrule,alertmanagerconfig,deploy,pod,svc -n "${namespace}" -o wide \
    >"${output_dir}/resources.txt" 2>&1 || true
  kubectl logs -n "${namespace}" deployment/"${sink_name}" --all-containers=true \
    >"${output_dir}/webhook-sink.log" 2>&1 || true
  kubectl get events -n "${namespace}" --sort-by=.lastTimestamp \
    >"${output_dir}/events.txt" 2>&1 || true
}

cleanup() {
  exit_code=$?
  trap - EXIT
  collect_diagnostics
  if [[ "${namespace_created}" == "true" && "${keep_resources}" != "true" ]]; then
    if namespace_owned_by_run; then
      kubectl delete namespace "${namespace}" --wait=false >/dev/null 2>&1 || true
    else
      echo "WARNING: namespace ownership changed; cleanup skipped for ${namespace}" >&2
      exit_code=1
    fi
  fi
  exit "${exit_code}"
}
trap cleanup EXIT

kubectl create namespace "${namespace}"
kubectl label namespace "${namespace}" "cluster-infra.rca.io/e2e-run-id=${run_id}" --overwrite
namespace_created=true

webhook_token="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
kubectl create secret generic "${sink_name}" -n "${namespace}" \
  --from-literal=token="${webhook_token}"
unset webhook_token
kubectl create configmap "${sink_name}" -n "${namespace}" \
  --from-file=server.py="${script_dir}/operator_webhook_sink.py"

kubectl apply -n "${namespace}" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${sink_name}
  labels:
    cluster-infra.rca.io/e2e-run-id: ${run_id}
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: ${sink_name}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ${sink_name}
        cluster-infra.rca.io/e2e-run-id: ${run_id}
    spec:
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        runAsGroup: 65532
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: webhook
          image: ${sink_image}
          imagePullPolicy: IfNotPresent
          command: ["python", "/app/server.py"]
          env:
            - name: WEBHOOK_TOKEN
              valueFrom:
                secretKeyRef:
                  name: ${sink_name}
                  key: token
            - name: EXPECTED_ALERT
              value: ${expected_alert}
            - name: PYTHONDONTWRITEBYTECODE
              value: "1"
          ports:
            - name: http
              containerPort: 8080
          readinessProbe:
            httpGet:
              path: /health
              port: http
            initialDelaySeconds: 1
            periodSeconds: 1
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          resources:
            requests:
              cpu: 10m
              memory: 24Mi
            limits:
              cpu: 100m
              memory: 64Mi
          volumeMounts:
            - name: sink-code
              mountPath: /app
              readOnly: true
      volumes:
        - name: sink-code
          configMap:
            name: ${sink_name}
---
apiVersion: v1
kind: Service
metadata:
  name: ${sink_name}
  labels:
    cluster-infra.rca.io/e2e-run-id: ${run_id}
spec:
  selector:
    app.kubernetes.io/name: ${sink_name}
  ports:
    - name: http
      port: 8080
      targetPort: http
EOF

kubectl rollout status -n "${namespace}" deployment/"${sink_name}" --timeout="${timeout_seconds}s"

kubectl apply -n "${namespace}" -f - <<EOF
apiVersion: monitoring.coreos.com/v1alpha1
kind: AlertmanagerConfig
metadata:
  name: ${alert_config_name}
  labels:
    cluster-infra.rca.io/e2e-run-id: ${run_id}
spec:
  route:
    receiver: rca-operator-e2e
    groupBy: [alertname, cluster_id, node]
    groupWait: 0s
    groupInterval: 1s
    repeatInterval: 1h
    matchers:
      - name: alertname
        matchType: "="
        value: ${expected_alert}
  receivers:
    - name: rca-operator-e2e
      webhookConfigs:
        - url: http://${sink_name}.${namespace}.svc:8080/api/webhooks/alertmanager
          sendResolved: true
          httpConfig:
            authorization:
              type: Bearer
              credentials:
                name: ${sink_name}
                key: token
---
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: ${rule_name}
  labels:
    cluster-infra.rca.io/e2e-run-id: ${run_id}
spec:
  groups:
    - name: rca-operator-delivery
      interval: 1s
      rules:
        - alert: ${expected_alert}
          expr: vector(1) > 0
          for: 0s
          labels:
            severity: info
            namespace: ${namespace}
            cluster_id: operator-delivery-e2e
            node: operator-delivery-e2e
          annotations:
            summary: Prometheus Operator delivery canary
EOF

kubectl label -n "${namespace}" alertmanagerconfig "${alert_config_name}" "${selector_label}" --overwrite
kubectl label -n "${namespace}" prometheusrule "${rule_name}" "${selector_label}" --overwrite

wait_for_status() {
  expected_status="$1"
  deadline=$(( $(date +%s) + timeout_seconds ))
  while (( $(date +%s) < deadline )); do
    logs="$(kubectl logs -n "${namespace}" deployment/"${sink_name}" --all-containers=true 2>/dev/null || true)"
    if grep -F "RCA_OPERATOR_DELIVERY" <<<"${logs}" \
      | grep -F "\"alert\": \"${expected_alert}\"" \
      | grep -Fq "\"status\": \"${expected_status}\""; then
      return 0
    fi
    if grep -Fq "RCA_OPERATOR_DELIVERY_ERROR" <<<"${logs}"; then
      echo "Webhook sink rejected an Alertmanager payload." >&2
      return 1
    fi
    sleep 2
  done
  echo "Timed out waiting for ${expected_status} notification." >&2
  return 1
}

wait_for_status firing
kubectl patch -n "${namespace}" prometheusrule "${rule_name}" --type=json \
  -p='[{"op":"replace","path":"/spec/groups/0/rules/0/expr","value":"vector(0) > 0"}]'
wait_for_status resolved

python3 - "${output_dir}/result.json" "${current_context}" "${namespace}" "${selector_key}" "${expected_alert}" <<'PY'
import json
import sys
from pathlib import Path

output, context, namespace, selector_key, alert = sys.argv[1:]
Path(output).write_text(
    json.dumps(
        {
            "status": "passed",
            "context": context,
            "namespace": namespace,
            "selector_label": selector_key,
            "alert": alert,
            "notification_statuses": ["firing", "resolved"],
            "authentication": "bearer secret reference",
        },
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)
PY
echo "Prometheus Operator delivery canary passed: firing and resolved notifications received."
