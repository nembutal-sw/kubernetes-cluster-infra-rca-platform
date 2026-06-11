from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from backend.app.models import (
    Confidence,
    EvidenceBundle,
    RcaJobStatus,
    RcaReport,
    RcaSummary,
    RecommendedAction,
    RootCauseCandidate,
)
from backend.app.services.llm import LlmAnalyzer
from backend.app.services.policy import PolicyEngine
from backend.app.services.preprocessor import build_preprocessed_evidence


@dataclass(frozen=True)
class DiagnosticSignal:
    signal: str
    component: str
    severity: str
    observed: Any
    interpretation: str
    next_step: str
    supporting_evidence: list[str]

    def as_report_item(self) -> dict[str, Any]:
        return {
            "signal": self.signal,
            "component": self.component,
            "severity": self.severity,
            "observed": self.observed,
            "interpretation": self.interpretation,
            "next_step": self.next_step,
            "supporting_evidence": self.supporting_evidence,
        }


class RuleBasedRcaAnalyzer:
    def __init__(self, policy_engine: PolicyEngine, llm_analyzer: LlmAnalyzer | None = None) -> None:
        self._policy_engine = policy_engine
        self._llm_analyzer = llm_analyzer

    def analyze(self, report_id: str, evidence: EvidenceBundle) -> RcaReport:
        collectors = evidence.collectors
        signals = _derive_signals(collectors)
        candidates = _build_candidates(evidence.alert_name, signals, collectors)
        if not candidates:
            candidates = _fallback_candidates(evidence.alert_name, collectors)

        recommended_actions = self._build_actions(evidence.alert_name, signals)
        signal_items = [signal.as_report_item() for signal in signals]
        preprocessed_evidence = build_preprocessed_evidence(evidence, signal_items)
        llm_analysis = self._run_llm_analysis(
            preprocessed_evidence=preprocessed_evidence,
            alert_name=evidence.alert_name,
            signals=signals,
            candidates=candidates,
            recommended_actions=recommended_actions,
        )
        llm_candidates = _candidates_from_llm(llm_analysis)
        if llm_candidates:
            candidates.extend(llm_candidates)
        llm_actions = self._actions_from_llm(llm_analysis)
        if llm_actions:
            recommended_actions = _dedupe_actions([*recommended_actions, *llm_actions])
        evidence_findings = _build_evidence_findings(
            collectors,
            signals,
            evidence.alert_name,
            preprocessed_evidence,
            llm_analysis,
        )
        summary = _build_summary(evidence, signals, candidates)

        return RcaReport(
            report_id=report_id,
            cluster_id=evidence.cluster_id,
            status=RcaJobStatus.COMPLETED,
            trigger={
                "source": "alertmanager",
                "alert_name": evidence.alert_name,
            },
            scope={
                "nodes": [evidence.node_name],
                "components": _scope_components(evidence.alert_name, signals),
            },
            summary=summary,
            evidence=evidence_findings,
            root_cause_candidates=candidates,
            recommended_actions=recommended_actions,
            policy_decisions=recommended_actions,
        )

    def _build_actions(self, alert_name: str, signals: list[DiagnosticSignal]) -> list[RecommendedAction]:
        actions = [
            self._policy_engine.classify(
                "collect_more_evidence",
                "장애 시간대의 kubelet, containerd, kernel, systemd journal을 추가 수집합니다.",
                "읽기 전용 증거 수집이며 서비스 상태를 변경하지 않습니다.",
            )
        ]

        signal_names = {signal.signal for signal in signals}
        components = {signal.component for signal in signals}

        if signal_names & {"containerd_socket_unhealthy", "containerd_unit_unhealthy"}:
            actions.append(
                self._policy_engine.classify(
                    "restart_containerd",
                    "containerd socket과 unit 상태가 계속 비정상이면 운영자 승인 후 containerd 재시작을 검토합니다.",
                    "runtime 재시작은 실행 중인 workload에 영향을 줄 수 있으므로 자동 실행하지 않습니다.",
                )
            )

        if signal_names & {"kubelet_unit_unhealthy", "kubelet_restarting"} or alert_name in {
            "KubeletDown",
            "KubeletUnhealthy",
        }:
            actions.append(
                self._policy_engine.classify(
                    "restart_kubelet",
                    "kubelet 상태가 failed/restarting이면 운영자 승인 후 kubelet 재시작을 검토합니다.",
                    "노드 상태 회복에 도움이 될 수 있지만 workload 영향이 있어 승인이 필요합니다.",
                )
            )

        if signal_names & {"disk_usage_critical", "inode_usage_critical"}:
            actions.append(
                self._policy_engine.classify(
                    "cleanup_disk",
                    "승인 후 불필요한 image, log, 임시 파일을 정리하거나 디스크 증설을 진행합니다.",
                    "디스크 정리는 데이터 손실 위험이 있어 대상 경로 확인과 승인이 필요합니다.",
                )
            )

        if signal_names & {"memory_pressure_critical", "oom_kill_detected"}:
            actions.append(
                self._policy_engine.classify(
                    "cordon_node",
                    "메모리 압박이 지속되면 운영자 승인 후 노드 cordon/drain을 검토합니다.",
                    "workload 재배치가 발생하므로 자동 실행하지 않습니다.",
                )
            )

        if signal_names & {
            "conntrack_near_limit",
            "cni_config_invalid",
            "dns_unconfigured",
            "dns_latency_high",
            "control_plane_peer_unreachable",
        }:
            actions.append(
                self._policy_engine.classify(
                    "open_gitops_pr",
                    "conntrack 한도, CNI, DNS/CoreDNS 설정 변경은 GitOps PR로만 제안합니다.",
                    "클러스터 설정 변경은 직접 실행하지 않고 리뷰 가능한 PR 흐름을 사용합니다.",
                )
            )

        if components & {"network", "kernel"} and signal_names & {
            "interface_down",
            "nic_link_flap",
            "kernel_io_error",
            "root_filesystem_read_only",
            "control_plane_peer_unreachable",
        }:
            actions.append(
                self._policy_engine.classify(
                    "manual_hardware_check",
                    "NIC link flap, kernel I/O error, read-only filesystem은 하드웨어/스토리지 경로 점검을 진행합니다.",
                    "장비, 커널, 스토리지 계층 확인이 필요하므로 수동 조사 대상으로 분류합니다.",
                )
            )

        if signal_names & {"blocked_task_detected", "root_filesystem_read_only"}:
            actions.append(
                self._policy_engine.classify(
                    "reboot_node",
                    "blocked task 또는 read-only filesystem이 지속되면 최후 수단으로 노드 재부팅을 검토합니다.",
                    "재부팅은 영향 범위가 크므로 절대 자동 실행하지 않습니다.",
                )
            )

        return _dedupe_actions(actions)

    def _run_llm_analysis(
        self,
        preprocessed_evidence: dict[str, Any],
        alert_name: str,
        signals: list[DiagnosticSignal],
        candidates: list[RootCauseCandidate],
        recommended_actions: list[RecommendedAction],
    ) -> dict[str, Any]:
        if self._llm_analyzer is None:
            return {"status": "skipped", "reason": "llm analyzer not configured"}
        return self._llm_analyzer.analyze(
            preprocessed_evidence,
            {
                "alert_name": alert_name,
                "derived_signal_names": [signal.signal for signal in signals],
                "rule_candidates": [candidate.model_dump(mode="json") for candidate in candidates],
                "policy_classified_actions": [action.model_dump(mode="json") for action in recommended_actions],
            },
        )

    def _actions_from_llm(self, llm_analysis: dict[str, Any]) -> list[RecommendedAction]:
        if llm_analysis.get("status") != "completed":
            return []
        result = llm_analysis.get("result")
        if not isinstance(result, dict):
            return []
        suggestions = result.get("action_suggestions")
        if not isinstance(suggestions, list):
            return []
        actions = []
        for suggestion in suggestions:
            if not isinstance(suggestion, dict):
                continue
            action = str(suggestion.get("action") or "").strip()
            reason = str(suggestion.get("reason") or "").strip()
            if not action or not reason:
                continue
            actions.append(
                self._policy_engine.classify(
                    str(suggestion.get("action_key") or "manual_investigation"),
                    action,
                    reason,
                    source="llm",
                )
            )
        return actions


def _derive_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    signals: list[DiagnosticSignal] = []
    signals.extend(_kubernetes_signals(collectors))
    signals.extend(_systemd_signals(collectors))
    signals.extend(_runtime_signals(collectors))
    signals.extend(_disk_signals(collectors))
    signals.extend(_kernel_signals(collectors))
    signals.extend(_memory_signals(collectors))
    signals.extend(_process_signals(collectors))
    signals.extend(_network_signals(collectors))
    signals.extend(_cni_signals(collectors))
    signals.extend(_dns_signals(collectors))
    return sorted(signals, key=lambda signal: (_severity_rank(signal.severity), signal.component, signal.signal))


def _systemd_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    systemd = _collector(collectors, "systemd")
    kubelet = _collector(collectors, "kubelet")
    signals = []

    kubelet_status = _first_present(systemd.get("kubelet_status"), kubelet.get("kubelet_status"))
    kubelet_sub_state = _first_present(systemd.get("kubelet_sub_state"), kubelet.get("kubelet_sub_state"))
    kubelet_restarts = _max_number(systemd.get("kubelet_restart_count"), kubelet.get("kubelet_restart_count"))
    if _bad_unit_state(kubelet_status, kubelet_sub_state):
        signals.append(
            DiagnosticSignal(
                signal="kubelet_unit_unhealthy",
                component="kubelet",
                severity="critical",
                observed={"status": kubelet_status, "sub_state": kubelet_sub_state},
                interpretation="kubelet systemd unit이 정상 active/running 상태가 아닙니다.",
                next_step="systemctl status kubelet과 journalctl -u kubelet로 마지막 실패 원인을 확인합니다.",
                supporting_evidence=["systemd", "kubelet"],
            )
        )
    elif kubelet_restarts is not None and kubelet_restarts >= 5:
        signals.append(
            DiagnosticSignal(
                signal="kubelet_restarting",
                component="kubelet",
                severity="warning",
                observed={"restart_count": kubelet_restarts},
                interpretation="kubelet 재시작 횟수가 높아 deadlock, 설정 오류, API Server 연결 장애 가능성이 있습니다.",
                next_step="journalctl -u kubelet에서 재시작 직전 오류와 API Server 연결 오류를 확인합니다.",
                supporting_evidence=["systemd", "kubelet"],
            )
        )

    containerd_status = systemd.get("containerd_status")
    containerd_sub_state = systemd.get("containerd_sub_state")
    if _bad_unit_state(containerd_status, containerd_sub_state):
        signals.append(
            DiagnosticSignal(
                signal="containerd_unit_unhealthy",
                component="containerd",
                severity="critical",
                observed={"status": containerd_status, "sub_state": containerd_sub_state},
                interpretation="containerd systemd unit이 정상 상태가 아니어서 kubelet runtime 연동이 실패할 수 있습니다.",
                next_step="systemctl status containerd와 journalctl -u containerd로 crash, hang, config 오류를 확인합니다.",
                supporting_evidence=["systemd"],
            )
        )

    rke2_server_status = systemd.get("rke2_server_status")
    rke2_server_sub_state = systemd.get("rke2_server_sub_state")
    if _bad_unit_state(rke2_server_status, rke2_server_sub_state):
        signals.append(
            DiagnosticSignal(
                signal="rke2_server_unit_unhealthy",
                component="rke2",
                severity="critical",
                observed={"status": rke2_server_status, "sub_state": rke2_server_sub_state},
                interpretation="rke2-server unit is not healthy, so embedded kubelet/containerd/control-plane components may be unstable.",
                next_step="Check systemctl status rke2-server and journalctl -u rke2-server around the incident window.",
                supporting_evidence=["systemd"],
            )
        )

    rke2_server_restarts = _number(systemd.get("rke2_server_restart_count"))
    if rke2_server_restarts is not None and rke2_server_restarts >= 5:
        signals.append(
            DiagnosticSignal(
                signal="rke2_server_restarting",
                component="rke2",
                severity="warning",
                observed={"restart_count": rke2_server_restarts},
                interpretation="rke2-server restart count is high; control-plane or embedded runtime instability may have occurred.",
                next_step="Correlate rke2-server restarts with node Ready changes, CNI restarts, and API timeout logs.",
                supporting_evidence=["systemd", "kubernetes"],
            )
        )

    failed_units = systemd.get("failed_units")
    if isinstance(failed_units, list) and failed_units:
        signals.append(
            DiagnosticSignal(
                signal="systemd_failed_units",
                component="systemd",
                severity="warning",
                observed=failed_units[:10],
                interpretation="노드에 failed systemd unit이 남아 있어 의존 서비스 장애 가능성이 있습니다.",
                next_step="systemctl --failed와 각 unit journal을 확인해 장애 전파 여부를 판단합니다.",
                supporting_evidence=["systemd"],
            )
        )

    return signals


def _kubernetes_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    kubernetes = _collector(collectors, "kubernetes")
    signals: list[DiagnosticSignal] = []
    if not kubernetes:
        return signals

    if kubernetes.get("api_available") is False:
        signals.append(
            DiagnosticSignal(
                signal="kubernetes_api_unavailable",
                component="kubernetes",
                severity="critical",
                observed={"api_error": kubernetes.get("api_error")},
                interpretation="The node agent could not read the Kubernetes API, which may indicate local API path, service account, or control-plane connectivity trouble.",
                next_step="Check in-cluster API service reachability, ServiceAccount RBAC, and kube-apiserver health from the node.",
                supporting_evidence=["kubernetes", "network"],
            )
        )

    if kubernetes.get("node_ready") is False:
        signals.append(
            DiagnosticSignal(
                signal="node_not_ready_condition",
                component="kubernetes",
                severity="critical",
                observed={"node_conditions": kubernetes.get("node_conditions")},
                interpretation="Kubernetes reports the node Ready condition as false.",
                next_step="Compare node condition transition time with kubelet, runtime, kernel, and network evidence.",
                supporting_evidence=["kubernetes", "kubelet"],
            )
        )

    pressure = kubernetes.get("node_pressure")
    if isinstance(pressure, dict):
        active_pressure = {key: value for key, value in pressure.items() if str(value).lower() == "true"}
        if active_pressure:
            signals.append(
                DiagnosticSignal(
                    signal="node_pressure_condition_active",
                    component="kubernetes",
                    severity="critical",
                    observed=active_pressure,
                    interpretation="Kubernetes node pressure conditions are active.",
                    next_step="Use disk, memory, process, and kernel collectors to identify the pressure source.",
                    supporting_evidence=["kubernetes", "disk", "memory", "process"],
                )
            )

    failed_peer_probe_count = _number(kubernetes.get("failed_peer_probe_count"))
    if failed_peer_probe_count is not None and failed_peer_probe_count > 0:
        signals.append(
            DiagnosticSignal(
                signal="control_plane_peer_unreachable",
                component="network",
                severity="critical",
                observed={
                    "failed_peer_probe_count": failed_peer_probe_count,
                    "peer_connectivity": kubernetes.get("control_plane_peer_connectivity"),
                },
                interpretation="Control-plane peer TCP probes failed from this node. This can break RKE2 remotedialer, API server, CNI watch, or etcd/client paths.",
                next_step="Check firewall, routing, security groups, node-to-node ACLs, and listener state for the failed peer ports.",
                supporting_evidence=["kubernetes", "network", "systemd"],
            )
        )

    cni_high_restart_pods = kubernetes.get("cni_high_restart_pods")
    if isinstance(cni_high_restart_pods, list) and cni_high_restart_pods:
        signals.append(
            DiagnosticSignal(
                signal="cni_pod_restarting",
                component="cni",
                severity="warning",
                observed={"pods": cni_high_restart_pods[:10]},
                interpretation="CNI pods on the node have high restart counts, which can indicate API watch timeouts, CNI agent crashes, or node network instability.",
                next_step="Inspect the CNI pod previous logs and correlate restart times with API server or node network errors.",
                supporting_evidence=["kubernetes", "cni"],
            )
        )

    high_restart_pods = kubernetes.get("high_restart_pods")
    if isinstance(high_restart_pods, list) and high_restart_pods and not cni_high_restart_pods:
        signals.append(
            DiagnosticSignal(
                signal="system_pod_restarts_high",
                component="kubernetes",
                severity="warning",
                observed={"pods": high_restart_pods[:10]},
                interpretation="Pods on the node have high restart counts, which may be a secondary symptom of node/runtime/network instability.",
                next_step="Separate application restarts from kube-system/runtime restarts before assigning root cause.",
                supporting_evidence=["kubernetes"],
            )
        )

    if kubernetes.get("metrics_available") is False and kubernetes.get("metrics_error"):
        signals.append(
            DiagnosticSignal(
                signal="node_metrics_unavailable",
                component="kubernetes",
                severity="warning",
                observed={"metrics_error": kubernetes.get("metrics_error")},
                interpretation="Node metrics are unavailable through metrics.k8s.io, so scheduler/autoscaler/operator visibility may be incomplete.",
                next_step="Check metrics-server logs and kubelet summary API reachability for the affected node.",
                supporting_evidence=["kubernetes"],
            )
        )

    readyz_failures = kubernetes.get("api_readyz_failed_checks")
    if isinstance(readyz_failures, list) and readyz_failures:
        signals.append(
            DiagnosticSignal(
                signal="apiserver_readyz_failed",
                component="apiserver",
                severity="critical",
                observed={"failed_checks": readyz_failures[:10]},
                interpretation="API server readiness has failed checks.",
                next_step="Inspect the failed readyz checks and correlate with etcd/API server logs.",
                supporting_evidence=["kubernetes"],
            )
        )

    cert_warnings = kubernetes.get("certificate_expiration_warnings")
    if isinstance(cert_warnings, list) and cert_warnings:
        signals.append(
            DiagnosticSignal(
                signal="node_certificate_expiring",
                component="kubernetes",
                severity="warning",
                observed={"warnings": cert_warnings[:5]},
                interpretation="Kubernetes emitted node certificate expiration warnings. This is usually not the immediate outage root cause, but it is operationally important.",
                next_step="Plan controlled RKE2 certificate rotation before expiry; do not restart control-plane nodes without an operator-approved maintenance plan.",
                supporting_evidence=["kubernetes"],
            )
        )

    return signals


def _runtime_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    runtime = _collector(collectors, "runtime")
    signals = []
    if runtime.get("containerd_socket_healthy") is False:
        signals.append(
            DiagnosticSignal(
                signal="containerd_socket_unhealthy",
                component="containerd",
                severity="critical",
                observed={
                    "socket": runtime.get("containerd_socket_path"),
                    "error": runtime.get("containerd_socket_error"),
                },
                interpretation="containerd Unix socket이 응답하지 않아 kubelet이 pod sandbox/container 상태를 갱신하지 못할 수 있습니다.",
                next_step="containerd socket, pid, journal, ctr version 결과를 함께 확인합니다.",
                supporting_evidence=["runtime"],
            )
        )

    latency_ms = _number(runtime.get("containerd_socket_latency_ms"))
    if latency_ms is not None and latency_ms >= 1000:
        signals.append(
            DiagnosticSignal(
                signal="containerd_socket_latency_high",
                component="containerd",
                severity="warning",
                observed={"latency_ms": latency_ms},
                interpretation="containerd socket 연결 지연이 높아 runtime hang 또는 I/O 병목 가능성이 있습니다.",
                next_step="containerd journal과 disk I/O pressure를 같이 확인합니다.",
                supporting_evidence=["runtime", "disk"],
            )
        )

    if runtime.get("containerd_pid_running") is False:
        signals.append(
            DiagnosticSignal(
                signal="containerd_pid_not_running",
                component="containerd",
                severity="critical",
                observed={"pid": runtime.get("containerd_pid")},
                interpretation="pid file 기준 containerd 프로세스가 존재하지 않습니다.",
                next_step="systemd 상태와 containerd crash loop 여부를 확인합니다.",
                supporting_evidence=["runtime", "systemd"],
            )
        )

    return signals


def _disk_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    disk = _collector(collectors, "disk")
    signals = []
    root_usage = _number(disk.get("root_usage_percent"))
    inode_usage = _number(_first_present(disk.get("inode_usage_percent"), _max_filesystem_value(disk, "inode_usage_percent")))

    if root_usage is not None and root_usage >= 90:
        signals.append(
            DiagnosticSignal(
                signal="disk_usage_critical" if root_usage >= 95 else "disk_usage_high",
                component="disk",
                severity="critical" if root_usage >= 95 else "warning",
                observed={"root_usage_percent": root_usage},
                interpretation="root filesystem 사용률이 높아 kubelet eviction 또는 log/image write 실패가 발생할 수 있습니다.",
                next_step="df -h, du, container image/log 사용량을 확인하고 정리 대상을 확정합니다.",
                supporting_evidence=["disk"],
            )
        )

    if inode_usage is not None and inode_usage >= 90:
        signals.append(
            DiagnosticSignal(
                signal="inode_usage_critical" if inode_usage >= 95 else "inode_usage_high",
                component="disk",
                severity="critical" if inode_usage >= 95 else "warning",
                observed={"inode_usage_percent": inode_usage},
                interpretation="inode 사용률이 높아 새 파일 생성 실패와 kubelet DiskPressure가 발생할 수 있습니다.",
                next_step="df -i와 작은 파일이 많은 경로를 확인해 정리 대상을 확정합니다.",
                supporting_evidence=["disk", "inode"],
            )
        )

    if disk.get("root_mount_read_only") is True:
        signals.append(
            DiagnosticSignal(
                signal="root_filesystem_read_only",
                component="disk",
                severity="critical",
                observed={"root_mount_read_only": True},
                interpretation="root filesystem이 read-only로 remount되어 kubelet/containerd 쓰기 작업이 실패할 수 있습니다.",
                next_step="kernel I/O error, filesystem error, 스토리지 경로 장애를 우선 확인합니다.",
                supporting_evidence=["disk", "kernel"],
            )
        )

    if disk.get("kernel_io_error_detected") is True:
        signals.append(_kernel_io_signal("disk"))

    io_pressure = _pressure_avg(disk.get("io_pressure"), "full", "avg10")
    if io_pressure is not None and io_pressure >= 10:
        signals.append(
            DiagnosticSignal(
                signal="io_pressure_high",
                component="disk",
                severity="warning",
                observed={"io_pressure_full_avg10": io_pressure},
                interpretation="I/O pressure가 높아 kubelet, containerd, etcd disk operation 지연이 발생할 수 있습니다.",
                next_step="/proc/pressure/io, iostat, diskstats를 함께 확인해 병목 장치를 특정합니다.",
                supporting_evidence=["disk"],
            )
        )

    return signals


def _kernel_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    kernel = _collector(collectors, "kernel")
    signals = []
    if kernel.get("io_error_detected") is True:
        signals.append(_kernel_io_signal("kernel"))
    if kernel.get("blocked_task_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="blocked_task_detected",
                component="kernel",
                severity="critical",
                observed={"blocked_task_detected": True},
                interpretation="kernel blocked task가 감지되어 I/O hang, driver hang, filesystem lock 가능성이 있습니다.",
                next_step="dmesg와 blocked task stack trace를 확인해 걸린 subsystem을 특정합니다.",
                supporting_evidence=["kernel"],
            )
        )
    if kernel.get("read_only_filesystem_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="read_only_filesystem_detected",
                component="kernel",
                severity="critical",
                observed={"read_only_filesystem_detected": True},
                interpretation="kernel log에서 filesystem read-only 전환 흔적이 감지되었습니다.",
                next_step="filesystem error 직전의 block device 오류와 storage event를 확인합니다.",
                supporting_evidence=["kernel", "disk"],
            )
        )
    if kernel.get("nic_error_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="kernel_nic_error",
                component="network",
                severity="warning",
                observed={"nic_error_detected": True},
                interpretation="kernel log에서 NIC link 또는 driver 오류가 감지되었습니다.",
                next_step="NIC driver log, carrier_changes, switch port event를 함께 확인합니다.",
                supporting_evidence=["kernel", "network"],
            )
        )
    if kernel.get("oom_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="kernel_oom_detected",
                component="memory",
                severity="critical",
                observed={"oom_detected": True},
                interpretation="kernel OOM 이벤트가 감지되어 노드 프로세스 또는 workload가 종료되었을 수 있습니다.",
                next_step="OOM victim, memory pressure, kubelet eviction event를 확인합니다.",
                supporting_evidence=["kernel", "memory"],
            )
        )
    if kernel.get("kernel_tainted") is True:
        signals.append(
            DiagnosticSignal(
                signal="kernel_tainted",
                component="kernel",
                severity="warning",
                observed={"kernel_tainted_raw": kernel.get("kernel_tainted_raw")},
                interpretation="kernel taint 상태라 서드파티 모듈, forced load, kernel warning 등 추가 해석이 필요합니다.",
                next_step="/proc/sys/kernel/tainted 값을 bitmask로 해석하고 최근 dmesg warning을 확인합니다.",
                supporting_evidence=["kernel", "node"],
            )
        )
    return signals


def _memory_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    memory = _collector(collectors, "memory")
    signals = []
    usage = _number(memory.get("usage_percent"))
    if usage is not None and usage >= 90:
        signals.append(
            DiagnosticSignal(
                signal="memory_pressure_critical" if usage >= 95 else "memory_pressure_high",
                component="memory",
                severity="critical" if usage >= 95 else "warning",
                observed={"usage_percent": usage},
                interpretation="노드 메모리 사용률이 높아 kubelet eviction, OOM kill, system daemon 지연이 발생할 수 있습니다.",
                next_step="MemAvailable, swap, top memory process, kubelet eviction event를 확인합니다.",
                supporting_evidence=["memory"],
            )
        )
    if memory.get("oom_kill_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="oom_kill_detected",
                component="memory",
                severity="critical",
                observed={"oom_kill_detected": True},
                interpretation="OOM kill 흔적이 있어 장애 시점에 프로세스가 강제 종료되었을 수 있습니다.",
                next_step="kernel log에서 OOM victim과 cgroup 정보를 확인합니다.",
                supporting_evidence=["memory", "kernel"],
            )
        )
    swap_usage = _number(memory.get("swap_usage_percent"))
    if swap_usage is not None and swap_usage >= 50:
        signals.append(
            DiagnosticSignal(
                signal="swap_usage_high",
                component="memory",
                severity="warning",
                observed={"swap_usage_percent": swap_usage},
                interpretation="swap 사용률이 높아 system daemon latency가 증가할 수 있습니다.",
                next_step="swap in/out 지표와 메모리 상위 프로세스를 확인합니다.",
                supporting_evidence=["memory"],
            )
        )
    memory_pressure = _pressure_avg(memory.get("pressure"), "full", "avg10")
    if memory_pressure is not None and memory_pressure >= 10:
        signals.append(
            DiagnosticSignal(
                signal="memory_psi_high",
                component="memory",
                severity="warning",
                observed={"memory_pressure_full_avg10": memory_pressure},
                interpretation="memory PSI가 높아 runnable task가 메모리 대기로 지연될 수 있습니다.",
                next_step="/proc/pressure/memory와 kubelet eviction event를 같이 확인합니다.",
                supporting_evidence=["memory"],
            )
        )
    return signals


def _process_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    process = _collector(collectors, "process")
    signals = []
    pid_usage = _number(process.get("pid_usage_percent"))
    if pid_usage is not None and pid_usage >= 80:
        signals.append(
            DiagnosticSignal(
                signal="pid_usage_high",
                component="process",
                severity="critical" if pid_usage >= 90 else "warning",
                observed={"pid_usage_percent": pid_usage},
                interpretation="PID 사용률이 높아 새 프로세스 생성 실패와 PIDPressure가 발생할 수 있습니다.",
                next_step="프로세스 수 급증 원인과 zombie process를 확인합니다.",
                supporting_evidence=["process"],
            )
        )
    zombie_count = _number(process.get("zombie_process_count"))
    if zombie_count is not None and zombie_count > 0:
        signals.append(
            DiagnosticSignal(
                signal="zombie_process_detected",
                component="process",
                severity="warning",
                observed={"zombie_process_count": zombie_count},
                interpretation="zombie process가 있어 부모 프로세스 reap 문제나 runtime shim 이상 가능성이 있습니다.",
                next_step="zombie process의 parent process와 runtime shim 상태를 확인합니다.",
                supporting_evidence=["process", "runtime"],
            )
        )
    return signals


def _network_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    network = _collector(collectors, "network")
    conntrack = _collector(collectors, "conntrack") or _collector(network, "conntrack")
    signals = []

    interfaces_down = network.get("interfaces_down")
    if isinstance(interfaces_down, list) and interfaces_down:
        signals.append(
            DiagnosticSignal(
                signal="interface_down",
                component="network",
                severity="critical",
                observed={"interfaces_down": interfaces_down},
                interpretation="하나 이상의 NIC가 down 상태라 노드 통신 장애가 발생할 수 있습니다.",
                next_step="ip link, ethtool, switch port event를 확인합니다.",
                supporting_evidence=["network"],
            )
        )

    if network.get("nic_link_flap_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="nic_link_flap",
                component="network",
                severity="warning",
                observed={"nic_link_flap_detected": True},
                interpretation="NIC carrier 변화가 감지되어 API Server, etcd, CNI 통신이 불안정할 수 있습니다.",
                next_step="carrier_changes, kernel NIC log, switch event를 시간대별로 대조합니다.",
                supporting_evidence=["network", "kernel"],
            )
        )

    conntrack_usage = _number(_first_present(network.get("conntrack_usage_percent"), conntrack.get("usage_percent")))
    if conntrack.get("near_limit") is True or (conntrack_usage is not None and conntrack_usage >= 80):
        signals.append(
            DiagnosticSignal(
                signal="conntrack_near_limit",
                component="conntrack",
                severity="critical" if conntrack_usage is not None and conntrack_usage >= 90 else "warning",
                observed={
                    "usage_percent": conntrack_usage,
                    "count": conntrack.get("count"),
                    "max": conntrack.get("max"),
                    "available": conntrack.get("available"),
                },
                interpretation="conntrack table이 한계에 가까워 DNS, Service, API Server 연결이 간헐적으로 실패할 수 있습니다.",
                next_step="nf_conntrack_count/max, drop log, connection 폭증 workload를 확인합니다.",
                supporting_evidence=["network", "conntrack"],
            )
        )

    rx_errors = _number(network.get("interface_rx_error_total")) or 0
    tx_errors = _number(network.get("interface_tx_error_total")) or 0
    rx_drops = _number(network.get("interface_rx_drop_total")) or 0
    tx_drops = _number(network.get("interface_tx_drop_total")) or 0
    if rx_errors + tx_errors + rx_drops + tx_drops > 0:
        signals.append(
            DiagnosticSignal(
                signal="interface_packet_errors",
                component="network",
                severity="warning",
                observed={
                    "rx_errors": rx_errors,
                    "tx_errors": tx_errors,
                    "rx_drops": rx_drops,
                    "tx_drops": tx_drops,
                },
                interpretation="NIC error/drop이 있어 패킷 손실 또는 driver/link 문제가 있을 수 있습니다.",
                next_step="/proc/net/dev, ethtool -S, CNI overlay interface error를 확인합니다.",
                supporting_evidence=["network"],
            )
        )

    retrans = _number(network.get("tcp_retrans_segments"))
    listen_overflows = _number(network.get("tcp_ext_listen_overflows"))
    listen_drops = _number(network.get("tcp_ext_listen_drops"))
    if (retrans is not None and retrans >= 100) or (listen_overflows is not None and listen_overflows > 0):
        signals.append(
            DiagnosticSignal(
                signal="tcp_error_counters_high",
                component="network",
                severity="warning",
                observed={
                    "tcp_retrans_segments": retrans,
                    "tcp_ext_listen_overflows": listen_overflows,
                    "tcp_ext_listen_drops": listen_drops,
                },
                interpretation="TCP retransmit/listen overflow가 높아 연결 지연 또는 backlog 고갈 가능성이 있습니다.",
                next_step="/proc/net/snmp, /proc/net/netstat, affected service backlog 설정을 확인합니다.",
                supporting_evidence=["network"],
            )
        )

    dns_latency = _number(network.get("dns_lookup_latency_ms"))
    if dns_latency is not None and dns_latency >= 500:
        signals.append(
            DiagnosticSignal(
                signal="dns_latency_high",
                component="dns",
                severity="warning",
                observed={"dns_lookup_latency_ms": dns_latency},
                interpretation="DNS lookup latency가 높아 pod scheduling, image pull, service discovery 지연이 발생할 수 있습니다.",
                next_step="CoreDNS latency, node resolver, upstream DNS 상태를 확인합니다.",
                supporting_evidence=["network", "dns"],
            )
        )

    return signals


def _cni_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    cni = _collector(collectors, "cni")
    signals = []
    parse_errors = cni.get("parse_errors")
    if isinstance(parse_errors, list) and parse_errors:
        signals.append(
            DiagnosticSignal(
                signal="cni_config_invalid",
                component="cni",
                severity="critical",
                observed=parse_errors[:10],
                interpretation="CNI config JSON 파싱 오류가 있어 kubelet pod sandbox 생성이 실패할 수 있습니다.",
                next_step="/etc/cni/net.d 파일을 검증하고 최근 CNI 설정 변경 이력을 확인합니다.",
                supporting_evidence=["cni"],
            )
        )
    if cni.get("plugin_errors_detected") is True:
        signals.append(
            DiagnosticSignal(
                signal="cni_plugin_error",
                component="cni",
                severity="critical",
                observed={"plugin_errors_detected": True},
                interpretation="CNI plugin 오류가 감지되어 pod network attach가 실패할 수 있습니다.",
                next_step="CNI plugin log와 kubelet pod sandbox event를 확인합니다.",
                supporting_evidence=["cni", "kubelet"],
            )
        )
    mtu_values = cni.get("mtu_values")
    if isinstance(mtu_values, list) and len(set(mtu_values)) > 1:
        signals.append(
            DiagnosticSignal(
                signal="cni_mtu_values_inconsistent",
                component="cni",
                severity="warning",
                observed={"mtu_values": mtu_values},
                interpretation="CNI 설정 안에 여러 MTU 값이 있어 overlay 경로 MTU 불일치 가능성이 있습니다.",
                next_step="노드 NIC MTU, CNI MTU, pod path MTU를 함께 비교합니다.",
                supporting_evidence=["cni", "network"],
            )
        )
    return signals


def _dns_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    dns = _collector(collectors, "dns")
    signals = []
    if dns and dns.get("dns_configured") is False:
        signals.append(
            DiagnosticSignal(
                signal="dns_unconfigured",
                component="dns",
                severity="critical",
                observed={"nameservers": dns.get("nameservers"), "resolv_conf_exists": dns.get("resolv_conf_exists")},
                interpretation="노드 resolver에 nameserver가 없어 DNS 조회가 실패할 수 있습니다.",
                next_step="/etc/resolv.conf와 node-local-dns/CoreDNS 설정을 확인합니다.",
                supporting_evidence=["dns"],
            )
        )
    attempts = _number(dns.get("attempts"))
    timeout_seconds = _number(dns.get("timeout_seconds"))
    if attempts is not None and timeout_seconds is not None and attempts * timeout_seconds >= 15:
        signals.append(
            DiagnosticSignal(
                signal="dns_resolver_timeout_budget_high",
                component="dns",
                severity="warning",
                observed={"attempts": attempts, "timeout_seconds": timeout_seconds},
                interpretation="resolver timeout budget이 커서 DNS 장애 시 요청 지연이 길어질 수 있습니다.",
                next_step="resolv.conf options와 CoreDNS timeout/retry 정책을 검토합니다.",
                supporting_evidence=["dns"],
            )
        )
    return signals


def _build_candidates(
    alert_name: str,
    signals: list[DiagnosticSignal],
    collectors: dict[str, Any],
) -> list[RootCauseCandidate]:
    del collectors
    candidates: list[RootCauseCandidate] = []
    names = {signal.signal for signal in signals}

    if names & {"containerd_socket_unhealthy", "containerd_unit_unhealthy", "containerd_pid_not_running"}:
        candidates.append(
            _candidate(
                "containerd hang, crash loop, 또는 socket 응답 실패로 kubelet runtime 연동이 끊겼습니다.",
                _confidence_for(names, {"containerd_socket_unhealthy", "containerd_unit_unhealthy"}),
                signals,
                {"containerd_socket_unhealthy", "containerd_unit_unhealthy", "containerd_pid_not_running"},
            )
        )

    if names & {"kubelet_unit_unhealthy", "kubelet_restarting"}:
        candidates.append(
            _candidate(
                "kubelet unit 실패 또는 반복 재시작으로 노드 상태 갱신과 pod lifecycle 처리가 불안정합니다.",
                _confidence_for(names, {"kubelet_unit_unhealthy", "kubelet_restarting"}),
                signals,
                {"kubelet_unit_unhealthy", "kubelet_restarting"},
            )
        )

    if names & {"root_filesystem_read_only", "read_only_filesystem_detected", "kernel_io_error"}:
        candidates.append(
            _candidate(
                "스토리지 또는 filesystem 오류로 root filesystem 쓰기 실패와 kubelet/containerd 장애가 발생했을 가능성이 큽니다.",
                Confidence.HIGH,
                signals,
                {"root_filesystem_read_only", "read_only_filesystem_detected", "kernel_io_error"},
            )
        )

    if names & {"disk_usage_critical", "inode_usage_critical", "io_pressure_high"}:
        candidates.append(
            _candidate(
                "디스크 용량, inode, 또는 I/O pressure가 높아 kubelet eviction과 runtime 지연이 발생했습니다.",
                _confidence_for(names, {"disk_usage_critical", "inode_usage_critical"}),
                signals,
                {"disk_usage_critical", "inode_usage_critical", "io_pressure_high"},
            )
        )

    if names & {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected"}:
        candidates.append(
            _candidate(
                "노드 메모리 고갈 또는 OOM kill로 system daemon이나 workload가 정상 동작하지 못했습니다.",
                _confidence_for(names, {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected"}),
                signals,
                {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected", "memory_psi_high"},
            )
        )

    if names & {
        "conntrack_near_limit",
        "interface_down",
        "nic_link_flap",
        "interface_packet_errors",
        "control_plane_peer_unreachable",
    }:
        candidates.append(
            _candidate(
                "노드 네트워크 경로, NIC link, 또는 conntrack 고갈로 API Server/CNI/DNS 통신이 불안정합니다.",
                _confidence_for(names, {"control_plane_peer_unreachable", "conntrack_near_limit", "interface_down"}),
                signals,
                {
                    "control_plane_peer_unreachable",
                    "conntrack_near_limit",
                    "interface_down",
                    "nic_link_flap",
                    "interface_packet_errors",
                },
            )
        )

    if names & {"cni_config_invalid", "cni_plugin_error", "cni_mtu_values_inconsistent", "cni_pod_restarting"}:
        candidates.append(
            _candidate(
                "CNI 설정 또는 plugin 오류로 pod network attach와 노드 네트워크 구성이 실패하고 있습니다.",
                _confidence_for(names, {"cni_config_invalid", "cni_plugin_error", "cni_pod_restarting"}),
                signals,
                {"cni_config_invalid", "cni_plugin_error", "cni_mtu_values_inconsistent", "cni_pod_restarting"},
            )
        )

    if names & {"dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high"}:
        candidates.append(
            _candidate(
                "노드 DNS resolver 또는 CoreDNS 경로 문제로 서비스 탐색과 control-plane 통신이 지연될 수 있습니다.",
                _confidence_for(names, {"dns_unconfigured", "dns_latency_high"}),
                signals,
                {"dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high"},
            )
        )

    if names & {"apiserver_readyz_failed", "kubernetes_api_unavailable", "node_metrics_unavailable"}:
        candidates.append(
            _candidate(
                "Kubernetes API readiness or metrics path is unhealthy, so controllers and operators may see stale or missing node state.",
                _confidence_for(names, {"apiserver_readyz_failed", "kubernetes_api_unavailable"}),
                signals,
                {"apiserver_readyz_failed", "kubernetes_api_unavailable", "node_metrics_unavailable"},
            )
        )

    if not candidates and alert_name in {"NodeNotReady", "NetworkUnavailable", "DiskPressure", "MemoryPressure"}:
        candidates.append(
            RootCauseCandidate(
                cause="현재 수집 증거만으로 단일 원인을 특정하지 못했습니다. 추가 로그와 시간대별 지표 대조가 필요합니다.",
                confidence=Confidence.LOW,
                supporting_evidence=list(_collector_names_for_alert(alert_name)),
            )
        )

    return candidates


def _fallback_candidates(alert_name: str, collectors: dict[str, Any]) -> list[RootCauseCandidate]:
    if alert_name == "NodeNotReady":
        return [
            RootCauseCandidate(
                cause="kubelet, containerd, network 중 하나가 노드 Ready 상태 갱신을 방해했을 가능성이 있습니다.",
                confidence=Confidence.LOW,
                supporting_evidence=["systemd", "runtime", "network"],
            )
        ]
    if alert_name == "DiskPressure":
        return [
            RootCauseCandidate(
                cause="디스크 용량, inode, 또는 I/O 지표 확인이 필요합니다.",
                confidence=Confidence.LOW,
                supporting_evidence=["disk", "inode", "kernel"],
            )
        ]
    if alert_name == "MemoryPressure":
        return [
            RootCauseCandidate(
                cause="메모리 사용률, OOM event, process count 확인이 필요합니다.",
                confidence=Confidence.LOW,
                supporting_evidence=["memory", "process", "kernel"],
            )
        ]
    if alert_name == "NetworkUnavailable":
        return [
            RootCauseCandidate(
                cause="NIC, route, conntrack, DNS, CNI 증거 확인이 필요합니다.",
                confidence=Confidence.LOW,
                supporting_evidence=["network", "conntrack", "dns", "cni"],
            )
        ]
    return [
        RootCauseCandidate(
            cause="알림 유형에 대한 전용 rule이 없거나 핵심 증거가 부족해 일반 인프라 장애로 분류했습니다.",
            confidence=Confidence.LOW,
            supporting_evidence=list(collectors.keys()),
        )
    ]


def _candidates_from_llm(llm_analysis: dict[str, Any]) -> list[RootCauseCandidate]:
    if llm_analysis.get("status") != "completed":
        return []
    result = llm_analysis.get("result")
    if not isinstance(result, dict):
        return []
    raw_candidates = result.get("root_cause_candidates")
    if not isinstance(raw_candidates, list):
        return []
    candidates = []
    for raw_candidate in raw_candidates:
        if not isinstance(raw_candidate, dict):
            continue
        cause = str(raw_candidate.get("cause") or "").strip()
        if not cause:
            continue
        supporting_evidence = raw_candidate.get("supporting_signals") or raw_candidate.get("evidence_paths") or []
        if not isinstance(supporting_evidence, list):
            supporting_evidence = []
        candidates.append(
            RootCauseCandidate(
                cause=f"LLM 분석: {cause}",
                confidence=_confidence_enum(raw_candidate.get("confidence")),
                supporting_evidence=[str(item) for item in supporting_evidence if item is not None],
            )
        )
    return candidates[:5]


def _build_summary(
    evidence: EvidenceBundle,
    signals: list[DiagnosticSignal],
    candidates: list[RootCauseCandidate],
) -> RcaSummary:
    critical_count = sum(1 for signal in signals if signal.severity == "critical")
    warning_count = sum(1 for signal in signals if signal.severity == "warning")
    if candidates:
        most_likely_cause = candidates[0].cause
        confidence = candidates[0].confidence
    else:
        most_likely_cause = "추가 증거 수집이 필요한 클러스터 인프라 장애입니다."
        confidence = Confidence.LOW

    if critical_count >= 2 and confidence != Confidence.HIGH:
        confidence = Confidence.MEDIUM

    symptom = f"{evidence.node_name}에서 {evidence.alert_name} 알림이 발생했습니다."
    if critical_count or warning_count:
        symptom = f"{symptom} 분석 결과 critical 신호 {critical_count}개, warning 신호 {warning_count}개가 확인되었습니다."

    return RcaSummary(
        symptom=symptom,
        most_likely_cause=most_likely_cause,
        confidence=confidence,
    )


def _build_evidence_findings(
    collectors: dict[str, Any],
    signals: list[DiagnosticSignal],
    alert_name: str,
    preprocessed_evidence: dict[str, Any],
    llm_analysis: dict[str, Any],
) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = [
        {"type": "collector", "collector": collector_name, "finding": finding}
        for collector_name, finding in collectors.items()
    ]
    findings.append(
        {
            "type": "preprocessed_evidence",
            "payload": preprocessed_evidence,
        }
    )
    findings.append(
        {
            "type": "llm_analysis",
            "analysis": llm_analysis,
        }
    )
    findings.append(
        {
            "type": "derived_signals",
            "signals": [signal.as_report_item() for signal in signals],
        }
    )
    findings.append(
        {
            "type": "resolution_checklist",
            "items": _resolution_checklist(signals, alert_name),
        }
    )
    return findings


def _resolution_checklist(signals: list[DiagnosticSignal], alert_name: str) -> list[dict[str, str]]:
    names = {signal.signal for signal in signals}
    items: list[dict[str, str]] = []

    if names & {"kubelet_unit_unhealthy", "kubelet_restarting"} or alert_name in {"NodeNotReady", "KubeletDown"}:
        items.append(
            {
                "component": "kubelet",
                "check": "kubelet unit과 최근 journal에서 재시작 원인을 확인",
                "command": "systemctl status kubelet --no-pager && journalctl -u kubelet -n 200 --no-pager",
            }
        )
    if names & {"containerd_socket_unhealthy", "containerd_unit_unhealthy", "containerd_pid_not_running"}:
        items.append(
            {
                "component": "containerd",
                "check": "containerd socket, pid, journal 상태 확인",
                "command": "systemctl status containerd --no-pager && journalctl -u containerd -n 200 --no-pager",
            }
        )
    if names & {"disk_usage_critical", "disk_usage_high", "inode_usage_critical", "inode_usage_high"}:
        items.append(
            {
                "component": "disk",
                "check": "용량과 inode 고갈 경로 확인",
                "command": "df -h / && df -i / && du -xhd1 /var /var/log /var/lib/containerd 2>/dev/null",
            }
        )
    if names & {"kernel_io_error", "root_filesystem_read_only", "read_only_filesystem_detected"}:
        items.append(
            {
                "component": "kernel",
                "check": "block device와 filesystem error 확인",
                "command": "dmesg -T --level=err,warn | tail -200",
            }
        )
    if names & {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected"}:
        items.append(
            {
                "component": "memory",
                "check": "메모리 압박과 OOM victim 확인",
                "command": "cat /proc/meminfo && dmesg -T | grep -Ei 'out of memory|oom|killed process' | tail -50",
            }
        )
    if names & {
        "conntrack_near_limit",
        "interface_down",
        "nic_link_flap",
        "interface_packet_errors",
        "control_plane_peer_unreachable",
    }:
        items.append(
            {
                "component": "network",
                "check": "NIC, route, conntrack 상태 확인",
                "command": "ip link && ip route && ss -ltn && cat /proc/sys/net/netfilter/nf_conntrack_count && cat /proc/sys/net/netfilter/nf_conntrack_max",
            }
        )
    if names & {"cni_config_invalid", "cni_plugin_error", "cni_mtu_values_inconsistent", "cni_pod_restarting"}:
        items.append(
            {
                "component": "cni",
                "check": "CNI config와 MTU 설정 확인",
                "command": "find /etc/cni/net.d -maxdepth 1 -type f -print -exec sed -n '1,160p' {} \\; && kubectl -n kube-system get pods -o wide",
            }
        )
    if names & {"apiserver_readyz_failed", "kubernetes_api_unavailable", "node_metrics_unavailable", "node_certificate_expiring"}:
        items.append(
            {
                "component": "kubernetes",
                "check": "Kubernetes API readiness, metrics path, and node events",
                "command": "kubectl get --raw='/readyz?verbose' && kubectl top nodes && kubectl get events -A --sort-by=.lastTimestamp | tail -100",
            }
        )
    if names & {"dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high"}:
        items.append(
            {
                "component": "dns",
                "check": "노드 resolver와 CoreDNS 경로 확인",
                "command": "cat /etc/resolv.conf",
            }
        )

    if not items:
        items.append(
            {
                "component": "node",
                "check": "증거가 부족하므로 노드 기본 상태와 systemd failed unit 확인",
                "command": "systemctl --failed --no-pager && dmesg -T --level=err,warn | tail -200",
            }
        )
    return items


def _scope_components(alert_name: str, signals: list[DiagnosticSignal]) -> list[str]:
    components = set(_collector_names_for_alert(alert_name))
    components.update(signal.component for signal in signals)
    return sorted(components)


def _collector_names_for_alert(alert_name: str) -> list[str]:
    if alert_name == "NodeNotReady":
        return ["kubernetes", "kubelet", "containerd", "network"]
    if alert_name == "DiskPressure":
        return ["disk", "inode", "kernel"]
    if alert_name == "MemoryPressure":
        return ["memory", "process", "kernel"]
    if alert_name == "PIDPressure":
        return ["process", "systemd", "kernel"]
    if alert_name == "NetworkUnavailable":
        return ["kubernetes", "network", "cni", "dns", "conntrack"]
    if alert_name in {"ContainerdDown", "ContainerRuntimeUnhealthy"}:
        return ["containerd", "systemd", "kernel"]
    if alert_name in {"KubeletDown", "KubeletUnhealthy"}:
        return ["kubelet", "systemd", "kernel"]
    return ["node", "systemd", "kernel"]


def _candidate(
    cause: str,
    confidence: Confidence,
    signals: list[DiagnosticSignal],
    signal_names: set[str],
) -> RootCauseCandidate:
    supporting_evidence: list[str] = []
    for signal in signals:
        if signal.signal in signal_names:
            supporting_evidence.extend(signal.supporting_evidence)
    return RootCauseCandidate(
        cause=cause,
        confidence=confidence,
        supporting_evidence=_dedupe_strings(supporting_evidence),
    )


def _confidence_for(names: set[str], high_signals: set[str]) -> Confidence:
    return Confidence.HIGH if names & high_signals else Confidence.MEDIUM


def _confidence_enum(value: Any) -> Confidence:
    value = str(value or Confidence.LOW.value).lower()
    try:
        return Confidence(value)
    except ValueError:
        return Confidence.LOW


def _kernel_io_signal(source: str) -> DiagnosticSignal:
    return DiagnosticSignal(
        signal="kernel_io_error",
        component="kernel",
        severity="critical",
        observed={"detected_by": source},
        interpretation="kernel I/O error가 감지되어 disk, filesystem, storage path 장애 가능성이 높습니다.",
        next_step="dmesg, filesystem 상태, cloud/storage event, node disk health를 확인합니다.",
        supporting_evidence=["kernel", "disk"],
    )


def _collector(collectors: dict[str, Any], name: str) -> dict[str, Any]:
    value = collectors.get(name)
    return value if isinstance(value, dict) else {}


def _max_filesystem_value(disk: dict[str, Any], key: str) -> float | None:
    values = []
    filesystems = disk.get("filesystems")
    if not isinstance(filesystems, list):
        return None
    for filesystem in filesystems:
        if isinstance(filesystem, dict):
            value = _number(filesystem.get(key))
            if value is not None:
                values.append(value)
    return max(values) if values else None


def _pressure_avg(value: Any, category: str, window: str) -> float | None:
    if not isinstance(value, dict):
        return None
    category_value = value.get(category)
    if not isinstance(category_value, dict):
        return None
    return _number(category_value.get(window))


def _first_present(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _max_number(*values: Any) -> float | None:
    numbers = [_number(value) for value in values]
    numbers = [value for value in numbers if value is not None]
    return max(numbers) if numbers else None


def _number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _bad_unit_state(active_state: Any, sub_state: Any) -> bool:
    active = str(active_state or "").lower()
    sub = str(sub_state or "").lower()
    return active in {"failed", "restarting", "deactivating"} or sub in {
        "failed",
        "auto-restart",
        "dead",
    }


def _severity_rank(severity: str) -> int:
    return {"critical": 0, "warning": 1, "info": 2}.get(severity, 3)


def _dedupe_actions(actions: list[RecommendedAction]) -> list[RecommendedAction]:
    seen = set()
    deduped = []
    for action in actions:
        key = (action.action, action.policy)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(action)
    return deduped


def _dedupe_strings(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result
