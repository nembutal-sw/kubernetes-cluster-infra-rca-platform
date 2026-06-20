# Testing

## Java

```powershell
cd web-console
mvn verify
```

검증 범위:

- Spring Boot context 및 HTTP E2E
- 인증, cluster, Agent, evidence, report, action 흐름
- durable analysis task lease/retry/dead-letter
- rule-based 장애 시나리오
- detector threshold와 matched evidence
- 정상/unknown/unrelated message 오탐 방지
- eBPF event에서 report와 timeline 생성
- PostgreSQL/MariaDB Testcontainers 호환성
- Agent/Webhook/Manifest 인증 실패 및 정상 token 경로
- `prod` profile 위험 설정 fail-fast
- `VIEWER`, `APPROVER`, `AUDITOR` 권한 경계
- session 만료 후 보호 API 접근 차단
- analysis task 동시 claim 및 lease 만료 후 재할당
- evidence, LLM 입력, 오류 메시지의 credential redaction

Docker daemon이 없으면 Testcontainers DB 테스트만 skip됩니다.

## Python

```powershell
python -m pytest -q --basetemp .pytest-tmp/run
python -m compileall -q node_agent tests
```

검증 범위:

- host-like fixture 기반 collector
- file mode systemd/kubelet 수집
- generic CRI runtime
- evidence request 처리와 spool
- spool file/byte limit
- 손상된 spool 파일의 `.invalid` 격리
- eBPF parser
- approved action allowlist
- collector registry metadata

## Build

Maven build는 React/TypeScript/Vite production build를 포함합니다.

```powershell
cd web-console
mvn package
```

PostgreSQL/MariaDB, Helm lint/template와 Docker image build는 Docker/Helm이 준비된 CI 또는
Linux 검증 환경에서 수행합니다.

CI는 Node Agent, Frontend, Spring Boot, Helm을 독립 job으로 검증합니다. 모든 선행 job이
통과해야 platform/agent Docker image build가 실행됩니다. Platform Docker build 자체도
`mvn verify`를 실행하므로 테스트를 생략한 이미지가 만들어지지 않습니다.
