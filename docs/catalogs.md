# Operational Catalogs

Backend는 collector selection, action policy/plan, rule detector enablement를 catalog에서 읽는다.

기본 catalog:

- `web-console/src/main/resources/catalog/operational-catalog.json`

외부 override:

- 환경 변수: `RCA_CATALOG_EXTERNAL_PATH`
- Spring property: `rca.catalog.external-path`

외부 catalog는 전체 파일일 필요가 없다. 필요한 key만 넣으면 기본 catalog와 병합된다.

## Scope

### Collector Catalog

`collectors`는 Agent collector의 의미, 활성화 여부, 필요한 permission mode를 정의한다.

`collector_selection`은 alert name별 요청 collector 목록을 정의한다. 예를 들어 `DiskPressure`는 기본적으로 `node`, `disk`, `inode`, `kernel`, `systemd`를 요청한다.

### Action Catalog

`actions`는 action key별 정책과 권장 문구를 정의한다.

중요한 원칙:

- `plan.executable`은 항상 `false`여야 한다.
- RCA Platform은 host mutation을 직접 실행하지 않는다.
- `APPROVAL_REQUIRED`, `GITOPS_PR_ONLY`, `NEVER_AUTO_EXECUTE`는 audit/runbook/GitOps 흐름으로만 연결한다.

`triggers`는 어떤 signal, component, alert에서 action을 추천할지 결정한다.

### Rule Catalog

`rules`는 detector id별 enablement를 정의한다.

예:

```json
{
  "schema_version": "rca-catalog/v1",
  "rules": {
    "disk-pressure": {
      "enabled": false
    }
  }
}
```

이 override를 적용하면 `disk-pressure` detector가 비활성화된다.

## Validation

부팅 시 다음 항목을 검증한다.

- `schema_version`이 `rca-catalog/v1`인지
- collector/action/rule key 형식이 유효한지
- collector selection이 존재하지 않는 collector를 참조하지 않는지
- action policy가 누락되지 않았는지
- `manual_investigation`, `collect_more_evidence` action이 존재하는지
- action plan이 `executable=true`를 사용하지 않는지

검증 실패 시 애플리케이션이 시작되지 않는다.

릴리즈 전 정적 검증:

```bash
python scripts/verify-operational-catalog.py
```

이 검증은 기본 catalog JSON의 필수 collector, alert selection, action, rule, `plan.executable=false` 계약을 Maven 실행 전에도 빠르게 확인한다.

## Platform Info

`GET /api/v1/platform/info`는 catalog 정보를 함께 반환한다.

- schema version
- catalog version
- source
- checksum
- collector/action/rule count
- default collectors

상세 조회:

- `GET /api/v1/catalog`
- `GET /api/catalog`

응답에는 `summary`, `collectors`, `collector_selection`, `actions`, `rules`가 포함된다.
Web Console의 Settings 화면에서도 같은 정보를 읽기 전용으로 확인할 수 있다.

Override preview:

- `POST /api/v1/catalog/preview`
- `POST /api/catalog/preview`
- 권한: `ADMIN`, `OPERATOR`

요청:

```json
{
  "override_json": "{\"schema_version\":\"rca-catalog/v1\",\"rules\":{\"disk-pressure\":{\"enabled\":false}}}",
  "reason": "disable disk detector in a canary validation window"
}
```

응답은 `valid`, `message`, `summary`, `diff`, `diff_count`, `diff_truncated`를 포함한다.
`diff`는 JSON Pointer 경로, 변경 유형, 현재 값, 제안 값을 반환한다.
preview 결과는 감사로그 `catalog.override.preview`로 남는다.

## Draft Workflow

검증을 통과한 override는 review draft로 저장할 수 있다.

API:

- 목록: `GET /api/v1/catalog/overrides/drafts`
- 생성: `POST /api/v1/catalog/overrides/drafts`
- 단건 조회: `GET /api/v1/catalog/overrides/drafts/{draftId}`
- 승인: `POST /api/v1/catalog/overrides/drafts/{draftId}/approve`
- 거절: `POST /api/v1/catalog/overrides/drafts/{draftId}/reject`
- 폐기: `POST /api/v1/catalog/overrides/drafts/{draftId}/discard`
- handoff: `GET /api/v1/catalog/overrides/drafts/{draftId}/handoff`
- GitOps PR 생성: `POST /api/v1/catalog/overrides/drafts/{draftId}/gitops-changes`

권한:

- draft 조회: `ADMIN`, `OPERATOR`, `APPROVER`, `AUDITOR`
- draft 생성/폐기: `ADMIN`, `OPERATOR`
- draft 승인/거절: `ADMIN`, `APPROVER`
- handoff 조회: `ADMIN`, `OPERATOR`, `APPROVER`
- GitOps PR 생성: `ADMIN`, `OPERATOR` (`approved` draft만 허용)

상태:

- `draft`: 검증 통과 후 저장됨
- `approved`: 사람이 검토하고 승인함
- `rejected`: 승인자가 거절함
- `discarded`: 운영자가 폐기함

`approved` 상태가 되어도 플랫폼은 catalog를 자동 적용하지 않는다.
handoff API는 GitOps PR 본문, runbook 단계, override 파일 내용을 반환한다.
운영자는 이 내용을 이용해 별도 변경관리/PR/배포 절차에서 반영한다.

GitOps 연동이 활성화되면 Web Console은 승인된 draft로 GitHub draft PR을 생성하고 상태를 추적할 수 있다. catalog를 실행 중인 Platform에 직접 적용하지는 않는다.
운영 변경은 외부 JSON 파일을 배포하고 `RCA_CATALOG_EXTERNAL_PATH` 또는 `rca.catalog.external-path`로 연결한다.
