# Agent Enrollment

Agent protocol v2는 최초 등록 identity와 등록 후 node credential을 분리합니다. 등록이 끝나면
heartbeat, evidence, realtime event는 항상 node-scoped Bearer token만 사용합니다.

## Modes

| Mode | 용도 | 등록 credential |
| --- | --- | --- |
| `bootstrap_token` | 기존 설치와 rolling upgrade | 짧은 TTL의 cluster bootstrap token |
| `kubernetes_token_review` | Kubernetes workload identity 검증 | projected ServiceAccount token |

기본값은 호환성을 위한 `bootstrap_token`입니다. 운영 전환은 Web Console의 **Clusters > Agent
enrollment**에서 설정합니다. CA 원문은 저장되지만 조회 API와 audit에는 노출하지 않고 SHA-256
fingerprint만 표시합니다.

## TokenReview Trust Flow

1. Agent가 projected token 파일을 등록 요청마다 다시 읽습니다.
2. Platform은 관리자가 저장한 HTTPS API Server URL과 전용 CA만 사용합니다.
3. Agent token으로 `TokenReview`를 호출하고 audience, subject, UID, group을 검증합니다.
4. token의 Pod name/UID를 사용해 같은 API Server에서 Pod를 다시 조회합니다.
5. Pod UID, namespace, ServiceAccount, node name, 삭제 상태가 모두 일치할 때만 등록합니다.
6. 신뢰된 identity metadata는 Platform이 생성해 Agent metadata를 덮어씁니다.

Kubernetes가 token extra에 넣은 node metadata는 그 자체로 신뢰하지 않습니다. Platform의 Pod
재조회 결과만 node binding의 근거로 사용합니다.

## Configure The Profile

`PUT /api/clusters/{clusterId}/agent-enrollment`은 `ADMIN`만 호출할 수 있습니다.

```json
{
  "mode": "kubernetes_token_review",
  "api_server_url": "https://api.example.internal:6443",
  "ca_bundle_pem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
  "audience": "https://kubernetes.default.svc",
  "namespace": "rca-system",
  "service_account": "cluster-infra-rca-agent",
  "bootstrap_fallback_allowed": false
}
```

기존 profile 수정 시 `ca_bundle_pem`을 비우면 저장된 CA를 유지합니다. URL은 path, query,
userinfo가 없는 HTTPS origin이어야 합니다. audience는 대상 API Server가 인증 대상으로 수락하는
값이어야 합니다. 그렇지 않으면 Agent token이 정상이어도 TokenReview가 거부됩니다.

## Helm Install

먼저 cluster ID만 포함한 Secret을 준비합니다. TokenReview mode에는 `agent-token`이 없습니다.

```bash
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id='<cluster-id>' \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set backendUrl=https://rca.example.com \
  --set enrollment.mode=kubernetes-token-review \
  --set enrollment.audience=https://kubernetes.default.svc \
  --set secret.existingSecret.name=cluster-infra-rca-agent
```

Chart는 audience와 600~86400초의 token lifetime을 검증하고 projected ServiceAccount token을
mount합니다. TokenReview mode에서만 Agent ServiceAccount에 `tokenreviews.create`를 추가합니다.
Pod 재검증에는 기존 read-only Pod `get/list` 권한을 사용합니다.

## Strict Mode And Recovery

`bootstrap_fallback_allowed=false`로 저장하면 같은 DB transaction에서 기존 bootstrap token을
폐기합니다. 이후 bootstrap header나 body credential은 등록에 사용할 수 없습니다.

bootstrap mode로 되돌릴 때 token을 자동 생성하지 않습니다. 응답의
`bootstrap_token_rotation_required=true`를 확인하고 Web Console에서 token을 명시적으로 회전한
후 Agent Secret을 갱신합니다. 이 동작은 의도하지 않은 장기 credential 생성을 막기 위한 것입니다.

## Security Boundaries

- token과 CA 원문을 로그, audit, profile 응답에 기록하지 않습니다.
- target API Server redirect를 따르지 않고 응답 크기와 timeout을 제한합니다.
- Agent가 보낸 API URL, CA, `_enrollment` metadata를 신뢰하지 않습니다.
- node credential 거부 시 bootstrap으로 자동 재등록하지 않습니다.
- ServiceAccount token file은 kubelet rotation을 반영하도록 요청 시점에 읽습니다.

## References

- [TokenReview v1 API](https://kubernetes.io/docs/reference/kubernetes-api/definitions/token-review-v1-authentication/)
- [ServiceAccount token projection](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)
- [Projected volumes](https://kubernetes.io/docs/concepts/storage/projected-volumes/)
- [ServiceAccount administration and bound token claims](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/)
