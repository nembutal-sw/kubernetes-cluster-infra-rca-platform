# RCA 범위

이 문서는 플랫폼이 어떤 장애를 메인으로 분석하고, 어떤 장애를 보조 신호로만 다루는지 정의합니다.

## 메인 분석 대상

| 영역 | 대표 증상 | 수집할 증거 | 가능한 실제 원인 |
| --- | --- | --- | --- |
| Node condition | `NodeNotReady` | node condition, kubelet status, journal, kernel log, node network | kubelet hang, container runtime 장애, NIC 문제, API Server 연결 불안정 |
| Disk | `DiskPressure` | disk usage, inode, I/O latency, mount 상태, kernel I/O error | inode 고갈, 디스크 용량 부족, I/O 병목, 파일시스템 오류 |
| Memory | `MemoryPressure` | memory usage, swap, OOM log, cgroup memory, top process | 시스템 메모리 고갈, 커널 OOM, 특정 프로세스 폭주 |
| PID | `PIDPressure` | pid 사용량, process count, systemd 상태 | 프로세스 폭증, zombie process, systemd unit 재시작 반복 |
| Network | `NetworkUnavailable` | NIC 상태, route, DNS, MTU, conntrack, CNI log | NIC link flap, CNI MTU 문제, conntrack 고갈, DNS 설정 오류 |
| kubelet | kubelet unhealthy | kubelet journal, config, cert 상태, API Server 연결 | kubelet deadlock, 인증서 문제, API Server latency |
| container runtime | containerd unhealthy | containerd journal, socket 상태, task 상태 | containerd hang, shim 문제, runtime socket 응답 지연 |
| CNI | pod network failure | CNI config, plugin log, route, iptables, MTU | CNI plugin crash, MTU mismatch, iptables rule 충돌 |
| CoreDNS | DNS 불안정 | CoreDNS pod 상태, node DNS config, upstream DNS, network path | 노드 DNS 설정 문제, upstream 지연, CNI 통신 장애 |
| etcd | etcd latency 증가 | etcd metric, disk I/O, network latency, leader change | disk fsync 지연, network jitter, member 불안정 |
| API Server | 응답 지연 | apiserver metric, audit hint, etcd latency, node-apiserver RTT | etcd 병목, control plane 부하, 네트워크 불안정 |
| systemd | unit failure | failed units, restart count, journal | kubelet/containerd 반복 재시작, 의존 unit 실패 |
| Kernel | kernel error | dmesg, journal kernel lines, I/O error, NIC driver log | 커널 I/O error, driver 문제, filesystem 문제 |

## 보조 신호

| 보조 신호 | 이 플랫폼에서의 의미 |
| --- | --- |
| `CrashLoopBackOff` | 앱 문제가 아니라 노드 리소스, DNS, runtime 문제의 결과인지 확인하는 신호 |
| `ImagePullBackOff` | registry 장애 외에 DNS, 노드 네트워크, container runtime 문제를 확인하는 신호 |
| Pod `OOMKilled` | 앱 메모리 사용량과 노드 전체 MemoryPressure를 함께 확인하는 신호 |
| Deployment rollout 실패 | 노드 스케줄링 불가, CNI 장애, image pull 장애의 결과일 수 있음 |
| HTTP 5xx 증가 | 서비스 자체 오류보다 DNS, endpoint, 노드 네트워크 불안정을 확인하는 신호 |
| Service endpoint 없음 | pod scheduling, readiness, node condition 문제로 연결될 수 있음 |
| Ingress 설정 오류 | 메인 대상은 아니며 네트워크/endpoint 이상 여부를 보조 확인 |

## 범위 밖

- 애플리케이션 비즈니스 로직 디버깅
- 코드 레벨 버그 분석
- SQL 쿼리 튜닝
- 애플리케이션 APM 중심 tracing
- LLM 기반 자동 수정 실행

