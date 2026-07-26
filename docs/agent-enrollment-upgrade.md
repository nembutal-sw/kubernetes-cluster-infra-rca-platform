# Agent Enrollment Upgrade

기존 DB의 Agent enrollment audience가 Kubernetes API audience와 같은 경우 새 Platform은 기동을
거부한다. 이 문서는 Agent 통신을 유지하면서 전용 audience로 전환하는 운영 절차다.

## Safety Contract

- 기본 pre-upgrade mode는 `audit`이며 DB를 변경하지 않는다.
- `apply`는 확인 문자열과 최대 100개의 cluster allowlist를 모두 요구한다.
- 선택한 profile만 변경하고 `profile_version`을 증가시킨다.
- 해당 cluster의 기존 node token은 같은 transaction에서 폐기한다.
- bootstrap token, node token, reviewer token과 DB password는 출력하지 않는다.
- 전역 legacy token grace는 허용하지 않는다.

## 1. 준비

DB를 백업하고 모든 Platform replica가 같은 DB를 사용하는지 확인한다. 전용 audience는 API Server가
수락하는 audience 목록과 달라야 한다.

```bash
helm template rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --show-only templates/platform-agent-enrollment-preflight-job.yaml
```

기본 Job은 `audit` mode다. 실제 upgrade에서도 이 Job이 위험 profile을 발견하면 exit code `3`으로
실패하고 기존 release를 유지한다.

## 2. Agent 선배포

먼저 Agent chart의 projected token audience를 전용 값으로 바꾼다. 기존 node token은 계속
동작하므로 이 단계만으로 재등록은 발생하지 않는다.

```bash
helm upgrade --install rca-agent charts/cluster-infra-rca-agent \
  --namespace rca-system \
  --set enrollment.mode=kubernetes-token-review \
  --set enrollment.audience=cluster-infra-rca-agent-enrollment \
  --set-json 'enrollment.kubernetesApiAudiences=["https://kubernetes.default.svc","https://kubernetes.default.svc.cluster.local"]'
```

DaemonSet rollout과 기존 heartbeat를 확인한 뒤 다음 단계로 이동한다.

## 3. 선택 Cluster 전환

한 번에 canary cluster부터 전환한다. 아래 값의 cluster ID를 실제 대상으로 바꾸고, 나머지 운영
values와 Secret 설정을 기존 release와 동일하게 제공한다.

```bash
helm upgrade rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --reuse-values \
  --set platform.agentEnrollmentPreflight.mode=apply \
  --set-string platform.agentEnrollmentPreflight.confirm=APPLY_AGENT_ENROLLMENT_AUDIENCE_MIGRATION \
  --set-json 'platform.agentEnrollmentPreflight.clusters=["cluster-canary"]'
```

Job은 대상 profile을 전용 audience로 바꾸고 기존 node token을 폐기한다. Agent는 인증 거부를 받은
뒤 새 projected token으로 재등록해야 한다. 정상화 후 다음 upgrade부터 mode를 다시 `audit`으로
되돌린다.

```bash
helm upgrade rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --reuse-values \
  --set platform.agentEnrollmentPreflight.mode=audit \
  --set platform.agentEnrollmentPreflight.confirm= \
  --set-json 'platform.agentEnrollmentPreflight.clusters=[]'
```

## 4. 검증

```bash
kubectl -n rca-system get jobs,pods
kubectl -n rca-system logs job/rca-cluster-infra-rca-platform-agent-enrollment-preflight
kubectl -n rca-system rollout status deployment/rca-cluster-infra-rca-platform
kubectl -n rca-system rollout status daemonset/rca-agent
```

Web Console의 **Clusters > Agent enrollment**에서 다음을 확인한다.

- audience가 전용 값인지
- workload identity가 ready인지
- Agent heartbeat가 복구됐는지
- legacy unbound agent가 0개인지
- 폐기된 token으로 인증이 되지 않는지

V24 이전 profile 미결합 Agent를 순차 전환해야 하면 해당 cluster profile에만 최대 30일의 UTC
종료 시각을 설정한다. 재등록이 끝나면 만료를 기다리지 말고 값을 지운다.

## Failure And Recovery

- `audit` 실패: DB는 변경되지 않는다. Agent audience 선배포와 대상 profile 목록을 다시 확인한다.
- `apply` 전 실패: 기존 release와 DB가 유지된다. 원인을 수정한 뒤 같은 allowlist로 재실행한다.
- `apply` 후 Agent 재등록 실패: profile audience를 Kubernetes API audience로 되돌리지 않는다.
  ServiceAccount/DaemonSet UID, image digest, reviewer credential과 Agent audience를 바로잡는다.
- 잘못된 cluster를 변경했다면 추가 조작보다 먼저 DB backup과 audit log를 보존한다. 복구 승인을
  받은 뒤 DB snapshot을 복원하거나 해당 cluster의 workload identity를 올바르게 재바인딩한다.

## Kubernetes References

- [Service Accounts](https://kubernetes.io/docs/concepts/security/service-accounts/)
- [Projected service account token](https://kubernetes.io/docs/concepts/storage/projected-volumes/#serviceaccounttoken)
- [TokenReview API](https://kubernetes.io/docs/reference/access-authn-authz/authentication/#tokenreview-api)
