# Node Agent

Node Agent는 Python으로 실행되며 Linux/Kubernetes node evidence를 read-only로 수집합니다.

## Collector Package

```text
node_agent/collectors/
  __init__.py
  registry.py
  common.py
  node.py
  kubernetes.py
  systemd.py
  kernel.py
  disk.py
  inode.py
  memory.py
  process.py
  network.py
  conntrack.py
  runtime.py
  kubelet.py
  cni.py
  dns.py
```

`registry.py`는 collector 함수와 운영 metadata를 함께 등록합니다.

- risk level
- host network/host PID 요구 여부
- privileged 요구 여부
- 기본 timeout
- 최대 출력 크기
- 기본 활성화 여부

Agent 등록 metadata에도 collector metadata가 포함되어 향후 Agent Health Dashboard와
collector degraded 상태 판단에 사용할 수 있습니다.

## Safety

- host path는 state directory 외에는 read-only mount
- systemd/journal은 기본 file mode
- backend 전송 실패 response는 local spool에 저장
- 임의 shell 실행 금지
- 승인 조치는 allowlist command key만 해석
- eBPF와 승인 조치 실행은 Helm에서 기본 비활성

현재 `_legacy.py`는 기존 collector helper와 parser의 호환 구현을 보관합니다. public API와
registry 경계는 분리되었으며, helper를 subsystem별 `common` 모듈로 더 이동하는 작업은
후속 구조 개선 항목입니다.
