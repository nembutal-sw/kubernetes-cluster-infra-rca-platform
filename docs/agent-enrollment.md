# Agent Enrollment

Agent protocol v2는 최초 등록 identity와 등록 후 node credential을 분리한다. 등록이 끝나면
heartbeat, evidence, realtime event는 node-scoped Bearer token만 사용한다.

## Modes

| Mode | 용도 | 등록 credential |
| --- | --- | --- |
| `bootstrap_token` | 초기 설치와 호환 배포 | 짧은 TTL의 cluster bootstrap token |
| `kubernetes_token_review` | Kubernetes workload identity 검증 | projected ServiceAccount token |

운영 환경은 `kubernetes_token_review`와 `bootstrap_fallback_allowed=false`를 권장한다.

## Trust Boundary

TokenReview mode에서는 Agent token을 Kubernetes API 호출 인증에 재사용하지 않는다.

1. Agent가 전용 audience의 projected token을 Platform에 제출한다.
2. Platform은 Backend에 마운트된 별도 reviewer token으로 TokenReview를 호출한다.
3. audience, ServiceAccount subject/UID, 인증 group을 검증한다.
4. TokenReview의 Pod name/UID로 Pod를 다시 조회한다.
5. Pod가 `Running`이고 삭제 중이 아닌지 확인한다.
6. namespace, ServiceAccount, node name, 필수 label을 확인한다.
7. controller owner가 예상 DaemonSet name/UID와 일치하는지 확인한다.
8. 실행 중인 `agent` container의 `imageID` digest를 허용 digest와 비교한다.

Agent가 보낸 API URL, CA, node metadata, `_enrollment` 값은 신뢰하지 않는다. 검증을 통과한
identity만 Platform이 생성해 저장한다.

Agent용 audience는 Kubernetes API audience와 분리한다.

```text
Agent projected token audience: cluster-infra-rca-agent-enrollment
Platform reviewer audience:     https://kubernetes.default.svc
```

`RCA_KUBERNETES_API_AUDIENCES`에는 대상 API Server가 인증용으로 수락하는 audience를 모두
쉼표로 구분해 설정한다. Agent enrollment profile의 audience가 이 목록과 같으면 저장이
거부되고, 운영 profile은 기존 DB에 위험한 설정이 남아 있어도 시작하지 않는다.

## Backend Reviewer

Platform과 대상 cluster가 같다면 Platform chart의 reviewer를 활성화한다.

```bash
helm upgrade --install rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --set platform.kubernetesReviewer.enabled=true
```

이 옵션은 Platform ServiceAccount에 `tokenreviews.create`와 `pods.get`만 추가하고, 회전되는
projected token을 `/var/run/secrets/kubernetes.io/serviceaccount/token`에 mount한다. Agent
ServiceAccount에는 TokenReview 생성 권한을 부여하지 않는다.

외부 cluster를 검증하는 경우 해당 cluster의 전용 reviewer credential을 Backend container의
`/var/run/secrets/cluster-infra-rca-reviewers/<cluster>/token`에 mount한다. 일반 파일 경로나
Agent projected token은 reviewer credential로 사용할 수 없다.

Platform chart에서는 Secret 원문을 values에 넣지 않고 참조만 설정한다.

```yaml
platform:
  kubernetesReviewer:
    externalCredentials:
      - name: cluster-a-current
        secretName: reviewer-cluster-a-current
        secretKey: token
      - name: cluster-a-next
        secretName: reviewer-cluster-a-next
        secretKey: token
```

각 Secret key는 container에서
`/var/run/secrets/cluster-infra-rca-reviewers/<name>/token`으로 읽기 전용 mount된다.

## Two-Step Binding

Kubernetes object UID는 배포 후에 생성되므로 profile을 두 단계로 저장한다.

### 1. Staged profile

Web Console의 **Clusters > Agent enrollment**에서 API Server, CA, audience, namespace,
ServiceAccount, reviewer token path를 저장한다. immutable UID와 digest가 비어 있으면
`workload_identity_ready=false`이며 Agent 등록은 차단된다.

```json
{
  "mode": "kubernetes_token_review",
  "api_server_url": "https://kubernetes.default.svc",
  "ca_bundle_pem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
  "audience": "cluster-infra-rca-agent-enrollment",
  "namespace": "rca-system",
  "service_account": "cluster-infra-rca-agent",
  "reviewer_token_path": "/var/run/secrets/kubernetes.io/serviceaccount/token",
  "expected_daemon_set_name": "cluster-infra-rca-agent",
  "required_pod_labels": {
    "app.kubernetes.io/name": "cluster-infra-rca-agent",
    "cluster-infra-rca.io/cluster-id": "<cluster-id>"
  },
  "bootstrap_fallback_allowed": false
}
```

기존 profile 수정 시 `ca_bundle_pem`을 생략하면 저장된 CA를 유지한다.

### 2. Bind immutable identity

Agent manifest를 적용한 뒤 UID와 digest를 확인한다.

```bash
kubectl -n rca-system get serviceaccount cluster-infra-rca-agent \
  -o jsonpath='{.metadata.uid}'

kubectl -n rca-system get daemonset cluster-infra-rca-agent \
  -o jsonpath='{.metadata.uid}'

kubectl -n rca-system get pods \
  -l cluster-infra-rca.io/cluster-id=<cluster-id> \
  -o jsonpath='{.items[0].status.containerStatuses[?(@.name=="agent")].imageID}'
```

Web Console에 `expected_service_account_uid`, `expected_daemon_set_uid`,
`allowed_image_digest`를 저장한다. digest는 `sha256:<64 lowercase hex>` 형식이다.
모든 필드가 채워지면 `workload_identity_ready=true`가 되고 Agent 등록이 허용된다.

## Helm Install

TokenReview mode에서는 `agent-token` Secret key를 만들지 않는다. `clusterId`는 workload
identity label에 사용되므로 반드시 별도로 지정한다.

```bash
kubectl -n rca-system create secret generic cluster-infra-rca-agent \
  --from-literal=cluster-id='<cluster-id>' \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --create-namespace \
  --set fullnameOverride=cluster-infra-rca-agent \
  --set backendUrl=https://rca.example.com \
  --set clusterId='<cluster-id>' \
  --set enrollment.mode=kubernetes-token-review \
  --set enrollment.audience=cluster-infra-rca-agent-enrollment \
  --set secret.existingSecret.name=cluster-infra-rca-agent
```

## Rotation And Re-Enrollment

profile의 보안 필드가 바뀌면 `profile_version`이 증가하고 기존 node token은 모두 폐기된다.
node token 검증 시 등록 당시 profile version과 현재 version도 비교한다.

Reviewer credential 경로는 최초 profile 설정 후 일반 profile update로 바꿀 수 없다. 새 Secret을
먼저 mount한 다음 Web Console의 **Reviewer credential rotation**에서 새 경로와 이전 credential
유예 만료를 지정한다. Platform은 다음 순서로 처리한다.

1. `expected_version`이 현재 reviewer credential version과 같은지 확인한다.
2. 새 경로가 platform ServiceAccount token 또는 전용 reviewer root인지 확인한다.
3. 새 token 파일이 읽히고 비어 있지 않으며, JWT라면 만료 또는 만료 임박 상태가 아닌지 확인한다.
4. 새 경로를 current로 전환하고 기존 경로를 bounded grace의 previous로 보관한다.
5. current 파일 읽기 실패 또는 Kubernetes API `401/403`에서만 previous로 한 번 fallback한다.
6. 검증이 끝나면 **Retire previous**로 이전 경로를 즉시 제거한다.

기본 만료 임박 구간은 300초, 최대 grace는 86400초다. 운영에서는 각각
`RCA_REVIEWER_CREDENTIAL_EXPIRING_SECONDS`,
`RCA_REVIEWER_CREDENTIAL_MAXIMUM_GRACE_SECONDS`로 설정하며 최대 grace는 7일을 넘길 수 없다.
`GET /api/clusters/{cluster_id}/agent-enrollment`의 `reviewer_credential_status`에서
`ready`, `rotating`, `expiring`, `expired`, `missing`, `invalid`, `unknown_expiry`를 확인한다.
Raw token은 DB, API 응답, audit에 기록되지 않는다.

V24 이전에 발급되어 `enrollment_profile_version`이 비어 있는 node token은 현재 profile이
존재하면 기본 거부된다. 순차 재등록 유예는 Web Console의 cluster별 enrollment profile에서만
설정한다. 유예는 현재 시각 기준 최대 30일이며, 종료 후 기존 token은 자동으로 인증되지 않는다.
화면에는 해당 cluster의 profile 미결합 Agent와 마지막 heartbeat, token 폐기 상태가 함께 표시된다.
운영자는 유예 안에 Agent를 재등록해 profile version, ServiceAccount UID, DaemonSet UID를
결합해야 한다. 과거 전역 설정 `RCA_LEGACY_UNBOUND_AGENT_TOKEN_GRACE_UNTIL`이 남아 있으면
Platform은 기동을 거부한다.

같은 node 이름에 활성 identity가 있으면 다른 Pod UID가 등록 정보를 덮어쓸 수 없다. DaemonSet을
재생성하거나 Agent state를 잃은 경우 관리자가 해당 node token을 명시적으로 revoke한 뒤
재등록한다. 같은 profile version에서는 ServiceAccount UID와 DaemonSet UID 연속성도 유지해야 한다.

기존 DB의 audience 전환 절차는 [Agent Enrollment Upgrade](agent-enrollment-upgrade.md)를 따른다.

## Strict Mode Recovery

`bootstrap_fallback_allowed=false`로 저장하면 기존 bootstrap token을 폐기한다. bootstrap mode로
되돌릴 때 새 token을 자동 발급하지 않으며, Web Console에서 명시적으로 회전해야 한다.

## References

- [Kubernetes Service Accounts](https://kubernetes.io/docs/concepts/security/service-accounts/)
- [ServiceAccount administration and TokenReview](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/)
- [Authentication and TokenReview](https://kubernetes.io/docs/reference/access-authn-authz/authentication/)
- [Owners and dependents](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/)
- [RBAC good practices](https://kubernetes.io/docs/concepts/security/rbac-good-practices/)
