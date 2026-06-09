from __future__ import annotations

from backend.app.models import (
    Confidence,
    EvidenceBundle,
    RcaJobStatus,
    RcaReport,
    RcaSummary,
    RootCauseCandidate,
)
from backend.app.services.policy import PolicyEngine


class RuleBasedRcaAnalyzer:
    def __init__(self, policy_engine: PolicyEngine) -> None:
        self._policy_engine = policy_engine

    def analyze(self, report_id: str, evidence: EvidenceBundle) -> RcaReport:
        collectors = evidence.collectors
        alert_name = evidence.alert_name
        candidates: list[RootCauseCandidate] = []
        evidence_findings: list[dict[str, object]] = []

        for collector_name, finding in collectors.items():
            evidence_findings.append({"collector": collector_name, "finding": finding})

        most_likely_cause = "추가 증거 수집이 필요한 클러스터 인프라 장애입니다."
        confidence = Confidence.LOW

        if alert_name == "NodeNotReady":
            most_likely_cause = "containerd socket 응답 지연과 kubelet 반복 재시작으로 노드 상태 갱신이 실패했을 가능성이 큽니다."
            confidence = Confidence.MEDIUM
            candidates.extend(
                [
                    RootCauseCandidate(
                        cause="containerd hang 또는 socket 응답 지연으로 kubelet이 정상 상태를 보고하지 못함",
                        confidence=Confidence.MEDIUM,
                        supporting_evidence=["runtime", "systemd"],
                    ),
                    RootCauseCandidate(
                        cause="conntrack table 사용률 증가로 노드와 API Server 간 통신이 불안정해짐",
                        confidence=Confidence.MEDIUM,
                        supporting_evidence=["network"],
                    ),
                ]
            )
        elif alert_name == "DiskPressure":
            most_likely_cause = "inode 고갈과 디스크 I/O 대기 증가로 kubelet eviction pressure가 발생했을 가능성이 큽니다."
            confidence = Confidence.HIGH
            candidates.append(
                RootCauseCandidate(
                    cause="inode 사용률이 임계치에 근접해 노드 디스크 pressure가 발생함",
                    confidence=Confidence.HIGH,
                    supporting_evidence=["disk"],
                )
            )
        elif alert_name == "MemoryPressure":
            most_likely_cause = "노드 메모리 사용률과 OOM kill 이벤트 증가로 MemoryPressure가 발생했습니다."
            confidence = Confidence.HIGH
            candidates.append(
                RootCauseCandidate(
                    cause="시스템 메모리 고갈 또는 특정 프로세스 메모리 폭주",
                    confidence=Confidence.HIGH,
                    supporting_evidence=["memory"],
                )
            )
        elif alert_name == "NetworkUnavailable":
            most_likely_cause = "NIC link flap, DNS 지연, CNI plugin error가 결합된 노드 네트워크 장애 가능성이 있습니다."
            confidence = Confidence.MEDIUM
            candidates.append(
                RootCauseCandidate(
                    cause="노드 네트워크 경로 또는 CNI 계층의 불안정",
                    confidence=Confidence.MEDIUM,
                    supporting_evidence=["network", "cni"],
                )
            )
        elif alert_name in {"KubeletDown", "KubeletUnhealthy"}:
            most_likely_cause = "kubelet systemd unit 실패 또는 반복 재시작이 감지되었습니다."
            confidence = Confidence.MEDIUM
            candidates.append(
                RootCauseCandidate(
                    cause="kubelet deadlock, 설정 문제, 또는 API Server 연결 장애",
                    confidence=Confidence.MEDIUM,
                    supporting_evidence=["systemd"],
                )
            )
        elif alert_name in {"ContainerdDown", "ContainerRuntimeUnhealthy"}:
            most_likely_cause = "containerd systemd unit 실패와 runtime socket 비정상이 감지되었습니다."
            confidence = Confidence.MEDIUM
            candidates.append(
                RootCauseCandidate(
                    cause="containerd hang, shim 문제, 또는 runtime socket 장애",
                    confidence=Confidence.MEDIUM,
                    supporting_evidence=["runtime", "systemd"],
                )
            )

        if not candidates:
            candidates.append(
                RootCauseCandidate(
                    cause="알림 유형에 대한 전용 rule이 없어 일반 인프라 장애로 분류됨",
                    confidence=Confidence.LOW,
                    supporting_evidence=list(collectors.keys()),
                )
            )

        recommended_actions = [
            self._policy_engine.classify(
                "collect_more_evidence",
                "장애 시간대의 kubelet, containerd, kernel journal을 추가 수집합니다.",
                "읽기 전용 증거 수집이며 서비스 상태를 변경하지 않습니다.",
            ),
            self._policy_engine.classify(
                "restart_kubelet",
                "노드가 계속 NotReady이면 운영자 승인 후 kubelet 재시작을 검토합니다.",
                "노드 상태 회복에 도움이 될 수 있지만 workload 영향이 있어 승인이 필요합니다.",
            ),
            self._policy_engine.classify(
                "open_gitops_pr",
                "conntrack, CNI MTU, CoreDNS 설정 변경은 GitOps PR로만 제안합니다.",
                "클러스터 설정 변경은 직접 실행하지 않고 리뷰 가능한 PR 흐름을 사용합니다.",
            ),
        ]

        return RcaReport(
            report_id=report_id,
            cluster_id=evidence.cluster_id,
            status=RcaJobStatus.COMPLETED,
            trigger={
                "source": "alertmanager",
                "alert_name": alert_name,
            },
            scope={
                "nodes": [evidence.node_name],
                "components": self._components_for(alert_name),
            },
            summary=RcaSummary(
                symptom=f"{evidence.node_name}에서 {alert_name} 알림이 발생했습니다.",
                most_likely_cause=most_likely_cause,
                confidence=confidence,
            ),
            evidence=evidence_findings,
            root_cause_candidates=candidates,
            recommended_actions=recommended_actions,
            policy_decisions=recommended_actions,
        )

    def _components_for(self, alert_name: str) -> list[str]:
        if alert_name == "NodeNotReady":
            return ["kubelet", "containerd", "network"]
        if alert_name == "DiskPressure":
            return ["disk", "inode", "kernel"]
        if alert_name == "MemoryPressure":
            return ["memory", "pid", "kernel"]
        if alert_name == "NetworkUnavailable":
            return ["network", "cni", "dns", "conntrack"]
        return ["node", "systemd", "kernel"]

