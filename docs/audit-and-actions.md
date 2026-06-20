# Audit And Action History

다음 이벤트는 `audit_events`에 기록합니다.

- 로그인 성공과 실패, 로그아웃, 비밀번호 변경
- 클러스터 등록과 삭제
- 수동 evidence 수집 요청
- Agent 등록과 evidence 제출
- incident 생성, 중복 correlation, 상태 변경
- 권장 조치 요청, 승인, 거절, 차단, 수동 처리 완료

`APPROVAL_REQUIRED` 조치는 승인 후에도 플랫폼과 Agent가 노드를 변경하지 않습니다. 승인
상태는 `approved_manual`로 기록되며 실제 변경은 운영자가 별도 runbook으로 수행합니다.
`GITOPS_PR_ONLY`는 YAML preview를 PR 작성 안내로만 사용합니다. 처리가 끝나면
`ADMIN` 또는 `OPERATOR`가 `completed` 상태를 기록합니다. `AUTO_SAFE`만 read-only evidence
request로 연결됩니다.

```text
GET  /api/rca/action-requests?report_id=...
POST /api/rca/action-requests/{id}/approve
POST /api/rca/action-requests/{id}/reject
POST /api/rca/action-requests/{id}/complete-manual
GET  /api/audit/events
```

기존 DB에 남은 `pending_approval`, `queued`, `leased` execution은 migration에서 `expired`로
격리됩니다. Agent action poll은 항상 빈 목록을 반환하며 action result 제출은 거부됩니다.

Audit 조회는 `ADMIN`과 `AUDITOR`에게만 허용합니다.
