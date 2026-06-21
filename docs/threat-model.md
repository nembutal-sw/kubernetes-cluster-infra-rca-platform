# Threat Model

## 보호 대상

- cluster bootstrap token과 node token
- 사용자 session과 관리자 계정
- 노드 로그, 커널 로그, systemd 상태
- 클러스터 토폴로지와 내부 Service 이름
- Evidence, RCA report, Audit event
- LLM 입력과 응답

## 주요 위협과 방어

### Webhook Abuse

공격자가 Alertmanager endpoint에 가짜 이벤트를 반복 전송할 수 있습니다.

- 빈 Webhook token 거부
- 운영 프로파일에서 강한 token 필수
- 요청 body 제한
- 인증 실패와 webhook 수신 Audit 기록

### Token Leakage

URL, proxy log, shell history를 통해 bootstrap token이 노출될 수 있습니다.

- bootstrap token을 manifest URL에서 제거
- 해시 저장된 1회용 manifest token 사용
- manifest token 만료 및 재사용 차단
- 관리자 전용 bootstrap token 회전 API

### Compromised Agent

탈취된 Agent가 다른 노드로 가장하거나 과대 Evidence를 전송할 수 있습니다.

- cluster token과 node token 동시 검증
- cluster/node identity 일치 검증
- 선택적 client certificate 강제
- Evidence response 크기 제한
- request ID 기반 멱등 처리

### Oversized Request

과대 JSON body로 JVM 메모리를 소모할 수 있습니다.

- 일반 API 기본 1 MiB
- Evidence response 기본 10 MiB
- Agent 전송 payload 기본 8 MiB
- Content-Length와 streaming read 양쪽 제한

### Sensitive Log Leakage

로그에 credential, kubeconfig 또는 cloud token이 포함될 수 있습니다.

- Agent 전송 직전 재귀 redaction
- Backend 저장 및 LLM 전달 전 redaction
- GitHub, Slack, AWS, Bearer, Cookie, DB URL 패턴 처리
- Export 권한과 `Cache-Control: no-store`

### Excessive Node Privilege

Node Agent의 hostPID, hostNetwork, root, hostPath가 공격면을 확대할 수 있습니다.

- 기본 `safe` 모드는 비-root, capability drop, hostPath 없음
- `node-diagnostics`는 명시적으로 선택
- `ebpf`는 가장 높은 권한이 필요한 별도 모드
- Agent 자동 조치 실행 금지

## 신뢰 경계

```text
Browser -> Platform session/RBAC
Alertmanager -> Webhook token
Node Agent -> cluster token + node token + optional mTLS
Platform -> PostgreSQL/MariaDB
Platform -> optional LLM provider
```
