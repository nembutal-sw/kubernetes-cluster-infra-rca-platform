# Supply Chain

이 프로젝트의 supply-chain 검증은 GitHub Actions에서 기본 CI와 별도 보안 workflow로 나누어 수행한다.

## CI Gate

`.github/workflows/ci.yml`은 기능 회귀를 막는 기본 gate다.

- Python Node Agent 테스트
- Web Console TypeScript/Vite build
- Spring Boot Maven `verify`
- Helm lint/template
- Docker image build
- release readiness static check
- kind smoke, agent distribution, DB backup/restore 검증

## Security Gate

`.github/workflows/security.yml`은 push, pull request, 주간 schedule에서 실행된다.

- Dependabot dependency review
- Gitleaks secret scan
- Trivy filesystem scan
- Syft repository SBOM 생성
- Grype SBOM vulnerability scan
- Platform/Agent container image build
- Platform/Agent image SBOM 생성
- Platform/Agent image Trivy scan
- CodeQL Java/Python 분석

Trivy와 Grype 결과는 SARIF로 업로드되어 GitHub code scanning에서 확인할 수 있다. SBOM과 scan report는 workflow artifact로 보존한다.

## Release Assets

`v*` tag를 push하면 `.github/workflows/release.yml`이 실행된다.

릴리스 workflow는 다음 작업을 수행한다.

- Platform/Agent multi-architecture image build
- GHCR push
- keyless Cosign image signing
- image SBOM 생성
- released image Trivy scan
- SBOM과 scan report를 GitHub Release asset으로 업로드

릴리스 이미지에 high/critical fixed vulnerability가 발견되면 release job은 실패한다.

## 운영 기준

- Dockerfile의 base image는 digest로 pinning한다.
- Helm chart 기본 image repository는 예시 값으로 유지한다.
- 운영 values 파일에서는 고정 tag 또는 digest를 사용한다.
- 취약점 예외는 코드 변경이 아니라 문서화된 release 승인 기준으로 처리한다.
- secret scan에서 걸리는 값은 repo에 남기지 않는다. 테스트용 값도 실제 token 형식으로 작성하지 않는다.

## Local Checks

로컬에서 빠르게 확인할 수 있는 항목:

```bash
python scripts/release-readiness-check.py
python scripts/verify-container-pinning.py
python scripts/verify-api-contract.py
```

Docker가 가능한 환경에서는 platform/agent image build와 Trivy image scan도 같이 확인한다.
