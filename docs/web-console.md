# Web Console

관리자 콘솔은 FastAPI가 정적 파일로 제공합니다. 서버가 실행 중이면 `/`에서 접근합니다.

```text
http://127.0.0.1:8000/
```

## 화면 구성

- `Overview`: 클러스터, RCA report, 승인 대기 사용자, webhook endpoint 요약
- `Access`: 회원가입 요청과 관리자 승인/거절 대기열
- `Clusters`: 클러스터 등록, 등록된 클러스터 목록, Agent 설치 명령어, manifest 링크
- `Webhooks`: Alertmanager webhook endpoint와 receiver 예시
- `RCA Reports`: RCA report 목록, 정책 분류 요약, 원인 후보/조치/신호/체크리스트 상세 drill-down
- `Settings`: 주요 환경변수 참조

디자인은 운영 관리자 페이지에 맞춰 저채도 배경, 좌측 메뉴, 고밀도 테이블형 리스트, 상태 badge 중심으로 구성했습니다. 화면 폭이 좁아지면 좌측 메뉴는 상단 가로 메뉴로 바뀌고, 2열 영역은 1열로 내려갑니다.

## 회원가입 승인 흐름

1. 사용자가 `Access` 화면에서 email, 이름, password, 요청 role, 사유를 입력합니다.
2. Backend는 사용자를 `pending_approval` 상태로 저장합니다.
3. 관리자는 `Admin token`에 `RCA_ADMIN_APPROVAL_TOKEN` 값을 입력합니다.
4. `Approval Queue`에서 승인 또는 거절합니다.
5. 승인된 사용자는 `active` 상태와 확정 role을 받습니다.
6. 승인된 사용자는 상단 로그인 폼에서 email/password로 로그인하고 Bearer 세션을 받습니다.

현재 MVP는 승인 기반 가입, 로그인 세션, 역할별 API 접근 제어까지 제공합니다.

## 관리자 토큰

개발 기본값:

```text
dev-admin-approval-token
```

운영 또는 공유 환경에서는 반드시 환경변수로 바꿉니다.

```powershell
$env:RCA_ADMIN_APPROVAL_TOKEN = "replace-with-secure-token"
```

콘솔은 로그인 세션을 우선 사용하고 `Authorization: Bearer <access_token>` header로 전송합니다.
bootstrap admin token이 입력되어 있으면 fallback이 필요한 요청에 `X-Admin-Token`도 함께 전송합니다. 세션이 만료되었거나 역할 권한이 부족해도 token 값이 맞으면 Backend가 bootstrap admin token으로 처리합니다.
브라우저 저장은 영구 `localStorage`가 아니라 탭 단위 `sessionStorage`만 사용합니다.

로그인 세션 또는 bootstrap admin token이 필요한 화면 동작:

- 승인 대기 사용자 조회
- 회원가입 승인/거절
- 클러스터 등록
- Agent 설치 명령어 조회
- 클러스터, RCA report, evidence 조회

Backend는 Web Console과 정적 자산 응답에 `Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` header를 붙입니다.

## API 연결

콘솔은 같은 origin의 Backend API를 호출합니다.

- `POST /api/auth/signup`
- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `GET /api/admin/users?status=pending_approval`
- `POST /api/admin/users/{user_id}/approval`
- `POST /api/clusters`
- `GET /api/clusters`
- `GET /api/clusters/{cluster_id}/install-command`
- `GET /api/rca/reports`
- `GET /api/rca/reports/{report_id}`

네트워크 오류나 JSON이 아닌 에러 응답은 화면 toast와 목록 영역에 사람이 읽을 수 있는 메시지로 표시합니다.

Alertmanager webhook URL은 현재 origin을 기준으로 화면에서 자동 표시합니다. 화면에는 `Authorization: Bearer ${RCA_WEBHOOK_TOKEN}` 예시도 같이 표시하지만 실제 token 값은 노출하지 않습니다.
