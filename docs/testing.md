# Testing

## Java

```powershell
cd web-console
mvn test
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
