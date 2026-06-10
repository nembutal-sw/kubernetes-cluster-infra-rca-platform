from __future__ import annotations

from pathlib import Path
from typing import Any

from node_agent.collectors import AgentPaths, collect_evidence
from node_agent.main import process_pending_requests


class FakeRunner:
    def run(self, command: list[str]) -> dict[str, Any]:
        if command[:2] == ["systemctl", "show"]:
            unit = command[2]
            state = "active" if unit == "kubelet" else "failed"
            return {
                "ok": True,
                "exit_code": 0,
                "stdout": f"Id={unit}.service\nActiveState={state}\nSubState=running\nNRestarts=2\nResult=success\n",
                "stderr": "",
            }
        return {
            "ok": False,
            "exit_code": None,
            "stdout": "",
            "stderr": "command unavailable in test",
        }


class FakeClient:
    def __init__(self) -> None:
        self.submitted: list[dict[str, Any]] = []

    def poll_evidence_requests(self, limit: int = 10) -> list[dict[str, Any]]:
        return [
            {
                "request_id": "evidence-request-1",
                "requested_collectors": ["node", "memory", "systemd"],
            }
        ]

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
        ["node", "memory", "process", "network", "conntrack", "systemd"],
        paths=paths,
        runner=FakeRunner(),
    )

    assert evidence["node"]["status"] == "ok"
    assert evidence["node"]["host_name"] == "worker-3"
    assert evidence["memory"]["usage_percent"] == 50.0
    assert evidence["process"]["process_count"] == 2
    assert evidence["network"]["interfaces"][0]["name"] == "eth0"
    assert evidence["network"]["conntrack_usage_percent"] == 50.0
    assert evidence["systemd"]["kubelet_status"] == "active"
    assert evidence["systemd"]["containerd_status"] == "failed"


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

    processed = process_pending_requests(
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


def _build_fake_host_paths(tmp_path: Path) -> AgentPaths:
    proc = tmp_path / "proc"
    etc = tmp_path / "etc"
    sys = tmp_path / "sys"
    var_log = tmp_path / "var-log"
    run = tmp_path / "run"

    for path in [
        proc / "sys/kernel",
        proc / "sys/net/netfilter",
        proc / "net",
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
    (proc / "uptime").write_text("1000.0 10.0\n", encoding="utf-8")
    (proc / "loadavg").write_text("0.10 0.20 0.30 1/100 1234\n", encoding="utf-8")
    (proc / "meminfo").write_text(
        "MemTotal:       1000 kB\n"
        "MemAvailable:    500 kB\n"
        "SwapTotal:         0 kB\n"
        "SwapFree:          0 kB\n",
        encoding="utf-8",
    )
    (proc / "net/dev").write_text(
        "Inter-|   Receive                                                |  Transmit\n"
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
        "  eth0: 1000 10 1 2 0 0 0 0 2000 20 3 4 0 0 0 0\n",
        encoding="utf-8",
    )
    (proc / "net/route").write_text("", encoding="utf-8")
    (proc / "net/snmp").write_text("", encoding="utf-8")
    (proc / "sys/net/netfilter/nf_conntrack_count").write_text("50\n", encoding="utf-8")
    (proc / "sys/net/netfilter/nf_conntrack_max").write_text("100\n", encoding="utf-8")
    (proc / "mounts").write_text("", encoding="utf-8")
    (proc / "diskstats").write_text("", encoding="utf-8")
    (etc / "os-release").write_text('NAME="Test Linux"\nVERSION_ID="1"\n', encoding="utf-8")
    (etc / "resolv.conf").write_text("nameserver 10.96.0.10\nsearch svc.cluster.local\n", encoding="utf-8")

    return AgentPaths(proc=proc, sys=sys, etc=etc, var_log=var_log, run=run)
