# Web Console

Web Console은 Spring Boot 플랫폼 내부의 JSP shell, React UI, Bootstrap 5로 구성됩니다. 별도 API proxy나 별도 frontend 서버가 없습니다.

## Access

```text
URL: http://localhost:8080
Default account: admin/admin
```

로그인하지 않은 사용자는 보호된 `/api/**` 경로에 접근할 수 없습니다. UI token은 브라우저 session storage에 저장되며 서버의 DB session과 함께 검증됩니다.

## Views

- Dashboard
- Cluster 등록, 상세, 삭제
- Agent 상태 및 heartbeat
- Evidence request와 raw evidence
- RCA report, 원인 후보, signal, 추가 확인 명령
- Policy 등급, 자동화 허용 여부, 위험 사유
- JSON report export
- 영어/한국어 사용자 설정

PC와 모바일은 같은 반응형 화면을 사용합니다. 모바일 전용 User-Agent 분기는 사용하지 않습니다.

## Security

- Bearer session 인증
- role 기반 API 접근
- CSP, frame 차단, MIME sniffing 차단
- destructive cluster 삭제 시 이름 재확인
- action 요청 시 2차 확인
- LLM action 자동화 차단
