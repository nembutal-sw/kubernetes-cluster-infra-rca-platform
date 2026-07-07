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

## Platform Info

`GET /api/v1/platform/info`는 catalog 정보를 함께 반환한다.

- schema version
- catalog version
- source
- checksum
- collector/action/rule count
- default collectors
