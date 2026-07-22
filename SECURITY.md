# Security Policy

이 플랫폼은 Kubernetes 노드와 Linux 시스템의 민감한 운영 증거를 다룹니다.
기능 편의보다 인증 경계, 최소 권한, 데이터 제한, 감사 가능성을 우선합니다.

## 기본 원칙

- 초기 관리자 계정은 환경 변수 또는 외부 Secret으로 명시적으로 주입합니다.
- 빈 Webhook token은 인증 성공으로 처리하지 않습니다.
- bootstrap token은 manifest URL에 사용하지 않습니다.
- bootstrap token은 최초 Agent 등록에만 사용하며 기본 30분 후 만료됩니다.
- 등록 이후 Agent API는 node-scoped Bearer token만 사용합니다.
- manifest 다운로드는 5분 이내 만료되는 1회용 token을 사용합니다.
- 운영 프로파일의 public/backend URL은 HTTPS만 허용합니다.
- Agent 기본 설치 모드는 `safe`입니다.
- LLM 제안은 항상 `automation_allowed=false`입니다.
- Platform과 Agent는 호스트 변경 명령을 자동 실행하지 않습니다.

상세 설계는 [docs/security.md](docs/security.md)와
[docs/threat-model.md](docs/threat-model.md)를 참고합니다.

## 취약점 제보

공개 Issue에 token, 로그, 내부 주소 또는 클러스터 정보를 첨부하지 마십시오.
재현 정보는 민감값을 제거한 뒤 저장소 관리자가 지정한 비공개 채널로 전달합니다.
