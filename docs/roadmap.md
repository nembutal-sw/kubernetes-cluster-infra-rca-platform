# Roadmap

## Completed

- Spring Boot 3.5.15 중앙 플랫폼 통합
- PostgreSQL/MariaDB 공통 JDBC 및 Flyway
- 사용자 session, Agent token, webhook 인증
- Rule-based RCA와 Policy Engine
- Spring AI provider 선택 구조
- 클러스터 등록/삭제, evidence, report export
- 반응형 Web Console
- Agent token persistence, evidence spool, exponential backoff, mTLS 옵션
- Platform/Agent Helm chart
- Prometheus 없이 동작하는 정기 수집 scheduler
- PostgreSQL/MariaDB Testcontainers 통합 검증
- GitHub Actions CI
- incident correlation 및 중복 보고서 억제
- 조치 승인 이력과 audit log
- LLM timeout, retry, circuit breaker, provider contract test
- External Secrets, NetworkPolicy, PDB, DB backup Helm 옵션
- 다중 Linux 배포판 Agent 검증 자동화
- CodeQL, Trivy, secret scan, SBOM, image signing release workflow

## Next

- 실제 다중 배포판과 managed Kubernetes에서 collector field 검증
- GitOps PR provider 연동
- 운영 HA 구성과 disaster recovery 검증
