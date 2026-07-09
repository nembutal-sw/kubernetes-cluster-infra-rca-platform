# Supply Chain

프로젝트의 공급망 보안 기준을 정리합니다.
실제 token, 서버 접속 정보, 운영 로그 원본은 저장소에 넣지 않습니다.

## CI Gate

`.github/workflows/security.yml`은 push, pull request, 주간 schedule, 수동 실행에서 동작합니다.

주요 검사:

- Dependabot dependency review
- Gitleaks secret scan
- Trivy filesystem scan
- Syft repository SBOM 생성
- Grype repository SBOM scan
- Platform/Agent container image build
- Platform/Agent image SBOM 생성
- Platform/Agent image Trivy scan
- CodeQL Java/Python 분석

Trivy SARIF는 모든 severity를 보고서로 남깁니다.
main branch의 blocking gate는 `CRITICAL` 기준입니다.
`HIGH` 이하 취약점은 GitHub Code Scanning과 workflow artifact에서 확인하고, triage 후 의존성 업데이트나 base image 교체로 처리합니다.

이렇게 분리한 이유:

- SARIF 생성 step이 실패하면 보고서가 누락될 수 있음
- image scan은 base image와 transitive dependency 영향이 커서 main 개발 흐름을 과도하게 막을 수 있음
- release 단계에서는 더 엄격한 gate를 적용할 수 있음

## Release Assets

`v*` tag를 push하면 `.github/workflows/release.yml`이 실행됩니다.

Release 작업:

- Platform/Agent multi-architecture image build
- GHCR push
- keyless Cosign image signing
- image SBOM 생성
- released image Trivy SARIF 생성
- released image vulnerability gate
- SBOM과 scan report를 GitHub Release asset으로 업로드

Release image gate는 `CRITICAL,HIGH` 기준입니다.
High 이상 취약점이 남아 있으면 release job은 실패해야 합니다.

## Local Checks

로컬에서 빠르게 확인할 항목:

```bash
python scripts/verify-supply-chain-workflows.py
python scripts/release-readiness-check.py
python scripts/verify-container-pinning.py
```

Docker가 가능한 환경에서는 platform/agent image build와 Trivy image scan을 함께 확인합니다.

## 운영 기준

- Dockerfile base image는 digest로 pinning
- Helm chart 기본 image repository는 예시 값으로 유지
- 운영 values 파일에서는 고정 tag 또는 digest 사용
- 취약점 예외는 코드 변경으로 숨기지 않고, 문서화된 triage 기준으로 처리
- secret scan에 걸릴 수 있는 값은 저장소에 커밋하지 않음
- 테스트용 값도 실제 token 형식으로 작성하지 않음
