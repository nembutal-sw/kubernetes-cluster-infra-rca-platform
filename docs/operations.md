# Operations

## Backup

운영 백업은 애플리케이션 배포와 별도로 검증합니다.

PostgreSQL:

```bash
export RCA_BACKUP_DATABASE_URL='postgresql://rca:...@db.example.com:5432/rca'
pg_dump --format=custom --file=rca.dump "$RCA_BACKUP_DATABASE_URL"
pg_restore --list rca.dump > rca.dump.list
```

`RCA_BACKUP_DATABASE_URL`은 아래 명령에서만 사용하는 shell 변수이며 Platform 환경 변수가 아닙니다.

MariaDB:

```bash
mariadb-dump --single-transaction --routines --triggers rca \
  | gzip > rca.sql.gz
gzip -t rca.sql.gz
```

Helm 내장 DB에서는 `backup.enabled=true`로 CronJob과 전용 PVC를 생성할 수 있습니다.
관리형 DB에서는 provider snapshot과 PITR을 우선합니다.

## Restore

1. Platform replica를 0으로 조정해 쓰기를 중지합니다.
2. 빈 복구 DB를 생성합니다.
3. backup을 복구 DB에 적용합니다.
4. row count와 핵심 참조 무결성을 확인합니다.
5. Platform을 복구 DB에 연결합니다.
6. Flyway validation 후 readiness를 확인합니다.
7. Agent heartbeat와 analysis queue 처리를 확인합니다.

복구 검증:

```bash
bash scripts/validate-database-backup.sh
```

## Recovery Objectives

기본 운영 목표 예시:

- RPO: 24시간 이하
- RTO: 2시간 이하

실제 값은 backup 주기, DB 규모, 저장소 성능에 맞춰 별도로 결정합니다.

## Multi-Replica Safety

- session과 queue 상태는 DB에 저장합니다.
- 분석 task는 lease 기반으로 중복 claim을 차단합니다.
- Evidence response는 멱등 처리합니다.
- 1회용 manifest token은 DB에서 원자적으로 소비합니다.

## Agent Credential Lifecycle

### Bootstrap Token

bootstrap token은 `bootstrap-token` mode의 신규 노드 등록에만 사용합니다. 관리자는 Web Console 또는
다음 API로 회전하거나 폐기합니다.

```text
POST /api/clusters/{cluster_id}/agent-token/rotate
POST /api/clusters/{cluster_id}/agent-token/revoke
```

새 token은 한 번만 표시됩니다. 신규 등록이 필요한 기간에만 대상 cluster의 Kubernetes Secret을
갱신합니다. 이미 node token을 가진 Agent를 단순히 재시작하기 위해 bootstrap token을 다시
배포하지 않습니다.

### Node Token

Agent는 기본 30일마다 `POST /api/agents/token/rotate`를 호출합니다. 새 token을 state에 원자적으로
stage하고 heartbeat로 검증한 뒤 승격하며, 일시적 실패나 재시작 시 이전 token으로 복구합니다.

침해되었거나 identity가 바뀐 노드는 관리자가 다음 API로 폐기한 뒤 명시적으로 재등록합니다.

```text
POST /api/clusters/{cluster_id}/agents/{node_name}/token/revoke
```

자동 회전 주기는 `AGENT_NODE_TOKEN_ROTATION_DAYS`, 실패 재시도 간격은
`AGENT_NODE_TOKEN_ROTATION_RETRY_SECONDS`로 조정합니다.

### TokenReview Enrollment

장기 운영과 autoscaling cluster는 Kubernetes TokenReview mode를 권장합니다. 이 모드에는 bootstrap
token Secret이 없으며, 전용 audience의 projected token을 Platform reviewer가 검증합니다.

- enrollment audience와 Kubernetes API audience를 분리합니다.
- reviewer credential은 Agent ServiceAccount와 분리하고 TokenReview·Pod 조회 최소 RBAC만 부여합니다.
- 외부 reviewer Secret은 `/var/run/secrets/cluster-infra-rca-reviewers/<name>/token`에 읽기 전용으로
  mount하고 원문을 DB나 audit에 기록하지 않습니다.
- 교체는 새 Secret mount를 먼저 배포한 뒤 Console의 credential rotation에서 새 경로와 최대 grace를
  지정합니다. 상태가 `ready` 또는 `rotating`인지 확인한 뒤 이전 credential을 폐기합니다.
- `missing`, `invalid`, `expired`, `expiring` 상태와
  `rca.agent.reviewer.credentials.unavailable.count`를 경보 대상으로 사용합니다.
- audience 변경은 Agent chart 선배포, Helm과 분리된 cluster allowlist one-shot Job, canary, 최종
  audit, Platform upgrade 순서로 진행합니다.
- 자세한 전환과 복구는 [Agent Enrollment Upgrade](agent-enrollment-upgrade.md)를 따릅니다.

### Opaque Token Pepper

bootstrap/node token digest key는 versioned key ring으로 회전합니다. reader 준비, writer 전환,
lazy rehash의 세 단계를 거치며 이전 key를 제거하기 전에 잔여 credential을 회전하거나 폐기해야 합니다.
[Opaque Token Pepper Rotation](opaque-token-key-rotation.md)의 검증 명령과 rollback 조건을 따릅니다.
