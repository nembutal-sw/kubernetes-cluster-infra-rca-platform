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
                "Collect additional kubelet, runtime, kernel, systemd, network, and disk evidence for the incident window.",
                "This is read-only evidence collection and does not change node or workload state.",
            )
        ]
        signal_names = {signal.signal for signal in signals}
        components = {signal.component for signal in signals}

        if signal_names & {"kubelet_unit_unhealthy", "kubelet_restarting", "containerd_socket_unhealthy", "containerd_unit_unhealthy", "container_runtime_socket_unhealthy", "container_runtime_unit_unhealthy", "systemd_failed_units", "blocked_task_detected", "kernel_tainted"}:
            actions.append(self._policy_engine.classify("collect_linux_low_level_evidence", "Collect low-level Linux state for systemd units, kernel logs, process state, runtime sockets, and host namespaces.", "Linux low-level inspection is read-only and is needed before any restart or node-level change is considered."))

        if components & {"disk", "kernel"} or alert_name == "DiskPressure":
            actions.append(self._policy_engine.classify("inspect_storage_state", "Inspect filesystem, inode, mount, block device, and kernel I/O state.", "Storage inspection is read-only and helps separate capacity pressure from filesystem or device errors."))

        if components & {"network", "conntrack", "dns", "cni"} or alert_name == "NetworkUnavailable":
            actions.append(self._policy_engine.classify("inspect_network_state", "Inspect NIC, route, socket, conntrack, resolver, and CNI state from the affected node.", "Network inspection is read-only and should precede any CNI, sysctl, or routing change."))

        if signal_names & {"container_runtime_socket_unhealthy", "container_runtime_unit_unhealthy"}:
            actions.append(self._policy_engine.classify("restart_container_runtime", "Container runtime socket or unit remains unhealthy; operator-approved runtime restart may be required.", "Runtime restart can disrupt running workloads and must never be executed automatically."))

        if signal_names & {"containerd_socket_unhealthy", "containerd_unit_unhealthy"}:
            actions.append(self._policy_engine.classify("restart_containerd", "containerd socket or unit remains unhealthy; operator-approved containerd restart may be required.", "Runtime restart can disrupt running workloads and must never be executed automatically."))

        if signal_names & {"kubelet_unit_unhealthy", "kubelet_restarting"} or alert_name in {"KubeletDown", "KubeletUnhealthy"}:
            actions.append(self._policy_engine.classify("restart_kubelet", "If kubelet is failed or repeatedly restarting, consider an operator-approved kubelet restart.", "A kubelet restart may recover node state updates, but it can affect workload lifecycle handling and requires approval."))

        if signal_names & {"disk_usage_critical", "inode_usage_critical"}:
            actions.append(self._policy_engine.classify("cleanup_disk", "After operator approval, clean confirmed-unused images, logs, or temporary files, or expand disk capacity.", "Disk cleanup can cause data loss if the target path is wrong, so path review and approval are required."))

        if signal_names & {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected"}:
            actions.append(self._policy_engine.classify("cordon_node", "If memory pressure or OOM events continue, consider operator-approved node cordon or drain.", "Cordon or drain causes workload rescheduling and must never be executed automatically."))

        if signal_names & {"pid_usage_high", "zombie_process_detected"}:
            actions.append(self._policy_engine.classify("manual_investigation", "Investigate PID pressure, process fan-out, zombie parents, and runtime shim state before remediation.", "PID exhaustion can be caused by workload behavior or host process leaks and requires human judgment."))

        if signal_names & {"conntrack_near_limit", "cni_config_invalid", "dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high", "control_plane_peer_unreachable", "cni_mtu_values_inconsistent"}:
            actions.append(self._policy_engine.classify("open_gitops_pr", "Propose conntrack, CNI, DNS/CoreDNS, MTU, or sysctl changes through GitOps PR only.", "Cluster configuration changes must not be applied directly from RCA and need a reviewable PR flow."))

        if components & {"network", "kernel"} and signal_names & {"interface_down", "nic_link_flap", "kernel_io_error", "root_filesystem_read_only", "control_plane_peer_unreachable"}:
            actions.append(self._policy_engine.classify("manual_hardware_check", "Investigate NIC link flap, kernel I/O error, read-only filesystem, and storage or network path health.", "Hardware, kernel, storage, and network path validation requires manual investigation."))

        if signal_names & {"blocked_task_detected", "root_filesystem_read_only"}:
            actions.append(self._policy_engine.classify("reboot_node", "If blocked tasks or read-only filesystem errors persist, node reboot may be a last-resort operator decision.", "Node reboot has broad impact and must never be executed automatically."))

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
    runtime = _collector(collectors, "runtime")
    signals = []
    kubelet_status = _first_present(systemd.get("kubelet_status"), kubelet.get("kubelet_status"))
    kubelet_sub_state = _first_present(systemd.get("kubelet_sub_state"), kubelet.get("kubelet_sub_state"))
    kubelet_restarts = _max_number(systemd.get("kubelet_restart_count"), kubelet.get("kubelet_restart_count"))
    rke2_server_status = systemd.get("rke2_server_status")
    rke2_server_sub_state = systemd.get("rke2_server_sub_state")
    rke2_agent_status = systemd.get("rke2_agent_status")
    rke2_agent_sub_state = systemd.get("rke2_agent_sub_state")
    rke2_active = _healthy_unit_state(rke2_server_status, rke2_server_sub_state) or _healthy_unit_state(rke2_agent_status, rke2_agent_sub_state)
    embedded_kubelet_active = systemd.get("embedded_kubelet_running") is True or (rke2_active and systemd.get("rke2_embedded_kubelet_running") is True)
    embedded_runtime_active = systemd.get("embedded_runtime_running") is True or (rke2_active and systemd.get("rke2_embedded_containerd_running") is True)
    runtime_kind = str(runtime.get("runtime_kind") or "containerd").lower()

    if _bad_unit_state(kubelet_status, kubelet_sub_state) and not embedded_kubelet_active:
        signals.append(DiagnosticSignal(signal="kubelet_unit_unhealthy", component="kubelet", severity="critical", observed={"status": kubelet_status, "sub_state": kubelet_sub_state}, interpretation="The kubelet systemd unit is not active/running.", next_step="Check systemctl status kubelet and journalctl -u kubelet for the last failure before the incident.", supporting_evidence=["systemd", "kubelet"]))
    elif kubelet_restarts is not None and kubelet_restarts >= 5:
        signals.append(DiagnosticSignal(signal="kubelet_restarting", component="kubelet", severity="warning", observed={"restart_count": kubelet_restarts}, interpretation="The kubelet restart count is high; deadlock, configuration error, or API server connectivity trouble is possible.", next_step="Inspect journalctl -u kubelet around each restart and correlate with API server connection errors.", supporting_evidence=["systemd", "kubelet"]))

    containerd_status = systemd.get("containerd_status")
    containerd_sub_state = systemd.get("containerd_sub_state")
    if _bad_unit_state(containerd_status, containerd_sub_state) and not embedded_runtime_active and runtime_kind in {"containerd", "unknown", ""}:
        signals.append(DiagnosticSignal(signal="containerd_unit_unhealthy", component="containerd", severity="critical", observed={"status": containerd_status, "sub_state": containerd_sub_state}, interpretation="The containerd systemd unit is unhealthy, so kubelet runtime operations may fail.", next_step="Check systemctl status containerd and journalctl -u containerd for crash, hang, or configuration errors.", supporting_evidence=["systemd"]))

    for unit in systemd.get("runtime_units", []) if isinstance(systemd.get("runtime_units"), list) else []:
        unit_name = str(unit.get("name") or "")
        unit_kind = _runtime_kind_for_unit(unit_name)
        if unit_kind == "containerd":
            continue
        if _optional_bad_unit_state(unit.get("status"), unit.get("sub_state")):
            signals.append(DiagnosticSignal(signal="container_runtime_unit_unhealthy", component=unit_kind, severity="critical", observed=unit, interpretation="Container runtime systemd unit is failed or restarting.", next_step="Check the runtime unit status and journal before restarting it with operator approval.", supporting_evidence=["systemd", "runtime"]))

    if _bad_unit_state(rke2_server_status, rke2_server_sub_state):
        signals.append(DiagnosticSignal(signal="rke2_server_unit_unhealthy", component="rke2", severity="critical", observed={"status": rke2_server_status, "sub_state": rke2_server_sub_state}, interpretation="rke2-server unit is not healthy, so embedded kubelet/containerd/control-plane components may be unstable.", next_step="Check systemctl status rke2-server and journalctl -u rke2-server around the incident window.", supporting_evidence=["systemd"]))

    rke2_server_restarts = _number(systemd.get("rke2_server_restart_count"))
    if rke2_server_restarts is not None and rke2_server_restarts >= 5:
        signals.append(DiagnosticSignal(signal="rke2_server_restarting", component="rke2", severity="warning", observed={"restart_count": rke2_server_restarts}, interpretation="rke2-server restart count is high; control-plane or embedded runtime instability may have occurred.", next_step="Correlate rke2-server restarts with node Ready changes, CNI restarts, and API timeout logs.", supporting_evidence=["systemd", "kubernetes"]))

    failed_units = systemd.get("failed_units")
    if isinstance(failed_units, list) and failed_units:
        signals.append(DiagnosticSignal(signal="systemd_failed_units", component="systemd", severity="warning", observed=failed_units[:10], interpretation="One or more failed systemd units remain on the node, which can indicate dependency service failure.", next_step="Run systemctl --failed and inspect each failed unit journal to determine whether the failure propagated to Kubernetes components.", supporting_evidence=["systemd"]))
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
    runtime_kind = str(runtime.get("runtime_kind") or "containerd").lower()
    component = "containerd" if runtime_kind in {"", "containerd"} else runtime_kind
    socket_healthy = _first_present(runtime.get("runtime_socket_healthy"), runtime.get("containerd_socket_healthy"))
    socket_permission_denied = _first_present(runtime.get("runtime_socket_permission_denied"), runtime.get("containerd_socket_permission_denied"))
    socket_path = _first_present(runtime.get("runtime_socket_path"), runtime.get("containerd_socket_path"))
    socket_error = _first_present(runtime.get("runtime_socket_error"), runtime.get("containerd_socket_error"))
    socket_latency_ms = _first_present(runtime.get("runtime_socket_latency_ms"), runtime.get("containerd_socket_latency_ms"))
    pid_running = _first_present(runtime.get("runtime_pid_running"), runtime.get("containerd_pid_running"))
    pid_value = _first_present(runtime.get("runtime_pid"), runtime.get("containerd_pid"))
    signal_prefix = "containerd" if component == "containerd" else "container_runtime"
    if socket_healthy is False and socket_permission_denied is True:
        signals.append(DiagnosticSignal(signal=f"{signal_prefix}_socket_permission_denied", component=component, severity="warning", observed={"runtime_kind": runtime_kind, "socket": socket_path, "error": socket_error}, interpretation="Container runtime socket exists but the agent could not probe it because of local permissions.", next_step="Run the node agent with sufficient host privileges or grant access to the runtime socket before treating this as a runtime outage.", supporting_evidence=["runtime"]))
    elif socket_healthy is False:
        signals.append(DiagnosticSignal(signal=f"{signal_prefix}_socket_unhealthy", component=component, severity="critical", observed={"runtime_kind": runtime_kind, "socket": socket_path, "error": socket_error}, interpretation="Container runtime Unix socket is not responding, so kubelet may fail pod sandbox/container operations.", next_step="Check the runtime socket, pid, unit, and journal for the detected runtime kind.", supporting_evidence=["runtime"]))
    latency_ms = _number(socket_latency_ms)
    if latency_ms is not None and latency_ms >= 1000:
        signals.append(DiagnosticSignal(signal=f"{signal_prefix}_socket_latency_high", component=component, severity="warning", observed={"runtime_kind": runtime_kind, "latency_ms": latency_ms}, interpretation="Container runtime socket latency is high; runtime hang or I/O pressure may be involved.", next_step="Check runtime journal and disk I/O pressure together.", supporting_evidence=["runtime", "disk"]))
    if pid_running is False:
        signals.append(DiagnosticSignal(signal=f"{signal_prefix}_pid_not_running", component=component, severity="critical", observed={"runtime_kind": runtime_kind, "pid": pid_value}, interpretation="Runtime pid file points to a process that is not running.", next_step="Check systemd state and runtime crash loop history.", supporting_evidence=["runtime", "systemd"]))
    return signals


def _disk_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    disk = _collector(collectors, "disk")
    signals = []
    root_usage = _number(disk.get("root_usage_percent"))
    inode_usage = _number(_first_present(disk.get("inode_usage_percent"), _max_filesystem_value(disk, "inode_usage_percent")))
    if root_usage is not None and root_usage >= 90:
        signals.append(DiagnosticSignal(signal="disk_usage_critical" if root_usage >= 95 else "disk_usage_high", component="disk", severity="critical" if root_usage >= 95 else "warning", observed={"root_usage_percent": root_usage}, interpretation="Root filesystem usage is high and may cause kubelet eviction, log writes, or image pulls to fail.", next_step="Use df, du, container image usage, and log size checks to confirm safe cleanup or capacity expansion targets.", supporting_evidence=["disk"]))
    if inode_usage is not None and inode_usage >= 90:
        signals.append(DiagnosticSignal(signal="inode_usage_critical" if inode_usage >= 95 else "inode_usage_high", component="disk", severity="critical" if inode_usage >= 95 else "warning", observed={"inode_usage_percent": inode_usage}, interpretation="Inode usage is high and may cause new file creation failures and kubelet DiskPressure.", next_step="Use df -i and high file-count directory checks to identify cleanup candidates.", supporting_evidence=["disk", "inode"]))
    if disk.get("root_mount_read_only") is True:
        signals.append(DiagnosticSignal(signal="root_filesystem_read_only", component="disk", severity="critical", observed={"root_mount_read_only": True}, interpretation="The root filesystem is mounted read-only, so kubelet/containerd write operations may fail.", next_step="Prioritize kernel I/O errors, filesystem errors, block device health, and storage path events.", supporting_evidence=["disk", "kernel"]))
    if disk.get("kernel_io_error_detected") is True:
        signals.append(_kernel_io_signal("disk"))
    io_pressure = _pressure_avg(disk.get("io_pressure"), "full", "avg10")
    if io_pressure is not None and io_pressure >= 10:
        signals.append(DiagnosticSignal(signal="io_pressure_high", component="disk", severity="warning", observed={"io_pressure_full_avg10": io_pressure}, interpretation="I/O pressure is high and may delay kubelet, containerd, etcd, or log operations.", next_step="Correlate /proc/pressure/io, iostat, diskstats, and runtime journal timestamps to identify the bottleneck device.", supporting_evidence=["disk"]))
    return signals


def _kernel_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    kernel = _collector(collectors, "kernel")
    signals = []
    if kernel.get("io_error_detected") is True:
        signals.append(_kernel_io_signal("kernel"))
    if kernel.get("blocked_task_detected") is True:
        signals.append(DiagnosticSignal(signal="blocked_task_detected", component="kernel", severity="critical", observed={"blocked_task_detected": True}, interpretation="Kernel blocked tasks were detected, which can indicate I/O hang, driver hang, or filesystem lock contention.", next_step="Inspect dmesg blocked task stack traces and identify the blocked subsystem before considering disruptive remediation.", supporting_evidence=["kernel"]))
    if kernel.get("read_only_filesystem_detected") is True:
        signals.append(DiagnosticSignal(signal="read_only_filesystem_detected", component="kernel", severity="critical", observed={"read_only_filesystem_detected": True}, interpretation="Kernel logs show filesystem read-only transition evidence.", next_step="Find block device or filesystem errors immediately before the remount and correlate with storage events.", supporting_evidence=["kernel", "disk"]))
    if kernel.get("nic_error_detected") is True:
        signals.append(DiagnosticSignal(signal="kernel_nic_error", component="network", severity="warning", observed={"nic_error_detected": True}, interpretation="Kernel logs show NIC link or driver errors.", next_step="Check NIC driver logs, carrier changes, ethtool counters, and switch port events together.", supporting_evidence=["kernel", "network"]))
    if kernel.get("oom_detected") is True:
        signals.append(DiagnosticSignal(signal="kernel_oom_detected", component="memory", severity="critical", observed={"oom_detected": True}, interpretation="Kernel OOM activity was detected and a host process or workload may have been killed.", next_step="Identify the OOM victim, cgroup, memory pressure, and kubelet eviction events around the incident window.", supporting_evidence=["kernel", "memory"]))
    if kernel.get("kernel_tainted") is True:
        signals.append(DiagnosticSignal(signal="kernel_tainted", component="kernel", severity="warning", observed={"kernel_tainted_raw": kernel.get("kernel_tainted_raw")}, interpretation="Kernel taint is set, so third-party modules, forced loads, or kernel warnings may need additional interpretation.", next_step="Decode /proc/sys/kernel/tainted and inspect recent dmesg warnings before assigning root cause.", supporting_evidence=["kernel", "node"]))
    return signals


def _memory_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    memory = _collector(collectors, "memory")
    signals = []
    usage = _number(memory.get("usage_percent"))
    if usage is not None and usage >= 90:
        signals.append(DiagnosticSignal(signal="memory_pressure_critical" if usage >= 95 else "memory_pressure_high", component="memory", severity="critical" if usage >= 95 else "warning", observed={"usage_percent": usage}, interpretation="Node memory usage is high and can cause kubelet eviction, OOM kills, or system daemon latency.", next_step="Check MemAvailable, swap usage, top memory consumers, and kubelet eviction events.", supporting_evidence=["memory"]))
    if memory.get("oom_kill_detected") is True:
        signals.append(DiagnosticSignal(signal="oom_kill_detected", component="memory", severity="critical", observed={"oom_kill_detected": True}, interpretation="OOM kill evidence exists around the incident window.", next_step="Inspect kernel logs for OOM victim, cgroup, and memory pressure context.", supporting_evidence=["memory", "kernel"]))
    swap_usage = _number(memory.get("swap_usage_percent"))
    if swap_usage is not None and swap_usage >= 50:
        signals.append(DiagnosticSignal(signal="swap_usage_high", component="memory", severity="warning", observed={"swap_usage_percent": swap_usage}, interpretation="Swap usage is high and may increase system daemon latency.", next_step="Check swap in/out activity and the top memory-consuming processes.", supporting_evidence=["memory"]))
    memory_pressure = _pressure_avg(memory.get("pressure"), "full", "avg10")
    if memory_pressure is not None and memory_pressure >= 10:
        signals.append(DiagnosticSignal(signal="memory_psi_high", component="memory", severity="warning", observed={"memory_pressure_full_avg10": memory_pressure}, interpretation="Memory PSI is high and runnable tasks may be delayed on memory reclaim or allocation.", next_step="Correlate /proc/pressure/memory with kubelet eviction and OOM events.", supporting_evidence=["memory"]))
    return signals


def _process_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    process = _collector(collectors, "process")
    signals = []
    pid_usage = _number(process.get("pid_usage_percent"))
    if pid_usage is not None and pid_usage >= 80:
        signals.append(DiagnosticSignal(signal="pid_usage_high", component="process", severity="critical" if pid_usage >= 90 else "warning", observed={"pid_usage_percent": pid_usage}, interpretation="PID usage is high and may cause process creation failures or PIDPressure.", next_step="Identify process fan-out, per-service process counts, and zombie processes before remediation.", supporting_evidence=["process"]))
    zombie_count = _number(process.get("zombie_process_count"))
    if zombie_count is not None and zombie_count > 0:
        signals.append(DiagnosticSignal(signal="zombie_process_detected", component="process", severity="warning", observed={"zombie_process_count": zombie_count}, interpretation="Zombie processes exist, which may indicate parent reaping issues or runtime shim problems.", next_step="Inspect zombie parent processes and runtime shim state.", supporting_evidence=["process", "runtime"]))
    return signals


def _network_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    network = _collector(collectors, "network")
    conntrack = _collector(collectors, "conntrack") or _collector(network, "conntrack")
    signals = []
    interfaces_down = network.get("interfaces_down")
    if isinstance(interfaces_down, list) and interfaces_down:
        signals.append(DiagnosticSignal(signal="interface_down", component="network", severity="critical", observed={"interfaces_down": interfaces_down}, interpretation="One or more NICs are down and node connectivity may be impaired.", next_step="Check ip link, ethtool, driver logs, and switch port events.", supporting_evidence=["network"]))
    if network.get("nic_link_flap_detected") is True:
        signals.append(DiagnosticSignal(signal="nic_link_flap", component="network", severity="warning", observed={"nic_link_flap_detected": True}, interpretation="NIC carrier changes were detected and API server, etcd, or CNI communication may be unstable.", next_step="Correlate carrier changes, kernel NIC logs, switch events, and control-plane connection failures by time.", supporting_evidence=["network", "kernel"]))
    conntrack_usage = _number(_first_present(network.get("conntrack_usage_percent"), conntrack.get("usage_percent")))
    if conntrack.get("near_limit") is True or (conntrack_usage is not None and conntrack_usage >= 80):
        signals.append(DiagnosticSignal(signal="conntrack_near_limit", component="conntrack", severity="critical" if conntrack_usage is not None and conntrack_usage >= 90 else "warning", observed={"usage_percent": conntrack_usage, "count": conntrack.get("count"), "max": conntrack.get("max"), "available": conntrack.get("available")}, interpretation="The conntrack table is near its limit and DNS, Service, or API server connections may fail intermittently.", next_step="Check nf_conntrack_count/max, conntrack drops, and workloads causing connection spikes.", supporting_evidence=["network", "conntrack"]))
    rx_errors = _number(_first_present(network.get("physical_interface_rx_error_total"), network.get("interface_rx_error_total"))) or 0
    tx_errors = _number(_first_present(network.get("physical_interface_tx_error_total"), network.get("interface_tx_error_total"))) or 0
    rx_drops = _number(_first_present(network.get("physical_interface_rx_drop_total"), network.get("interface_rx_drop_total"))) or 0
    tx_drops = _number(_first_present(network.get("physical_interface_tx_drop_total"), network.get("interface_tx_drop_total"))) or 0
    packet_drop_threshold = 1000
    if rx_errors + tx_errors > 0 or rx_drops + tx_drops >= packet_drop_threshold:
        signals.append(DiagnosticSignal(signal="interface_packet_errors", component="network", severity="warning", observed={"physical_interfaces": network.get("physical_interfaces"), "rx_errors": rx_errors, "tx_errors": tx_errors, "rx_drops": rx_drops, "tx_drops": tx_drops, "drop_threshold": packet_drop_threshold}, interpretation="NIC errors or drops were detected and packet loss or driver/link trouble may be present.", next_step="Check /proc/net/dev, ethtool -S, and CNI overlay interface errors.", supporting_evidence=["network"]))
    retrans = _number(network.get("tcp_retrans_segments"))
    retrans_per_hour = _number(network.get("tcp_retrans_segments_per_hour_since_boot"))
    listen_overflows = _number(network.get("tcp_ext_listen_overflows"))
    listen_drops = _number(network.get("tcp_ext_listen_drops"))
    if (listen_overflows is not None and listen_overflows > 0) or (listen_drops is not None and listen_drops > 0) or (retrans_per_hour is not None and retrans_per_hour >= 50000):
        signals.append(DiagnosticSignal(signal="tcp_error_counters_high", component="network", severity="warning", observed={"tcp_retrans_segments": retrans, "tcp_retrans_segments_per_hour_since_boot": retrans_per_hour, "tcp_ext_listen_overflows": listen_overflows, "tcp_ext_listen_drops": listen_drops}, interpretation="TCP retransmit or listen overflow counters are high, indicating connection latency or backlog exhaustion.", next_step="Check /proc/net/snmp, /proc/net/netstat, affected service backlog settings, and upstream packet loss.", supporting_evidence=["network"]))
    dns_latency = _number(network.get("dns_lookup_latency_ms"))
    if dns_latency is not None and dns_latency >= 500:
        signals.append(DiagnosticSignal(signal="dns_latency_high", component="dns", severity="warning", observed={"dns_lookup_latency_ms": dns_latency}, interpretation="DNS lookup latency is high and can delay pod scheduling, image pulls, or service discovery.", next_step="Check CoreDNS latency, node resolver configuration, and upstream DNS health.", supporting_evidence=["network", "dns"]))
    return signals


def _cni_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    cni = _collector(collectors, "cni")
    signals = []
    parse_errors = cni.get("parse_errors")
    if isinstance(parse_errors, list) and parse_errors:
        signals.append(DiagnosticSignal(signal="cni_config_invalid", component="cni", severity="critical", observed=parse_errors[:10], interpretation="CNI configuration JSON parse errors were detected, so kubelet pod sandbox creation may fail.", next_step="Validate /etc/cni/net.d files and review recent CNI configuration changes.", supporting_evidence=["cni"]))
    if cni.get("plugin_errors_detected") is True:
        signals.append(DiagnosticSignal(signal="cni_plugin_error", component="cni", severity="critical", observed={"plugin_errors_detected": True}, interpretation="CNI plugin errors were detected and pod network attachment may fail.", next_step="Inspect CNI plugin logs and kubelet pod sandbox events.", supporting_evidence=["cni", "kubelet"]))
    mtu_values = cni.get("mtu_values")
    if isinstance(mtu_values, list) and len(set(mtu_values)) > 1:
        signals.append(DiagnosticSignal(signal="cni_mtu_values_inconsistent", component="cni", severity="warning", observed={"mtu_values": mtu_values}, interpretation="Multiple MTU values are present in CNI configuration, so overlay path MTU mismatch is possible.", next_step="Compare node NIC MTU, CNI MTU, pod path MTU, and overlay interface MTU together.", supporting_evidence=["cni", "network"]))
    return signals


def _dns_signals(collectors: dict[str, Any]) -> list[DiagnosticSignal]:
    dns = _collector(collectors, "dns")
    signals = []
    if dns and dns.get("dns_configured") is False:
        signals.append(DiagnosticSignal(signal="dns_unconfigured", component="dns", severity="critical", observed={"nameservers": dns.get("nameservers"), "resolv_conf_exists": dns.get("resolv_conf_exists")}, interpretation="The node resolver has no usable nameserver and DNS lookups may fail.", next_step="Check /etc/resolv.conf, node-local-dns, CoreDNS, and upstream DNS configuration.", supporting_evidence=["dns"]))
    attempts = _number(dns.get("attempts"))
    timeout_seconds = _number(dns.get("timeout_seconds"))
    if attempts is not None and timeout_seconds is not None and attempts * timeout_seconds >= 15:
        signals.append(DiagnosticSignal(signal="dns_resolver_timeout_budget_high", component="dns", severity="warning", observed={"attempts": attempts, "timeout_seconds": timeout_seconds}, interpretation="The resolver timeout budget is high and DNS failures may create long request delays.", next_step="Review resolv.conf options and CoreDNS timeout/retry policy.", supporting_evidence=["dns"]))
    return signals


def _build_candidates(
    alert_name: str,
    signals: list[DiagnosticSignal],
    collectors: dict[str, Any],
) -> list[RootCauseCandidate]:
    del collectors
    candidates: list[RootCauseCandidate] = []
    names = {signal.signal for signal in signals}
    if names & {"container_runtime_socket_unhealthy", "container_runtime_unit_unhealthy", "container_runtime_pid_not_running"}:
        candidates.append(_candidate("Container runtime hang, crash loop, or socket failure is disrupting kubelet runtime integration.", _confidence_for(names, {"container_runtime_socket_unhealthy", "container_runtime_unit_unhealthy"}), signals, {"container_runtime_socket_unhealthy", "container_runtime_unit_unhealthy", "container_runtime_pid_not_running"}))
    if names & {"containerd_socket_unhealthy", "containerd_unit_unhealthy", "containerd_pid_not_running"}:
        candidates.append(_candidate("containerd hang, crash loop, or socket failure is disrupting kubelet runtime integration.", _confidence_for(names, {"containerd_socket_unhealthy", "containerd_unit_unhealthy"}), signals, {"containerd_socket_unhealthy", "containerd_unit_unhealthy", "containerd_pid_not_running"}))
    if names & {"kubelet_unit_unhealthy", "kubelet_restarting"}:
        candidates.append(_candidate("kubelet unit failure or repeated restarts are making node status updates and pod lifecycle handling unstable.", _confidence_for(names, {"kubelet_unit_unhealthy", "kubelet_restarting"}), signals, {"kubelet_unit_unhealthy", "kubelet_restarting"}))
    if names & {"root_filesystem_read_only", "read_only_filesystem_detected", "kernel_io_error"}:
        candidates.append(_candidate("Storage or filesystem errors may be causing root filesystem write failures and kubelet/containerd disruption.", Confidence.HIGH, signals, {"root_filesystem_read_only", "read_only_filesystem_detected", "kernel_io_error"}))
    if names & {"disk_usage_critical", "inode_usage_critical", "io_pressure_high"}:
        candidates.append(_candidate("Disk capacity, inode exhaustion, or I/O pressure is likely causing kubelet eviction and runtime latency.", _confidence_for(names, {"disk_usage_critical", "inode_usage_critical"}), signals, {"disk_usage_critical", "inode_usage_critical", "io_pressure_high"}))
    if names & {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected", "memory_psi_high"}:
        candidates.append(_candidate("Node memory pressure or OOM activity may be preventing system daemons or workloads from running normally.", _confidence_for(names, {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected"}), signals, {"memory_pressure_critical", "oom_kill_detected", "kernel_oom_detected", "memory_psi_high"}))
    if names & {"pid_usage_high", "zombie_process_detected"}:
        candidates.append(_candidate("PID exhaustion or zombie process buildup may be preventing kubelet or runtime from spawning required processes.", _confidence_for(names, {"pid_usage_high"}), signals, {"pid_usage_high", "zombie_process_detected"}))
    if names & {"conntrack_near_limit", "interface_down", "nic_link_flap", "interface_packet_errors", "control_plane_peer_unreachable", "tcp_error_counters_high"}:
        candidates.append(_candidate("Node network path, NIC link instability, TCP errors, or conntrack exhaustion is making API Server, CNI, or DNS communication unstable.", _confidence_for(names, {"control_plane_peer_unreachable", "conntrack_near_limit", "interface_down"}), signals, {"control_plane_peer_unreachable", "conntrack_near_limit", "interface_down", "nic_link_flap", "interface_packet_errors", "tcp_error_counters_high"}))
    if names & {"cni_config_invalid", "cni_plugin_error", "cni_mtu_values_inconsistent", "cni_pod_restarting"}:
        candidates.append(_candidate("CNI configuration, plugin errors, or MTU mismatch may be breaking pod network attachment or node networking.", _confidence_for(names, {"cni_config_invalid", "cni_plugin_error", "cni_pod_restarting"}), signals, {"cni_config_invalid", "cni_plugin_error", "cni_mtu_values_inconsistent", "cni_pod_restarting"}))
    if names & {"dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high"}:
        candidates.append(_candidate("Node resolver, CoreDNS, or upstream DNS trouble may be delaying service discovery and control-plane communication.", _confidence_for(names, {"dns_unconfigured", "dns_latency_high"}), signals, {"dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high"}))
    if names & {"apiserver_readyz_failed", "kubernetes_api_unavailable", "node_metrics_unavailable"}:
        candidates.append(_candidate("Kubernetes API readiness or metrics path is unhealthy, so controllers and operators may see stale or missing node state.", _confidence_for(names, {"apiserver_readyz_failed", "kubernetes_api_unavailable"}), signals, {"apiserver_readyz_failed", "kubernetes_api_unavailable", "node_metrics_unavailable"}))
    if names & {"systemd_failed_units"}:
        candidates.append(_candidate("Failed systemd units may be contributing to node-level service degradation.", Confidence.MEDIUM, signals, {"systemd_failed_units"}))
    if not candidates and alert_name in {"NodeNotReady", "NetworkUnavailable", "DiskPressure", "MemoryPressure", "PIDPressure"}:
        candidates.append(RootCauseCandidate(cause="Current evidence is insufficient to isolate a single root cause; additional logs and time-correlated metrics are required.", confidence=Confidence.LOW, supporting_evidence=list(_collector_names_for_alert(alert_name))))
    return candidates


def _fallback_candidates(alert_name: str, collectors: dict[str, Any]) -> list[RootCauseCandidate]:
    if alert_name == "NodeNotReady":
        return [RootCauseCandidate(cause="kubelet, runtime, systemd, or node network evidence is required to explain the NotReady transition.", confidence=Confidence.LOW, supporting_evidence=["systemd", "runtime", "network", "kubernetes"])]
    if alert_name == "DiskPressure":
        return [RootCauseCandidate(cause="Disk capacity, inode, mount, filesystem, or I/O pressure evidence is required.", confidence=Confidence.LOW, supporting_evidence=["disk", "inode", "kernel"])]
    if alert_name == "MemoryPressure":
        return [RootCauseCandidate(cause="Memory usage, OOM, swap, PSI, and process evidence is required.", confidence=Confidence.LOW, supporting_evidence=["memory", "process", "kernel"])]
    if alert_name == "PIDPressure":
        return [RootCauseCandidate(cause="PID usage, process fan-out, zombie process, and runtime shim evidence is required.", confidence=Confidence.LOW, supporting_evidence=["process", "systemd", "runtime"])]
    if alert_name == "NetworkUnavailable":
        return [RootCauseCandidate(cause="NIC, route, conntrack, DNS, CNI, and control-plane connectivity evidence is required.", confidence=Confidence.LOW, supporting_evidence=["network", "conntrack", "dns", "cni", "kubernetes"])]
    return [RootCauseCandidate(cause="No dedicated rule matched the alert type, or the core evidence is insufficient; classify as a generic infrastructure incident for now.", confidence=Confidence.LOW, supporting_evidence=list(collectors.keys()))]


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
        candidates.append(RootCauseCandidate(cause=f"LLM analysis: {cause}", confidence=_confidence_enum(raw_candidate.get("confidence")), supporting_evidence=[str(item) for item in supporting_evidence if item is not None]))
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
        most_likely_cause = "Additional evidence is required to analyze the cluster infrastructure incident."
        confidence = Confidence.LOW
    if critical_count >= 2 and confidence != Confidence.HIGH:
        confidence = Confidence.MEDIUM
    symptom = f"{evidence.alert_name} was reported on node {evidence.node_name}."
    if critical_count or warning_count:
        symptom = f"{symptom} Rule analysis found {critical_count} critical signal(s) and {warning_count} warning signal(s)."
    return RcaSummary(symptom=symptom, most_likely_cause=most_likely_cause, confidence=confidence)


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
    if names & {"systemd_failed_units"} or alert_name in {"NodeNotReady", "PIDPressure"}:
        items.append({"component": "systemd", "check": "Check failed units and dependency failures", "command": "systemctl --failed --no-pager && systemctl list-units --state=failed --no-pager"})
    if names & {"kubelet_unit_unhealthy", "kubelet_restarting", "node_not_ready_condition"} or alert_name in {"NodeNotReady", "KubeletDown"}:
        items.append({"component": "kubelet", "check": "Check kubelet unit state, restart history, and node condition messages", "command": "systemctl status kubelet --no-pager && journalctl -u kubelet -n 200 --no-pager && kubectl describe node ${NODE_NAME:-$(hostname)} 2>/dev/null || true"})
    if names & {"container_runtime_socket_unhealthy", "container_runtime_unit_unhealthy", "container_runtime_pid_not_running"}:
        items.append({"component": "container-runtime", "check": "Check runtime sockets, unit state, pids, and recent journal lines", "command": "systemctl status containerd crio cri-docker docker --no-pager || true; journalctl -u containerd -u crio -u docker -n 200 --no-pager || true"})
    if names & {"containerd_socket_unhealthy", "containerd_unit_unhealthy", "containerd_pid_not_running"}:
        items.append({"component": "containerd", "check": "Check containerd socket, unit, pid, and recent journal lines", "command": "systemctl status containerd --no-pager && journalctl -u containerd -n 200 --no-pager"})
    if names & {"disk_usage_critical", "disk_usage_high", "inode_usage_critical", "inode_usage_high"}:
        items.append({"component": "disk", "check": "Confirm disk and inode pressure by mountpoint", "command": "for p in / /var /var/log /var/lib/containerd /var/lib/kubelet; do [ -e \"$p\" ] && df -hT \"$p\" && df -ih \"$p\"; done"})
        items.append({"component": "runtime-storage", "check": "Find large runtime, kubelet, and log directories without crossing filesystems", "command": "du -xhd1 /var /var/log /var/lib/containerd /var/lib/kubelet 2>/dev/null | sort -h"})
    if names & {"inode_usage_critical", "inode_usage_high"}:
        items.append({"component": "inode", "check": "Find directories with unusually high file counts", "command": "find /var /var/log /var/lib/containerd /var/lib/kubelet -xdev -printf '%h\\n' 2>/dev/null | sort | uniq -c | sort -nr | head -30"})
    if names & {"kernel_io_error", "root_filesystem_read_only", "read_only_filesystem_detected", "blocked_task_detected", "kernel_tainted"}:
        items.append({"component": "kernel", "check": "Check block device, filesystem, blocked task, taint, and read-only remount errors", "command": "dmesg -T --level=err,warn | tail -200 && journalctl -k -n 200 --no-pager && cat /proc/sys/kernel/tainted 2>/dev/null || true"})
    if names & {"memory_pressure_critical", "memory_pressure_high", "oom_kill_detected", "kernel_oom_detected", "memory_psi_high", "swap_usage_high"}:
        items.append({"component": "memory", "check": "Check memory pressure, swap pressure, PSI, and recent OOM victims", "command": "cat /proc/meminfo && cat /proc/pressure/memory 2>/dev/null || true; dmesg -T | grep -Ei 'out of memory|oom|killed process' | tail -50"})
    if names & {"pid_usage_high", "zombie_process_detected"}:
        items.append({"component": "process", "check": "Check PID pressure, process fan-out, and zombie parents", "command": "cat /proc/sys/kernel/pid_max && ps -eo pid,ppid,stat,comm --sort=ppid | awk '$3 ~ /Z/ || NR==1 {print}' | head -100 && ps -eo user= | sort | uniq -c | sort -nr | head -20"})
    if names & {"conntrack_near_limit", "interface_down", "nic_link_flap", "interface_packet_errors", "control_plane_peer_unreachable", "tcp_error_counters_high", "kernel_nic_error"}:
        items.append({"component": "network", "check": "Check NIC, route, socket, TCP, and conntrack state", "command": "ip -s link && ip route && ss -s && ss -ltn && cat /proc/net/snmp && cat /proc/net/netstat && cat /proc/sys/net/netfilter/nf_conntrack_count 2>/dev/null && cat /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null"})
    if names & {"cni_config_invalid", "cni_plugin_error", "cni_mtu_values_inconsistent", "cni_pod_restarting"}:
        items.append({"component": "cni", "check": "Check CNI config, MTU settings, plugin logs, and kube-system pods", "command": "find /etc/cni/net.d -maxdepth 1 -type f -print -exec sed -n '1,160p' {} \\; && ip link show && kubectl -n kube-system get pods -o wide 2>/dev/null || true"})
    if names & {"apiserver_readyz_failed", "kubernetes_api_unavailable", "node_metrics_unavailable", "node_certificate_expiring"}:
        items.append({"component": "kubernetes", "check": "Check Kubernetes API readiness, metrics path, certificate warnings, and node events", "command": "kubectl get --raw='/readyz?verbose' 2>/dev/null || true; kubectl top nodes 2>/dev/null || true; kubectl get events -A --sort-by=.lastTimestamp 2>/dev/null | tail -100"})
    if names & {"dns_unconfigured", "dns_latency_high", "dns_resolver_timeout_budget_high"}:
        items.append({"component": "dns", "check": "Check node resolver path, timeout budget, and CoreDNS pods", "command": "cat /etc/resolv.conf && kubectl -n kube-system get pods -l k8s-app=kube-dns -o wide 2>/dev/null || true"})
    if not items:
        items.append({"component": "node", "check": "Evidence is insufficient; check failed units and kernel errors first", "command": "systemctl --failed --no-pager && dmesg -T --level=err,warn | tail -200"})
    return items


def _scope_components(alert_name: str, signals: list[DiagnosticSignal]) -> list[str]:
    components = set(_collector_names_for_alert(alert_name))
    components.update(signal.component for signal in signals)
    return sorted(components)


def _collector_names_for_alert(alert_name: str) -> list[str]:
    if alert_name == "NodeNotReady":
        return ["kubernetes", "kubelet", "runtime", "systemd", "network", "kernel"]
    if alert_name == "DiskPressure":
        return ["disk", "inode", "kernel", "runtime"]
    if alert_name == "MemoryPressure":
        return ["memory", "process", "kernel", "kubelet"]
    if alert_name == "PIDPressure":
        return ["process", "systemd", "runtime", "kernel"]
    if alert_name == "NetworkUnavailable":
        return ["kubernetes", "network", "cni", "dns", "conntrack", "kernel"]
    if alert_name in {"ContainerdDown", "ContainerRuntimeUnhealthy"}:
        return ["runtime", "containerd", "systemd", "kernel", "disk"]
    if alert_name in {"KubeletDown", "KubeletUnhealthy"}:
        return ["kubelet", "systemd", "runtime", "kernel", "network"]
    if alert_name in {"CNIUnavailable", "CNIError", "CNIMTUProblem"}:
        return ["cni", "network", "kubelet", "kubernetes"]
    if alert_name in {"CoreDNSDown", "DNSLatencyHigh", "DNSUnavailable"}:
        return ["dns", "kubernetes", "network", "cni"]
    if alert_name in {"EtcdLatencyHigh", "APIServerLatencyHigh", "APIServerDown"}:
        return ["kubernetes", "network", "disk", "kernel", "systemd"]
    return ["node", "systemd", "kernel", "network"]


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
        interpretation="Kernel I/O errors were detected, so disk, filesystem, or storage path failure is likely.",
        next_step="Check dmesg, filesystem state, cloud or storage events, and node disk health.",
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


def _optional_bad_unit_state(active_state: Any, sub_state: Any) -> bool:
    active = str(active_state or "").lower()
    sub = str(sub_state or "").lower()
    return active in {"failed", "restarting", "deactivating"} or sub in {"failed", "auto-restart"}


def _runtime_kind_for_unit(unit_name: str) -> str:
    lowered = unit_name.lower()
    if "crio" in lowered:
        return "crio"
    if "cri-docker" in lowered:
        return "cri-dockerd"
    if "docker" in lowered:
        return "docker"
    if "containerd" in lowered:
        return "containerd"
    return "container-runtime"


def _healthy_unit_state(active_state: Any, sub_state: Any) -> bool:
    active = str(active_state or "").lower()
    sub = str(sub_state or "").lower()
    return active == "active" and sub in {"running", "exited", ""}


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
