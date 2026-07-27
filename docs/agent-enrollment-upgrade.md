# Agent Enrollment Upgrade

기존 DB의 Agent enrollment audience가 Kubernetes API audience와 같은 경우 새 Platform은 기동을
거부한다. 이 문서는 Agent 통신을 유지하면서 전용 audience로 전환하는 운영 절차다.

## Safety Contract

- Helm pre-upgrade hook은 audit 전용이며 DB를 변경하지 않는다.
- `apply`는 Helm release와 분리된 one-shot Job으로만 실행한다.
- Apply Job은 확인 문자열과 최대 100개의 cluster allowlist를 모두 요구한다.
- 선택한 profile만 변경하고 `profile_version`을 증가시킨다.
- 해당 cluster의 기존 node token은 같은 transaction에서 폐기한다.
- Job에는 DB URL·사용자·비밀번호 세 항목만 Secret에서 주입한다.
- Hook과 one-shot Job은 신규 DB-client label과 기존 Platform selector를 함께 사용해 첫 rolling
  upgrade의 구버전 NetworkPolicy에서도 DB에 접근한다.
- 모든 unsafe profile이 사라진 최종 audit 전에는 Platform을 upgrade하지 않는다.
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

기존 Platform release를 실행한 상태에서 canary cluster부터 전환한다. Apply Job은 Helm values에
저장되지 않으며 `generateName`으로 생성되는 일회성 Kubernetes Job이다. 아래 image는 upgrade할
Platform image의 digest 고정 주소, Secret은 DB 접속 키 `RCA_JDBC_URL`, `RCA_DB_USERNAME`,
`RCA_DB_PASSWORD`를 가진 Secret으로 바꾼다.

```bash
job_ref="$(
  python3 scripts/render-agent-enrollment-migration-job.py \
    --mode apply \
    --image 'ghcr.io/example/cluster-infra-rca-web-console@sha256:<digest>' \
    --namespace rca-system \
    --helm-instance rca \
    --database-secret cluster-infra-rca-platform \
    --cluster cluster-canary \
    --kubernetes-api-audience https://kubernetes.default.svc \
    --kubernetes-api-audience https://kubernetes.default.svc.cluster.local \
    --confirm APPLY_AGENT_ENROLLMENT_AUDIENCE_MIGRATION \
  | kubectl create -f - -o name
)"
kubectl -n rca-system wait --for=condition=complete --timeout=180s "${job_ref}"
kubectl -n rca-system logs "${job_ref}"
```

Job은 대상 profile을 전용 audience로 바꾸고 기존 node token을 폐기한다. Agent는 인증 거부를 받은
뒤 새 projected token으로 재등록해야 한다. Web Console에서 해당 cluster의 profile version,
workload identity, node token과 heartbeat가 정상화된 것을 확인한 뒤 다음 cluster에 대해 새 Job을
생성한다.

Apply manifest는 token 원문을 포함하지 않지만 운영 변경 기록으로 취급한다. Job 이름, 대상 cluster,
image digest, 결과와 승인 번호를 Audit 또는 변경 티켓에 연결한다.

## 4. 최종 Audit과 Platform Upgrade

모든 cluster 전환이 끝나면 같은 image로 audit-only one-shot Job을 실행한다.

```bash
audit_ref="$(
  python3 scripts/render-agent-enrollment-migration-job.py \
    --mode audit \
    --image 'ghcr.io/example/cluster-infra-rca-web-console@sha256:<digest>' \
    --namespace rca-system \
    --helm-instance rca \
    --database-secret cluster-infra-rca-platform \
    --kubernetes-api-audience https://kubernetes.default.svc \
    --kubernetes-api-audience https://kubernetes.default.svc.cluster.local \
  | kubectl create -f - -o name
)"
kubectl -n rca-system wait --for=condition=complete --timeout=180s "${audit_ref}"
kubectl -n rca-system logs "${audit_ref}"
```

`unsafe_profile_count=0`을 확인한 뒤에만 실제 upgrade를 실행한다. Helm pre-upgrade audit hook이 같은
검사를 다시 수행하며 unsafe profile이 발견되면 새 Platform rollout 전에 upgrade를 실패시킨다.

```bash
helm upgrade rca charts/cluster-infra-rca-platform \
  --namespace rca-system \
  --reuse-values

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
- `apply` 전 실패: Helm release는 실행되지 않는다. DB transaction은 rollback되며 원인을 수정한 뒤
  새 one-shot Job을 만든다.
- `apply` 후 Agent 재등록 실패: profile audience를 Kubernetes API audience로 되돌리지 않는다.
  ServiceAccount/DaemonSet UID, image digest, reviewer credential과 Agent audience를 바로잡는다.
- 최종 audit 실패: 남은 unsafe cluster를 전환하기 전에는 Platform upgrade를 실행하지 않는다.
- pre-upgrade hook의 DB 연결 실패: `rca.clusterinfra.io/database-client=true` label과 DB
  NetworkPolicy·외부 방화벽, 세 개 DB Secret key를 확인한다.
- 잘못된 cluster를 변경했다면 추가 조작보다 먼저 DB backup과 audit log를 보존한다. 복구 승인을
  받은 뒤 DB snapshot을 복원하거나 해당 cluster의 workload identity를 올바르게 재바인딩한다.

## Kubernetes References

- [Service Accounts](https://kubernetes.io/docs/concepts/security/service-accounts/)
- [Projected service account token](https://kubernetes.io/docs/concepts/storage/projected-volumes/#serviceaccounttoken)
- [TokenReview API](https://kubernetes.io/docs/reference/access-authn-authz/authentication/#tokenreview-api)
