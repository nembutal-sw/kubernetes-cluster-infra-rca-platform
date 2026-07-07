# Cluster Threshold Overrides

클러스터마다 운영 기준이 다를 수 있어 기본 RCA threshold 위에 cluster override를 저장할 수 있다.

## API

- 조회: `GET /api/clusters/{clusterId}/thresholds`
- 저장: `PUT /api/clusters/{clusterId}/thresholds`
- 초기화: `DELETE /api/clusters/{clusterId}/thresholds`

저장은 `ADMIN`, `OPERATOR`만 가능하다. 조회는 `ADMIN`, `OPERATOR`, `VIEWER`, `APPROVER`가 가능하다.

## Request

```json
{
  "thresholds": {
    "disk.warning.percent": 93,
    "disk.critical.percent": 95
  },
  "reason": "staging storage pool has a higher normal baseline"
}
```

키는 `disk_warning_percent`, `disk-critical-percent`처럼 들어와도 canonical key로 정규화된다.

## Validation

- 알 수 없는 key는 저장하지 않는다.
- percent 값은 `0 < value <= 100`만 허용한다.
- latency/ms 계열 값은 0보다 커야 한다.
- `critical` 값은 같은 계열의 `warning` 값보다 작을 수 없다.

## Supported Keys

지원 key는 `GET /api/platform/info`의 `thresholds.supported_keys` 또는 cluster thresholds 응답의 `supported_keys`에서 확인한다.

현재 기본 key:

- `disk.warning.percent`
- `disk.critical.percent`
- `inode.warning.percent`
- `inode.critical.percent`
- `memory.critical.percent`
- `pid.warning.percent`
- `pid.critical.percent`
- `conntrack.warning.percent`
- `conntrack.critical.percent`
- `disk.await.warning.ms`
- `dns.latency.warning.ms`
- `api-server.latency.warning.ms`
- `etcd.latency.warning.ms`

## RCA 적용

RCA 분석 시 `EvidenceBundle.clusterId` 기준으로 effective threshold를 계산한다.

`effective = application.yml defaults + cluster_threshold_overrides`

저장된 override는 report evidence의 `derived_signals[].threshold`에 반영된다.
