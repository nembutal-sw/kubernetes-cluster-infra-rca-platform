# Supply Chain

프로젝트의 공급망 보안 기준을 정리한다. 실제 토큰, 서버 접속 정보, 운영 로그 원본은 저장소에 넣지 않는다.

## CI Gate

`.github/workflows/security.yml`은 push, pull request, 주간 schedule, 수동 실행에서 동작한다.

주요 검사:

- Dependabot dependency review
- Gitleaks secret scan
- Trivy filesystem scan
- Syft repository SBOM 생성
- Grype repository SBOM 취약점 검사
- Platform/Agent container image build
- Platform/Agent image SBOM 생성
- Platform/Agent image Trivy scan
- CodeQL Java/Python 분석

Trivy와 Grype 결과는 SARIF로 업로드한다. GitHub Code Scanning에서 결과를 확인하고, SBOM과 scan report는 workflow artifact로 보관한다.

## Release Assets

`v*` tag를 push하면 `.github/workflows/release.yml`이 실행된다.

릴리즈 작업:

- Platform/Agent multi-architecture image build
- GHCR push
- keyless Cosign image signing
- image SBOM 생성
- released image Trivy scan
- SBOM과 scan report를 GitHub Release asset으로 업로드

릴리즈 이미지에서 fixed high/critical 취약점이 발견되면 release job은 실패해야 한다.

## Local Checks

로컬에서 빠르게 확인할 항목:

```bash
python scripts/verify-supply-chain-workflows.py
python scripts/release-readiness-check.py
python scripts/verify-container-pinning.py
```

Docker가 가능한 환경에서는 platform/agent image build와 Trivy image scan도 같이 확인한다.

## 운영 기준

- Dockerfile base image는 digest로 pinning한다.
- Helm chart 기본 image repository는 예시 값으로 유지한다.
- 운영 values 파일에서는 고정 tag 또는 digest를 사용한다.
- 취약점 예외는 코드 변경으로 숨기지 않고, 문서화된 release 승인 기준으로 처리한다.
- secret scan에 걸릴 수 있는 값은 저장소에 넣지 않는다.
- 테스트용 값도 실제 token 형식으로 작성하지 않는다.
