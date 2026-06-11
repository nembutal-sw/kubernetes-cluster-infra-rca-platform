# Real Cluster Validation Notes

This document records findings from the first read-only check against an actual
RKE2 cluster and the code changes made from that evidence.

Observed signals:

- All nodes were `Ready`, but `core-a` repeatedly failed to connect to
  `core-b` on `10.0.0.2:9345`.
- A direct TCP probe from `core-a` to `core-b` failed on both `9345` and
  `6443`, while `core-a` to `core-c:9345` succeeded.
- Recent Kubernetes events repeatedly reported RKE2 node certificate expiration
  warnings.
- `edbe-b` had a very high Cilium agent restart count. Previous Cilium logs
  included API watch loss and TLS handshake timeout messages.
- `kubectl top nodes` returned metrics for some nodes but `<unknown>` for
  others.
- `core-a` local disk, inode, memory, conntrack, and API readyz checks looked
  healthy during the sample window.

Collector changes derived from those signals:

- Added a `kubernetes` collector that reads the Kubernetes API directly through
  the in-cluster ServiceAccount token.
- Added node condition, node pressure, pod restart, CNI restart, metrics API,
  readyz, node certificate warning, and control-plane peer TCP probe fields.
- Added RKE2 systemd fields for `rke2-server` and `rke2-agent`.
- Added read-only RBAC to the generated and static agent manifests.
- Updated RCA preprocessing and rules so control-plane peer connectivity,
  CNI restarts, metrics unavailability, API readyz failures, and node
  certificate warnings can become explicit report signals.

The collector intentionally degrades gracefully outside Kubernetes. If the
ServiceAccount environment or token is missing, it returns a structured
`api_error` instead of failing the whole agent.
