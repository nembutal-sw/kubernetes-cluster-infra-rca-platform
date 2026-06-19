# Supply Chain

GitHub Actions는 다음 검증을 제공합니다.

- Maven, pytest, JavaScript, Helm, Testcontainers CI
- Ubuntu, Debian, Rocky Linux, openSUSE Agent collector 실행
- CodeQL Java/Python 분석
- Trivy filesystem scan
- Gitleaks secret scan
- Dependabot dependency update

`v*` tag를 push하면 platform과 agent multi-architecture image를 GHCR에 게시합니다. 각 image는 keyless Cosign으로 서명하며 SPDX JSON SBOM을 GitHub Release에 첨부합니다.

Helm chart의 기본 image repository는 예시 값으로 유지됩니다. 운영 values 파일에서 릴리스 image와 고정 tag 또는 digest를 지정합니다.
