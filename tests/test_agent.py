from __future__ import annotations

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import pytest

from node_agent.client import AgentClient, AgentClientError
from node_agent.ebpf import parse_event
import node_agent.capabilities as capabilities
import node_agent.collectors as collectors
from node_agent.collectors import AgentPaths, collect_evidence
from node_agent.collectors import collector_metadata
from node_agent.collectors.builtin_collectors import _KubernetesApiClient, _topology_collector_node
import node_agent.main as agent_main
from node_agent.state import AgentStateStore
from node_agent.payload import bounded_collectors_payload
from node_agent.redaction import redact_value


@pytest.fixture(autouse=True)
def diagnostics_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_MODE", "node-diagnostics")


def test_ebpf_event_parsers_normalize_kernel_events(monkeypatch: pytest.MonkeyPatch) -> None:
    oom = parse_event("oom", "bash 100 1000 1 worker 200 2000")
    tcp = parse_event("tcp", "12:00:00 R 10.0.0.1:1234 10.0.0.2:443 retrans")
    monkeypatch.setenv("EBPF_DNS_LATENCY_THRESHOLD_MS", "1000")
    dns = parse_event("dns", "worker 123 getaddrinfo api.internal 1500 ms")

    assert oom and oom["event_type"] == "oom_kill" and oom["severity"] == "critical"
    assert tcp and tcp["event_type"] == "tcp_retransmit"
    assert dns and dns["event_type"] == "dns_timeout"
    assert dns["payload"]["latency_ms"] == 1500


def test_collector_registry_exposes_operational_metadata(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)
    metadata = collector_metadata(paths, FakeRunner())  # type: ignore[arg-type]

    by_name = {item["name"]: item for item in metadata}
    assert set(by_name) >= {"node", "kubernetes", "systemd", "network", "conntrack"}
    assert by_name["network"]["requires_host_network"] is True
    assert by_name["systemd"]["requires_host_pid"] is True
    assert by_name["disk"]["risk_level"] == "read_only"
    assert by_name["disk"]["max_output_bytes"] == 1_048_576
    assert by_name["disk"]["schema_version"] == "collector-evidence/v1"


def test_capability_report_marks_node_diagnostics_ready(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    root = tmp_path / "root"
    for child in ["etc", "var", "run"]:
        (root / child).mkdir(parents=True, exist_ok=True)
    paths = AgentPaths(
        root=root,
        proc=paths.proc,
        sys=paths.sys,
        etc=paths.etc,
        var_log=paths.var_log,
        run=paths.run,
    )
    runtime_socket = paths.run / "containerd/containerd.sock"
    runtime_socket.parent.mkdir(parents=True, exist_ok=True)
    runtime_socket.write_text("", encoding="utf-8")
    token = tmp_path / "serviceaccount-token"
    ca = tmp_path / "serviceaccount-ca.crt"
    token.write_text("token", encoding="utf-8")
    ca.write_text("ca", encoding="utf-8")

    monkeypatch.setenv("AGENT_MODE", "node-diagnostics")
    monkeypatch.setenv("AGENT_STATE_DIR", str(tmp_path / "state"))
    monkeypatch.setenv("KUBERNETES_SERVICE_HOST", "10.96.0.1")
    monkeypatch.setenv("KUBERNETES_SERVICE_PORT", "443")
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_TOKEN", str(token))
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_CA", str(ca))
    monkeypatch.setattr(capabilities.stat, "S_ISSOCK", lambda mode: True)

    report = capabilities.collect_capabilities(
        paths=paths,
        runner=FakeRunner(),  # type: ignore[arg-type]
        mode="node-diagnostics",
    )

    assert report["overall_status"] == "ready"
    assert report["collectors"]["runtime"]["status"] == "available"
    assert report["collectors"]["kernel"]["status"] == "available"
    assert report["collectors"]["ebpf"]["status"] == "disabled"


def test_capability_report_respects_safe_mode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    token = tmp_path / "serviceaccount-token"
    ca = tmp_path / "serviceaccount-ca.crt"
    token.write_text("token", encoding="utf-8")
    ca.write_text("ca", encoding="utf-8")

    monkeypatch.setenv("AGENT_MODE", "safe")
    monkeypatch.setenv("AGENT_STATE_DIR", str(tmp_path / "state"))
    monkeypatch.setenv("KUBERNETES_SERVICE_HOST", "10.96.0.1")
    monkeypatch.setenv("KUBERNETES_SERVICE_PORT", "443")
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_TOKEN", str(token))
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_CA", str(ca))

    report = capabilities.collect_capabilities(
        paths=paths,
        runner=FakeRunner(),  # type: ignore[arg-type]
        mode="safe",
    )

    assert report["overall_status"] == "ready"
    assert report["collectors"]["node"]["status"] == "available"
    assert report["collectors"]["kubernetes"]["status"] == "available"
    assert report["collectors"]["kernel"]["status"] == "disabled"
    assert report["collectors"]["runtime"]["status"] == "disabled"


def test_cni_capability_treats_permission_denied_as_limited(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    denied = paths.etc_root() / "cni/net.d"
    original_exists = Path.exists

    def permission_aware_exists(path: Path) -> bool:
        if path == denied:
            raise PermissionError(13, "Permission denied", str(path))
        return original_exists(path)

    monkeypatch.setattr(Path, "exists", permission_aware_exists)

    check = capabilities._cni_config_check(paths)

    assert check["status"] == "limited"
    assert check["details"]["readable_dirs"] == []
    assert str(denied) in check["details"]["candidate_dirs"]


def test_safe_agent_mode_disables_host_level_collectors(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AGENT_MODE", "safe")
    paths = _build_fake_host_paths(tmp_path)

    evidence = collect_evidence(
        ["node", "kubernetes", "kernel", "runtime"],
        paths=paths,
        runner=FakeRunner(),
    )

    assert evidence["node"]["status"] == "ok"
    assert evidence["kubernetes"]["status"] == "ok"
    assert evidence["node"]["_schema_version"] == "collector-evidence/v1"
    assert evidence["kernel"]["status"] == "disabled"
    assert evidence["runtime"]["status"] == "disabled"


def test_topology_collector_prefers_control_plane_then_lexical_name() -> None:
    nodes = [
        {"metadata": {"name": "worker-b", "labels": {}}},
        {
            "metadata": {
                "name": "control-b",
                "labels": {"node-role.kubernetes.io/control-plane": ""},
            }
        },
        {
            "metadata": {
                "name": "control-a",
                "labels": {"node-role.kubernetes.io/master": ""},
            }
        },
        {"metadata": {"name": "worker-a", "labels": {}}},
    ]

    assert _topology_collector_node(nodes) == "control-a"
    assert _topology_collector_node(nodes[:1] + nodes[-1:]) == "worker-a"
    assert _topology_collector_node([]) is None


@pytest.mark.parametrize(
    ("endpoint_ok", "expected_complete"),
    [(True, True), (False, False)],
)
def test_kubernetes_topology_inventory_requires_services_and_endpointslices(
    monkeypatch: pytest.MonkeyPatch,
    endpoint_ok: bool,
    expected_complete: bool,
) -> None:
    class FakeKubernetesClient:
        configured = True
        config_error = None

        def __init__(self, timeout_seconds: float) -> None:
            self.timeout_seconds = timeout_seconds

        def get_json(self, path: str) -> dict[str, Any]:
            if path == "/api/v1/nodes/control-a":
                return {
                    "ok": True,
                    "data": {
                        "metadata": {
                            "name": "control-a",
                            "labels": {"node-role.kubernetes.io/control-plane": ""},
                        },
                        "status": {"conditions": [{"type": "Ready", "status": "True"}]},
                    },
                }
            if path.startswith("/api/v1/pods?"):
                return {
                    "ok": True,
                    "data": {
                        "items": [
                            {
                                "metadata": {
                                    "namespace": "kube-system",
                                    "name": "kindnet-a",
                                    "labels": {"app": "kindnet"},
                                },
                                "status": {"phase": "Running", "containerStatuses": [{"restartCount": 0}]},
                                "spec": {"nodeName": "control-a"},
                            },
                            {
                                "metadata": {
                                    "namespace": "kube-system",
                                    "name": "kube-apiserver-control-a",
                                    "labels": {"component": "kube-apiserver"},
                                },
                                "status": {"phase": "Running", "containerStatuses": [{"restartCount": 1}]},
                                "spec": {"nodeName": "control-a"},
                            },
                            {
                                "metadata": {
                                    "namespace": "kube-system",
                                    "name": "etcd-control-a",
                                    "labels": {"component": "etcd"},
                                },
                                "status": {"phase": "Running", "containerStatuses": [{"restartCount": 2}]},
                                "spec": {"nodeName": "control-a"},
                            }
                        ]
                    },
                }
            if path.startswith("/apis/apps/v1/namespaces/kube-system/daemonsets?"):
                return {
                    "ok": True,
                    "data": {
                        "items": [
                            {
                                "metadata": {
                                    "namespace": "kube-system",
                                    "name": "kindnet",
                                    "labels": {"app": "kindnet"},
                                },
                                "status": {
                                    "desiredNumberScheduled": 1,
                                    "currentNumberScheduled": 1,
                                    "numberReady": 1,
                                    "numberAvailable": 1,
                                    "updatedNumberScheduled": 1,
                                    "numberMisscheduled": 0,
                                },
                            }
                        ]
                    },
                }
            if path.startswith("/api/v1/namespaces/kube-system/pods?"):
                return {
                    "ok": True,
                    "data": {
                        "items": [
                            {
                                "metadata": {"namespace": "kube-system", "name": "coredns-a"},
                                "status": {"phase": "Running", "containerStatuses": [{"restartCount": 1}]},
                                "spec": {"nodeName": "control-a"},
                            }
                        ]
                    },
                }
            if path.startswith("/apis/discovery.k8s.io/v1/namespaces/kube-system/endpointslices?"):
                return {
                    "ok": True,
                    "data": {
                        "items": [
                            {
                                "metadata": {
                                    "name": "kube-dns-abcd",
                                    "labels": {"kubernetes.io/service-name": "kube-dns"},
                                },
                                "endpoints": [{"conditions": {"ready": endpoint_ok}}],
                            }
                        ]
                    },
                }
            if path.startswith("/api/v1/events?"):
                return {"ok": True, "data": {"items": []}}
            if path.startswith("/apis/metrics.k8s.io/"):
                return {"ok": False, "error": "metrics unavailable"}
            if path == "/api/v1/nodes":
                return {
                    "ok": True,
                    "data": {
                        "items": [
                            {
                                "metadata": {
                                    "name": "control-a",
                                    "labels": {"node-role.kubernetes.io/control-plane": ""},
                                }
                            }
                        ]
                    },
                }
            if path.startswith("/api/v1/services?"):
                return {"ok": True, "data": {"metadata": {}, "items": []}}
            if path.startswith("/apis/discovery.k8s.io/v1/endpointslices?"):
                return (
                    {"ok": True, "data": {"metadata": {}, "items": []}}
                    if endpoint_ok
                    else {"ok": False, "error": "forbidden"}
                )
            raise AssertionError(f"unexpected Kubernetes API path: {path}")

        def get_text(self, path: str) -> dict[str, Any]:
            assert path in {"/readyz?verbose", "/livez?verbose"}
            return {
                "ok": True,
                "status_code": 200,
                "latency_ms": 12.5 if path.startswith("/readyz") else 10.0,
                "body": "[+]ping ok\n[+]etcd ok\nreadyz check passed\n",
            }

    monkeypatch.setenv("NODE_NAME", "control-a")
    monkeypatch.setattr(collectors._builtin, "_KubernetesApiClient", FakeKubernetesClient)
    monkeypatch.setattr(collectors._builtin, "_probe_control_plane_peers", lambda **_: [])

    evidence = collectors.collect_kubernetes()

    assert evidence["topology_inventory_collected"] is True
    assert evidence["topology_inventory_complete"] is expected_complete
    assert evidence["cni_pod_count_on_node"] == 1
    assert evidence["cni_running_pod_count_on_node"] == 1
    assert evidence["cni_daemonset_count"] == 1
    assert evidence["cni_daemonset_unavailable_count"] == 0
    assert evidence["api_server_pod_count_on_node"] == 1
    assert evidence["api_server_restart_count_total"] == 1
    assert evidence["etcd_pod_count_on_node"] == 1
    assert evidence["etcd_restart_count_total"] == 2
    assert evidence["api_readyz_latency_ms"] == 12.5
    assert evidence["api_livez_latency_ms"] == 10.0
    assert evidence["api_readyz_failed_check_count"] == 0
    assert evidence["etcd_readyz_healthy"] is True
    assert evidence["api_request_latencies"]
    assert evidence["coredns_pod_count"] == 1
    assert evidence["coredns_endpoint_count"] == 1
    assert evidence["coredns_ready_endpoint_count"] == (1 if endpoint_ok else 0)


class FakeRunner:
    def run(self, command: list[str]) -> dict[str, Any]:
        if command[:2] == ["systemctl", "show"]:
            unit = command[2]
            state = {
                "kubelet": "active",
                "containerd": "failed",
                "rke2-server": "active",
                "rke2-agent": "inactive",
            }.get(unit, "inactive")
            return {
                "ok": True,
                "exit_code": 0,
                "stdout": f"Id={unit}.service\nActiveState={state}\nSubState=running\nNRestarts=2\nResult=success\n",
                "stderr": "",
            }
        if command[:2] == ["systemctl", "--failed"]:
            return {
                "ok": True,
                "exit_code": 0,
                "stdout": "containerd.service loaded failed failed container runtime\n",
                "stderr": "",
            }
        if command == ["ps", "-eo", "pid=,comm=,args="]:
            return {
                "ok": True,
                "exit_code": 0,
                "stdout": (
                    "100 rke2 /usr/local/bin/rke2 server\n"
                    "101 containerd containerd -c /var/lib/rancher/rke2/agent/etc/containerd/config.toml\n"
                    "102 kubelet kubelet --container-runtime-endpoint=unix:///run/k3s/containerd/containerd.sock\n"
                ),
                "stderr": "",
            }
        if command[:1] == ["crictl"]:
            return {
                "ok": True,
                "exit_code": 0,
                "stdout": '{"status":{"runtimeName":"cri-o"}}',
                "stderr": "",
            }
        return {
            "ok": False,
            "exit_code": None,
            "stdout": "",
            "stderr": "command unavailable in test",
        }


class FakeClient:
    def __init__(self, pending_requests: list[dict[str, Any]] | None = None) -> None:
        self.pending_requests = pending_requests or [
            {
                "request_id": "evidence-request-1",
                "requested_collectors": ["node", "memory", "systemd"],
            }
        ]
        self.submitted: list[dict[str, Any]] = []

    def poll_evidence_requests(self, limit: int = 10) -> list[dict[str, Any]]:
        return self.pending_requests[:limit]

    def submit_evidence_response(
        self,
        request_id: str,
        status: str,
        collectors: dict[str, Any] | None = None,
        error_message: str | None = None,
    ) -> dict[str, Any]:
        payload = {
            "request_id": request_id,
            "status": status,
            "collectors": collectors or {},
            "error_message": error_message,
        }
        self.submitted.append(payload)
        return payload


def test_collectors_read_host_like_proc_files(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)

    evidence = collect_evidence(
        [
            "node",
            "kernel",
            "disk",
            "inode",
            "memory",
            "process",
            "network",
            "conntrack",
            "systemd",
            "kubelet",
            "cni",
            "dns",
        ],
        paths=paths,
        runner=FakeRunner(),
    )

    assert evidence["node"]["status"] == "ok"
    assert evidence["node"]["host_name"] == "worker-3"
    assert evidence["node"]["boot_id"] == "11111111-2222-3333-4444-555555555555"
    assert evidence["node"]["kernel_tainted"] is True
    assert evidence["kernel"]["kernel_tainted_raw"] == 512
    assert evidence["kernel"]["blocked_task_detected"] is True
    assert evidence["kernel"]["read_only_filesystem_detected"] is True
    assert evidence["disk"]["root_path_available"] is False
    assert evidence["disk"]["root_usage_percent"] is None
    assert evidence["disk"]["root_mount_read_only"] is True
    assert evidence["disk"]["io_pressure"]["some"]["avg10"] == 0.1
    assert evidence["disk"]["kernel_io_error_detected"] is True
    assert evidence["inode"]["filesystems"][0]["role"] == "var_log"
    assert evidence["memory"]["usage_percent"] == 50.0
    assert evidence["memory"]["swap_used_kib"] == 128
    assert evidence["memory"]["swap_usage_percent"] == 50.0
    assert evidence["memory"]["dirty_kib"] == 7
    assert evidence["memory"]["pressure"]["full"]["avg60"] == 0.2
    assert evidence["memory"]["oom_kill_detected"] is True
    assert evidence["process"]["process_count"] == 2
    assert evidence["process"]["zombie_process_count"] == 1
    assert evidence["network"]["interfaces"][0]["name"] == "eth0"
    assert evidence["network"]["interfaces"][0]["mtu"] == 1450
    assert evidence["network"]["interface_rx_error_total"] == 1
    assert evidence["network"]["interface_tx_error_total"] == 3
    assert evidence["network"]["tcp_retrans_segments"] == 9
    assert evidence["network"]["tcp_retrans_segments_per_hour_since_boot"] == 32.4
    assert evidence["network"]["tcp_ext_listen_overflows"] == 2
    assert evidence["network"]["default_route_interfaces"] == ["eth0"]
    assert evidence["network"]["nic_link_flap_detected"] is True
    assert evidence["network"]["physical_interfaces"] == ["eth0"]
    assert evidence["network"]["physical_interface_tx_drop_total"] == 4
    assert evidence["network"]["mtu_mismatch_suspected"] is None
    assert evidence["network"]["conntrack_usage_percent"] == 50.0
    assert evidence["conntrack"]["available"] == 50
    assert evidence["conntrack"]["near_limit"] is False
    assert evidence["conntrack"]["buckets"] == 256
    assert evidence["conntrack"]["hashsize"] == 256
    assert evidence["conntrack"]["insert_failed"] == 0
    assert evidence["conntrack"]["drop"] == 0
    assert evidence["conntrack"]["early_drop"] == 0
    assert evidence["conntrack"]["stats"]["insert"] == 8
    assert evidence["systemd"]["kubelet_status"] == "active"
    assert evidence["systemd"]["kubelet_sub_state"] == "running"
    assert evidence["systemd"]["containerd_status"] == "failed"
    assert evidence["systemd"]["rke2_server_status"] == "active"
    assert evidence["systemd"]["embedded_kubelet_running"] is True
    assert evidence["systemd"]["embedded_runtime_running"] is True
    assert "containerd" in evidence["systemd"]["runtime_units"][0]["name"]
    assert evidence["systemd"]["rke2_embedded_kubelet_running"] is True
    assert evidence["systemd"]["rke2_embedded_containerd_running"] is True
    assert evidence["systemd"]["failed_units"][0]["unit"] == "containerd.service"
    assert evidence["kubelet"]["status"] == "ok"
    assert evidence["kubelet"]["kubelet_status"] == "active"
    assert evidence["kubelet"]["kubelet_restart_count"] == 2
    assert evidence["cni"]["plugin_types"] == ["bridge", "portmap"]
    assert evidence["cni"]["mtu"] == 1450
    assert evidence["cni"]["config_count"] == 1
    assert evidence["cni"]["parse_errors"] == []
    assert evidence["cni"]["plugin_errors_detected"] is None
    assert evidence["dns"]["dns_configured"] is True
    assert evidence["dns"]["nameserver_count"] == 1
    assert evidence["dns"]["resolv_conf_exists"] is True
    assert evidence["dns"]["ndots"] == 5
    assert evidence["dns"]["dns_lookup_latency_ms"] is None


def test_conntrack_collector_parses_failure_counters(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)
    (paths.proc / "sys/net/netfilter/nf_conntrack_count").write_text("990\n", encoding="utf-8")
    (paths.proc / "sys/net/netfilter/nf_conntrack_max").write_text("1000\n", encoding="utf-8")
    (paths.proc / "net/stat/nf_conntrack").write_text(
        "entries searched found new invalid ignore delete delete_list insert insert_failed drop early_drop error search_restart\n"
        "000003de 00000020 0000001e 00000012 00000004 00000000 00000002 00000000 00000012 00000002 00000003 00000001 00000005 00000000\n"
        "000003de 00000010 0000000f 00000008 00000001 00000000 00000001 00000000 00000008 00000004 00000000 00000002 00000000 00000000\n",
        encoding="utf-8",
    )
    (paths.var_log / "kern.log").write_text("kernel: nf_conntrack: table full, dropping packet\n", encoding="utf-8")

    evidence = collect_evidence(["conntrack"], paths=paths, runner=FakeRunner())

    assert evidence["conntrack"]["usage_percent"] == 99.0
    assert evidence["conntrack"]["near_limit"] is True
    assert evidence["conntrack"]["insert_failed"] == 6
    assert evidence["conntrack"]["drop"] == 3
    assert evidence["conntrack"]["early_drop"] == 3
    assert evidence["conntrack"]["invalid"] == 5
    assert evidence["conntrack"]["error"] == 5
    assert evidence["conntrack"]["failure_total"] == 17
    assert evidence["conntrack"]["table_full_detected"] is True


def test_systemd_and_kubelet_collectors_support_daemonset_file_mode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    (paths.var_log / "syslog").write_text(
        "kubelet: failed to update node status\n"
        "containerd: shim disconnected after I/O timeout\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("SYSTEMD_COLLECTOR_MODE", "file")

    evidence = collect_evidence(["systemd", "kubelet"], paths=paths, runner=FakeRunner())

    assert evidence["systemd"]["status"] == "ok"
    assert evidence["systemd"]["collection_mode"] == "file"
    assert evidence["systemd"]["systemctl_skipped"] is True
    assert evidence["systemd"]["failed_units_command"]["skipped"] is True
    assert evidence["systemd"]["host_log_files"]
    assert evidence["kubelet"]["status"] == "ok"
    assert evidence["kubelet"]["collection_mode"] == "file"
    assert evidence["kubelet"]["journal"]["skipped"] is True
    assert "journalctl disabled" in evidence["kubelet"]["journal"]["stderr"]
    assert any("kubelet" in line for line in evidence["kubelet"]["host_log_excerpt"])


def test_systemd_file_mode_detects_usr_local_host_unit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    root = tmp_path / "root"
    unit_dir = root / "usr/local/lib/systemd/system"
    unit_dir.mkdir(parents=True)
    (unit_dir / "rke2-server.service").write_text(
        "[Service]\nExecStart=/usr/local/bin/rke2 server\n",
        encoding="utf-8",
    )
    paths = AgentPaths(
        root=root,
        proc=paths.proc,
        sys=paths.sys,
        etc=paths.etc,
        var_log=paths.var_log,
        run=paths.run,
    )
    monkeypatch.setenv("SYSTEMD_COLLECTOR_MODE", "file")

    evidence = collect_evidence(["systemd"], paths=paths, runner=FakeRunner())
    unit = evidence["systemd"]["units"]["rke2-server"]

    assert unit["unit_file_present"] is True
    assert unit["paths"] == [str(unit_dir / "rke2-server.service")]


def test_kubernetes_collector_reports_config_error_outside_cluster(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    monkeypatch.delenv("KUBERNETES_SERVICE_HOST", raising=False)
    monkeypatch.delenv("KUBERNETES_SERVICE_PORT", raising=False)

    evidence = collect_evidence(["kubernetes"], paths=paths, runner=FakeRunner())

    assert evidence["kubernetes"]["status"] == "ok"
    assert evidence["kubernetes"]["api_available"] is False
    assert "KUBERNETES_SERVICE_HOST" in evidence["kubernetes"]["api_error"]


def test_kubernetes_api_client_reuses_successful_response_within_ttl(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    token_path = tmp_path / "token"
    token_path.write_text("service-account-token", encoding="utf-8")
    monkeypatch.setenv("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc")
    monkeypatch.setenv("KUBERNETES_SERVICE_PORT", "443")
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_TOKEN", str(token_path))
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_CA", str(tmp_path / "missing-ca.crt"))
    monkeypatch.setenv("KUBERNETES_API_CACHE_TTL_SECONDS", "10")
    _KubernetesApiClient._cache.clear()
    calls = 0

    class FakeResponse:
        status = 200

        def __enter__(self) -> "FakeResponse":
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def read(self, _: int) -> bytes:
            return b'{"items":[]}'

    def fake_urlopen(*_: object, **__: object) -> FakeResponse:
        nonlocal calls
        calls += 1
        return FakeResponse()

    monkeypatch.setattr(collectors._builtin.urllib.request, "urlopen", fake_urlopen)
    client = _KubernetesApiClient(timeout_seconds=1)

    first = client.get_json("/api/v1/nodes")
    second = client.get_json("/api/v1/nodes")

    assert first["ok"] is True
    assert second["ok"] is True
    assert second["cache_hit"] is True
    assert calls == 1


def test_kubernetes_api_client_retries_transport_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    token_path = tmp_path / "token"
    token_path.write_text("service-account-token", encoding="utf-8")
    monkeypatch.setenv("KUBERNETES_API_URL", "https://127.0.0.1:6443")
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_TOKEN", str(token_path))
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_CA", str(tmp_path / "missing-ca.crt"))
    monkeypatch.setenv("KUBERNETES_API_MAX_ATTEMPTS", "2")
    monkeypatch.setenv("KUBERNETES_API_CACHE_TTL_SECONDS", "0")
    monkeypatch.setattr(collectors._builtin.time, "sleep", lambda _: None)
    calls = 0

    class FakeResponse:
        status = 200

        def __enter__(self) -> "FakeResponse":
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def read(self, _: int) -> bytes:
            return b'{"kind":"Node"}'

    def flaky_urlopen(*_: object, **__: object) -> FakeResponse:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise collectors._builtin.urllib.error.URLError("timed out")
        return FakeResponse()

    monkeypatch.setattr(collectors._builtin.urllib.request, "urlopen", flaky_urlopen)
    client = _KubernetesApiClient(timeout_seconds=1)

    response = client.get_json("/api/v1/nodes/worker-a")

    assert response["ok"] is True
    assert response["attempts"] == 2
    assert client.endpoint_source == "explicit"
    assert calls == 2


def test_kubernetes_api_client_parses_response_larger_than_legacy_text_limit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    token_path = tmp_path / "token"
    token_path.write_text("service-account-token", encoding="utf-8")
    monkeypatch.setenv("KUBERNETES_API_URL", "https://127.0.0.1:6443")
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_TOKEN", str(token_path))
    monkeypatch.setenv("KUBERNETES_SERVICEACCOUNT_CA", str(tmp_path / "missing-ca.crt"))
    monkeypatch.setenv("KUBERNETES_API_MAX_RESPONSE_BYTES", "65536")
    payload = json.dumps({"items": [{"metadata": {"name": "pod-a"}, "padding": "x" * 30000}]}).encode()

    class FakeResponse:
        status = 200

        def __enter__(self) -> "FakeResponse":
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def read(self, _: int) -> bytes:
            return payload

    monkeypatch.setattr(collectors._builtin.urllib.request, "urlopen", lambda *_args, **_kwargs: FakeResponse())

    response = _KubernetesApiClient(timeout_seconds=1).get_json("/api/v1/pods")

    assert response["ok"] is True
    assert response["data"]["items"][0]["metadata"]["name"] == "pod-a"


def test_kubernetes_pod_view_omits_environment_and_commands() -> None:
    response = {
        "ok": True,
        "status_code": 200,
        "data": {
            "metadata": {"resourceVersion": "1"},
            "items": [{
                "metadata": {"namespace": "payments", "name": "api", "labels": {"app": "api"}},
                "spec": {
                    "nodeName": "worker-a",
                    "containers": [{
                        "name": "api",
                        "command": ["server", "--token", "secret-value"],
                        "env": [{"name": "PASSWORD", "value": "secret-value"}],
                    }],
                },
                "status": {
                    "phase": "Running",
                    "containerStatuses": [{"name": "api", "ready": True, "restartCount": 1}],
                },
            }],
        },
    }

    view = collectors._builtin._kubernetes_response_view(response, collectors._builtin._sanitize_pod)
    encoded = json.dumps(view)

    assert "secret-value" not in encoded
    assert view["data"]["items"][0]["spec"]["nodeName"] == "worker-a"
    assert view["data"]["items"][0]["status"]["containerStatuses"][0]["restartCount"] == 1


def test_api_request_summary_excludes_failed_request_latency() -> None:
    summary: dict[str, Any] = {}

    collectors._builtin._summarize_api_requests(summary, [
        ("node", {"ok": False, "latency_ms": 5_002.0, "timeout": True, "error": "timed out"}),
        ("readyz", {"ok": True, "latency_ms": 42.0, "status_code": 200}),
    ])

    assert summary["api_server_latency_ms"] == 42.0
    assert summary["api_request_error_count"] == 1
    assert summary["api_timeout_detected"] is True


def test_file_systemd_collection_reads_host_processes_without_ps(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    process = paths.proc / "42"
    process.mkdir(exist_ok=True)
    (process / "comm").write_text("rke2\n", encoding="utf-8")
    (process / "cmdline").write_bytes(b"rke2\x00server\x00--token\x00secret-value\x00")
    monkeypatch.setenv("SYSTEMD_COLLECTOR_MODE", "file")

    evidence = collect_evidence(["systemd"], paths=paths, runner=FakeRunner())

    assert evidence["systemd"]["rke2_process_sample"]
    assert evidence["systemd"]["embedded_kubelet_running"] is False
    assert "secret-value" not in json.dumps(evidence["systemd"])
    assert "<redacted>" in json.dumps(evidence["systemd"])


def test_runtime_collector_uses_generic_cri_socket_fields(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    crio_socket = paths.run / "crio/crio.sock"
    crio_socket.parent.mkdir(parents=True, exist_ok=True)
    crio_socket.write_text("", encoding="utf-8")

    monkeypatch.setenv("CONTAINER_RUNTIME_SOCKET_PATHS", "crio=/run/crio/crio.sock")
    monkeypatch.setattr(collectors.stat, "S_ISSOCK", lambda mode: True)
    monkeypatch.setattr(
        collectors._builtin,
        "_probe_unix_socket",
        lambda path: {"ok": True, "latency_ms": 1.5},
    )

    runtime = collectors.collect_runtime(paths, FakeRunner())  # type: ignore[arg-type]

    assert runtime["runtime_kind"] == "crio"
    assert runtime["runtime_socket_path"] == str(crio_socket)
    assert runtime["runtime_socket_healthy"] is True
    assert runtime["containerd_socket_healthy"] is None
    assert runtime["crictl_info"]["ok"] is True


def test_collector_errors_are_returned_as_evidence(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)

    def broken_collector() -> dict[str, Any]:
        raise RuntimeError("collector exploded")

    evidence = collect_evidence(
        ["broken", "unknown"],
        paths=paths,
        runner=FakeRunner(),
        registry={"broken": broken_collector},
    )

    assert evidence["broken"]["status"] == "error"
    assert "collector exploded" in evidence["broken"]["error"]
    assert evidence["unknown"]["status"] == "unsupported"


def test_process_pending_requests_submits_completed_evidence(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)
    client = FakeClient()

    processed = agent_main.process_pending_requests(
        client=client,  # type: ignore[arg-type]
        paths=paths,
        runner=FakeRunner(),  # type: ignore[arg-type]
        limit=10,
    )

    assert processed == 1
    assert len(client.submitted) == 1
    submitted = client.submitted[0]
    assert submitted["request_id"] == "evidence-request-1"
    assert submitted["status"] == "completed"
    assert submitted["collectors"]["node"]["host_name"] == "worker-3"
    assert submitted["collectors"]["memory"]["usage_percent"] == 50.0


def test_process_pending_requests_submits_failed_on_collection_error(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    client = FakeClient()

    def fail_collection(*args: object, **kwargs: object) -> dict[str, Any]:
        raise RuntimeError("forced collector failure")

    monkeypatch.setattr(agent_main, "collect_evidence", fail_collection)

    processed = agent_main.process_pending_requests(
        client=client,  # type: ignore[arg-type]
        paths=paths,
        runner=FakeRunner(),  # type: ignore[arg-type]
        limit=10,
    )

    assert processed == 0
    assert len(client.submitted) == 1
    submitted = client.submitted[0]
    assert submitted["request_id"] == "evidence-request-1"
    assert submitted["status"] == "failed"
    assert "forced collector failure" in submitted["error_message"]


def test_large_collector_payload_is_reduced_to_configured_budget() -> None:
    bounded = bounded_collectors_payload(
        {
            "kernel": {"log": "k" * 100_000},
            "runtime": {"log": "r" * 100_000},
            "node": {"status": "ok"},
        },
        64 * 1024,
    )

    assert bounded["_agent_payload"]["status"] == "truncated"
    assert bounded["_agent_payload"]["truncated_collectors"]
    assert any(
        item.get("status") == "truncated"
        for name, item in bounded.items()
        if name != "_agent_payload"
    )


def test_agent_redaction_removes_secrets_from_nested_collector_output() -> None:
    redacted = redact_value(
        {
            "headers": {"Authorization": "Bearer secret-token"},
            "log": (
                "github=" + "gh" + "p_abcdefghijklmnopqrstuvwxyz123456 "
                "db=postgresql://rca:secret-password@db.internal/rca"
            ),
            "kubeconfig": {"client-certificate-data": "base64-secret"},
        }
    )

    encoded = json.dumps(redacted)
    assert "secret-token" not in encoded
    assert "abcdefghijklmnopqrstuvwxyz123456" not in encoded
    assert "secret-password" not in encoded
    assert "base64-secret" not in encoded
    assert "[redacted]" in encoded


def test_process_pending_requests_skips_malformed_request(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)
    client = FakeClient(pending_requests=[{"requested_collectors": ["node"]}])

    processed = agent_main.process_pending_requests(
        client=client,  # type: ignore[arg-type]
        paths=paths,
        runner=FakeRunner(),  # type: ignore[arg-type]
        limit=10,
    )

    assert processed == 0
    assert client.submitted == []


def test_collect_local_evidence_writes_json_without_backend_env(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _build_fake_host_paths(tmp_path)
    output_path = tmp_path / "local-evidence.json"

    monkeypatch.delenv("BACKEND_URL", raising=False)
    monkeypatch.delenv("CLUSTER_ID", raising=False)
    monkeypatch.delenv("AGENT_TOKEN", raising=False)
    monkeypatch.setenv("NODE_NAME", "worker-local")
    monkeypatch.setenv("HOST_PROC", str(paths.proc))
    monkeypatch.setenv("HOST_SYS", str(paths.sys))
    monkeypatch.setenv("HOST_ETC", str(paths.etc))
    monkeypatch.setenv("HOST_VAR_LOG", str(paths.var_log))
    monkeypatch.setenv("HOST_RUN", str(paths.run))

    exit_code = agent_main.main(
        [
            "--collect-local",
            "--collectors",
            "node,memory,network",
            "--output",
            str(output_path),
        ]
    )

    assert exit_code == 0
    evidence = json.loads(output_path.read_text(encoding="utf-8"))
    assert evidence["node_name"] == "worker-local"
    assert evidence["requested_collectors"] == ["node", "memory", "network"]
    assert set(evidence["collectors"]) == {"node", "memory", "network"}
    assert evidence["collectors"]["node"]["host_name"] == "worker-3"
    assert evidence["collectors"]["memory"]["usage_percent"] == 50.0
    assert evidence["collectors"]["network"]["default_route_interfaces"] == ["eth0"]


def test_collect_local_evidence_uses_all_collectors_when_list_is_empty(tmp_path: Path) -> None:
    paths = _build_fake_host_paths(tmp_path)

    evidence = agent_main.collect_local_evidence(
        paths=paths,
        runner=FakeRunner(),  # type: ignore[arg-type]
        requested_collectors=agent_main._parse_collector_list(""),
    )

    assert "node" in evidence["collectors"]
    assert "systemd" in evidence["collectors"]
    assert "dns" in evidence["collectors"]
    assert evidence["host_paths"]["proc"] == str(paths.proc)


def test_agent_client_posts_expected_payloads() -> None:
    server = _TestHttpServer(
        {
            "/api/agents/register": (201, {"agent_id": "agent-1", "node_token": "node-token-1"}),
            "/api/agents/heartbeat": (200, {"status": "healthy"}),
            "/api/agents/evidence-requests": (
                200,
                [{"request_id": "evidence-request-1", "requested_collectors": ["node"]}],
            ),
            "/api/agents/evidence-responses": (200, {"status": "completed"}),
        }
    )
    try:
        client = AgentClient(
            backend_url=server.url,
            cluster_id="cluster-1",
            node_name="worker-1",
            agent_token="token-1",
            timeout_seconds=2,
        )

        assert client.register("0.1.0", ["node"], {"kernel": "test"}) == {
            "agent_id": "agent-1",
            "node_token": "node-token-1",
        }
        assert client.node_token == "node-token-1"
        assert client.heartbeat("0.1.0", ["node"], {"agent": "running"}) == {"status": "healthy"}
        assert client.poll_evidence_requests(limit=5) == [
            {"request_id": "evidence-request-1", "requested_collectors": ["node"]}
        ]
        assert client.submit_evidence_response(
            request_id="evidence-request-1",
            status="completed",
            collectors={"node": {"status": "ok"}},
        ) == {"status": "completed"}

        assert [item["path"] for item in server.records] == [
            "/api/agents/register",
            "/api/agents/heartbeat",
            "/api/agents/evidence-requests",
            "/api/agents/evidence-responses",
        ]
        assert server.records[0]["payload"]["cluster_id"] == "cluster-1"
        assert "agent_token" not in server.records[0]["payload"]
        assert server.records[0]["authorization"] == "Bearer token-1"
        assert server.records[0]["payload"]["agent_protocol_version"] == "2"
        assert "node_token" not in server.records[1]["payload"]
        assert "agent_token" not in server.records[1]["payload"]
        assert server.records[1]["authorization"] == "Bearer node-token-1"
        assert server.records[1]["payload"]["agent_protocol_version"] == "2"
        assert server.records[2]["payload"]["limit"] == 5
        assert "node_token" not in server.records[2]["payload"]
        assert server.records[2]["authorization"] == "Bearer node-token-1"
        assert server.records[3]["payload"]["collectors"]["node"]["status"] == "ok"
        assert "node_token" not in server.records[3]["payload"]
        assert server.records[3]["authorization"] == "Bearer node-token-1"
    finally:
        server.close()


def test_agent_client_uses_fresh_projected_token_for_kubernetes_enrollment(tmp_path: Path) -> None:
    identity_token = tmp_path / "enrollment-token"
    identity_token.write_text("projected-token-1\n", encoding="utf-8")
    server = _TestHttpServer({
        "/api/agents/register": (201, {"agent_id": "agent-1", "node_token": "node-token-1"}),
    })
    try:
        client = AgentClient(
            backend_url=server.url,
            cluster_id="cluster-1",
            node_name="worker-1",
            agent_token=None,
            timeout_seconds=2,
            enrollment_mode="kubernetes-token-review",
            identity_token_path=str(identity_token),
        )

        client.register("0.1.0", ["node"], {})
        identity_token.write_text("projected-token-2\n", encoding="utf-8")
        client.register("0.1.0", ["node"], {})

        assert [record["authorization"] for record in server.records] == [
            "Bearer projected-token-1",
            "Bearer projected-token-2",
        ]
        assert all(
            record["enrollment"] == "kubernetes-token-review" for record in server.records
        )
        assert all("agent_token" not in record["payload"] for record in server.records)
    finally:
        server.close()


def test_build_client_accepts_kubernetes_enrollment_without_bootstrap_token(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    identity_token = tmp_path / "enrollment-token"
    identity_token.write_text("projected-token", encoding="utf-8")
    monkeypatch.setenv("BACKEND_URL", "https://backend.example")
    monkeypatch.setenv("CLUSTER_ID", "cluster-1")
    monkeypatch.setenv("NODE_NAME", "worker-1")
    monkeypatch.setenv("AGENT_ENROLLMENT_MODE", "kubernetes-token-review")
    monkeypatch.setenv("AGENT_IDENTITY_TOKEN_PATH", str(identity_token))
    monkeypatch.delenv("AGENT_TOKEN", raising=False)

    client = agent_main.build_client_from_env(timeout_seconds=5)

    assert client.agent_token is None
    assert client.enrollment_mode == "kubernetes-token-review"
    assert client.identity_token_path == str(identity_token)


def test_agent_client_raises_clear_errors() -> None:
    http_error_server = _TestHttpServer({"/api/agents/heartbeat": (500, {"detail": "failed"})})
    try:
        client = AgentClient(
            http_error_server.url,
            "cluster-1",
            "worker-1",
            "token-1",
            node_token="node-token-1",
            timeout_seconds=2,
        )
        with pytest.raises(AgentClientError, match="HTTP 500"):
            client.heartbeat("0.1.0", ["node"], {})
    finally:
        http_error_server.close()

    invalid_json_server = _TestHttpServer({"/api/agents/register": (200, "not-json")})
    try:
        client = AgentClient(invalid_json_server.url, "cluster-1", "worker-1", "token-1", timeout_seconds=2)
        with pytest.raises(AgentClientError, match="invalid JSON"):
            client.register("0.1.0", ["node"], {})
    finally:
        invalid_json_server.close()

    non_list_server = _TestHttpServer({"/api/agents/evidence-requests": (200, {"request_id": "bad-shape"})})
    try:
        client = AgentClient(
            non_list_server.url,
            "cluster-1",
            "worker-1",
            "token-1",
            node_token="node-token-1",
            timeout_seconds=2,
        )
        with pytest.raises(AgentClientError, match="non-list"):
            client.poll_evidence_requests()
    finally:
        non_list_server.close()


def test_agent_client_requires_registration_before_node_requests() -> None:
    client = AgentClient("http://127.0.0.1:1", "cluster-1", "worker-1", "token-1", timeout_seconds=0.1)

    with pytest.raises(AgentClientError, match="node_token is missing"):
        client.heartbeat("0.1.0", ["node"], {})


def test_agent_client_requests_pending_token_rotation_without_switching_early() -> None:
    server = _TestHttpServer({
        "/api/agents/token/rotate": (200, {"node_token": "node-token-2"}),
    })
    try:
        client = AgentClient(
            server.url,
            "cluster-1",
            "worker-1",
            None,
            node_token="node-token-1",
            timeout_seconds=2,
        )

        assert client.request_node_token_rotation() == "node-token-2"
        assert client.node_token == "node-token-1"
        assert server.records[0]["authorization"] == "Bearer node-token-1"
        assert server.records[0]["payload"] == {
            "cluster_id": "cluster-1",
            "node_name": "worker-1",
        }
    finally:
        server.close()


def test_agent_client_discards_bootstrap_token_after_enrollment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_TOKEN", "bootstrap-token")
    client = AgentClient("http://127.0.0.1:1", "cluster-1", "worker-1", "bootstrap-token")

    client.discard_bootstrap_token()

    assert client.agent_token is None
    assert "AGENT_TOKEN" not in os.environ
    with pytest.raises(AgentClientError, match="bootstrap registration is required"):
        client.register("0.1.0", ["node"], {})


def test_agent_state_persists_identity_and_spooled_response(tmp_path: Path) -> None:
    state = AgentStateStore(tmp_path / "state", "cluster-1", "worker-1")
    state.save_node_token("node-token-1")
    state.enqueue_response(
        {
            "request_id": "evidence-request-1",
            "status": "completed",
            "collectors": {"disk": {"root_usage_percent": 95}},
            "error_message": None,
        }
    )

    reloaded = AgentStateStore(tmp_path / "state", "cluster-1", "worker-1")
    assert reloaded.load_node_token() == "node-token-1"
    assert reloaded.has_pending_response("evidence-request-1") is True
    assert reloaded.pending_responses()[0]["collectors"]["disk"]["root_usage_percent"] == 95

    reloaded.acknowledge_response("evidence-request-1")
    assert reloaded.pending_responses() == []
    reloaded.clear_node_token()
    assert reloaded.load_node_token() is None


def test_spooled_response_is_retried_without_recollecting(tmp_path: Path) -> None:
    state = AgentStateStore(tmp_path / "state", "cluster-1", "worker-1")
    state.enqueue_response(
        {
            "request_id": "evidence-request-1",
            "status": "completed",
            "collectors": {"node": {"status": "ok"}},
            "error_message": None,
        }
    )
    client = FakeClient()

    submitted = agent_main.flush_spooled_responses(client, state)  # type: ignore[arg-type]

    assert submitted == 1
    assert client.submitted[0]["request_id"] == "evidence-request-1"
    assert state.pending_responses() == []


def test_retry_backoff_is_bounded(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(agent_main.random, "uniform", lambda low, high: 1.0)
    backoff = agent_main.RetryBackoff(initial_seconds=2, maximum_seconds=5)

    assert [backoff.next_delay() for _ in range(4)] == [2.0, 4.0, 5.0, 5.0]
    backoff.reset()
    assert backoff.next_delay() == 2.0


def test_positive_int_env_accepts_scientific_notation(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_MAX_SPOOL_BYTES", "2.68435456e+08")

    assert agent_main._positive_int_env("AGENT_MAX_SPOOL_BYTES", 1, minimum=1024) == 268435456


@pytest.mark.parametrize("raw_value", ["1.5", "nan", "invalid", "0"])
def test_positive_int_env_rejects_invalid_values(
    monkeypatch: pytest.MonkeyPatch,
    raw_value: str,
) -> None:
    monkeypatch.setenv("AGENT_MAX_SPOOL_FILES", raw_value)

    with pytest.raises(ValueError):
        agent_main._positive_int_env("AGENT_MAX_SPOOL_FILES", 1000)


def test_agent_state_enforces_spool_file_limit(tmp_path: Path) -> None:
    state = AgentStateStore(
        tmp_path / "state",
        "cluster-1",
        "worker-1",
        max_spool_files=1,
        max_spool_bytes=1024 * 1024,
    )
    state.enqueue_response({"request_id": "request-1", "status": "failed"})

    with pytest.raises(RuntimeError, match="file limit"):
        state.enqueue_response({"request_id": "request-2", "status": "failed"})


def test_agent_state_enforces_spool_byte_limit(tmp_path: Path) -> None:
    state = AgentStateStore(
        tmp_path / "state",
        "cluster-1",
        "worker-1",
        max_spool_files=10,
        max_spool_bytes=1024,
    )

    with pytest.raises(RuntimeError, match="byte limit"):
        state.enqueue_response(
            {
                "request_id": "request-large",
                "status": "failed",
                "error_message": "x" * 2048,
            }
        )


def test_agent_state_quarantines_invalid_spool_file(tmp_path: Path) -> None:
    state = AgentStateStore(tmp_path / "state", "cluster-1", "worker-1")
    state.initialize()
    invalid = state.spool_dir / "broken.json"
    invalid.write_text("{not-json", encoding="utf-8")

    assert state.pending_responses() == []
    assert not invalid.exists()
    assert (state.spool_dir / "broken.invalid").exists()


def _build_fake_host_paths(tmp_path: Path) -> AgentPaths:
    proc = tmp_path / "proc"
    etc = tmp_path / "etc"
    sys = tmp_path / "sys"
    var_log = tmp_path / "var-log"
    run = tmp_path / "run"

    for path in [
        proc / "sys/kernel",
        proc / "sys/kernel/random",
        proc / "sys/net/netfilter",
        proc / "pressure",
        proc / "net",
        proc / "net/stat",
        sys / "class/net/eth0",
        sys / "module/nf_conntrack/parameters",
        etc / "cni/net.d",
        proc / "1",
        proc / "2",
        etc,
        sys,
        var_log,
        run,
    ]:
        path.mkdir(parents=True, exist_ok=True)

    (proc / "sys/kernel/hostname").write_text("worker-3\n", encoding="utf-8")
    (proc / "sys/kernel/pid_max").write_text("100\n", encoding="utf-8")
    (proc / "sys/kernel/random/boot_id").write_text("11111111-2222-3333-4444-555555555555\n", encoding="utf-8")
    (proc / "sys/kernel/tainted").write_text("512\n", encoding="utf-8")
    (proc / "stat").write_text("cpu  100 0 100 20 80 0 0 0 0 0\n", encoding="utf-8")
    (proc / "uptime").write_text("1000.0 10.0\n", encoding="utf-8")
    (proc / "loadavg").write_text("0.10 0.20 0.30 1/100 1234\n", encoding="utf-8")
    (proc / "1/status").write_text("Name:\tinit\nState:\tS (sleeping)\n", encoding="utf-8")
    (proc / "2/status").write_text("Name:\tzombie\nState:\tZ (zombie)\n", encoding="utf-8")
    (proc / "meminfo").write_text(
        "MemTotal:       1000 kB\n"
        "MemAvailable:    500 kB\n"
        "MemFree:         400 kB\n"
        "Buffers:          10 kB\n"
        "Cached:          200 kB\n"
        "SwapTotal:       256 kB\n"
        "SwapFree:        128 kB\n"
        "Dirty:             7 kB\n"
        "Writeback:         3 kB\n"
        "Slab:             30 kB\n",
        encoding="utf-8",
    )
    (proc / "pressure/io").write_text(
        "some avg10=0.10 avg60=0.20 avg300=0.30 total=100\n"
        "full avg10=0.00 avg60=0.01 avg300=0.02 total=10\n",
        encoding="utf-8",
    )
    (proc / "pressure/memory").write_text(
        "some avg10=0.00 avg60=0.10 avg300=0.20 total=50\n"
        "full avg10=0.00 avg60=0.20 avg300=0.30 total=25\n",
        encoding="utf-8",
    )
    (proc / "net/dev").write_text(
        "Inter-|   Receive                                                |  Transmit\n"
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
        "  eth0: 1000 10 1 2 0 0 0 0 2000 20 3 4 0 0 0 0\n",
        encoding="utf-8",
    )
    (sys / "class/net/eth0/operstate").write_text("up\n", encoding="utf-8")
    (sys / "class/net/eth0/carrier").write_text("1\n", encoding="utf-8")
    (sys / "class/net/eth0/carrier_changes").write_text("3\n", encoding="utf-8")
    (sys / "class/net/eth0/mtu").write_text("1450\n", encoding="utf-8")
    (proc / "net/route").write_text(
        "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n"
        "eth0\t00000000\t01060A0A\t0003\t0\t0\t0\t00000000\t0\t0\t0\n",
        encoding="utf-8",
    )
    (proc / "net/snmp").write_text(
        "Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets "
        "CurrEstab InSegs OutSegs RetransSegs\n"
        "Tcp: 1 200 120000 -1 10 20 3 4 5 100 200 9\n",
        encoding="utf-8",
    )
    (proc / "net/netstat").write_text(
        "TcpExt: ListenOverflows ListenDrops TCPTimeouts\n"
        "TcpExt: 2 4 6\n",
        encoding="utf-8",
    )
    (proc / "sys/net/netfilter/nf_conntrack_count").write_text("50\n", encoding="utf-8")
    (proc / "sys/net/netfilter/nf_conntrack_max").write_text("100\n", encoding="utf-8")
    (proc / "sys/net/netfilter/nf_conntrack_buckets").write_text("256\n", encoding="utf-8")
    (sys / "module/nf_conntrack/parameters/hashsize").write_text("256\n", encoding="utf-8")
    (proc / "net/stat/nf_conntrack").write_text(
        "entries searched found new invalid ignore delete delete_list insert insert_failed drop early_drop error search_restart\n"
        "00000032 0000000a 00000009 00000008 00000000 00000000 00000002 00000000 00000008 00000000 00000000 00000000 00000000 00000000\n",
        encoding="utf-8",
    )
    (proc / "mounts").write_text("/dev/sda1 / ext4 ro,relatime 0 0\n", encoding="utf-8")
    (proc / "diskstats").write_text("", encoding="utf-8")
    (var_log / "kern.log").write_text(
        "kernel: blk_update_request: I/O error\n"
        "kernel: Out of memory: Killed process 1000\n"
        "kernel: task kubelet blocked for more than 120 seconds\n"
        "kernel: EXT4-fs error: Remounting filesystem read-only\n",
        encoding="utf-8",
    )
    (etc / "os-release").write_text('NAME="Test Linux"\nVERSION_ID="1"\n', encoding="utf-8")
    (etc / "resolv.conf").write_text(
        "nameserver 10.96.0.10\n"
        "search svc.cluster.local\n"
        "options ndots:5 timeout:2 attempts:3 rotate single-request-reopen\n",
        encoding="utf-8",
    )
    (etc / "cni/net.d/10-test.conflist").write_text(
        '{"plugins":[{"type":"bridge","mtu":1450},{"type":"portmap"}]}',
        encoding="utf-8",
    )

    return AgentPaths(proc=proc, sys=sys, etc=etc, var_log=var_log, run=run)


class _TestHttpServer:
    def __init__(self, routes: dict[str, tuple[int, Any]]) -> None:
        self.records: list[dict[str, Any]] = []
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler(routes, self.records))
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        host, port = self._server.server_address
        self.url = f"http://{host}:{port}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=2)

    @staticmethod
    def _handler(routes: dict[str, tuple[int, Any]], records: list[dict[str, Any]]):
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 - http.server callback name.
                length = int(self.headers.get("Content-Length", "0"))
                raw_body = self.rfile.read(length).decode("utf-8")
                payload = json.loads(raw_body) if raw_body else {}
                records.append({
                    "path": self.path,
                    "payload": payload,
                    "authorization": self.headers.get("Authorization"),
                    "enrollment": self.headers.get("X-RCA-Agent-Enrollment"),
                })

                status_code, response_body = routes.get(self.path, (404, {"detail": "not found"}))
                if isinstance(response_body, str):
                    encoded = response_body.encode("utf-8")
                else:
                    encoded = json.dumps(response_body).encode("utf-8")

                self.send_response(status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def log_message(self, format: str, *args: object) -> None:
                return

        return Handler
