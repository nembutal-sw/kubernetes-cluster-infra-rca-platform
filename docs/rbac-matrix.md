# RBAC Matrix

Web Console API의 기본 역할 계약을 정리한다. 실제 회귀 검증은 `RbacHttpAuthorizationTests`와 `scripts/verify-api-contract.py`가 담당한다.

## Roles

| Role | Purpose |
| --- | --- |
| `ADMIN` | 플랫폼 설정, 클러스터 삭제, 토큰 회전, 모든 운영 조치 승인/조회 |
| `OPERATOR` | 클러스터 등록, evidence 수집 요청, report 확인, 수동 조치 완료 기록 |
| `VIEWER` | 운영 현황과 RCA report 조회 |
| `APPROVER` | 승인 요청 조회, 승인/거절 기록 |
| `AUDITOR` | audit event 조회와 export |

## Sensitive Operations

| Operation | Allowed Roles | Notes |
| --- | --- | --- |
| Cluster create | `ADMIN`, `OPERATOR` | 신규 클러스터 등록 |
| Cluster delete | `ADMIN` | 운영 영향이 커서 관리자 전용 |
| Agent token rotate | `ADMIN` | 기존 bootstrap token 즉시 무효화 |
| Report list/detail | `ADMIN`, `OPERATOR`, `VIEWER`, `APPROVER` | 조회 전용 |
| Report JSON export | `ADMIN`, `OPERATOR` | 운영 로그와 evidence 요약 포함 |
| Evidence bundle download | `ADMIN`, `OPERATOR` | 민감한 노드/클러스터 구조 정보 포함 가능 |
| Audit event read/export | `ADMIN`, `AUDITOR` | 접근 기록, IP, user agent 포함 |
| Action execute request | `ADMIN`, `OPERATOR` | 직접 실행이 아니라 승인 요청 또는 수동 처리 흐름 |
| Action approve/reject | `ADMIN`, `APPROVER` | 승인 후에도 agent 자동 실행은 금지 |
| Manual completion | `ADMIN`, `OPERATOR` | 외부 runbook/GitOps 처리 완료 표시 |
| Metrics scrape | `ADMIN`, `OPERATOR`, `AUDITOR`, `METRICS` | `METRICS`는 token 기반 전용 role |

## Guardrails

- `VIEWER`는 변경 API에 접근할 수 없다.
- `APPROVER`는 승인 역할이며 export 역할이 아니다.
- `AUDITOR`는 audit/metrics 중심 역할이며 운영 변경 API에 접근할 수 없다.
- Agent/Webhook/Manifest API는 session role이 아니라 전용 token/filter로 보호한다.
- LLM이 제안한 조치는 항상 `automation_allowed=false` 상태를 유지하고, action workflow는 승인/감사/수동 완료 기록으로 제한한다.
