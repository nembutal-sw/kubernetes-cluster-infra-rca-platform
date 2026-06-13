# Runtime Compatibility

The node agent must not assume RKE2 or containerd only. Runtime evidence is
reported through generic `runtime_*` fields first, with legacy `containerd_*`
fields kept only for backward compatibility.

Supported runtime socket layouts:

- kubeadm/containerd: `/run/containerd/containerd.sock`
- RKE2/K3s: `/run/k3s/containerd/containerd.sock`, `/run/rke2/containerd/containerd.sock`
- K0s: `/run/k0s/containerd.sock`, `/var/lib/k0s/run/containerd.sock`
- MicroK8s: `/var/snap/microk8s/common/run/containerd.sock`
- CRI-O: `/run/crio/crio.sock`, `/var/run/crio/crio.sock`
- cri-dockerd: `/run/cri-dockerd.sock`, `/var/run/cri-dockerd.sock`
- Docker socket evidence: `/run/docker.sock`, `/var/run/docker.sock`

Operators can override detection with `CONTAINER_RUNTIME_SOCKET_PATHS`.

Example:

```text
CONTAINER_RUNTIME_SOCKET_PATHS=crio=/run/crio/crio.sock,containerd=/run/containerd/containerd.sock
```

Runtime analyzer behavior:

- `containerd_*` report signals are emitted only when the detected runtime is containerd.
- Non-containerd runtimes emit `container_runtime_*` signals, with `component` set to `crio`, `cri-dockerd`, or `docker`.
- Socket permission errors are classified separately from runtime outages.
- Optional systemd units such as `crio` or `docker` are not treated as failures just because they are inactive on a node that does not use them.
