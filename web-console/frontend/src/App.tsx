// @ts-nocheck
import React from "react";
import { createRoot } from "react-dom/client";
import "bootstrap/dist/css/bootstrap.min.css";
import "bootstrap-icons/font/bootstrap-icons.css";
import "./styles.css";

  const rootElement = document.getElementById("rca-console-root");
  const h = React.createElement;
  const apiBase = "";
  const publicApiBase = window.location.origin;
  const LANGUAGE_STORAGE_KEY = "rca_console_language";
  const views = [
    { id: "overview", label: "Overview", icon: "speedometer2" },
    { id: "clusters", label: "Clusters", icon: "hdd-network" },
    { id: "webhooks", label: "Webhooks", icon: "diagram-3" },
    { id: "incidents", label: "Incidents", icon: "exclamation-diamond" },
    { id: "pipeline", label: "Pipeline", icon: "list-task" },
    { id: "reports", label: "Reports", icon: "clipboard2-pulse" },
    { id: "demo", label: "Demo Scenarios", icon: "play-circle" },
    { id: "audit", label: "Audit", icon: "journal-check" },
    { id: "settings", label: "Settings", icon: "sliders" },
  ];
  let activeLocale = normalizeLocale(localStorage.getItem(LANGUAGE_STORAGE_KEY));

  const translations = {
    ko: {
      "Pipeline": "분석 파이프라인",
      "Analysis Tasks": "분석 작업",
      "Attempt": "시도",
      "Next attempt": "다음 시도",
      "Last error": "최근 오류",
      "Retry task": "작업 재시도",
      "No analysis tasks loaded.": "분석 작업이 없습니다.",
      "Incidents": "인시던트",
      "Incident": "인시던트",
      "Audit": "감사 로그",
      "Actor": "행위자",
      "Event": "이벤트",
      "Resource": "대상",
      "Outcome": "결과",
      "Root Cause": "근본 원인",
      "Root trigger": "최초 원인 신호",
      "Causal inference": "인과 추론",
      "Observed sequence": "관측 순서",
      "observed next in the incident window": "인시던트 구간에서 다음으로 관측됨",
      "Occurrences": "발생 횟수",
      "Recurrence": "재발",
      "Previous incident": "이전 인시던트",
      "Resolved at": "종료 시각",
      "Resolution source": "종료 주체",
      "Action History": "조치 이력",
      "Approval and execution attempts": "승인 및 실행 요청 이력",
      "Approval and manual handling history": "승인 및 수동 처리 이력",
      "Approve for Manual Handling": "수동 처리 승인",
      "Approve this request for human-operated handling? The platform and agent will not execute it.": "사람이 직접 처리하도록 승인할까요? 플랫폼과 Agent는 조치를 실행하지 않습니다.",
      "Mark Manually Completed": "수동 처리 완료",
      "Mark this approved request as manually completed?": "승인된 요청을 수동 처리 완료로 표시할까요?",
      "Manual completion recorded.": "수동 처리 완료를 기록했습니다.",
      "Collect Evidence": "근거 수집",
      "Runbook / GitOps guidance": "Runbook / GitOps 안내",
      "Commands are guidance only and are never executed by the platform or agent.": "명령은 안내용이며 플랫폼이나 Agent가 실행하지 않습니다.",
      "Observed Services": "관찰된 Service",
      "Service relationship unverified": "Service 영향 관계 미검증",
      "No action requests.": "조치 요청 이력이 없습니다.",
      "No incidents loaded.": "등록된 인시던트가 없습니다.",
      "No audit events loaded.": "감사 이벤트가 없습니다.",
      "Approve": "승인",
      "Reject": "거절",
      "Requested by": "요청자",
      "Reviewed by": "검토자",
      "Resolve": "해결 처리",
      "Reopen": "다시 열기",
      "Overview": "개요",
      "Clusters": "클러스터",
      "Webhooks": "웹훅",
      "Reports": "보고서",
      "Settings": "설정",
      "Operations Console": "운영 콘솔",
      "Cluster Infrastructure RCA": "클러스터 인프라 RCA",
      "Checking session": "세션 확인 중",
      "Please wait.": "잠시만 기다려주세요.",
      "Sign in with the administrator account. The initial account is admin / admin.": "관리자 계정으로 로그인하세요. 초기 계정은 admin / admin 입니다.",
      "Account": "계정",
      "Password": "비밀번호",
      "Login": "로그인",
      "Logout": "로그아웃",
      "Refresh": "새로고침",
      "Reload": "다시 불러오기",
      "Auto": "자동",
      "Manual": "수동",
      "Not refreshed": "아직 새로고침 안 됨",
      "Language": "언어",
      "English": "영어",
      "Korean": "한국어",
      "RCA Reports": "RCA 보고서",
      "Root Cause Candidates": "원인 후보",
      "Evidence Signals": "근거 신호",
      "Additional Checks": "추가 확인 명령",
      "Recommended Actions": "권장 조치",
      "Policy Engine": "정책 엔진",
      "Rule gate before any action request": "조치 요청 전 Rule 기반 정책 검증",
      "Rule-based candidates first, LLM candidates only as supporting context": "Rule 기반 후보를 우선 표시하고 LLM 후보는 보조 근거로만 사용",
      "Read-only commands to verify the candidate cause": "원인 후보를 검증하기 위한 읽기 전용 명령",
      "Policy Engine decides whether an action can be automated": "정책 엔진이 조치 자동화 가능 여부를 판단합니다",
      "Policy keeps automation_allowed=false for every LLM-origin action.": "LLM 출처 조치는 항상 automation_allowed=false로 유지됩니다.",
      "LLM suggestion only. automation_allowed=false until a rule or operator explicitly approves it.": "LLM 제안은 참고용입니다. Rule 또는 운영자 승인 전까지 automation_allowed=false 입니다.",
      "LLM suggestion only. It cannot become executable and must remain diagnostic context.": "LLM 제안은 실행 가능 상태가 될 수 없으며 진단 참고 정보로만 사용합니다.",
      "Risk reasons": "위험 사유",
      "Guardrails": "가드레일",
      "No policy risk factors.": "정책 위험 사유 없음",
      "No guardrails triggered.": "트리거된 가드레일 없음",
      "Read-only rule-based collection or verification.": "읽기 전용 Rule 기반 수집 또는 검증",
      "Node or service state may change. Operator approval is required.": "노드 또는 서비스 상태 변경 가능성이 있어 운영자 승인이 필요합니다.",
      "Configuration change. Propose through a reviewable PR only.": "설정 변경은 리뷰 가능한 PR로만 제안합니다.",
      "Prohibited for automation. Human decision only.": "자동화 금지 대상이며 사람의 판단만 허용됩니다.",
      "Needs manual investigation or external validation.": "수동 조사 또는 외부 검증이 필요합니다.",
      "Unclassified policy decision.": "분류되지 않은 정책 결정입니다.",
      "Read-only rule-based action requests": "읽기 전용 Rule 기반 조치 요청",
      "Needs review, approval, PR, or manual handling": "리뷰, 승인, PR 또는 수동 처리가 필요합니다",
      "Console Settings": "콘솔 설정",
      "Runtime references": "런타임 참조",
      "Change Password": "비밀번호 변경",
      "Change the current administrator password": "현재 관리자 계정의 비밀번호를 변경합니다",
      "Current password": "현재 비밀번호",
      "New password": "새 비밀번호",
      "Confirm password": "비밀번호 확인",
      "Update password": "비밀번호 변경",
      "Language preference is saved in this browser.": "언어 설정은 이 브라우저에 저장됩니다.",
      "Language changed.": "언어가 변경되었습니다.",
      "Name": "이름",
      "Agents": "에이전트",
      "Access": "접근",
      "Session": "세션",
      "Webhook": "웹훅",
      "Platform API": "플랫폼 API",
      "same origin": "동일 출처",
      "Public API": "Public API",
      "Signed in": "로그인 계정",
      "Role": "역할",
      "Refresh mode": "새로고침 모드",
      "Webhook token env": "웹훅 토큰 환경 변수",
      "LLM provider env": "LLM provider 환경 변수",
      "Database env": "데이터베이스 환경 변수",
      "Platform version": "플랫폼 버전",
      "API version": "API 버전",
      "Agent protocol": "에이전트 프로토콜",
      "Minimum agent version": "최소 에이전트 버전",
      "Webhook endpoint copied.": "웹훅 엔드포인트를 복사했습니다.",
      "Receiver sample copied.": "Receiver 예시를 복사했습니다.",
      "Install command copied.": "설치 명령을 복사했습니다.",
      "Last refresh": "마지막 새로고침",
      "Loading": "불러오는 중",
      "Registered targets": "등록된 대상",
      "high confidence": "높은 신뢰도",
      "HttpOnly session": "HttpOnly 세션",
      "Cluster Snapshot": "클러스터 스냅샷",
      "Cluster Topology": "클러스터 토폴로지",
      "Topology Relationship Graph": "토폴로지 관계 그래프",
      "Service to node endpoint and selector relationships": "Service와 노드 사이의 endpoint 및 selector 관계",
      "No topology relationships observed.": "관측된 토폴로지 관계가 없습니다.",
      "Endpoint relationship": "Endpoint 관계",
      "Selector-derived relationship": "Selector 기반 관계",
      "Graph is limited to the most connected resources.": "그래프는 연결이 많은 주요 리소스만 표시합니다.",
      "Pods": "파드",
      "Services": "서비스",
      "Relations": "관계",
      "Not observed": "관측되지 않음",
      "Cluster-wide Service and EndpointSlice inventory is complete.": "클러스터 전체 Service 및 EndpointSlice 인벤토리 수집이 완료되었습니다.",
      "Topology is partial until the elected agent collects Service and EndpointSlice inventory.": "선정된 Agent가 Service 및 EndpointSlice 인벤토리를 수집하기 전까지 토폴로지는 일부 정보만 표시됩니다.",
      "Latest registered clusters": "최근 등록된 클러스터",
      "Open": "열기",
      "Recent Reports": "최근 보고서",
      "Root cause candidates": "원인 후보",
      "No reports loaded.": "불러온 보고서가 없습니다.",
      "Cluster Onboarding": "클러스터 온보딩",
      "Register once, then install the node agent": "한 번 등록한 뒤 노드 에이전트를 설치합니다",
      "Register": "등록",
      "Create a cluster id and bootstrap token.": "cluster id와 bootstrap token을 생성합니다.",
      "Install": "설치",
      "Run the generated kubectl command.": "생성된 kubectl 명령을 실행합니다.",
      "Verify": "검증",
      "Check node agents after DaemonSet rollout.": "DaemonSet 배포 후 노드 에이전트를 확인합니다.",
      "Cluster name": "클러스터 이름",
      "Platform API URL for agents": "에이전트용 Platform API URL",
      "Agents and kubectl will use this platform API URL.": "에이전트와 kubectl이 이 Platform API URL을 사용합니다.",
      "Enter the platform API URL reachable from your kubectl workstation and cluster nodes.": "kubectl 작업 PC와 클러스터 노드에서 접근 가능한 Platform API URL을 입력하세요.",
      "Description": "설명",
      "Optional note for operators": "운영자용 선택 메모",
      "Register and show install command": "등록 후 설치 명령 표시",
      "Registered Clusters": "등록된 클러스터",
      "clusters": "개 클러스터",
      "Cluster": "클러스터",
      "Environment": "환경",
      "Status": "상태",
      "Actions": "작업",
      "Data": "데이터",
      "Collect": "수집",
      "Agent install command": "에이전트 설치 명령",
      "Run this from a workstation with kubectl access to the target cluster.": "대상 클러스터에 kubectl 접근이 가능한 작업 PC에서 실행하세요.",
      "Copy": "복사",
      "Namespace and secret are created first.": "Namespace와 Secret을 먼저 생성합니다.",
      "DaemonSet is applied from the generated manifest URL.": "생성된 manifest URL에서 DaemonSet을 적용합니다.",
      "Click Agents after rollout to confirm node registration.": "배포 후 Agents를 눌러 노드 등록을 확인합니다.",
      "Webhook Endpoint": "웹훅 엔드포인트",
      "Alertmanager integration": "Alertmanager 연동",
      "Endpoint": "엔드포인트",
      "Authorization": "인증",
      "Alertmanager Receiver": "Alertmanager Receiver",
      "YAML sample": "YAML 예시",
      "Symptom": "증상",
      "Policy": "정책",
      "Hide": "숨기기",
      "Detail": "상세",
      "Export": "내보내기",
      "Export all": "전체 내보내기",
      "Report summary copied.": "보고서 요약을 복사했습니다.",
      "Report export downloaded.": "보고서 export 파일을 다운로드했습니다.",
      "Reports export downloaded.": "보고서 export 파일을 다운로드했습니다.",
      "Cluster reports export downloaded.": "클러스터 보고서 export 파일을 다운로드했습니다.",
      "No registered clusters loaded.": "불러온 등록 클러스터가 없습니다.",
      "No clusters loaded.": "불러온 클러스터가 없습니다.",
      "Loading agents.": "에이전트를 불러오는 중입니다.",
      "No agents registered.": "등록된 에이전트가 없습니다.",
      "Node": "노드",
      "Version": "버전",
      "Last seen": "마지막 확인",
      "Loading cluster data.": "클러스터 데이터를 불러오는 중입니다.",
      "Evidence requests": "근거 수집 요청",
      "Node Agents": "노드 에이전트",
      "Evidence Requests": "근거 수집 요청",
      "Collected Evidence": "수집된 근거",
      "Evidence bundle copied.": "근거 번들을 복사했습니다.",
      "Recent RCA": "최근 RCA",
      "Cluster RCA reports copied.": "클러스터 RCA 보고서를 복사했습니다.",
      "Close": "닫기",
      "Evidence": "근거",
      "Alert": "알림",
      "Collectors": "수집기",
      "No evidence requests.": "근거 수집 요청이 없습니다.",
      "Request": "요청",
      "Created": "생성 시간",
      "View": "보기",
      "Loading evidence bundle.": "근거 번들을 불러오는 중입니다.",
      "Select a completed evidence request.": "완료된 근거 수집 요청을 선택하세요.",
      "No RCA reports for this cluster.": "이 클러스터의 RCA 보고서가 없습니다.",
      "Confirm Action": "조치 확인",
      "No reason": "사유 없음",
      "This will request read-only follow-up evidence from the node agent.": "노드 에이전트에 읽기 전용 추가 근거 수집을 요청합니다.",
      "The policy gate will record the request status without direct node mutation.": "정책 게이트는 직접 노드 변경 없이 요청 상태만 기록합니다.",
      "Cancel": "취소",
      "Processing": "처리 중",
      "Confirm": "확인",
      "Confirm Collection": "수집 확인",
      "Cluster collection": "클러스터 수집",
      "Backend will create read-only evidence requests for registered online node agents. Submitted evidence will be analyzed by the existing RCA pipeline.": "백엔드는 등록된 온라인 노드 에이전트에 읽기 전용 근거 수집 요청을 생성합니다. 제출된 근거는 기존 RCA 파이프라인으로 분석됩니다.",
      "No Prometheus or Alertmanager trigger is required.": "Prometheus 또는 Alertmanager 트리거가 없어도 됩니다.",
      "Delete": "삭제",
      "Deleting": "삭제 중",
      "Confirm Delete": "삭제 확인",
      "Delete cluster": "클러스터 삭제",
      "Type the cluster name to confirm deletion.": "삭제하려면 클러스터 이름을 입력하세요.",
      "This removes the cluster registration and all stored agents, evidence requests, evidence bundles, RCA jobs, and reports from the platform.": "플랫폼에서 클러스터 등록 정보와 저장된 agent, evidence request, evidence bundle, RCA job, report를 모두 삭제합니다.",
      "Agent DaemonSets in target clusters are not removed automatically.": "대상 클러스터에 배포된 agent DaemonSet은 자동으로 제거되지 않습니다.",
      "Confirmation does not match the cluster name.": "확인 값이 클러스터 이름과 일치하지 않습니다.",
      "Cluster deleted.": "클러스터를 삭제했습니다.",
      "Requesting": "요청 중",
      "Loading report detail.": "보고서 상세를 불러오는 중입니다.",
      "No policy decisions.": "정책 결정이 없습니다.",
      "No items.": "항목이 없습니다.",
      "No actions.": "조치가 없습니다.",
      "No signals.": "신호가 없습니다.",
      "Next:": "다음:",
      "No checklist.": "체크리스트가 없습니다.",
      "Command copied.": "명령을 복사했습니다.",
      "Read-only verification": "읽기 전용 검증",
      "Report": "보고서",
      "Nodes": "노드",
      "Automation": "자동화",
      "Components": "구성 요소",
      "Policies": "정책",
      "Provider": "제공자",
      "allowed": "허용",
      "gated": "차단",
      "action": "조치",
      "derived signals": "파생 신호",
      "mode": "모드",
      "approval": "승인",
      "review": "리뷰",
      "key": "키",
      "required": "필요",
      "not required": "불필요",
      "Execute": "실행",
      "PR Gate": "PR 게이트",
      "Blocked": "차단",
      "Review": "검토",
      "Unknown cause": "알 수 없는 원인",
      "Unknown symptom": "알 수 없는 증상",
      "n/a": "없음",
      "unknown": "알 수 없음",
      "manual": "수동",
      "read_only": "읽기 전용",
      "operator_approval": "운영자 승인",
      "gitops_pr": "GitOps PR",
      "prohibited": "금지",
      "completed": "완료",
      "skipped": "건너뜀",
      "failed": "실패",
      "active": "활성",
      "registered": "등록됨",
      "agent_pending": "에이전트 대기",
      "healthy": "정상",
      "offline": "오프라인",
      "degraded": "저하",
      "high": "높음",
      "medium": "중간",
      "low": "낮음",
      "critical": "심각",
      "warning": "경고",
      "rule_based": "Rule 기반",
      "llm_suggestion": "LLM 제안",
      "automation_allowed": "자동화 허용",
      "automation_blocked": "자동화 차단",
      "llm_auto_blocked": "LLM 자동화 차단",
      "Current evidence is insufficient to isolate a single root cause; additional logs and time-correlated metrics are required.": "현재 근거만으로 단일 원인을 분리하기 어렵습니다. 추가 로그와 시간 기준으로 맞춘 메트릭이 필요합니다.",
      "Container runtime hang, crash loop, or socket failure is disrupting kubelet runtime integration.": "컨테이너 런타임 hang, crash loop 또는 socket 장애가 kubelet 런타임 연동을 방해하고 있습니다.",
      "containerd hang, crash loop, or socket failure is disrupting kubelet runtime integration.": "containerd hang, crash loop 또는 socket 장애가 kubelet 런타임 연동을 방해하고 있습니다.",
      "kubelet unit failure or repeated restarts are making node status updates and pod lifecycle handling unstable.": "kubelet unit 장애 또는 반복 재시작으로 노드 상태 갱신과 Pod lifecycle 처리가 불안정합니다.",
      "Storage or filesystem errors may be causing root filesystem write failures and kubelet/containerd disruption.": "스토리지 또는 파일시스템 오류로 root filesystem 쓰기 실패와 kubelet/containerd 장애가 발생했을 수 있습니다.",
      "Disk capacity, inode exhaustion, or I/O pressure is likely causing kubelet eviction and runtime latency.": "디스크 용량, inode 고갈 또는 I/O pressure가 kubelet eviction과 런타임 지연을 유발했을 가능성이 높습니다.",
      "Node memory pressure or OOM activity may be preventing system daemons or workloads from running normally.": "노드 메모리 pressure 또는 OOM 활동으로 시스템 데몬이나 workload가 정상 동작하지 못했을 수 있습니다.",
      "PID exhaustion or zombie process buildup may be preventing kubelet or runtime from spawning required processes.": "PID 고갈 또는 zombie process 누적으로 kubelet이나 런타임이 필요한 프로세스를 생성하지 못했을 수 있습니다.",
      "Node network path, NIC link instability, TCP errors, or conntrack exhaustion is making API Server, CNI, or DNS communication unstable.": "노드 네트워크 경로, NIC link 불안정, TCP 오류 또는 conntrack 고갈로 API Server, CNI, DNS 통신이 불안정합니다.",
      "CNI configuration, plugin errors, or MTU mismatch may be breaking pod network attachment or node networking.": "CNI 설정, plugin 오류 또는 MTU 불일치로 Pod 네트워크 연결이나 노드 네트워킹이 깨졌을 수 있습니다.",
      "Node resolver, CoreDNS, or upstream DNS trouble may be delaying service discovery and control-plane communication.": "노드 resolver, CoreDNS 또는 upstream DNS 문제로 서비스 디스커버리와 control-plane 통신이 지연될 수 있습니다.",
      "Kubernetes API readiness or metrics path is unhealthy, so controllers and operators may see stale or missing node state.": "Kubernetes API readiness 또는 metrics 경로가 비정상이라 controller와 operator가 오래되거나 누락된 노드 상태를 볼 수 있습니다.",
      "Failed systemd units may be contributing to node-level service degradation.": "실패한 systemd unit이 노드 레벨 서비스 저하에 영향을 주고 있을 수 있습니다.",
      "Check failed units and dependency failures": "실패한 unit과 의존성 장애를 확인",
      "Check kubelet unit state, restart history, and node condition messages": "kubelet unit 상태, 재시작 이력, 노드 condition 메시지 확인",
      "Check runtime sockets, unit state, pids, and recent journal lines": "런타임 socket, unit 상태, pid, 최근 journal 확인",
      "Check containerd socket, unit, pid, and recent journal lines": "containerd socket, unit, pid, 최근 journal 확인",
      "Confirm disk and inode pressure by mountpoint": "mountpoint별 disk와 inode pressure 확인",
      "Find large runtime, kubelet, and log directories without crossing filesystems": "파일시스템 경계를 넘지 않고 큰 runtime/kubelet/log 디렉터리 확인",
      "Find directories with unusually high file counts": "파일 개수가 비정상적으로 많은 디렉터리 확인",
      "Check block device, filesystem, blocked task, taint, and read-only remount errors": "block device, filesystem, blocked task, taint, read-only remount 오류 확인",
      "Check memory pressure, swap pressure, PSI, and recent OOM victims": "memory pressure, swap pressure, PSI, 최근 OOM victim 확인",
      "Check PID pressure, process fan-out, and zombie parents": "PID pressure, process fan-out, zombie parent 확인",
      "Check NIC, route, socket, TCP, and conntrack state": "NIC, route, socket, TCP, conntrack 상태 확인",
      "Check CNI config, MTU settings, plugin logs, and kube-system pods": "CNI config, MTU 설정, plugin 로그, kube-system pod 확인",
      "Check Kubernetes API readiness, metrics path, certificate warnings, and node events": "Kubernetes API readiness, metrics 경로, certificate 경고, node event 확인",
      "Check node resolver path, timeout budget, and CoreDNS pods": "노드 resolver 경로, timeout budget, CoreDNS pod 확인",
      "Evidence is insufficient; check failed units and kernel errors first": "근거가 부족합니다. 실패한 unit과 kernel error부터 확인하세요.",
    },
  };

  const actionTranslations = {
    ko: {
      collect_more_evidence: {
        action: "장애 시간대의 kubelet, runtime, kernel, systemd, network, disk 근거를 추가 수집합니다.",
        reason: "읽기 전용 근거 수집이며 노드나 workload 상태를 변경하지 않습니다.",
      },
      collect_linux_low_level_evidence: {
        action: "systemd unit, kernel log, process state, runtime socket, host namespace의 Linux low-level 상태를 수집합니다.",
        reason: "Linux low-level 점검은 읽기 전용이며 restart나 노드 변경을 검토하기 전에 필요합니다.",
      },
      inspect_storage_state: {
        action: "filesystem, inode, mount, block device, kernel I/O 상태를 점검합니다.",
        reason: "스토리지 점검은 읽기 전용이며 capacity pressure와 filesystem/device 오류를 분리하는 데 필요합니다.",
      },
      inspect_network_state: {
        action: "영향받은 노드에서 NIC, route, socket, conntrack, resolver, CNI 상태를 점검합니다.",
        reason: "네트워크 점검은 읽기 전용이며 CNI, sysctl, routing 변경 전 선행되어야 합니다.",
      },
      restart_container_runtime: {
        action: "컨테이너 런타임 socket 또는 unit이 계속 비정상이면 운영자 승인 후 런타임 재시작을 검토합니다.",
        reason: "런타임 재시작은 실행 중인 workload에 영향을 줄 수 있으므로 자동 실행하면 안 됩니다.",
      },
      restart_containerd: {
        action: "containerd socket 또는 unit이 계속 비정상이면 운영자 승인 후 containerd 재시작을 검토합니다.",
        reason: "런타임 재시작은 실행 중인 workload에 영향을 줄 수 있으므로 자동 실행하면 안 됩니다.",
      },
      restart_kubelet: {
        action: "kubelet이 failed 상태이거나 반복 재시작 중이면 운영자 승인 후 kubelet 재시작을 검토합니다.",
        reason: "kubelet 재시작은 노드 상태 갱신을 회복할 수 있지만 workload lifecycle 처리에 영향을 줄 수 있어 승인이 필요합니다.",
      },
      cleanup_disk: {
        action: "운영자 승인 후 사용하지 않는 image, log, temporary file을 정리하거나 디스크 용량을 확장합니다.",
        reason: "잘못된 경로를 정리하면 데이터 손실이 발생할 수 있으므로 경로 검토와 승인이 필요합니다.",
      },
      cordon_node: {
        action: "memory pressure 또는 OOM이 계속되면 운영자 승인 후 node cordon 또는 drain을 검토합니다.",
        reason: "cordon 또는 drain은 workload 재스케줄링을 유발하므로 자동 실행하면 안 됩니다.",
      },
      manual_investigation: {
        action: "조치 전에 PID pressure, process fan-out, zombie parent, runtime shim 상태를 조사합니다.",
        reason: "PID 고갈은 workload 동작이나 host process leak이 원인일 수 있어 사람의 판단이 필요합니다.",
      },
      open_gitops_pr: {
        action: "conntrack, CNI, DNS/CoreDNS, MTU 또는 sysctl 변경은 GitOps PR로만 제안합니다.",
        reason: "클러스터 설정 변경은 RCA에서 직접 적용하면 안 되며 리뷰 가능한 PR 흐름이 필요합니다.",
      },
      manual_hardware_check: {
        action: "NIC link flap, kernel I/O error, read-only filesystem, storage 또는 network path 상태를 조사합니다.",
        reason: "하드웨어, kernel, storage, network path 검증은 수동 조사가 필요합니다.",
      },
      reboot_node: {
        action: "blocked task 또는 read-only filesystem 오류가 지속되면 node reboot는 최후 수단으로만 검토합니다.",
        reason: "node reboot는 영향 범위가 크므로 절대 자동 실행하면 안 됩니다.",
      },
    },
  };

  const signalTranslations = {
    ko: {
      kubelet_unit_unhealthy: {
        interpretation: "kubelet systemd unit이 active/running 상태가 아닙니다.",
        next_step: "장애 직전의 systemctl status kubelet과 journalctl -u kubelet 실패 로그를 확인하세요.",
      },
      kubelet_restarting: {
        interpretation: "kubelet 재시작 횟수가 높습니다. deadlock, 설정 오류, API server 연결 문제가 가능성이 있습니다.",
        next_step: "각 재시작 시점의 journalctl -u kubelet 로그를 API server 연결 오류와 함께 대조하세요.",
      },
      containerd_unit_unhealthy: {
        interpretation: "containerd systemd unit이 비정상이라 kubelet runtime 작업이 실패할 수 있습니다.",
        next_step: "systemctl status containerd와 journalctl -u containerd에서 crash, hang, 설정 오류를 확인하세요.",
      },
      container_runtime_unit_unhealthy: {
        interpretation: "컨테이너 런타임 systemd unit이 failed 또는 restarting 상태입니다.",
        next_step: "운영자 승인으로 재시작하기 전에 runtime unit 상태와 journal을 확인하세요.",
      },
      rke2_server_unit_unhealthy: {
        interpretation: "rke2-server unit이 정상 상태가 아니어서 embedded kubelet/containerd/control-plane이 불안정할 수 있습니다.",
        next_step: "장애 시간대의 systemctl status rke2-server와 journalctl -u rke2-server를 확인하세요.",
      },
      rke2_server_restarting: {
        interpretation: "rke2-server 재시작 횟수가 높습니다. control-plane 또는 embedded runtime 불안정이 있었을 수 있습니다.",
        next_step: "rke2-server 재시작 시점을 node Ready 변화, CNI 재시작, API timeout 로그와 대조하세요.",
      },
      systemd_failed_units: {
        interpretation: "노드에 failed systemd unit이 남아 있어 의존 서비스 장애를 나타낼 수 있습니다.",
        next_step: "systemctl --failed를 실행하고 각 failed unit journal이 Kubernetes 구성 요소로 전파됐는지 확인하세요.",
      },
      kubernetes_api_unavailable: {
        interpretation: "노드 에이전트가 Kubernetes API를 읽지 못했습니다. local API path, service account, control-plane 연결 문제가 가능성이 있습니다.",
        next_step: "노드에서 in-cluster API service reachability, ServiceAccount RBAC, kube-apiserver 상태를 확인하세요.",
      },
      node_not_ready_condition: {
        interpretation: "Kubernetes가 해당 노드의 Ready condition을 false로 보고합니다.",
        next_step: "node condition transition time을 kubelet, runtime, kernel, network 근거와 비교하세요.",
      },
      node_pressure_condition_active: {
        interpretation: "Kubernetes node pressure condition이 활성화되어 있습니다.",
        next_step: "disk, memory, process, kernel collector로 pressure source를 식별하세요.",
      },
      control_plane_peer_unreachable: {
        interpretation: "이 노드에서 control-plane peer TCP probe가 실패했습니다. API server, CNI watch, etcd/client 경로가 깨질 수 있습니다.",
        next_step: "실패한 peer port의 firewall, routing, ACL, listener 상태를 확인하세요.",
      },
      cni_pod_restarting: {
        interpretation: "노드의 CNI pod 재시작 횟수가 높습니다. API watch timeout, CNI agent crash, node network 불안정이 가능성이 있습니다.",
        next_step: "CNI pod previous log와 API server 또는 node network 오류 시간을 대조하세요.",
      },
      system_pod_restarts_high: {
        interpretation: "노드의 pod 재시작 횟수가 높습니다. node/runtime/network 불안정의 2차 증상일 수 있습니다.",
        next_step: "원인으로 확정하기 전에 application restart와 kube-system/runtime restart를 분리하세요.",
      },
      node_metrics_unavailable: {
        interpretation: "metrics.k8s.io를 통한 node metrics가 불가하여 scheduler/autoscaler/operator 가시성이 부족할 수 있습니다.",
        next_step: "metrics-server 로그와 영향을 받은 노드의 kubelet summary API 접근성을 확인하세요.",
      },
      apiserver_readyz_failed: {
        interpretation: "API server readiness check가 실패했습니다.",
        next_step: "실패한 readyz check를 etcd/API server 로그와 대조하세요.",
      },
      node_certificate_expiring: {
        interpretation: "Kubernetes가 node certificate 만료 경고를 발생시켰습니다. 즉시 장애 원인은 아닐 수 있지만 운영상 중요합니다.",
        next_step: "만료 전 통제된 RKE2 certificate rotation을 계획하고, 승인된 maintenance plan 없이 control-plane node를 재시작하지 마세요.",
      },
      container_runtime_socket_permission_denied: {
        interpretation: "컨테이너 런타임 socket은 있지만 로컬 권한 때문에 agent가 probe하지 못했습니다.",
        next_step: "런타임 장애로 보기 전에 노드 에이전트 권한 또는 runtime socket 접근 권한을 확인하세요.",
      },
      container_runtime_socket_unhealthy: {
        interpretation: "컨테이너 런타임 Unix socket이 응답하지 않아 kubelet의 pod sandbox/container 작업이 실패할 수 있습니다.",
        next_step: "감지된 runtime 종류의 socket, pid, unit, journal을 확인하세요.",
      },
      container_runtime_socket_latency_high: {
        interpretation: "컨테이너 런타임 socket latency가 높습니다. runtime hang 또는 I/O pressure가 관련될 수 있습니다.",
        next_step: "runtime journal과 disk I/O pressure를 함께 확인하세요.",
      },
      container_runtime_pid_not_running: {
        interpretation: "runtime pid file이 가리키는 프로세스가 실행 중이 아닙니다.",
        next_step: "systemd 상태와 runtime crash loop 이력을 확인하세요.",
      },
      containerd_socket_permission_denied: {
        interpretation: "containerd socket은 있지만 로컬 권한 때문에 agent가 probe하지 못했습니다.",
        next_step: "containerd 장애로 보기 전에 노드 에이전트 권한 또는 socket 접근 권한을 확인하세요.",
      },
      containerd_socket_unhealthy: {
        interpretation: "containerd Unix socket이 응답하지 않아 kubelet의 pod sandbox/container 작업이 실패할 수 있습니다.",
        next_step: "containerd socket, pid, unit, journal을 확인하세요.",
      },
      containerd_socket_latency_high: {
        interpretation: "containerd socket latency가 높습니다. runtime hang 또는 I/O pressure가 관련될 수 있습니다.",
        next_step: "containerd journal과 disk I/O pressure를 함께 확인하세요.",
      },
      containerd_pid_not_running: {
        interpretation: "containerd pid file이 가리키는 프로세스가 실행 중이 아닙니다.",
        next_step: "systemd 상태와 containerd crash loop 이력을 확인하세요.",
      },
      disk_usage_critical: {
        interpretation: "root filesystem 사용률이 높아 kubelet eviction, log write, image pull 실패가 발생할 수 있습니다.",
        next_step: "df, du, container image usage, log size를 확인해 안전한 정리 또는 용량 확장 대상을 검증하세요.",
      },
      disk_usage_high: {
        interpretation: "root filesystem 사용률이 높아 kubelet eviction, log write, image pull 실패가 발생할 수 있습니다.",
        next_step: "df, du, container image usage, log size를 확인해 안전한 정리 또는 용량 확장 대상을 검증하세요.",
      },
      inode_usage_critical: {
        interpretation: "inode 사용률이 높아 새 파일 생성 실패와 kubelet DiskPressure가 발생할 수 있습니다.",
        next_step: "df -i와 file count가 높은 디렉터리 점검으로 정리 후보를 식별하세요.",
      },
      inode_usage_high: {
        interpretation: "inode 사용률이 높아 새 파일 생성 실패와 kubelet DiskPressure가 발생할 수 있습니다.",
        next_step: "df -i와 file count가 높은 디렉터리 점검으로 정리 후보를 식별하세요.",
      },
      root_filesystem_read_only: {
        interpretation: "root filesystem이 read-only로 mount되어 kubelet/containerd write 작업이 실패할 수 있습니다.",
        next_step: "kernel I/O error, filesystem error, block device health, storage path event를 우선 확인하세요.",
      },
      kernel_io_error: {
        interpretation: "kernel 또는 disk collector에서 I/O error가 감지되었습니다.",
        next_step: "dmesg, journalctl -k, block device health, filesystem 상태를 확인하세요.",
      },
      io_pressure_high: {
        interpretation: "I/O pressure가 높아 kubelet, containerd, etcd 또는 log 작업이 지연될 수 있습니다.",
        next_step: "/proc/pressure/io, iostat, diskstats, runtime journal timestamp를 대조해 병목 device를 식별하세요.",
      },
      blocked_task_detected: {
        interpretation: "kernel blocked task가 감지되었습니다. I/O hang, driver hang, filesystem lock contention 가능성이 있습니다.",
        next_step: "dmesg blocked task stack trace를 보고 disruptive 조치 전에 blocked subsystem을 식별하세요.",
      },
      read_only_filesystem_detected: {
        interpretation: "kernel log에 filesystem read-only 전환 근거가 있습니다.",
        next_step: "remount 직전의 block device 또는 filesystem error를 찾아 storage event와 대조하세요.",
      },
      kernel_nic_error: {
        interpretation: "kernel log에 NIC link 또는 driver error가 있습니다.",
        next_step: "NIC driver log, carrier change, ethtool counter, switch port event를 함께 확인하세요.",
      },
      kernel_oom_detected: {
        interpretation: "kernel OOM 활동이 감지되어 host process 또는 workload가 종료됐을 수 있습니다.",
        next_step: "OOM victim, cgroup, memory pressure, kubelet eviction event를 장애 시간대와 함께 확인하세요.",
      },
      kernel_tainted: {
        interpretation: "kernel taint가 설정되어 third-party module, forced load, kernel warning에 대한 추가 해석이 필요할 수 있습니다.",
        next_step: "/proc/sys/kernel/tainted를 decode하고 최근 dmesg warning을 확인한 뒤 원인을 확정하세요.",
      },
      memory_pressure_critical: {
        interpretation: "노드 memory 사용률이 높아 kubelet eviction, OOM kill, system daemon latency가 발생할 수 있습니다.",
        next_step: "MemAvailable, swap usage, top memory consumer, kubelet eviction event를 확인하세요.",
      },
      memory_pressure_high: {
        interpretation: "노드 memory 사용률이 높아 kubelet eviction, OOM kill, system daemon latency가 발생할 수 있습니다.",
        next_step: "MemAvailable, swap usage, top memory consumer, kubelet eviction event를 확인하세요.",
      },
      oom_kill_detected: {
        interpretation: "장애 시간대에 OOM kill 근거가 있습니다.",
        next_step: "kernel log에서 OOM victim, cgroup, memory pressure context를 확인하세요.",
      },
      swap_usage_high: {
        interpretation: "swap 사용률이 높아 system daemon latency가 증가할 수 있습니다.",
        next_step: "swap in/out 활동과 상위 memory-consuming process를 확인하세요.",
      },
      memory_psi_high: {
        interpretation: "Memory PSI가 높아 runnable task가 memory reclaim 또는 allocation에서 지연될 수 있습니다.",
        next_step: "/proc/pressure/memory를 kubelet eviction과 OOM event와 대조하세요.",
      },
      pid_usage_high: {
        interpretation: "PID 사용률이 높아 process creation 실패 또는 PIDPressure가 발생할 수 있습니다.",
        next_step: "조치 전에 process fan-out, service별 process count, zombie process를 식별하세요.",
      },
      zombie_process_detected: {
        interpretation: "Zombie process가 존재합니다. parent reaping 문제 또는 runtime shim 문제가 가능성이 있습니다.",
        next_step: "zombie parent process와 runtime shim 상태를 확인하세요.",
      },
      interface_down: {
        interpretation: "하나 이상의 NIC가 down 상태이며 노드 연결성이 손상됐을 수 있습니다.",
        next_step: "ip link, ethtool, driver log, switch port event를 확인하세요.",
      },
      nic_link_flap: {
        interpretation: "NIC carrier change가 감지되어 API server, etcd, CNI 통신이 불안정할 수 있습니다.",
        next_step: "carrier change, kernel NIC log, switch event, control-plane connection failure를 시간 기준으로 대조하세요.",
      },
      conntrack_near_limit: {
        interpretation: "conntrack table이 한도에 가까워 DNS, Service, API server 연결이 간헐적으로 실패할 수 있습니다.",
        next_step: "nf_conntrack_count/max, conntrack drop, connection spike를 유발하는 workload를 확인하세요.",
      },
      interface_packet_errors: {
        interpretation: "NIC error 또는 drop이 감지되어 packet loss나 driver/link 문제가 있을 수 있습니다.",
        next_step: "/proc/net/dev, ethtool -S, CNI overlay interface error를 확인하세요.",
      },
      tcp_error_counters_high: {
        interpretation: "TCP retransmit 또는 listen overflow counter가 높아 connection latency 또는 backlog exhaustion이 의심됩니다.",
        next_step: "/proc/net/snmp, /proc/net/netstat, service backlog 설정, upstream packet loss를 확인하세요.",
      },
      dns_latency_high: {
        interpretation: "DNS lookup latency가 높아 pod scheduling, image pull, service discovery가 지연될 수 있습니다.",
        next_step: "CoreDNS latency, node resolver 설정, upstream DNS 상태를 확인하세요.",
      },
      cni_config_invalid: {
        interpretation: "CNI configuration JSON parse error가 감지되어 kubelet pod sandbox 생성이 실패할 수 있습니다.",
        next_step: "/etc/cni/net.d 파일을 검증하고 최근 CNI 설정 변경을 확인하세요.",
      },
      cni_plugin_error: {
        interpretation: "CNI plugin error가 감지되어 pod network attachment가 실패할 수 있습니다.",
        next_step: "CNI plugin log와 kubelet pod sandbox event를 확인하세요.",
      },
      cni_mtu_values_inconsistent: {
        interpretation: "CNI 설정에 여러 MTU 값이 있어 overlay path MTU mismatch가 가능성이 있습니다.",
        next_step: "node NIC MTU, CNI MTU, pod path MTU, overlay interface MTU를 함께 비교하세요.",
      },
      dns_unconfigured: {
        interpretation: "노드 resolver에 사용 가능한 nameserver가 없어 DNS lookup이 실패할 수 있습니다.",
        next_step: "/etc/resolv.conf, node-local-dns, CoreDNS, upstream DNS 설정을 확인하세요.",
      },
      dns_resolver_timeout_budget_high: {
        interpretation: "resolver timeout budget이 높아 DNS 실패가 긴 요청 지연을 만들 수 있습니다.",
        next_step: "resolv.conf option과 CoreDNS timeout/retry policy를 검토하세요.",
      },
    },
  };

  function App() {
    const [activeView, setActiveView] = React.useState("overview");
    const [locale, setLocale] = React.useState(activeLocale);
    const [clusters, setClusters] = React.useState([]);
    const [reports, setReports] = React.useState([]);
    const [incidents, setIncidents] = React.useState([]);
    const [analysisTasks, setAnalysisTasks] = React.useState([]);
    const [auditEvents, setAuditEvents] = React.useState([]);
    const [demoScenarios, setDemoScenarios] = React.useState({ loading: true, enabled: false, items: [] });
    const [platformInfo, setPlatformInfo] = React.useState(null);
    const [reportDetails, setReportDetails] = React.useState({});
    const [agentsByCluster, setAgentsByCluster] = React.useState({});
    const [installCommands, setInstallCommands] = React.useState({});
    const [currentUser, setCurrentUser] = React.useState(null);
    const [authChecking, setAuthChecking] = React.useState(true);
    const [toast, setToast] = React.useState("");
    const [loading, setLoading] = React.useState({});
    const [autoRefresh, setAutoRefresh] = React.useState(true);
    const [lastRefresh, setLastRefresh] = React.useState(null);
    const [clusterData, setClusterData] = React.useState(null);
    const [actionDialog, setActionDialog] = React.useState(null);
    const [collectionDialog, setCollectionDialog] = React.useState(null);
    const [deleteDialog, setDeleteDialog] = React.useState(null);
    activeLocale = locale;
    document.documentElement.lang = locale;

    const notify = React.useCallback((message) => {
      setToast(message);
      window.clearTimeout(notify.timer);
      notify.timer = window.setTimeout(() => setToast(""), 3200);
    }, []);

    const changeLanguage = React.useCallback((value) => {
      const nextLocale = normalizeLocale(value);
      activeLocale = nextLocale;
      setLocale(nextLocale);
      localStorage.setItem(LANGUAGE_STORAGE_KEY, nextLocale);
      notify(tr("Language changed."));
    }, [notify]);

    const authHeaders = React.useCallback(() => {
      return {};
    }, []);

    const callApi = React.useCallback(async (path, options = {}) => {
      let response;
      try {
        response = await fetch(`${apiBase}${path}`, {
          cache: "no-store",
          credentials: "same-origin",
          ...options,
          headers: {
            "Content-Type": "application/json",
            ...(options.headers || {}),
          },
        });
      } catch (error) {
        throw new Error("Platform API is unreachable.");
      }

      const contentType = response.headers.get("content-type") || "";
      const text = await response.text();
      const body = contentType.includes("application/json") && text ? JSON.parse(text) : text;
      if (!response.ok) {
        throw new Error(readError(body, response.statusText));
      }
      return body;
    }, []);

    const loadCurrentUser = React.useCallback(async (silent) => {
      try {
        const user = await callApi("/api/auth/me", { headers: authHeaders() });
        setCurrentUser(user);
      } catch (error) {
        setCurrentUser(null);
        if (!silent) notify(error.message);
      } finally {
        setAuthChecking(false);
      }
    }, [authHeaders, callApi, notify]);

    const loadClusters = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, clusters: true }));
        const result = await callApi("/api/clusters", { headers: authHeaders() });
        setClusters(Array.isArray(result) ? result : []);
      } catch (error) {
        setClusters([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, clusters: false }));
      }
    }, [authHeaders, callApi, notify]);

    const loadReports = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, reports: true }));
        const result = await callApi("/api/rca/reports", { headers: authHeaders() });
        setReports(Array.isArray(result) ? result : []);
      } catch (error) {
        setReports([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, reports: false }));
      }
    }, [authHeaders, callApi, notify]);

    const loadIncidents = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, incidents: true }));
        const result = await callApi("/api/rca/incidents", { headers: authHeaders() });
        setIncidents(Array.isArray(result) ? result : []);
      } catch (error) {
        setIncidents([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, incidents: false }));
      }
    }, [authHeaders, callApi, notify]);

    const loadAuditEvents = React.useCallback(async (silent) => {
      if (!["admin", "auditor"].includes(currentUser?.role)) return;
      try {
        setLoading((value) => ({ ...value, audit: true }));
        const result = await callApi("/api/audit/events?limit=300", { headers: authHeaders() });
        setAuditEvents(Array.isArray(result) ? result : []);
      } catch (error) {
        setAuditEvents([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, audit: false }));
      }
    }, [currentUser, authHeaders, callApi, notify]);

    const loadAnalysisTasks = React.useCallback(async (silent) => {
      try {
        setLoading((value) => ({ ...value, pipeline: true }));
        const result = await callApi("/api/rca/analysis-tasks?limit=300", { headers: authHeaders() });
        setAnalysisTasks(Array.isArray(result) ? result : []);
      } catch (error) {
        setAnalysisTasks([]);
        if (!silent) notify(error.message);
      } finally {
        setLoading((value) => ({ ...value, pipeline: false }));
      }
    }, [authHeaders, callApi, notify]);

    const loadDemoScenarios = React.useCallback(async (silent) => {
      try {
        setDemoScenarios((value) => ({ ...value, loading: true }));
        const result = await callApi("/api/demo/scenarios", { headers: authHeaders() });
        setDemoScenarios({
          loading: false,
          enabled: result.enabled === true,
          items: Array.isArray(result.scenarios) ? result.scenarios : [],
        });
      } catch (error) {
        setDemoScenarios({ loading: false, enabled: false, items: [], error: error.message });
        if (!silent) notify(error.message);
      }
    }, [authHeaders, callApi, notify]);

    const loadPlatformInfo = React.useCallback(async (silent) => {
      try {
        setPlatformInfo(await callApi("/api/v1/platform/info", { headers: authHeaders() }));
      } catch (error) {
        setPlatformInfo(null);
        if (!silent) notify(error.message);
      }
    }, [authHeaders, callApi, notify]);

    const refreshAll = React.useCallback(async (silent) => {
      await Promise.allSettled([
        loadClusters(silent),
        loadReports(silent),
        loadIncidents(silent),
        loadAnalysisTasks(silent),
        loadAuditEvents(silent),
        loadDemoScenarios(silent),
        loadPlatformInfo(silent),
      ]);
      setLastRefresh(new Date());
    }, [loadClusters, loadReports, loadIncidents, loadAnalysisTasks, loadAuditEvents, loadDemoScenarios, loadPlatformInfo]);

    React.useEffect(() => {
      loadCurrentUser(true);
    }, [loadCurrentUser]);

    React.useEffect(() => {
      if (currentUser) refreshAll(true);
    }, [currentUser, refreshAll]);

    React.useEffect(() => {
      if (!autoRefresh || !currentUser) return undefined;
      const timer = window.setInterval(() => refreshAll(true), 30000);
      return () => window.clearInterval(timer);
    }, [autoRefresh, currentUser, refreshAll]);

    async function login(event) {
      event.preventDefault();
      const form = event.currentTarget;
      const payload = formPayload(form);
      try {
        const session = await callApi("/api/auth/login", {
          method: "POST",
          body: JSON.stringify(payload),
        });
        setCurrentUser(session.user);
        form.reset();
        notify(`Signed in: ${session.user.email}`);
      } catch (error) {
        notify(error.message);
      }
    }

    async function logout() {
      if (currentUser) {
        await callApi("/api/auth/logout", {
          method: "POST",
          headers: authHeaders(),
        }).catch(() => null);
      }
      setCurrentUser(null);
      setClusters([]);
      setReports([]);
      setIncidents([]);
      setAnalysisTasks([]);
      setAuditEvents([]);
      setDemoScenarios({ loading: true, enabled: false, items: [] });
      setPlatformInfo(null);
      setReportDetails({});
      setAgentsByCluster({});
      setClusterData(null);
      setActionDialog(null);
      setCollectionDialog(null);
      setDeleteDialog(null);
      notify("Signed out.");
    }

    async function changePassword(event) {
      event.preventDefault();
      const form = event.currentTarget;
      const payload = formPayload(form);
      if (payload.new_password !== payload.confirm_password) {
        notify("New passwords do not match.");
        return;
      }
      delete payload.confirm_password;
      try {
        await callApi("/api/auth/change-password", {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify(payload),
        });
        form.reset();
        notify("Password changed.");
      } catch (error) {
        notify(error.message);
      }
    }

    async function createCluster(event) {
      event.preventDefault();
      const form = event.currentTarget;
      try {
        const payload = formPayload(form);
        const backendUrl = payload.backend_url;
        delete payload.backend_url;
        const cluster = await callApi("/api/clusters", {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify(payload),
        });
        form.reset();
        notify(`Cluster registered: ${cluster.name}. Install command is ready.`);
        await loadClusters(false);
        await loadInstallCommand(cluster.cluster_id, backendUrl);
        await loadAgents(cluster.cluster_id);
      } catch (error) {
        notify(error.message);
      }
    }

    async function loadInstallCommand(clusterId, backendUrl) {
      try {
        setInstallCommands((value) => ({ ...value, [clusterId]: "Loading..." }));
        const query = new URLSearchParams();
        if (backendUrl || publicApiBase) query.set("backend_url", backendUrl || publicApiBase);
        const suffix = query.toString() ? `?${query}` : "";
        const response = await callApi(`/api/clusters/${encodeURIComponent(clusterId)}/install-command${suffix}`, {
          headers: authHeaders(),
        });
        setInstallCommands((value) => ({ ...value, [clusterId]: response.commands.join("\n") }));
      } catch (error) {
        setInstallCommands((value) => ({ ...value, [clusterId]: error.message }));
      }
    }

    async function loadAgents(clusterId) {
      try {
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: true, items: [] } }));
        const agents = await callApi(`/api/clusters/${encodeURIComponent(clusterId)}/agent-health`, {
          headers: authHeaders(),
        });
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: false, items: agents } }));
      } catch (error) {
        setAgentsByCluster((value) => ({ ...value, [clusterId]: { loading: false, error: error.message, items: [] } }));
      }
    }

    async function loadClusterData(clusterId) {
      setClusterData({ open: true, loading: true, clusterId });
      try {
        const [cluster, agents, evidenceRequests, allReports, topology, topologyHistory] = await Promise.all([
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}`, { headers: authHeaders() }),
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}/agent-health`, { headers: authHeaders() }),
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}/evidence-requests`, { headers: authHeaders() }),
          callApi("/api/rca/reports", { headers: authHeaders() }),
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}/topology`, { headers: authHeaders() }),
          callApi(`/api/clusters/${encodeURIComponent(clusterId)}/topology/history?limit=2`, { headers: authHeaders() }),
        ]);
        const history = Array.isArray(topologyHistory) ? topologyHistory : [];
        let topologyComparison = null;
        if (history.length >= 2) {
          const targetAt = history[0].observed_at;
          const baselineAt = history[1].observed_at;
          const query = new URLSearchParams({
            baseline_at: baselineAt,
            target_at: targetAt,
          });
          topologyComparison = await callApi(
            `/api/clusters/${encodeURIComponent(clusterId)}/topology/compare?${query}`,
            { headers: authHeaders() }
          );
        }
        setClusterData({
          open: true,
          loading: false,
          clusterId,
          cluster,
          agents: Array.isArray(agents) ? agents : [],
          evidenceRequests: Array.isArray(evidenceRequests) ? evidenceRequests : [],
          reports: (Array.isArray(allReports) ? allReports : []).filter((report) => report.cluster_id === clusterId),
          topology,
          topologyHistory: history,
          topologyComparison,
        });
      } catch (error) {
        setClusterData({ open: true, loading: false, clusterId, error: error.message });
      }
    }

    async function loadEvidenceBundle(evidenceId) {
      if (!evidenceId) return;
      setClusterData((value) => ({ ...(value || {}), evidenceLoading: true, selectedEvidence: null, evidenceError: null }));
      try {
        const evidence = await callApi(`/api/evidence/${encodeURIComponent(evidenceId)}`, {
          headers: authHeaders(),
        });
        setClusterData((value) => ({ ...(value || {}), evidenceLoading: false, selectedEvidence: evidence, evidenceError: null }));
      } catch (error) {
        setClusterData((value) => ({ ...(value || {}), evidenceLoading: false, evidenceError: error.message }));
      }
    }

    async function toggleReport(reportId) {
      if (reportDetails[reportId]?.open) {
        setReportDetails((value) => ({ ...value, [reportId]: { ...value[reportId], open: false } }));
        return;
      }
      setReportDetails((value) => ({ ...value, [reportId]: { ...(value[reportId] || {}), open: true, loading: true } }));
      try {
        const report = await callApi(`/api/rca/reports/${encodeURIComponent(reportId)}`, { headers: authHeaders() });
        const [actionRequests, actionExecutions, timeline] = await Promise.all([
          callApi(`/api/rca/action-requests?report_id=${encodeURIComponent(reportId)}`, { headers: authHeaders() }),
          ["admin", "operator"].includes(currentUser?.role)
            ? callApi(`/api/rca/action-executions?report_id=${encodeURIComponent(reportId)}`, { headers: authHeaders() })
            : Promise.resolve([]),
          report.incident_id
            ? callApi(`/api/rca/incidents/${encodeURIComponent(report.incident_id)}/timeline`, { headers: authHeaders() })
            : Promise.resolve(null),
        ]);
        setReportDetails((value) => ({
          ...value,
          [reportId]: {
            open: true,
            loading: false,
            report,
            actionRequests: Array.isArray(actionRequests) ? actionRequests : [],
            actionExecutions: Array.isArray(actionExecutions) ? actionExecutions : [],
            timeline,
          },
        }));
      } catch (error) {
        setReportDetails((value) => ({ ...value, [reportId]: { open: true, loading: false, error: error.message } }));
      }
    }

    function prepareRecommendedAction(report, action, actionIndex) {
      setActionDialog({ report, action, actionIndex, loading: false });
    }

    function prepareClusterCollection(cluster) {
      setCollectionDialog({ cluster, loading: false });
    }

    function prepareClusterDelete(cluster) {
      setDeleteDialog({ cluster, confirmName: "", loading: false });
    }

    async function executeClusterDelete(event) {
      event.preventDefault();
      if (!deleteDialog?.cluster) return;
      const cluster = deleteDialog.cluster;
      const confirmName = (deleteDialog.confirmName || "").trim();
      if (confirmName !== cluster.name) {
        setDeleteDialog((value) => ({ ...value, error: tr("Confirmation does not match the cluster name.") }));
        return;
      }
      setDeleteDialog((value) => ({ ...value, loading: true, error: null }));
      try {
        const query = new URLSearchParams({ confirm_name: confirmName });
        await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}?${query}`, {
          method: "DELETE",
          headers: authHeaders(),
        });
        setDeleteDialog(null);
        setInstallCommands((value) => {
          const next = { ...value };
          delete next[cluster.cluster_id];
          return next;
        });
        setAgentsByCluster((value) => {
          const next = { ...value };
          delete next[cluster.cluster_id];
          return next;
        });
        if (clusterData?.clusterId === cluster.cluster_id) {
          setClusterData(null);
        }
        notify(tr("Cluster deleted."));
        await Promise.all([loadClusters(false), loadReports(false)]);
      } catch (error) {
        setDeleteDialog((value) => ({ ...value, loading: false, error: error.message }));
      }
    }

    async function executeClusterCollection() {
      if (!collectionDialog?.cluster) return;
      const cluster = collectionDialog.cluster;
      setCollectionDialog((value) => ({ ...value, loading: true }));
      try {
        const result = await callApi(`/api/clusters/${encodeURIComponent(cluster.cluster_id)}/collection-runs`, {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify({
            confirmed: true,
            reason: "Manual platform collection from web console",
            context: { source: "web-console" },
          }),
        });
        setCollectionDialog(null);
        notify(`Collection requested: ${result.created_evidence_requests.length} nodes, skipped ${result.skipped_nodes.length}.`);
        await loadAgents(cluster.cluster_id);
        if (clusterData?.clusterId === cluster.cluster_id) {
          await loadClusterData(cluster.cluster_id);
        }
      } catch (error) {
        setCollectionDialog((value) => ({ ...value, loading: false, error: error.message }));
      }
    }

    async function executeRecommendedAction() {
      if (!actionDialog) return;
      const { report, actionIndex } = actionDialog;
      setActionDialog((value) => ({ ...value, loading: true }));
      try {
        const result = await callApi(
          `/api/rca/reports/${encodeURIComponent(report.report_id)}/actions/${actionIndex}/execute`,
          {
            method: "POST",
            headers: authHeaders(),
            body: JSON.stringify({ confirmed: true }),
          }
        );
        setActionDialog(null);
        notify(result.evidence_request ? `${result.message} ${result.evidence_request.request_id}` : result.message);
        await reloadActionRequests(report.report_id);
        if (result.evidence_request) loadClusterData(report.cluster_id);
      } catch (error) {
        setActionDialog((value) => ({ ...value, loading: false, error: error.message }));
      }
    }

    async function reloadActionRequests(reportId) {
      const actionRequests = await callApi(
        `/api/rca/action-requests?report_id=${encodeURIComponent(reportId)}`,
        { headers: authHeaders() }
      );
      const actionExecutions = ["admin", "operator"].includes(currentUser?.role)
        ? await callApi(
            `/api/rca/action-executions?report_id=${encodeURIComponent(reportId)}`,
            { headers: authHeaders() }
          )
        : [];
      setReportDetails((value) => ({
        ...value,
        [reportId]: {
          ...(value[reportId] || {}),
          actionRequests: Array.isArray(actionRequests) ? actionRequests : [],
          actionExecutions: Array.isArray(actionExecutions) ? actionExecutions : [],
        },
      }));
      if (currentUser?.role === "admin") loadAuditEvents(true);
    }

    async function decideActionRequest(actionRequestId, reportId, decision) {
      if (!window.confirm(
        decision === "approve"
          ? tr("Approve this request for human-operated handling? The platform and agent will not execute it.")
          : tr("Reject this action request?")
      )) return;
      try {
        const result = await callApi(
          `/api/rca/action-requests/${encodeURIComponent(actionRequestId)}/${decision}`,
          {
            method: "POST",
            headers: authHeaders(),
            body: JSON.stringify({ confirmed: true, note: `Decision from web console: ${decision}` }),
          }
        );
        notify(`Action request ${(result.action_request || result).status}.`);
        await reloadActionRequests(reportId);
      } catch (error) {
        notify(error.message);
      }
    }

    async function completeManualActionRequest(actionRequestId, reportId) {
      if (!window.confirm(tr("Mark this approved request as manually completed?"))) return;
      try {
        await callApi(
          `/api/rca/action-requests/${encodeURIComponent(actionRequestId)}/complete-manual`,
          {
            method: "POST",
            headers: authHeaders(),
            body: JSON.stringify({
              confirmed: true,
              note: "Manual handling completion recorded from web console",
            }),
          }
        );
        notify(tr("Manual completion recorded."));
        await reloadActionRequests(reportId);
      } catch (error) {
        notify(error.message);
      }
    }

    async function changeIncidentStatus(incidentId, status) {
      try {
        await callApi(`/api/rca/incidents/${encodeURIComponent(incidentId)}/${status}`, {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify({ confirmed: true, note: `Status changed from web console: ${status}` }),
        });
        notify(`Incident ${status}.`);
        await loadIncidents(false);
        if (currentUser?.role === "admin") loadAuditEvents(true);
      } catch (error) {
        notify(error.message);
      }
    }

    async function retryAnalysisTask(taskId) {
      if (!window.confirm(tr("Retry task") + `: ${taskId}?`)) return;
      try {
        await callApi(`/api/rca/analysis-tasks/${encodeURIComponent(taskId)}/retry`, {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify({ confirmed: true, note: "Requeued from web console" }),
        });
        notify("Analysis task queued.");
        await loadAnalysisTasks(false);
      } catch (error) {
        notify(error.message);
      }
    }

    async function downloadReportsExport(clusterId) {
      const query = new URLSearchParams();
      if (clusterId) query.set("cluster_id", clusterId);
      const suffix = query.toString() ? `?${query}` : "";
      await downloadApiFile(
        `/api/rca/reports/export${suffix}`,
        clusterId ? `rca-reports-${clusterId}.json` : "rca-reports.json",
        clusterId ? "Cluster reports export downloaded." : "Reports export downloaded."
      );
    }

    async function downloadReportExport(reportId) {
      if (!reportId) return;
      await downloadApiFile(
        `/api/rca/reports/${encodeURIComponent(reportId)}/export`,
        `rca-report-${reportId}.json`,
        "Report export downloaded."
      );
    }

    async function downloadEvidenceBundle(reportId) {
      if (!reportId) return;
      await downloadApiFile(
        `/api/rca/reports/${encodeURIComponent(reportId)}/bundle`,
        `incident-${reportId}.zip`,
        "Evidence bundle downloaded."
      );
    }

    async function downloadAuditExport(format) {
      await downloadApiFile(
        `/api/audit/events/export?limit=5000&format=${encodeURIComponent(format)}`,
        `audit-events.${format}`,
        "Audit export downloaded."
      );
    }

    async function rotateAgentToken(cluster) {
      if (!window.confirm(
        `Rotate the Agent bootstrap token for ${cluster.name}? Existing Agent Secrets will stop authenticating until updated.`
      )) return;
      try {
        const result = await callApi(
          `/api/clusters/${encodeURIComponent(cluster.cluster_id)}/agent-token/rotate`,
          { method: "POST", headers: authHeaders() }
        );
        await navigator.clipboard.writeText(result.agent_token);
        notify("Agent token rotated and copied. Update the Kubernetes Secret now.");
        await loadInstallCommand(cluster.cluster_id);
      } catch (error) {
        notify(error.message);
      }
    }

    async function runDemoScenario(scenario) {
      if (!demoScenarios.enabled) {
        notify("Demo Scenario Mode is disabled.");
        return;
      }
      if (!window.confirm(`Run demo scenario: ${scenario.name}?`)) return;
      try {
        const result = await callApi(`/api/demo/scenarios/${encodeURIComponent(scenario.key)}/run`, {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify({ confirmed: true }),
        });
        notify(`Demo queued: ${result.analysis_task.task_id}`);
        await Promise.all([loadClusters(true), loadAnalysisTasks(false)]);
        setActiveView("pipeline");
      } catch (error) {
        notify(error.message);
      }
    }

    async function downloadApiFile(path, fallbackFilename, message) {
      let response;
      try {
        response = await fetch(`${apiBase}${path}`, {
          cache: "no-store",
          credentials: "same-origin",
          headers: authHeaders(),
        });
      } catch (error) {
        notify("Platform API is unreachable.");
        return;
      }
      if (!response.ok) {
        const contentType = response.headers.get("content-type") || "";
        const text = await response.text();
        let body = text;
        if (contentType.includes("application/json") && text) {
          try {
            body = JSON.parse(text);
          } catch (error) {
            body = text;
          }
        }
        notify(readError(body, response.statusText));
        return;
      }
      const blob = await response.blob();
      const filename = filenameFromContentDisposition(response.headers.get("content-disposition")) || fallbackFilename;
      triggerDownload(blob, filename);
      notify(message);
    }

    async function copyText(value, message) {
      try {
        await navigator.clipboard.writeText(value);
        notify(message);
      } catch (error) {
        notify(value);
      }
    }

    const webhookEndpoint = `${publicApiBase.replace(/\/$/, "")}/api/webhooks/alertmanager`;

    if (authChecking) {
      return h("div", { className: "login-shell" },
        h("div", { className: "login-card" },
          h("div", { className: "console-brand-mark mb-3" }, "RCA"),
          h("h1", { className: "h5 mb-2" }, tr("Checking session")),
          h("p", { className: "text-muted mb-0" }, tr("Please wait."))
        )
      );
    }

    if (!currentUser) {
      return h(React.Fragment, null,
        h(LoginPage, { onLogin: login, locale, onChangeLanguage: changeLanguage }),
        toast && h(Toast, { message: toast, onClose: () => setToast("") })
      );
    }

    return h("div", { className: "console-shell" },
      h(Sidebar, { activeView, setActiveView, currentUser }),
      h("main", { className: "console-main" },
        h(Topbar, {
          currentUser,
          autoRefresh,
          lastRefresh,
          onLogout: logout,
          onRefresh: () => refreshAll(false),
          onToggleAutoRefresh: () => setAutoRefresh((value) => !value),
          locale,
          onChangeLanguage: changeLanguage,
        }),
        activeView === "overview" && h(OverviewView, {
          clusters,
          reports,
          loading,
          webhookEndpoint,
          onNavigate: setActiveView,
        }),
        activeView === "clusters" && h(ClustersView, {
          clusters,
          loading,
          agentsByCluster,
          installCommands,
          onCreateCluster: createCluster,
          onLoadClusters: () => loadClusters(false),
          onLoadInstallCommand: loadInstallCommand,
          onLoadAgents: loadAgents,
          onOpenClusterData: loadClusterData,
          onCollectCluster: prepareClusterCollection,
          onDeleteCluster: prepareClusterDelete,
          onRotateAgentToken: rotateAgentToken,
          onCopy: copyText,
          publicApiBase,
          currentUser,
        }),
        activeView === "webhooks" && h(WebhooksView, {
          endpoint: webhookEndpoint,
          onCopy: copyText,
        }),
        activeView === "incidents" && h(IncidentsView, {
          incidents,
          loading: loading.incidents,
          onReload: () => loadIncidents(false),
          onChangeStatus: changeIncidentStatus,
          currentUser,
        }),
        activeView === "pipeline" && h(PipelineView, {
          tasks: analysisTasks,
          loading: loading.pipeline,
          onReload: () => loadAnalysisTasks(false),
          onRetry: retryAnalysisTask,
          currentUser,
        }),
        activeView === "reports" && h(ReportsView, {
          reports,
          loading,
          reportDetails,
          onLoadReports: () => loadReports(false),
          onToggleReport: toggleReport,
          onPrepareAction: prepareRecommendedAction,
          onExportReports: () => downloadReportsExport(),
          onExportReport: downloadReportExport,
          onExportBundle: downloadEvidenceBundle,
          onCopy: copyText,
          currentUser,
          onDecideAction: decideActionRequest,
          onCompleteManual: completeManualActionRequest,
        }),
        activeView === "audit" && ["admin", "auditor"].includes(currentUser.role) && h(AuditView, {
          events: auditEvents,
          loading: loading.audit,
          onReload: () => loadAuditEvents(false),
          onExport: downloadAuditExport,
        }),
        activeView === "demo" && h(DemoScenariosView, {
          state: demoScenarios,
          onReload: () => loadDemoScenarios(false),
          onRun: runDemoScenario,
          canRun: ["admin", "operator"].includes(currentUser?.role),
        }),
        activeView === "settings" && h(SettingsView, {
          apiBase,
          publicApiBase,
          autoRefresh,
          currentUser,
          onChangePassword: changePassword,
          locale,
          onChangeLanguage: changeLanguage,
          platformInfo,
        }),
        clusterData?.open && h(ClusterDataModal, {
          state: clusterData,
          onClose: () => setClusterData(null),
          onRefresh: () => loadClusterData(clusterData.clusterId),
          onLoadEvidence: loadEvidenceBundle,
          onCollectCluster: prepareClusterCollection,
          onExportReports: () => downloadReportsExport(clusterData.clusterId),
          onCopy: copyText,
          canExport: ["admin", "operator"].includes(currentUser?.role),
        }),
        actionDialog && h(ActionConfirmDialog, {
          state: actionDialog,
          onCancel: () => setActionDialog(null),
          onConfirm: executeRecommendedAction,
        }),
        collectionDialog && h(CollectionConfirmDialog, {
          state: collectionDialog,
          onCancel: () => setCollectionDialog(null),
          onConfirm: executeClusterCollection,
        }),
        deleteDialog && h(DeleteClusterDialog, {
          state: deleteDialog,
          onCancel: () => setDeleteDialog(null),
          onChangeConfirm: (confirmName) => setDeleteDialog((value) => ({ ...value, confirmName, error: null })),
          onConfirm: executeClusterDelete,
        }),
        toast && h(Toast, { message: toast, onClose: () => setToast("") })
      )
    );
  }

  function Sidebar({ activeView, setActiveView, currentUser }) {
    return h("aside", { className: "console-sidebar" },
      h("div", { className: "console-brand" },
        h("div", { className: "console-brand-mark" }, "RCA"),
        h("div", null,
          h("div", { className: "fw-bold" }, "Infra RCA"),
          h("div", { className: "small text-white-50" }, tr("Operations Console"))
        )
      ),
      h("nav", { className: "console-nav", "aria-label": "Console navigation" },
        views.filter((view) => view.id !== "audit" || currentUser?.role === "admin").map((view) => h("button", {
          key: view.id,
          type: "button",
          className: activeView === view.id ? "active" : "",
          onClick: () => setActiveView(view.id),
        }, h(Icon, { name: view.icon }), h("span", null, tr(view.label))))
      )
    );
  }

  function Topbar(props) {
    return h("section", { className: "console-topbar" },
      h("div", { className: "row g-3 align-items-end" },
        h("div", { className: "col-12 col-xl-4" },
          h("div", { className: "d-flex align-items-center gap-2 mb-1" },
            h("span", { className: "status-dot online" }),
            h("span", { className: "small text-muted fw-semibold" }, `${props.currentUser.email} / ${props.currentUser.role}`)
          ),
          h("h1", { className: "h4 mb-0" }, tr("Cluster Infrastructure RCA"))
        ),
        h("div", { className: "col-12 col-xl-8" },
          h("div", { className: "topbar-actions d-flex gap-2 flex-wrap justify-content-xl-end" },
            h(LanguageSelect, { locale: props.locale, onChangeLanguage: props.onChangeLanguage, compact: true }),
            h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: props.onRefresh }, h(Icon, { name: "arrow-clockwise" }), tr("Refresh")),
            h("button", { type: "button", className: `btn btn-outline-secondary btn-icon ${props.autoRefresh ? "active" : ""}`, onClick: props.onToggleAutoRefresh }, h(Icon, { name: "activity" }), props.autoRefresh ? tr("Auto") : tr("Manual")),
            h("button", { type: "button", className: "btn btn-outline-secondary btn-icon", onClick: props.onLogout }, h(Icon, { name: "box-arrow-right" }), tr("Logout"))
          ),
          h("div", { className: "small text-muted mt-1" }, props.lastRefresh ? `${tr("Last refresh")} ${formatDate(props.lastRefresh)}` : tr("Not refreshed"))
        )
      )
    );
  }

  function LoginPage({ onLogin, locale, onChangeLanguage }) {
    return h("div", { className: "login-shell" },
      h("section", { className: "login-card" },
        h("div", { className: "d-flex justify-content-end mb-2" }, h(LanguageSelect, { locale, onChangeLanguage, compact: true })),
        h("div", { className: "console-brand-mark mb-3" }, "RCA"),
        h("h1", { className: "h4 mb-2" }, tr("Cluster Infrastructure RCA")),
        h("p", { className: "text-muted mb-4" }, tr("Sign in with the administrator account. The initial account is admin / admin.")),
        h("form", { className: "d-grid gap-3", onSubmit: onLogin },
          h("div", null,
            h("label", { className: "form-label", htmlFor: "login-username" }, tr("Account")),
            h("input", { id: "login-username", className: "form-control", name: "username", autoComplete: "username", defaultValue: "admin", required: true })
          ),
          h("div", null,
            h("label", { className: "form-label", htmlFor: "login-password" }, tr("Password")),
            h("input", { id: "login-password", className: "form-control", name: "password", type: "password", autoComplete: "current-password", required: true })
          ),
          h("button", { className: "btn btn-primary btn-icon justify-content-center", type: "submit" }, h(Icon, { name: "box-arrow-in-right" }), tr("Login"))
        )
      )
    );
  }

  function OverviewView({ clusters, reports, loading, webhookEndpoint, onNavigate }) {
    const highConfidence = reports.filter((report) => report.summary?.confidence === "high").length;
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "row g-3" },
        h(MetricTile, { label: "Clusters", value: clusters.length, hint: loading.clusters ? "Loading" : "Registered targets", icon: "hdd-network" }),
        h(MetricTile, { label: "RCA Reports", value: reports.length, hint: `${highConfidence} high confidence`, icon: "clipboard2-pulse" }),
        h(MetricTile, { label: "Access", value: "Session", hint: "HttpOnly session", icon: "shield-lock" }),
        h(MetricTile, { label: "Webhook", value: "Alertmanager", hint: webhookEndpoint, icon: "diagram-3", compact: true })
      ),
      h("div", { className: "row g-3" },
        h("div", { className: "col-12 col-xl-7" },
          h(Panel, { title: "Cluster Snapshot", subtitle: "Latest registered clusters", action: h("button", { className: "btn btn-sm btn-outline-secondary", onClick: () => onNavigate("clusters") }, tr("Open")) },
            h(ClusterTable, { clusters: clusters.slice(0, 6) })
          )
        ),
        h("div", { className: "col-12 col-xl-5" },
          h(Panel, { title: "Recent Reports", subtitle: "Root cause candidates", action: h("button", { className: "btn btn-sm btn-outline-secondary", onClick: () => onNavigate("reports") }, tr("Open")) },
            reports.length ? h("div", { className: "list-group list-group-flush" },
              reports.slice(0, 5).map((report) => h("div", { key: report.report_id, className: "list-group-item px-0" },
                h("div", { className: "d-flex justify-content-between gap-2" },
                  h("strong", { className: "small" }, displaySummary(report.summary?.symptom)),
                  h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })
                ),
                h("div", { className: "small text-muted text-truncate" }, displayText(report.summary?.most_likely_cause || report.report_id))
              ))
            ) : h(EmptyState, { message: "No reports loaded." })
          )
        )
      )
    );
  }

  function ClustersView(props) {
    const backendUrlHelp = props.publicApiBase
      ? "Agents and kubectl will use this platform API URL."
      : "Enter the platform API URL reachable from your kubectl workstation and cluster nodes.";
    return h("div", { className: "d-grid gap-3" },
      h("div", { className: "row g-3" },
        h("div", { className: "col-12 col-xl-5" },
          h(Panel, { title: "Cluster Onboarding", subtitle: "Register once, then install the node agent" },
            h("ol", { className: "onboarding-steps mb-3" },
              h("li", null, h("span", null, "1"), h("div", null, h("strong", null, tr("Register")), h("small", null, tr("Create a cluster id and bootstrap token.")))),
              h("li", null, h("span", null, "2"), h("div", null, h("strong", null, tr("Install")), h("small", null, tr("Run the generated kubectl command.")))),
              h("li", null, h("span", null, "3"), h("div", null, h("strong", null, tr("Verify")), h("small", null, tr("Check node agents after DaemonSet rollout."))))
            ),
            h("form", { className: "row g-3", onSubmit: props.onCreateCluster },
              h(InputField, { label: "Cluster name", name: "name", required: true, placeholder: "prod-cluster" }),
              h("div", { className: "col-12" },
                h("label", { className: "form-label", htmlFor: "cluster-environment" }, tr("Environment")),
                h("select", { id: "cluster-environment", className: "form-select", name: "environment", defaultValue: "prod" },
                  h("option", { value: "prod" }, "prod"),
                  h("option", { value: "stage" }, "stage"),
                  h("option", { value: "dev" }, "dev")
                )
              ),
              h("div", { className: "col-12" },
                h("label", { className: "form-label", htmlFor: "cluster-backend-url" }, tr("Platform API URL for agents")),
                h("input", {
                  id: "cluster-backend-url",
                  className: "form-control font-monospace",
                  name: "backend_url",
                  type: "url",
                  defaultValue: props.publicApiBase || "",
                  placeholder: "https://rca-api.example.com",
                  required: true,
                }),
                h("div", { className: "form-text" }, tr(backendUrlHelp))
              ),
              h("div", { className: "col-12" },
                h("label", { className: "form-label", htmlFor: "cluster-description" }, tr("Description")),
                h("textarea", { id: "cluster-description", className: "form-control", name: "description", rows: 2, placeholder: tr("Optional note for operators") })
              ),
              h("div", { className: "col-12 d-grid" },
                h("button", { className: "btn btn-primary btn-icon justify-content-center", type: "submit" }, h(Icon, { name: "plus-lg" }), tr("Register and show install command"))
              )
            )
          )
        ),
        h("div", { className: "col-12 col-xl-7" },
          h(Panel, {
            title: "Registered Clusters",
            subtitle: props.loading.clusters ? "Loading" : `${props.clusters.length} clusters`,
            action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: props.onLoadClusters }, h(Icon, { name: "arrow-clockwise" }), "Reload"),
          }, props.clusters.length ? h(React.Fragment, null,
            h("div", { className: "table-responsive desktop-table-view" },
              h("table", { className: "table table-hover mb-0" },
                h("thead", null, h("tr", null,
                  h("th", null, tr("Cluster")),
                  h("th", null, tr("Environment")),
                  h("th", null, tr("Status")),
                  h("th", { className: "text-end" }, tr("Actions"))
                )),
                h("tbody", null, props.clusters.map((cluster) => h(React.Fragment, { key: cluster.cluster_id },
                  h("tr", null,
                    h("td", null,
                      h("button", {
                        type: "button",
                        className: "cluster-name-button",
                        onClick: () => props.onOpenClusterData(cluster.cluster_id),
                      }, cluster.name),
                      h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id)
                    ),
                    h("td", null, cluster.environment),
                    h("td", null, h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) })),
                    h("td", { className: "text-end" },
                      h(ClusterActionGroup, { cluster, props })
                    )
                  ),
                  (props.installCommands[cluster.cluster_id] || props.agentsByCluster[cluster.cluster_id]) && h("tr", null,
                    h("td", { colSpan: 4 },
                      props.installCommands[cluster.cluster_id] && h(InstallCommandPanel, {
                        command: props.installCommands[cluster.cluster_id],
                        onCopy: props.onCopy,
                      }),
                      props.agentsByCluster[cluster.cluster_id] && h(AgentsTable, { state: props.agentsByCluster[cluster.cluster_id] })
                    )
                  )
                )))
              )
            ),
            h("div", { className: "mobile-card-list" },
              props.clusters.map((cluster) => h("article", { key: cluster.cluster_id, className: "mobile-data-card" },
                h("div", { className: "mobile-card-header" },
                  h("div", { className: "mobile-card-title" },
                    h("button", {
                      type: "button",
                      className: "cluster-name-button",
                      onClick: () => props.onOpenClusterData(cluster.cluster_id),
                    }, cluster.name),
                    h("span", { className: "small text-muted font-monospace" }, cluster.cluster_id)
                  ),
                  h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) })
                ),
                h("div", { className: "mobile-field-grid" },
                  h(MobileField, { label: "Environment", value: cluster.environment })
                ),
                h("div", { className: "mobile-card-actions" },
                  h(ClusterActionGroup, { cluster, props, mobile: true })
                ),
                (props.installCommands[cluster.cluster_id] || props.agentsByCluster[cluster.cluster_id]) && h("div", { className: "mobile-card-expanded" },
                  props.installCommands[cluster.cluster_id] && h(InstallCommandPanel, {
                    command: props.installCommands[cluster.cluster_id],
                    onCopy: props.onCopy,
                  }),
                  props.agentsByCluster[cluster.cluster_id] && h(AgentsTable, { state: props.agentsByCluster[cluster.cluster_id] })
                )
              ))
            )
          ) : h(EmptyState, { message: "No registered clusters loaded." }))
        )
      )
    );
  }

  function ClusterActionGroup({ cluster, props, mobile }) {
    const secondaryClass = mobile ? "btn btn-sm btn-outline-secondary btn-icon" : "btn btn-outline-secondary btn-icon";
    const dangerClass = mobile ? "btn btn-sm btn-outline-danger btn-icon" : "btn btn-outline-danger btn-icon";
    return h("div", { className: mobile ? "mobile-action-grid" : "btn-group btn-group-sm" },
      h("button", { type: "button", className: secondaryClass, onClick: () => props.onOpenClusterData(cluster.cluster_id) }, h(Icon, { name: "window-sidebar" }), tr("Data")),
      h("button", { type: "button", className: secondaryClass, onClick: () => props.onCollectCluster(cluster) }, h(Icon, { name: "radar" }), tr("Collect")),
      h("button", { type: "button", className: secondaryClass, onClick: () => props.onLoadInstallCommand(cluster.cluster_id) }, h(Icon, { name: "terminal" }), tr("Install")),
      h("button", { type: "button", className: secondaryClass, onClick: () => props.onLoadAgents(cluster.cluster_id) }, h(Icon, { name: "hdd-stack" }), tr("Agents")),
      props.currentUser?.role === "admin"
        ? h("button", { type: "button", className: secondaryClass, onClick: () => props.onRotateAgentToken(cluster) }, h(Icon, { name: "key" }), tr("Rotate token"))
        : null,
      h("button", { type: "button", className: dangerClass, onClick: () => props.onDeleteCluster(cluster) }, h(Icon, { name: "trash3" }), tr("Delete"))
    );
  }

  function InstallCommandPanel({ command, onCopy }) {
    const isLoading = command === "Loading...";
    const canCopy = command && !isLoading && !command.toLowerCase().includes("failed") && !command.toLowerCase().includes("invalid");
    return h("div", { className: "install-command-panel mb-3" },
      h("div", { className: "install-command-header" },
        h("div", null,
          h("div", { className: "fw-semibold" }, tr("Agent install command")),
          h("div", { className: "small text-muted" }, tr("Run this from a workstation with kubectl access to the target cluster."))
        ),
        h("button", {
          type: "button",
          className: "btn btn-sm btn-outline-secondary btn-icon",
          disabled: !canCopy,
          onClick: () => onCopy(command, "Install command copied."),
        }, h(Icon, { name: "clipboard" }), tr("Copy"))
      ),
      h("pre", { className: "code-block" }, command),
      h("div", { className: "install-checklist" },
        h("span", null, h(Icon, { name: "1-circle" }), tr("Namespace and secret are created first.")),
        h("span", null, h(Icon, { name: "2-circle" }), tr("DaemonSet is applied from the generated manifest URL.")),
        h("span", null, h(Icon, { name: "3-circle" }), tr("Click Agents after rollout to confirm node registration."))
      )
    );
  }

  function WebhooksView({ endpoint, onCopy }) {
    const sample = `receivers:\n  - name: cluster-infra-rca\n    webhook_configs:\n      - url: ${endpoint}\n        send_resolved: true\n        http_config:\n          authorization:\n            type: Bearer\n            credentials_file: /etc/alertmanager/secrets/rca-webhook-token`;
    return h("div", { className: "row g-3" },
      h("div", { className: "col-12 col-xl-5" },
        h(Panel, { title: "Webhook Endpoint", subtitle: "Alertmanager integration" },
          h("div", { className: "d-grid gap-3" },
            h("div", null,
              h("label", { className: "form-label" }, tr("Endpoint")),
              h("div", { className: "input-group" },
                h("input", { className: "form-control font-monospace", readOnly: true, value: endpoint }),
                h("button", { className: "btn btn-outline-secondary", onClick: () => onCopy(endpoint, "Webhook endpoint copied.") }, h(Icon, { name: "clipboard" }))
              )
            ),
            h("div", null,
              h("label", { className: "form-label" }, tr("Authorization")),
              h("code", { className: "d-block p-3 bg-light rounded-2" }, "Authorization: Bearer ${RCA_WEBHOOK_TOKEN}")
            )
          )
        )
      ),
      h("div", { className: "col-12 col-xl-7" },
        h(Panel, { title: "Alertmanager Receiver", subtitle: "YAML sample", action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: () => onCopy(sample, "Receiver sample copied.") }, h(Icon, { name: "clipboard" }), tr("Copy")) },
          h("pre", { className: "code-block" }, sample)
        )
      )
    );
  }

  function IncidentsView({ incidents, loading, onReload, onChangeStatus, currentUser }) {
    return h(Panel, {
      title: "Incidents",
      subtitle: loading ? "Loading" : `${incidents.length} incidents`,
      action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: onReload },
        h(Icon, { name: "arrow-clockwise" }), tr("Reload")),
    }, incidents.length ? h("div", { className: "table-responsive" },
      h("table", { className: "table table-hover align-middle mb-0" },
        h("thead", null, h("tr", null,
          h("th", null, tr("Incident")),
          h("th", null, tr("Cluster")),
          h("th", null, tr("Node")),
          h("th", null, tr("Root Cause")),
          h("th", null, tr("Occurrences")),
          h("th", null, tr("Last seen")),
          h("th", { className: "text-end" }, tr("Actions"))
        )),
        h("tbody", null, incidents.map((incident) => h("tr", { key: incident.incident_id },
          h("td", null,
            h(StatusBadge, { value: incident.status, tone: incident.status === "open" ? "red" : "green" }),
            incident.recurrence_sequence > 0
              ? h("span", { className: "badge text-bg-warning ms-2" },
                  `${tr("Recurrence")} #${incident.recurrence_sequence}`)
              : null,
            h("div", { className: "small font-monospace mt-1" }, incident.incident_id),
            incident.recurrence_of_incident_id
              ? h("div", { className: "small text-secondary mt-1" },
                  `${tr("Previous incident")}: `,
                  h("span", { className: "font-monospace" }, incident.recurrence_of_incident_id))
              : null
          ),
          h("td", { className: "font-monospace small" }, incident.cluster_id),
          h("td", null, listValue(
            Array.isArray(incident.node_names) && incident.node_names.length
              ? incident.node_names
              : [incident.node_name]
          )),
          h("td", null, displayText(incident.root_cause)),
          h("td", null, incident.occurrence_count),
          h("td", null,
            h("div", null, formatDate(incident.last_seen_at)),
            incident.resolved_at
              ? h("div", { className: "small text-secondary mt-1" },
                  `${tr("Resolved at")}: ${formatDate(incident.resolved_at)}`)
              : null,
            incident.resolution_source
              ? h("div", { className: "small text-secondary" },
                  `${tr("Resolution source")}: ${displayText(incident.resolution_source)}`)
              : null
          ),
          h("td", { className: "text-end" },
            ["admin", "operator"].includes(currentUser?.role)
              ? h("button", {
                  className: "btn btn-sm btn-outline-secondary",
                  onClick: () => onChangeStatus(
                    incident.incident_id,
                    incident.status === "open" ? "resolve" : "reopen"
                  ),
                }, tr(incident.status === "open" ? "Resolve" : "Reopen"))
              : null
          )
        )))
      )
    ) : h(EmptyState, { message: "No incidents loaded." }));
  }

  function AuditView({ events, loading, onReload, onExport }) {
    return h(Panel, {
      title: "Audit",
      subtitle: loading ? "Loading" : `${events.length} events`,
      action: h("div", { className: "btn-group btn-group-sm" },
        h("button", { className: "btn btn-outline-secondary btn-icon", onClick: onReload },
          h(Icon, { name: "arrow-clockwise" }), tr("Reload")),
        h("button", { className: "btn btn-outline-secondary btn-icon", onClick: () => onExport("json") },
          h(Icon, { name: "download" }), "JSON"),
        h("button", { className: "btn btn-outline-secondary btn-icon", onClick: () => onExport("csv") },
          h(Icon, { name: "filetype-csv" }), "CSV")
      ),
    }, events.length ? h("div", { className: "table-responsive" },
      h("table", { className: "table table-hover align-middle mb-0" },
        h("thead", null, h("tr", null,
          h("th", null, tr("Created")),
          h("th", null, tr("Actor")),
          h("th", null, tr("Event")),
          h("th", null, tr("Resource")),
          h("th", null, tr("Outcome"))
        )),
        h("tbody", null, events.map((event) => h("tr", { key: event.audit_event_id },
          h("td", null, formatDate(event.created_at)),
          h("td", null, `${event.actor_type}: ${event.actor_id}`),
          h("td", { className: "font-monospace small" }, event.event_type),
          h("td", { className: "small" }, `${event.resource_type}${event.resource_id ? ` / ${event.resource_id}` : ""}`),
          h("td", null, h(StatusBadge, {
            value: event.outcome,
            tone: ["success", "accepted", "approved_manual", "report_created"].includes(event.outcome) ? "green"
              : ["failed", "blocked"].includes(event.outcome) ? "red" : "amber",
          }))
        )))
      )
    ) : h(EmptyState, { message: "No audit events loaded." }));
  }

  function PipelineView({ tasks, loading, onReload, onRetry, currentUser }) {
    const tone = (status) => {
      if (["completed", "skipped"].includes(status)) return "green";
      if (status === "dead_letter") return "red";
      if (status === "retry_wait") return "amber";
      return "blue";
    };
    return h(Panel, {
      title: "Analysis Tasks",
      subtitle: loading ? "Loading" : `${tasks.length} tasks`,
      action: h("button", { className: "btn btn-sm btn-outline-secondary btn-icon", onClick: onReload },
        h(Icon, { name: "arrow-clockwise" }), tr("Reload")),
    }, tasks.length ? h("div", { className: "table-responsive" },
      h("table", { className: "table table-hover align-middle mb-0" },
        h("thead", null, h("tr", null,
          h("th", null, tr("Status")),
          h("th", null, tr("Node")),
          h("th", null, tr("Symptom")),
          h("th", null, tr("Attempt")),
          h("th", null, tr("Next attempt")),
          h("th", null, tr("Last error")),
          h("th", { className: "text-end" }, tr("Actions"))
        )),
        h("tbody", null, tasks.map((task) => h("tr", { key: task.task_id },
          h("td", null,
            h(StatusBadge, { value: task.status, tone: tone(task.status) }),
            h("div", { className: "small font-monospace mt-1" }, task.task_id)
          ),
          h("td", null,
            h("div", null, task.node_name),
            h("div", { className: "small text-muted font-monospace" }, task.cluster_id)
          ),
          h("td", null, task.alert_name),
          h("td", null, `${task.attempt_count} / ${task.max_attempts}`),
          h("td", null, formatDate(task.next_attempt_at)),
          h("td", { className: "small text-break", style: { minWidth: "14rem" } },
            task.last_error || "-"),
          h("td", { className: "text-end" },
            task.status === "dead_letter" && ["admin", "operator"].includes(currentUser?.role)
              ? h("button", {
                  className: "btn btn-sm btn-outline-danger",
                  onClick: () => onRetry(task.task_id),
                }, tr("Retry task"))
              : task.report_id
                ? h("span", { className: "small font-monospace" }, task.report_id)
                : "-"
          )
        )))
      )
    ) : h(EmptyState, { message: "No analysis tasks loaded." }));
  }

  function ReportsView({
    reports,
    loading,
    reportDetails,
    onLoadReports,
    onToggleReport,
    onPrepareAction,
    onExportReports,
    onExportReport,
    onExportBundle,
    onCopy,
    currentUser,
    onDecideAction,
    onCompleteManual,
  }) {
    const canExport = ["admin", "operator"].includes(currentUser?.role);
    return h(Panel, {
      title: "RCA Reports",
      subtitle: loading.reports ? "Loading" : `${reports.length} reports`,
      action: h("div", { className: "btn-group btn-group-sm" },
        canExport ? h("button", { className: "btn btn-outline-secondary btn-icon", onClick: onExportReports }, h(Icon, { name: "download" }), tr("Export all")) : null,
        h("button", { className: "btn btn-outline-secondary btn-icon", onClick: onLoadReports }, h(Icon, { name: "arrow-clockwise" }), tr("Reload"))
      ),
    }, reports.length ? h(React.Fragment, null,
      h("div", { className: "table-responsive desktop-table-view" },
        h("table", { className: "table table-hover mb-0" },
          h("thead", null, h("tr", null,
            h("th", null, tr("Symptom")),
            h("th", null, tr("Cluster")),
            h("th", null, tr("Confidence")),
            h("th", null, tr("Policy")),
            h("th", { className: "text-end" }, tr("Actions"))
          )),
          h("tbody", null, reports.map((report) => {
            const detail = reportDetails[report.report_id];
            return h(React.Fragment, { key: report.report_id },
              h("tr", null,
                h("td", null,
                  h("div", { className: "fw-semibold" }, displaySummary(report.summary?.symptom)),
                  h("div", { className: "small text-muted text-truncate-cell" }, displayText(report.summary?.most_likely_cause || report.report_id))
                ),
                h("td", { className: "font-monospace small" }, report.cluster_id),
                h("td", null, h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })),
                h("td", null, uniquePolicies(report).map((policy) => h(StatusBadge, { key: policy, value: policy, tone: policyTone(policy) }))),
                h("td", { className: "text-end" },
                  h("div", { className: "btn-group btn-group-sm" },
                    h("button", { className: "btn btn-outline-secondary", onClick: () => onToggleReport(report.report_id) }, detail?.open ? tr("Hide") : tr("Detail")),
                    canExport ? h("button", { className: "btn btn-outline-secondary", onClick: () => onExportReport(report.report_id) }, tr("Export")) : null,
                    canExport ? h("button", { className: "btn btn-outline-secondary", onClick: () => onCopy(JSON.stringify(report, null, 2), "Report summary copied.") }, tr("Copy")) : null
                  )
                )
              ),
              detail?.open && h("tr", null, h("td", { colSpan: 5 }, h(ReportDetail, {
                detail,
                onPrepareAction,
                onExportReport,
                onExportBundle,
                onCopy,
                currentUser,
                onDecideAction,
                onCompleteManual,
              })))
            );
          }))
        )
      ),
      h("div", { className: "mobile-card-list" },
        reports.map((report) => {
          const detail = reportDetails[report.report_id];
          return h("article", { key: report.report_id, className: "mobile-data-card" },
            h("div", { className: "mobile-card-header" },
              h("div", { className: "mobile-card-title" },
                h("strong", null, displaySummary(report.summary?.symptom)),
                h("span", { className: "small text-muted" }, displayText(report.summary?.most_likely_cause || report.report_id))
              ),
              h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })
            ),
            h("div", { className: "mobile-field-grid" },
              h(MobileField, { label: "Cluster", value: report.cluster_id, mono: true }),
              h(MobileField, { label: "Policy" },
                h("div", { className: "d-flex gap-1 flex-wrap" },
                  uniquePolicies(report).map((policy) => h(StatusBadge, { key: policy, value: policy, tone: policyTone(policy) }))
                )
              )
            ),
            h("div", { className: "mobile-card-actions" },
              h("div", { className: "btn-group btn-group-sm" },
                h("button", { className: "btn btn-outline-secondary", onClick: () => onToggleReport(report.report_id) }, detail?.open ? tr("Hide") : tr("Detail")),
                canExport ? h("button", { className: "btn btn-outline-secondary", onClick: () => onExportReport(report.report_id) }, tr("Export")) : null,
                canExport ? h("button", { className: "btn btn-outline-secondary", onClick: () => onCopy(JSON.stringify(report, null, 2), "Report summary copied.") }, tr("Copy")) : null
              )
            ),
            detail?.open && h("div", { className: "mobile-card-expanded" },
              h(ReportDetail, { detail, onPrepareAction, onExportReport, onExportBundle, onCopy, currentUser, onDecideAction, onCompleteManual })
            )
          );
        })
      )
    ) : h(EmptyState, { message: "No reports loaded." }));
  }

  function DemoScenariosView({ state, onReload, onRun, canRun }) {
    return h("div", { className: "d-grid gap-3" },
      h(Panel, {
        title: "Demo Scenarios",
        subtitle: state.enabled
          ? "Generate evidence through the same RCA analysis pipeline used by node agents."
          : "Disabled by configuration. Set RCA_DEMO_ENABLED=true outside production.",
        action: h("button", {
          type: "button",
          className: "btn btn-sm btn-outline-secondary btn-icon",
          onClick: onReload,
        }, h(Icon, { name: "arrow-clockwise" }), tr("Reload")),
      },
      state.loading
        ? h(EmptyState, { message: "Loading demo scenarios." })
        : state.error
          ? h(EmptyState, { message: state.error })
          : h("div", { className: "demo-scenario-grid" },
              state.items.map((scenario) => h("article", {
                key: scenario.key,
                className: "demo-scenario-card",
              },
              h("div", { className: "d-flex justify-content-between gap-2 align-items-start" },
                h("div", null,
                  h("strong", null, scenario.name),
                  h("div", { className: "small text-muted font-monospace mt-1" }, scenario.alert_name)
                ),
                h(StatusBadge, { value: state.enabled ? "ready" : "disabled", tone: state.enabled ? "green" : "amber" })
              ),
              h("p", { className: "small text-muted mb-0" }, scenario.description),
              h("button", {
                type: "button",
                className: "btn btn-sm btn-outline-primary btn-icon",
                disabled: !state.enabled || !canRun,
                onClick: () => onRun(scenario),
              }, h(Icon, { name: "play-fill" }), "Run")
              ))
            )
      )
    );
  }

  function SettingsView({ apiBase, publicApiBase, autoRefresh, currentUser, onChangePassword, locale, onChangeLanguage, platformInfo }) {
    const rows = [
      ["Platform API", apiBase || tr("same origin")],
      ["Public API", publicApiBase],
      ["Signed in", currentUser.email],
      ["Role", currentUser.role],
      ["Refresh mode", autoRefresh ? "auto / 30s" : "manual"],
      ["Webhook token env", "RCA_WEBHOOK_TOKEN"],
      ["LLM provider env", "RCA_LLM_PROVIDER"],
      ["Database env", "RCA_JDBC_URL"],
      ["Platform version", platformInfo?.platform_version || "n/a"],
      ["API version", platformInfo?.api_version || "n/a"],
      ["Agent protocol", platformInfo
        ? `${platformInfo.minimum_supported_agent_protocol_version} - ${platformInfo.agent_protocol_version}`
        : "n/a"],
      ["Minimum agent version", platformInfo?.minimum_supported_agent_version || "n/a"],
    ];
    return h("div", { className: "row g-3" },
      h("div", { className: "col-12 col-xl-8" },
        h(Panel, { title: tr("Console Settings"), subtitle: tr("Runtime references") },
          h("div", { className: "row g-3" },
            rows.map(([label, value]) => h("div", { className: "col-12 col-md-6 col-xl-3", key: label },
              h("div", { className: "border rounded-2 p-3 bg-light h-100" },
                h("div", { className: "small text-muted fw-semibold mb-2" }, tr(label)),
                h("code", { className: "small text-break" }, value)
              )
            )),
            h("div", { className: "col-12 col-md-6 col-xl-3" },
              h("div", { className: "border rounded-2 p-3 bg-light h-100" },
                h("div", { className: "small text-muted fw-semibold mb-2" }, tr("Language")),
                h(LanguageSelect, { locale, onChangeLanguage }),
                h("div", { className: "small text-muted mt-2" }, tr("Language preference is saved in this browser."))
              )
            )
          )
        )
      ),
      h("div", { className: "col-12 col-xl-4" },
        h(Panel, { title: tr("Change Password"), subtitle: tr("Change the current administrator password") },
          h("form", { className: "row g-3", onSubmit: onChangePassword },
            h(InputField, { label: tr("Current password"), name: "current_password", type: "password", required: true, autoComplete: "current-password" }),
            h(InputField, { label: tr("New password"), name: "new_password", type: "password", minLength: 8, required: true, autoComplete: "new-password" }),
            h(InputField, { label: tr("Confirm password"), name: "confirm_password", type: "password", minLength: 8, required: true, autoComplete: "new-password" }),
            h("div", { className: "col-12 d-grid" },
              h("button", { className: "btn btn-primary btn-icon justify-content-center", type: "submit" }, h(Icon, { name: "key" }), tr("Update password"))
            )
          )
        )
      )
    );
  }

  function MetricTile({ label, value, hint, icon, compact }) {
    return h("div", { className: "col-12 col-md-6 col-xl-3" },
      h("div", { className: "metric-tile" },
        h("div", { className: "d-flex justify-content-between gap-2" },
          h("span", { className: "label" }, tr(label)),
          h(Icon, { name: icon })
        ),
        h("span", { className: compact ? "value h5" : "value" }, typeof value === "string" ? displayText(value) : value),
        h("div", { className: "hint text-truncate" }, displayText(hint))
      )
    );
  }

  function Panel({ title, subtitle, action, children }) {
    return h("section", { className: "console-panel" },
      h("div", { className: "console-panel-header" },
        h("div", null, h("h2", { className: "console-panel-title" }, tr(title)), subtitle && h("p", { className: "console-panel-subtitle" }, displayText(subtitle))),
        action && h("div", null, action)
      ),
      h("div", { className: "console-panel-body" }, children)
    );
  }

  function MobileField({ label, value, children, mono }) {
    const content = children !== undefined
      ? h("div", { className: mono ? "font-monospace" : "" }, children)
      : h("strong", { className: mono ? "font-monospace" : "" }, displayText(value));
    return h("div", { className: "mobile-field" },
      h("span", null, tr(label)),
      content
    );
  }

  function ClusterTable({ clusters }) {
    if (!clusters.length) return h(EmptyState, { message: "No clusters loaded." });
    return h(React.Fragment, null,
      h("div", { className: "table-responsive desktop-table-view" },
        h("table", { className: "table table-hover mb-0" },
          h("thead", null, h("tr", null, h("th", null, tr("Name")), h("th", null, tr("Environment")), h("th", null, tr("Status")))),
          h("tbody", null, clusters.map((cluster) => h("tr", { key: cluster.cluster_id },
            h("td", null, h("div", { className: "fw-semibold" }, cluster.name), h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id)),
            h("td", null, cluster.environment),
            h("td", null, h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) }))
          )))
        )
      ),
      h("div", { className: "mobile-card-list" },
        clusters.map((cluster) => h("article", { key: cluster.cluster_id, className: "mobile-data-card" },
          h("div", { className: "mobile-card-header" },
            h("div", { className: "mobile-card-title" },
              h("strong", null, cluster.name),
              h("span", { className: "small text-muted font-monospace" }, cluster.cluster_id)
            ),
            h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) })
          ),
          h("div", { className: "mobile-field-grid" },
            h(MobileField, { label: "Environment", value: cluster.environment })
          )
        ))
      )
    );
  }

  function AgentsTable({ state }) {
    if (state.loading) return h(EmptyState, { message: "Loading agents." });
    if (state.error) return h(EmptyState, { message: state.error });
    if (!state.items.length) return h(EmptyState, { message: "No agents registered." });
    return h(React.Fragment, null,
      h("div", { className: "table-responsive desktop-table-view" },
        h("table", { className: "table table-sm mb-0" },
          h("thead", null, h("tr", null, h("th", null, tr("Node")), h("th", null, tr("Status")), h("th", null, tr("Version")), h("th", null, tr("Last seen")))),
          h("tbody", null, state.items.map((agent) => h("tr", { key: agent.node_name },
            h("td", { className: "font-monospace small" }, agent.node_name),
            h("td", null,
              h(StatusBadge, {
                value: agent.health_status || agent.status || "unknown",
                tone: agentStatusTone(agent.health_status || agent.status),
              }),
              (agent.reasons || []).length
                ? h("div", { className: "small text-muted mt-1" }, agent.reasons.join(" "))
                : null
            ),
            h("td", null,
              h("div", null, agent.agent_version || "n/a"),
              h("div", { className: "small text-muted" },
                `protocol ${agent.agent_protocol_version || "1"} / ${agent.platform_protocol_version || "1"}`
              )
            ),
            h("td", null, formatAgentLastSeen(agent))
          )))
        )
      ),
      h("div", { className: "mobile-card-list" },
        state.items.map((agent) => h("article", { key: agent.node_name, className: "mobile-data-card" },
          h("div", { className: "mobile-card-header" },
            h("div", { className: "mobile-card-title" },
              h("strong", { className: "font-monospace" }, agent.node_name)
            ),
            h(StatusBadge, {
              value: agent.health_status || agent.status || "unknown",
              tone: agentStatusTone(agent.health_status || agent.status),
            })
          ),
          h("div", { className: "mobile-field-grid" },
            h(MobileField, {
              label: "Version",
              value: `${agent.agent_version || "n/a"} / protocol ${agent.agent_protocol_version || "1"}`,
            }),
            h(MobileField, { label: "Last seen", value: formatAgentLastSeen(agent) }),
            (agent.reasons || []).length
              ? h(MobileField, { label: "Reason", value: agent.reasons.join(" ") })
              : null
          )
        ))
      )
    );
  }

  function ClusterDataModal({ state, onClose, onRefresh, onLoadEvidence, onCollectCluster, onExportReports, onCopy, canExport }) {
    const cluster = state.cluster || {};
    const agents = state.agents || [];
    const evidenceRequests = state.evidenceRequests || [];
    const reports = state.reports || [];
    const topology = state.topology || {};
    const topologyComparison = state.topologyComparison || null;
    const topologyEntities = Array.isArray(topology.entities) ? topology.entities : [];
    const topologyRelations = Array.isArray(topology.relations) ? topology.relations : [];
    const topologyPods = topologyEntities.filter((entity) => entity.kind === "Pod").length;
    const topologyServices = topologyEntities.filter((entity) => entity.kind === "Service").length;
    const body = state.loading
      ? h(EmptyState, { message: "Loading cluster data." })
      : state.error
        ? h(EmptyState, { message: state.error })
        : h("div", { className: "d-grid gap-3" },
          h("div", { className: "summary-grid" },
            h(SummaryBox, { label: "Status", value: h(StatusBadge, { value: cluster.status, tone: clusterStatusTone(cluster.status) }) }),
            h(SummaryBox, { label: "Environment", value: cluster.environment || "n/a" }),
            h(SummaryBox, { label: "Agents", value: agents.length }),
            h(SummaryBox, { label: "Evidence requests", value: evidenceRequests.length })
          ),
          h("div", null,
            h("div", { className: "d-flex justify-content-between gap-2 align-items-center mb-2" },
              h("h3", { className: "h6 mb-0" }, tr("Cluster Topology")),
              h("span", { className: "small text-muted" },
                topology.observed_at ? formatDate(topology.observed_at) : tr("Not observed"))
            ),
            h("div", { className: "summary-grid" },
              h(SummaryBox, { label: "Nodes", value: (topology.nodes || []).length }),
              h(SummaryBox, { label: "Pods", value: topologyPods }),
              h(SummaryBox, { label: "Services", value: topologyServices }),
              h(SummaryBox, { label: "Relations", value: topologyRelations.length })
            ),
            h(TopologyGraph, { topology, comparison: topologyComparison }),
            topologyComparison
              ? h("div", { className: "topology-change-summary small mt-2" },
                  h("strong", null, tr("Topology change")),
                  h("span", null, `+${topologyComparison.added_entity_ids?.length || 0} / -${topologyComparison.removed_entity_ids?.length || 0} resources`),
                  h("span", null, `+${topologyComparison.added_relations?.length || 0} / -${topologyComparison.removed_relations?.length || 0} relations`)
                )
              : null,
            h("div", { className: "small text-muted mt-2" },
              topology.inventory_complete
                ? tr("Cluster-wide Service and EndpointSlice inventory is complete.")
                : tr("Topology is partial until the elected agent collects Service and EndpointSlice inventory."))
          ),
          h("div", null,
            h("h3", { className: "h6 mb-2" }, tr("Node Agents")),
            h(AgentsTable, { state: { loading: false, items: agents } })
          ),
          h("div", null,
            h("h3", { className: "h6 mb-2" }, tr("Evidence Requests")),
            h(EvidenceRequestTable, { items: evidenceRequests, onLoadEvidence })
          ),
          h("div", null,
            h("div", { className: "d-flex justify-content-between gap-2 align-items-center mb-2" },
              h("h3", { className: "h6 mb-0" }, tr("Collected Evidence")),
              state.selectedEvidence && canExport && h("button", {
                type: "button",
                className: "btn btn-sm btn-outline-secondary btn-icon",
                onClick: () => onCopy(JSON.stringify(state.selectedEvidence, null, 2), "Evidence bundle copied."),
              }, h(Icon, { name: "clipboard" }), tr("Copy"))
            ),
            h(EvidenceBundlePreview, { state })
          ),
          h("div", null,
            h("div", { className: "d-flex justify-content-between gap-2 align-items-center mb-2" },
              h("h3", { className: "h6 mb-0" }, tr("Recent RCA")),
              reports.length ? h("div", { className: "btn-group btn-group-sm" },
                canExport ? h("button", {
                  type: "button",
                  className: "btn btn-outline-secondary btn-icon",
                  onClick: onExportReports,
                }, h(Icon, { name: "download" }), tr("Export")) : null,
                canExport ? h("button", {
                  type: "button",
                  className: "btn btn-outline-secondary btn-icon",
                  onClick: () => onCopy(JSON.stringify(reports, null, 2), "Cluster RCA reports copied."),
                }, h(Icon, { name: "clipboard" }), tr("Copy")) : null
              ) : null
            ),
            h(ClusterReportList, { items: reports })
          )
        );

    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onClose() },
      h("section", { className: "console-modal cluster-data-modal", role: "dialog", "aria-modal": "true" },
        h("div", { className: "console-modal-header" },
          h("div", null,
            h("h2", { className: "h5 mb-1" }, cluster.name || state.clusterId),
          h("div", { className: "small text-muted font-monospace" }, state.clusterId)
          ),
          h("div", { className: "d-flex gap-2" },
            h("button", { type: "button", className: "btn btn-sm btn-outline-secondary btn-icon", disabled: !state.cluster, onClick: () => onCollectCluster(state.cluster) }, h(Icon, { name: "radar" }), tr("Collect")),
            h("button", { type: "button", className: "btn btn-sm btn-outline-secondary btn-icon", onClick: onRefresh }, h(Icon, { name: "arrow-clockwise" }), tr("Reload")),
            h("button", { type: "button", className: "btn btn-sm btn-outline-secondary", onClick: onClose }, tr("Close"))
          )
        ),
        h("div", { className: "console-modal-body" }, body)
      )
    );
  }

  function SummaryBox({ label, value }) {
    return h("div", { className: "summary-box" },
      h("div", { className: "small text-muted fw-semibold" }, tr(label)),
      h("div", { className: "summary-value" }, value)
    );
  }

  function TopologyGraph({ topology, comparison }) {
    const entities = Array.isArray(topology?.entities) ? topology.entities : [];
    const relations = Array.isArray(topology?.relations) ? topology.relations : [];
    const entityById = new Map(entities.map((entity) => [entity.id, entity]));
    const changedEntityIds = new Set(comparison?.added_entity_ids || []);
    const nodeByPod = new Map();
    relations
      .filter((relation) => relation.relationship === "hosts")
      .forEach((relation) => nodeByPod.set(relation.target, relation.source));

    const pairMap = new Map();
    function addPair(serviceId, nodeId, relationship) {
      if (entityById.get(serviceId)?.kind !== "Service" || entityById.get(nodeId)?.kind !== "Node") return;
      const key = `${serviceId}|${nodeId}`;
      const current = pairMap.get(key);
      const direct = relationship === "has_endpoint_on";
      pairMap.set(key, {
        serviceId,
        nodeId,
        direct: direct || current?.direct === true,
      });
    }
    relations.forEach((relation) => {
      if (relation.relationship === "has_endpoint_on") {
        addPair(relation.source, relation.target, relation.relationship);
      }
      if (relation.relationship === "routes_to" || relation.relationship === "selects") {
        const nodeId = nodeByPod.get(relation.target);
        if (nodeId) addPair(relation.source, nodeId, relation.relationship);
      }
    });

    const pairs = Array.from(pairMap.values());
    const connectionCount = new Map();
    pairs.forEach((pair) => {
      connectionCount.set(pair.serviceId, (connectionCount.get(pair.serviceId) || 0) + 1);
      connectionCount.set(pair.nodeId, (connectionCount.get(pair.nodeId) || 0) + 1);
    });
    const services = entities
      .filter((entity) => entity.kind === "Service")
      .sort((left, right) =>
        (connectionCount.get(right.id) || 0) - (connectionCount.get(left.id) || 0)
          || topologyLabel(left).localeCompare(topologyLabel(right)))
      .slice(0, 8);
    const nodes = entities
      .filter((entity) => entity.kind === "Node")
      .sort((left, right) =>
        (connectionCount.get(right.id) || 0) - (connectionCount.get(left.id) || 0)
          || left.name.localeCompare(right.name))
      .slice(0, 10);
    if (!services.length && !nodes.length) {
      return h("div", { className: "topology-graph-empty" }, tr("No topology relationships observed."));
    }

    const serviceIds = new Set(services.map((entity) => entity.id));
    const nodeIds = new Set(nodes.map((entity) => entity.id));
    const visiblePairs = pairs.filter((pair) =>
      serviceIds.has(pair.serviceId) && nodeIds.has(pair.nodeId));
    const height = Math.max(240, Math.max(services.length, nodes.length, 1) * 58 + 50);
    const serviceY = new Map(services.map((entity, index) => [
      entity.id,
      graphPosition(index, services.length, height),
    ]));
    const nodeY = new Map(nodes.map((entity, index) => [
      entity.id,
      graphPosition(index, nodes.length, height),
    ]));
    const limited = services.length < entities.filter((entity) => entity.kind === "Service").length
      || nodes.length < entities.filter((entity) => entity.kind === "Node").length;

    return h("section", { className: "topology-graph-shell mt-3" },
      h("div", { className: "topology-graph-header" },
        h("div", null,
          h("div", { className: "fw-semibold small" }, tr("Topology Relationship Graph")),
          h("div", { className: "small text-muted" },
            tr("Service to node endpoint and selector relationships"))
        ),
        h("div", { className: "topology-graph-legend" },
          h("span", null, h("i", { className: "topology-legend-line direct" }), tr("Endpoint relationship")),
          h("span", null, h("i", { className: "topology-legend-line inferred" }), tr("Selector-derived relationship"))
        )
      ),
      h("div", { className: "topology-graph-scroll" },
        h("svg", {
          className: "topology-graph",
          viewBox: `0 0 960 ${height}`,
          role: "img",
          "aria-label": tr("Topology Relationship Graph"),
        },
          h("text", { x: 28, y: 24, className: "topology-column-label" }, tr("Services")),
          h("text", { x: 702, y: 24, className: "topology-column-label" }, tr("Nodes")),
          visiblePairs.map((pair) => {
            const startY = serviceY.get(pair.serviceId);
            const endY = nodeY.get(pair.nodeId);
            return h("path", {
              key: `${pair.serviceId}-${pair.nodeId}`,
              className: `topology-edge ${pair.direct ? "direct" : "inferred"}`,
              d: `M 274 ${startY} C 420 ${startY}, 540 ${endY}, 686 ${endY}`,
            });
          }),
          services.map((service) => h("g", { key: service.id },
            h("rect", {
              className: `topology-resource-box service ${changedEntityIds.has(service.id) ? "changed" : ""}`,
              x: 24,
              y: serviceY.get(service.id) - 22,
              width: 250,
              height: 44,
              rx: 6,
            }),
            h("text", {
              className: "topology-resource-name",
              x: 38,
              y: serviceY.get(service.id) - 3,
            }, shortTopologyLabel(topologyLabel(service), 32)),
            h("text", {
              className: "topology-resource-meta",
              x: 38,
              y: serviceY.get(service.id) + 13,
            }, `${connectionCount.get(service.id) || 0} node(s)`)
          )),
          nodes.map((node) => h("g", { key: node.id },
            h("rect", {
              className: `topology-resource-box node ${node.attributes?.ready === false ? "not-ready" : ""} ${changedEntityIds.has(node.id) ? "changed" : ""}`,
              x: 686,
              y: nodeY.get(node.id) - 22,
              width: 250,
              height: 44,
              rx: 6,
            }),
            h("text", {
              className: "topology-resource-name",
              x: 700,
              y: nodeY.get(node.id) - 3,
            }, shortTopologyLabel(node.name, 32)),
            h("text", {
              className: "topology-resource-meta",
              x: 700,
              y: nodeY.get(node.id) + 13,
            }, `${(node.roles || []).join(", ") || "worker"} / ${node.attributes?.ready === false ? "NotReady" : "Ready"}`)
          ))
        )
      ),
      limited
        ? h("div", { className: "small text-muted px-3 pb-2" },
            tr("Graph is limited to the most connected resources."))
        : null
    );
  }

  function graphPosition(index, count, height) {
    if (count <= 1) return height / 2;
    return 48 + (index * (height - 82)) / (count - 1);
  }

  function topologyLabel(entity) {
    return entity.namespace
      ? `${entity.namespace}/${entity.name}`
      : entity.name;
  }

  function shortTopologyLabel(value, maxLength) {
    const text = String(value || "");
    return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1)}…`;
  }

  function EvidenceRequestTable({ items, onLoadEvidence }) {
    if (!items.length) return h(EmptyState, { message: "No evidence requests." });
    const visibleItems = items.slice(0, 8);
    return h(React.Fragment, null,
      h("div", { className: "table-responsive desktop-table-view" },
        h("table", { className: "table table-sm mb-0" },
          h("thead", null, h("tr", null,
            h("th", null, tr("Request")),
            h("th", null, tr("Node")),
            h("th", null, tr("Alert")),
            h("th", null, tr("Status")),
            h("th", null, tr("Created")),
            h("th", { className: "text-end" }, tr("Data"))
          )),
          h("tbody", null, visibleItems.map((item) => h("tr", { key: item.request_id },
            h("td", { className: "font-monospace small" }, item.request_id),
            h("td", { className: "font-monospace small" }, item.node_name),
            h("td", null, item.alert_name),
            h("td", null, h(StatusBadge, { value: item.status, tone: evidenceStatusTone(item.status) })),
            h("td", null, formatDate(item.created_at)),
            h("td", { className: "text-end" },
              h("button", {
                type: "button",
                className: "btn btn-sm btn-outline-secondary",
                disabled: !item.evidence_id,
                onClick: () => onLoadEvidence(item.evidence_id),
              }, tr("View"))
            )
          )))
        )
      ),
      h("div", { className: "mobile-card-list" },
        visibleItems.map((item) => h("article", { key: item.request_id, className: "mobile-data-card" },
          h("div", { className: "mobile-card-header" },
            h("div", { className: "mobile-card-title" },
              h("strong", { className: "font-monospace" }, item.request_id),
              h("span", { className: "small text-muted" }, item.alert_name)
            ),
            h(StatusBadge, { value: item.status, tone: evidenceStatusTone(item.status) })
          ),
          h("div", { className: "mobile-field-grid" },
            h(MobileField, { label: "Node", value: item.node_name, mono: true }),
            h(MobileField, { label: "Created", value: formatDate(item.created_at) })
          ),
          h("div", { className: "mobile-card-actions" },
            h("button", {
              type: "button",
              className: "btn btn-sm btn-outline-secondary btn-icon w-100",
              disabled: !item.evidence_id,
              onClick: () => onLoadEvidence(item.evidence_id),
            }, h(Icon, { name: "eye" }), tr("View"))
          )
        ))
      )
    );
  }

  function EvidenceBundlePreview({ state }) {
    if (state.evidenceLoading) return h(EmptyState, { message: "Loading evidence bundle." });
    if (state.evidenceError) return h(EmptyState, { message: state.evidenceError });
    const evidence = state.selectedEvidence;
    if (!evidence) return h(EmptyState, { message: "Select a completed evidence request." });
    const collectors = Object.keys(evidence.collectors || {});
    return h("div", { className: "evidence-preview" },
      h("div", { className: "detail-grid mb-2" },
        h("dl", { className: "detail-list mb-0" },
          h(DetailRow, { label: "Evidence", value: evidence.evidence_id || "n/a" }),
          h(DetailRow, { label: "Node", value: evidence.node_name || "n/a" })
        ),
        h("dl", { className: "detail-list mb-0" },
          h(DetailRow, { label: "Alert", value: evidence.alert_name || "n/a" }),
          h(DetailRow, { label: "Collectors", value: listValue(collectors) })
        )
      ),
      h("pre", { className: "code-block evidence-code" }, JSON.stringify(evidence.collectors || {}, null, 2))
    );
  }

  function ClusterReportList({ items }) {
    if (!items.length) return h(EmptyState, { message: "No RCA reports for this cluster." });
    return h("div", { className: "list-group list-group-flush" },
      items.slice(0, 5).map((report) => h("div", { key: report.report_id, className: "list-group-item px-0" },
        h("div", { className: "d-flex justify-content-between gap-2" },
          h("strong", { className: "small" }, displaySummary(report.summary?.symptom || report.report_id)),
          h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) })
        ),
        h("div", { className: "small text-muted text-truncate" }, displayText(report.summary?.most_likely_cause || "n/a"))
      ))
    );
  }

  function ActionConfirmDialog({ state, onCancel, onConfirm }) {
    const action = state.action || {};
    const report = state.report || {};
    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onCancel() },
      h("section", { className: "console-modal action-confirm-modal", role: "dialog", "aria-modal": "true" },
        h("div", { className: "console-modal-header" },
          h("div", null,
            h("h2", { className: "h5 mb-1" }, tr("Confirm Action")),
            h("div", { className: "small text-muted font-monospace" }, report.report_id)
          ),
          h(StatusBadge, { value: action.policy, tone: policyTone(action.policy) })
        ),
        h("div", { className: "console-modal-body d-grid gap-3" },
          h("div", { className: "action-card" },
            h("div", { className: "fw-semibold" }, displayActionText(action)),
            h("div", { className: "small text-muted mt-1" }, displayReasonText(action)),
            h("div", { className: "small text-muted mt-1" }, action.automation_allowed
              ? tr("This will request read-only follow-up evidence from the node agent.")
              : tr("The policy gate will record the request status without direct node mutation."))
          ),
          Boolean(action.guardrails?.length) && h("div", { className: "alert alert-warning mb-0 py-2" }, `${tr("Guardrails")}: ${action.guardrails.join(", ")}`),
          state.error && h("div", { className: "alert alert-danger mb-0 py-2" }, state.error)
        ),
        h("div", { className: "console-modal-footer" },
          h("button", { type: "button", className: "btn btn-outline-secondary", onClick: onCancel, disabled: state.loading }, tr("Cancel")),
          h("button", { type: "button", className: "btn btn-primary btn-icon", onClick: onConfirm, disabled: state.loading },
            state.loading ? h(Icon, { name: "hourglass-split" }) : h(Icon, { name: "check2" }),
            state.loading ? tr("Processing") : tr("Confirm")
          )
        )
      )
    );
  }

  function CollectionConfirmDialog({ state, onCancel, onConfirm }) {
    const cluster = state.cluster || {};
    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onCancel() },
      h("section", { className: "console-modal action-confirm-modal", role: "dialog", "aria-modal": "true" },
        h("div", { className: "console-modal-header" },
          h("div", null,
            h("h2", { className: "h5 mb-1" }, tr("Confirm Collection")),
            h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id || "n/a")
          ),
          h(StatusBadge, { value: "read-only", tone: "green" })
        ),
        h("div", { className: "console-modal-body d-grid gap-3" },
          h("div", { className: "action-card" },
            h("div", { className: "fw-semibold" }, cluster.name || tr("Cluster collection")),
            h("div", { className: "small text-muted mt-1" },
              tr("Backend will create read-only evidence requests for registered online node agents. Submitted evidence will be analyzed by the existing RCA pipeline.")
            ),
            h("div", { className: "small text-muted mt-1" }, tr("No Prometheus or Alertmanager trigger is required."))
          ),
          state.error && h("div", { className: "alert alert-danger mb-0 py-2" }, state.error)
        ),
        h("div", { className: "console-modal-footer" },
          h("button", { type: "button", className: "btn btn-outline-secondary", onClick: onCancel, disabled: state.loading }, tr("Cancel")),
          h("button", { type: "button", className: "btn btn-primary btn-icon", onClick: onConfirm, disabled: state.loading },
            state.loading ? h(Icon, { name: "hourglass-split" }) : h(Icon, { name: "radar" }),
            state.loading ? tr("Requesting") : tr("Collect")
          )
        )
      )
    );
  }

  function DeleteClusterDialog({ state, onCancel, onChangeConfirm, onConfirm }) {
    const cluster = state.cluster || {};
    const confirmName = state.confirmName || "";
    const canDelete = confirmName.trim() === cluster.name;
    return h("div", { className: "console-modal-backdrop", role: "presentation", onMouseDown: (event) => event.target === event.currentTarget && onCancel() },
      h("section", { className: "console-modal action-confirm-modal", role: "dialog", "aria-modal": "true" },
        h("form", { onSubmit: onConfirm },
          h("div", { className: "console-modal-header" },
            h("div", null,
              h("h2", { className: "h5 mb-1" }, tr("Confirm Delete")),
              h("div", { className: "small text-muted font-monospace" }, cluster.cluster_id || "n/a")
            ),
            h(StatusBadge, { value: "prohibited", tone: "red" })
          ),
          h("div", { className: "console-modal-body d-grid gap-3" },
            h("div", { className: "action-card" },
              h("div", { className: "fw-semibold" }, `${tr("Delete cluster")}: ${cluster.name || "n/a"}`),
              h("div", { className: "small text-muted mt-1" },
                tr("This removes the cluster registration and all stored agents, evidence requests, evidence bundles, RCA jobs, and reports from the platform.")
              ),
              h("div", { className: "small text-muted mt-1" }, tr("Agent DaemonSets in target clusters are not removed automatically."))
            ),
            h("div", null,
              h("label", { className: "form-label", htmlFor: "delete-cluster-confirm-name" }, tr("Type the cluster name to confirm deletion.")),
              h("input", {
                id: "delete-cluster-confirm-name",
                className: "form-control font-monospace",
                value: confirmName,
                autoFocus: true,
                disabled: state.loading,
                onChange: (event) => onChangeConfirm(event.target.value),
                placeholder: cluster.name || "",
              })
            ),
            state.error && h("div", { className: "alert alert-danger mb-0 py-2" }, state.error)
          ),
          h("div", { className: "console-modal-footer" },
            h("button", { type: "button", className: "btn btn-outline-secondary", onClick: onCancel, disabled: state.loading }, tr("Cancel")),
            h("button", { type: "submit", className: "btn btn-danger btn-icon", disabled: state.loading || !canDelete },
              state.loading ? h(Icon, { name: "hourglass-split" }) : h(Icon, { name: "trash3" }),
              state.loading ? tr("Deleting") : tr("Delete")
            )
          )
        )
      )
    );
  }

  function ReportDetail({ detail, onPrepareAction, onExportReport, onExportBundle, onCopy, currentUser, onDecideAction, onCompleteManual }) {
    if (detail.loading) return h(EmptyState, { message: "Loading report detail." });
    if (detail.error) return h(EmptyState, { message: detail.error });
    const report = detail.report;
    const signals = section(report, "derived_signals")?.signals || [];
    const checklist = section(report, "resolution_checklist")?.items || [];
    const llm = section(report, "llm_analysis")?.analysis || {};
    const actions = report.recommended_actions || [];
    return h("div", { className: "d-grid gap-3" },
      ["admin", "operator"].includes(currentUser?.role) ? h("div", { className: "d-flex justify-content-end gap-2 flex-wrap" },
        h("button", {
          type: "button",
          className: "btn btn-sm btn-outline-secondary btn-icon",
          onClick: () => onExportReport(report.report_id),
        }, h(Icon, { name: "filetype-json" }), tr("Export")),
        h("button", {
          type: "button",
          className: "btn btn-sm btn-outline-secondary btn-icon",
          onClick: () => onExportBundle(report.report_id),
        }, h(Icon, { name: "file-earmark-zip" }), "Evidence Bundle")
      ) : null,
      h(ReportSummaryStrip, { report, llm, actions }),
      h(PolicyOverview, { actions }),
      detail.timeline ? h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Cascading Failure Timeline")),
          h("span", { className: "small text-muted" }, tr("Time-correlated propagation from the first trigger"))
        ),
        h(TimelineGraph, { timeline: detail.timeline })
      ) : null,
      h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Impact Scope")),
          h("span", { className: "small text-muted" }, displayText(report.scope?.impact_assessment))
        ),
        h(ImpactScope, { scope: report.scope || {} })
      ),
      h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Root Cause Candidates")),
          h("span", { className: "small text-muted" }, tr("Rule-based candidates first, LLM candidates only as supporting context"))
        ),
        h(OrderedFacts, { items: report.root_cause_candidates || [], titleKey: "cause", metaKey: "confidence", textKey: "supporting_evidence" })
      ),
      h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Evidence Signals")),
          h("span", { className: "small text-muted" }, activeLocale === "ko" ? `파생 신호 ${signals.length}개` : `${signals.length} ${tr("derived signals")}`)
        ),
        h(SignalFacts, { items: signals })
      ),
      h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Additional Checks")),
          h("span", { className: "small text-muted" }, tr("Read-only commands to verify the candidate cause"))
        ),
        h(ChecklistFacts, { items: checklist, onCopy })
      ),
      h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Recommended Actions")),
          h("span", { className: "small text-muted" }, tr("Policy Engine decides whether an action can be automated"))
        ),
        h(ActionFacts, { items: actions, report, onPrepareAction, currentUser })
      ),
      h("section", { className: "report-section" },
        h("div", { className: "report-section-title" },
          h("h3", { className: "h6 mb-0" }, tr("Action History")),
          h("span", { className: "small text-muted" }, tr("Approval and manual handling history"))
        ),
        h(ActionRequestHistory, {
          items: detail.actionRequests || [],
          executions: detail.actionExecutions || [],
          reportId: report.report_id,
          currentUser,
          onDecideAction,
          onCompleteManual,
        })
      )
    );
  }

  function ImpactScope({ scope }) {
    const groups = [
      ["Affected Pods", scope.affected_pods || []],
      ["Affected Namespaces", scope.affected_namespaces || []],
      ["Affected Workloads", scope.affected_workloads || []],
      ["Observed Services", scope.observed_services || []],
    ];
    const hasInventory = groups.some(([, values]) => values.length);
    if (!hasInventory) {
      return h(EmptyState, { message: scope.impact_assessment || "No workload inventory was available in the collected evidence." });
    }
    return h(React.Fragment, null,
      scope.service_impact_assessment ? h("div", { className: "alert alert-info py-2 mb-3" },
        h("strong", null, `${tr("Service relationship unverified")}: `),
        displayText(scope.service_impact_assessment)
      ) : null,
      h("div", { className: "impact-scope-grid" },
      groups.map(([label, values]) => h("div", { key: label, className: "impact-scope-item" },
        h("div", { className: "small fw-semibold mb-2" }, tr(label)),
        h(ChipList, { items: values, tone: "blue", empty: tr("No items.") })
      )))
    );
  }

  function ReportSummaryStrip({ report, llm, actions }) {
    const allowed = actions.filter((action) => action.automation_allowed).length;
    const blocked = actions.length - allowed;
    const llmCount = actions.filter((action) => action.source === "llm").length;
    return h("div", { className: "report-summary-grid" },
      h(SummaryBox, { label: "Report", value: h("span", { className: "font-monospace small text-break" }, report.report_id) }),
      h(SummaryBox, { label: "Incident", value: h("span", { className: "font-monospace small text-break" }, report.incident_id || "n/a") }),
      h(SummaryBox, { label: "Nodes", value: listValue(report.scope?.nodes) }),
      h(SummaryBox, { label: "Confidence", value: h(StatusBadge, { value: report.summary?.confidence || "unknown", tone: confidenceTone(report.summary?.confidence) }) }),
      h(SummaryBox, { label: "Automation", value: activeLocale === "ko" ? `${allowed}개 ${tr("allowed")} / ${blocked}개 ${tr("gated")}` : `${allowed} allowed / ${blocked} gated` }),
      h(SummaryBox, { label: "Components", value: listValue(report.scope?.components) }),
      h(SummaryBox, { label: "Policies", value: uniquePolicies(report).map((policy) => h(StatusBadge, { key: policy, value: policy, tone: policyTone(policy) })) }),
      h(SummaryBox, { label: "LLM", value: h("span", null, h(StatusBadge, { value: llm.status || "unknown", tone: llm.status === "completed" ? "green" : "amber" }), llmCount ? h("span", { className: "small text-muted ms-2" }, `${llmCount} ${tr("action")}`) : null) }),
      h(SummaryBox, { label: "Provider", value: llm.provider || llm.reason || llm.error || "n/a" })
    );
  }

  function ActionRequestHistory({ items, executions, reportId, currentUser, onDecideAction, onCompleteManual }) {
    if (!items.length) return h(EmptyState, { message: "No action requests." });
    return h("div", { className: "d-grid gap-2" }, items.map((item) => h("article", {
      key: item.action_request_id,
      className: "action-card",
    },
    h("div", { className: "d-flex justify-content-between gap-2 flex-wrap" },
      h("div", null,
        h("strong", null, item.action_key),
        h("div", { className: "small text-muted font-monospace" }, item.action_request_id)
      ),
      h("div", { className: "d-flex gap-2 align-items-center flex-wrap" },
        h(StatusBadge, { value: item.policy, tone: policyTone(item.policy) }),
        h(StatusBadge, {
          value: item.status,
          tone: ["accepted", "approved_manual", "completed"].includes(item.status) ? "green"
            : item.status === "blocked" || item.status === "rejected" ? "red" : "amber",
        }),
        ["admin", "approver"].includes(currentUser?.role) && item.status === "pending_approval"
          ? h("div", { className: "btn-group btn-group-sm" },
              h("button", {
                className: "btn btn-outline-success",
                onClick: () => onDecideAction(item.action_request_id, reportId, "approve"),
              }, tr("Approve for Manual Handling")),
              h("button", {
                className: "btn btn-outline-danger",
                onClick: () => onDecideAction(item.action_request_id, reportId, "reject"),
              }, tr("Reject"))
            )
          : null,
        ["admin", "operator"].includes(currentUser?.role) && item.status === "approved_manual"
          ? h("button", {
              className: "btn btn-sm btn-outline-primary",
              onClick: () => onCompleteManual(item.action_request_id, reportId),
            }, tr("Mark Manually Completed"))
          : null
      )
    ),
    h("div", { className: "small text-muted mt-2" },
      `${tr("Requested by")}: ${item.requested_by} / ${formatDate(item.created_at)}`
    ),
    item.reviewed_by ? h("div", { className: "small text-muted" },
      `${tr("Reviewed by")}: ${item.reviewed_by} / ${formatDate(item.reviewed_at)}`
    ) : null,
    (() => {
      const execution = (executions || []).find((value) => value.action_request_id === item.action_request_id);
      return execution ? h("div", { className: "execution-result mt-2" },
        h("div", { className: "d-flex justify-content-between gap-2" },
          h("span", { className: "font-monospace small" }, execution.command_key),
          h(StatusBadge, {
            value: execution.status,
            tone: execution.status === "completed" ? "green"
              : execution.status === "failed" ? "red" : "amber",
          })
        ),
        execution.stdout ? h("pre", { className: "execution-output mt-2 mb-0" }, execution.stdout) : null,
        execution.stderr ? h("pre", { className: "execution-output execution-error mt-2 mb-0" }, execution.stderr) : null
      ) : null;
    })()
    )));
  }

  function TimelineGraph({ timeline }) {
    const nodes = timeline.nodes || [];
    const edges = timeline.edges || [];
    if (!nodes.length) return h(EmptyState, { message: "No timeline evidence." });
    return h("div", { className: "timeline-scroll" },
      h("div", { className: "failure-timeline" }, nodes.map((node, index) => {
        const incoming = edges.find((edge) => edge.target === node.id);
        const nextNode = nodes[index + 1];
        const nextEdge = nextNode ? edges.find((edge) => edge.target === nextNode.id) : null;
        const sourceNode = incoming ? nodes.find((item) => item.id === incoming.source) : null;
        return h("div", { key: node.id, className: "timeline-step" },
          h("article", { className: `timeline-node severity-${node.severity} ${node.root_trigger ? "root-trigger" : ""}` },
            h("div", { className: "d-flex justify-content-between gap-2" },
              h("span", { className: "timeline-component" },
                `${node.component} / ${node.signal_family || "unknown"}`
              ),
              h(StatusBadge, { value: node.severity, tone: severityTone(node.severity) })
            ),
            h("strong", null, displayText(node.title)),
            h("span", { className: "small text-muted" }, formatDate(node.timestamp)),
            h("p", { className: "small mb-0" }, displayText(node.detail)),
            incoming ? h("div", { className: "causal-edge-meta" },
              h("span", null,
                `${sourceNode?.signal_family || sourceNode?.component || "signal"} → ${node.signal_family || node.component}`
              ),
              h("span", null,
                `${incoming.rule_id} · ${Math.round((incoming.confidence || 0) * 100)}%`
              )
            ) : null,
            node.root_trigger ? h("span", { className: "root-label" }, tr("Root trigger")) : null
          ),
          index < nodes.length - 1
            ? h("div", { className: "timeline-link" },
                h(Icon, { name: "arrow-right" }),
                h("span", null, displayText(nextEdge?.relationship || "observed next in the incident window")),
                nextEdge ? h("small", null,
                  `${nextEdge.inferred ? tr("Causal inference") : tr("Observed sequence")} · ${Math.round((nextEdge.confidence || 0) * 100)}%`
                ) : null
              )
            : null
        );
      }))
    );
  }

  function PolicyOverview({ actions }) {
    if (!actions.length) return h(EmptyState, { message: "No policy decisions." });
    const counts = policyCounts(actions);
    const llmActions = actions.filter((action) => action.source === "llm").length;
    const blocked = actions.filter((action) => !action.automation_allowed).length;
    const allowed = actions.length - blocked;
    return h("section", { className: "policy-overview" },
      h("div", { className: "report-section-title" },
        h("h3", { className: "h6 mb-0" }, tr("Policy Engine")),
        h("span", { className: "small text-muted" }, tr("Rule gate before any action request"))
      ),
      h("div", { className: "policy-overview-grid" },
        policyOrder().map((policy) => counts[policy] ? h("div", { key: policy, className: "policy-overview-item" },
          h(StatusBadge, { value: policy, tone: policyTone(policy) }),
          h("strong", null, counts[policy]),
          h("span", null, policyDescription(policy))
        ) : null),
        h("div", { className: "policy-overview-item" },
          h(StatusBadge, { value: "automation_allowed", tone: "green" }),
          h("strong", null, allowed),
          h("span", null, tr("Read-only rule-based action requests"))
        ),
        h("div", { className: "policy-overview-item policy-overview-warning" },
          h(StatusBadge, { value: "automation_blocked", tone: blocked ? "red" : "green" }),
          h("strong", null, blocked),
          h("span", null, tr("Needs review, approval, PR, or manual handling"))
        )
      ),
      llmActions ? h("div", { className: "llm-action-warning mt-2" },
        h(Icon, { name: "exclamation-triangle" }),
        h("span", null, activeLocale === "ko"
          ? `LLM 조치 ${llmActions}개. ${tr("Policy keeps automation_allowed=false for every LLM-origin action.")}`
          : `${llmActions} LLM action(s). ${tr("Policy keeps automation_allowed=false for every LLM-origin action.")}`)
      ) : null
    );
  }

  function OrderedFacts({ items, titleKey, metaKey, textKey }) {
    if (!items.length) return h("div", { className: "empty-state" }, tr("No items."));
    return h("div", { className: "candidate-list" }, items.map((item, index) => h("article", { key: index, className: "candidate-card" },
      h("div", { className: "d-flex justify-content-between gap-2 align-items-start" },
        h("strong", null, `${index + 1}. ${displayText(item[titleKey] || "Unknown cause")}`),
        h("div", { className: "d-flex align-items-center gap-2 flex-wrap justify-content-end" },
          Number.isFinite(item.confidence_score)
            ? h("strong", { className: "candidate-score" }, `${item.confidence_score}%`)
            : null,
          h(StatusBadge, { value: item[metaKey], tone: confidenceTone(item[metaKey]) })
        )
      ),
      Number.isFinite(item.confidence_score)
        ? h("div", {
            className: "confidence-meter mt-2",
            role: "progressbar",
            "aria-valuenow": item.confidence_score,
            "aria-valuemin": 0,
            "aria-valuemax": 100,
          }, h("span", { style: { width: `${Math.max(0, Math.min(100, item.confidence_score))}%` } }))
        : null,
      h("div", { className: "small text-muted mt-1" }, displayText(listValue(item[textKey]))),
      h(ChipList, { items: item.evidence_paths || [], tone: "blue", empty: null })
    )));
  }

  function ActionFacts({ items, report, onPrepareAction, currentUser }) {
    if (!items.length) return h("div", { className: "empty-state" }, tr("No actions."));
    return h("div", { className: "d-grid gap-2" }, items.map((item, index) => {
      const llmSourced = item.source === "llm";
      const automationAllowed = item.automation_allowed === true;
      return h("article", {
        key: index,
        className: `action-card action-card-rca ${automationAllowed ? "automation-allowed" : "automation-blocked"} ${llmSourced ? "llm-sourced" : ""}`,
      },
      h("div", { className: "d-flex justify-content-between gap-2 align-items-start" },
        h("div", null,
          h("strong", null, displayActionText(item)),
          h("div", { className: "small text-muted mt-1" }, displayReasonText(item))
        ),
        h("div", { className: "d-flex gap-2 flex-wrap justify-content-end" },
          h(StatusBadge, { value: item.policy, tone: policyTone(item.policy) }),
          h(StatusBadge, { value: sourceLabel(item.source), tone: sourceTone(item.source) }),
          h(StatusBadge, { value: automationLabel(item), tone: automationTone(item) }),
          h("button", {
            type: "button",
            className: `btn btn-sm ${item.automation_allowed ? "btn-primary" : "btn-outline-secondary"} btn-icon`,
            disabled: item.policy === "NEVER_AUTO_EXECUTE"
              || !onPrepareAction
              || !["admin", "operator"].includes(currentUser?.role),
            onClick: () => onPrepareAction(report, item, index),
          }, h(Icon, { name: actionIcon(item) }), actionButtonLabel(item))
        )
      ),
      h("div", { className: "policy-description mt-2" }, policyDescription(item.policy)),
      llmSourced ? h("div", { className: "llm-action-warning mt-2" },
        h(Icon, { name: "exclamation-triangle" }),
        h("span", null, tr("LLM suggestion only. It cannot become executable and must remain diagnostic context."))
      ) : null,
      h("div", { className: "action-meta-grid mt-2" },
        h(MetaPill, { label: "mode", value: item.automation_mode || "manual" }),
        h(MetaPill, { label: "approval", value: item.requires_approval ? "required" : "not required", tone: item.requires_approval ? "amber" : "green" }),
        h(MetaPill, { label: "review", value: item.review_required ? "required" : "not required", tone: item.review_required ? "amber" : "green" }),
        h(MetaPill, { label: "key", value: item.action_key || "n/a" })
      ),
      item.execution_plan ? h("div", { className: "action-plan mt-2" },
        h("div", { className: "small fw-semibold mb-1" }, tr("Runbook / GitOps guidance")),
        (item.execution_plan.command_preview || []).map((command, commandIndex) =>
          h("pre", { key: commandIndex, className: "command-preview mb-1" }, command)
        ),
        item.execution_plan.yaml_patch
          ? h("pre", { className: "command-preview mb-1" }, item.execution_plan.yaml_patch)
          : null,
        h(StatusBadge, { value: "manual guidance only", tone: "blue" }),
        h("div", { className: "small text-muted mt-1" },
          tr("Commands are guidance only and are never executed by the platform or agent.")
        )
      ) : null,
      h("div", { className: "mt-2" },
        h(ChipList, { label: tr("Risk reasons"), items: item.risk_factors || [], tone: "red", empty: tr("No policy risk factors.") })
      ),
      h("div", { className: "mt-2" },
        h(ChipList, { label: tr("Guardrails"), items: item.guardrails || [], tone: "amber", empty: tr("No guardrails triggered.") })
      )
    ); }));
  }

  function SignalFacts({ items }) {
    if (!items.length) return h("div", { className: "empty-state" }, tr("No signals."));
    return h("div", { className: "signal-grid" }, items.map((item, index) => h("article", { key: index, className: "signal-card" },
      h("div", { className: "d-flex justify-content-between gap-2 align-items-start" },
        h("strong", null, item.signal || "unknown_signal"),
        h(StatusBadge, { value: item.severity, tone: severityTone(item.severity) })
      ),
      h("div", { className: "small text-muted mt-1" }, `${item.component || "node"}: ${signalFieldText(item, "interpretation")}`),
      h("div", { className: "small mt-2" }, h("span", { className: "text-muted fw-semibold" }, `${tr("Next:")} `), signalFieldText(item, "next_step"))
    )));
  }

  function ChecklistFacts({ items, onCopy }) {
    if (!items.length) return h("div", { className: "empty-state" }, tr("No checklist."));
    return h("div", { className: "checklist-grid" }, items.map((item, index) => h("article", { key: index, className: "check-card" },
      h("div", { className: "d-flex justify-content-between gap-2 align-items-start" },
        h("div", null,
          h("div", { className: "fw-semibold" }, item.component || "node"),
          h("div", { className: "small text-muted" }, displayText(item.check || "Read-only verification"))
        ),
        onCopy ? h("button", {
          type: "button",
          className: "btn btn-sm btn-outline-secondary btn-icon",
          onClick: () => onCopy(item.command || "", "Command copied."),
        }, h(Icon, { name: "clipboard" }), tr("Copy")) : null
      ),
      h("code", { className: "small d-block text-break mt-2" }, item.command || "n/a")
    )));
  }

  function MetaPill({ label, value, tone }) {
    return h("div", { className: `meta-pill ${tone || ""}` },
      h("span", null, tr(label)),
      h("strong", null, displayText(value))
    );
  }

  function ChipList({ label, items, tone, empty }) {
    const values = Array.isArray(items) ? items.filter(Boolean) : [];
    if (!values.length && empty === null) return null;
    return h("div", { className: "chip-list-wrap" },
      label ? h("div", { className: "chip-label" }, tr(label)) : null,
      values.length
        ? h("div", { className: "chip-list" }, values.map((value) => h("span", { key: value, className: `chip ${tone || ""}` }, value)))
        : h("div", { className: "small text-muted" }, displayText(empty || "n/a"))
    );
  }

  function LanguageSelect({ locale, onChangeLanguage, compact }) {
    return h("label", { className: compact ? "language-select compact" : "language-select" },
      !compact && h("span", null, tr("Language")),
      h("select", {
        className: "form-select form-select-sm",
        value: locale || "en",
        onChange: (event) => onChangeLanguage(event.target.value),
        "aria-label": tr("Language"),
      },
        h("option", { value: "en" }, tr("English")),
        h("option", { value: "ko" }, tr("Korean"))
      )
    );
  }

  function InputField({ label, ...props }) {
    const inputId = props.id || `field-${props.name || label.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
    return h("div", { className: "col-12 col-md-6" },
      h("label", { className: "form-label", htmlFor: inputId }, tr(label)),
      h("input", { className: "form-control", ...props, id: inputId })
    );
  }

  function DetailRow({ label, value }) {
    return h("div", { className: "detail-row" }, h("dt", null, tr(label)), h("dd", null, value));
  }

  function StatusBadge({ value, tone }) {
    const label = value === null || value === undefined || value === "" ? "n/a" : String(value);
    return h("span", { className: `badge badge-soft ${tone || ""}` }, tr(label));
  }

  function EmptyState({ message }) {
    return h("div", { className: "empty-state" }, displayText(message));
  }

  function Toast({ message, onClose }) {
    return h("div", { className: "toast-area" },
      h("div", { className: "toast show align-items-center text-bg-dark border-0", role: "status" },
        h("div", { className: "d-flex" },
          h("div", { className: "toast-body" }, displayText(message)),
          h("button", { type: "button", className: "btn-close btn-close-white me-2 m-auto", onClick: onClose })
        )
      )
    );
  }

  function Icon({ name }) {
    return h("i", { className: `bi bi-${name}`, "aria-hidden": "true" });
  }

  function formPayload(form) {
    return Object.fromEntries([...new FormData(form).entries()].map(([key, value]) => {
      const normalized = typeof value === "string" ? value.trim() : value;
      return [key, normalized === "" ? null : normalized];
    }));
  }

  function readError(body, fallback) {
    const detail = body && typeof body === "object" ? body.detail : body;
    if (Array.isArray(detail)) return detail.map((item) => item.msg || String(item)).join(", ");
    if (detail && typeof detail === "object") return JSON.stringify(detail);
    return detail || fallback || "Request failed.";
  }

  function filenameFromContentDisposition(value) {
    if (!value) return null;
    const utf8Match = value.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8Match) return decodeURIComponent(utf8Match[1].replace(/"/g, ""));
    const asciiMatch = value.match(/filename="?([^";]+)"?/i);
    return asciiMatch ? asciiMatch[1] : null;
  }

  function triggerDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename || "rca-export.json";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function normalizeLocale(value) {
    return value === "ko" ? "ko" : "en";
  }

  function tr(key) {
    if (key === null || key === undefined) return key;
    const text = String(key);
    return translations[activeLocale]?.[text] || text;
  }

  function displayText(value) {
    if (Array.isArray(value)) return value.map(displayText).join(", ");
    if (value === null || value === undefined || value === "") return tr("n/a");
    const text = String(value);
    if (activeLocale === "ko") {
      if (text.startsWith("LLM analysis: ")) {
        return `LLM 분석: ${text.slice("LLM analysis: ".length)}`;
      }
      const highConfidence = text.match(/^(\d+) high confidence$/);
      if (highConfidence) return `높은 신뢰도 ${highConfidence[1]}개`;
      const clusters = text.match(/^(\d+) clusters$/);
      if (clusters) return `클러스터 ${clusters[1]}개`;
      const reports = text.match(/^(\d+) reports$/);
      if (reports) return `보고서 ${reports[1]}개`;
    }
    return tr(text);
  }

  function displaySummary(value) {
    if (!value) return tr("Unknown symptom");
    const text = String(value);
    if (activeLocale === "ko") {
      const detailed = text.match(/^(.+) was reported on node (.+)\. Rule analysis found (\d+) critical signal\(s\) and (\d+) warning signal\(s\)\.$/);
      if (detailed) {
        return `${detailed[1]} 알림이 ${detailed[2]} 노드에서 보고되었습니다. Rule 분석 결과 critical 신호 ${detailed[3]}개, warning 신호 ${detailed[4]}개가 확인되었습니다.`;
      }
      const simple = text.match(/^(.+) was reported on node (.+)\.$/);
      if (simple) return `${simple[1]} 알림이 ${simple[2]} 노드에서 보고되었습니다.`;
    }
    return displayText(text);
  }

  function displayActionText(action) {
    const translated = actionTranslations[activeLocale]?.[action.action_key]?.action;
    return translated || displayText(action.action || "n/a");
  }

  function displayReasonText(action) {
    const translated = actionTranslations[activeLocale]?.[action.action_key]?.reason;
    return translated || displayText(action.reason || "No reason");
  }

  function signalFieldText(item, field) {
    const translated = signalTranslations[activeLocale]?.[item.signal]?.[field];
    return translated || displayText(item[field] || "n/a");
  }

  function formatDate(value) {
    if (!value) return "n/a";
    return new Intl.DateTimeFormat(activeLocale === "ko" ? "ko-KR" : "en-US", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(value));
  }

  function formatAgentLastSeen(agent) {
    return formatDate(agent.last_heartbeat_at || agent.health?.freshness?.last_seen_at || agent.registered_at);
  }

  function uniquePolicies(report) {
    return [...new Set((report.recommended_actions || []).map((action) => action.policy).filter(Boolean))];
  }

  function confidenceTone(value) {
    if (value === "high") return "green";
    if (value === "medium") return "amber";
    return "red";
  }

  function clusterStatusTone(value) {
    if (value === "active") return "green";
    if (value === "agent_pending" || value === "registered") return "amber";
    return "blue";
  }

  function agentStatusTone(value) {
    if (value === "healthy") return "green";
    if (value === "offline" || value === "unauthorized") return "red";
    if (["degraded", "stale", "version_mismatch", "collector_degraded"].includes(value)) return "amber";
    return "blue";
  }

  function evidenceStatusTone(value) {
    if (value === "completed") return "green";
    if (value === "failed") return "red";
    if (value === "pending") return "amber";
    return "blue";
  }

  function policyOrder() {
    return ["AUTO_SAFE", "MANUAL_INVESTIGATION", "APPROVAL_REQUIRED", "GITOPS_PR_ONLY", "NEVER_AUTO_EXECUTE"];
  }

  function policyCounts(actions) {
    return actions.reduce((acc, action) => {
      const key = action.policy || "UNKNOWN";
      acc[key] = (acc[key] || 0) + 1;
      return acc;
    }, {});
  }

  function policyTone(value) {
    if (value === "AUTO_SAFE") return "green";
    if (value === "NEVER_AUTO_EXECUTE") return "red";
    if (value === "APPROVAL_REQUIRED" || value === "GITOPS_PR_ONLY") return "amber";
    return "blue";
  }

  function policyDescription(value) {
    if (value === "AUTO_SAFE") return tr("Read-only rule-based collection or verification.");
    if (value === "APPROVAL_REQUIRED") return tr("Node or service state may change. Operator approval is required.");
    if (value === "GITOPS_PR_ONLY") return tr("Configuration change. Propose through a reviewable PR only.");
    if (value === "NEVER_AUTO_EXECUTE") return tr("Prohibited for automation. Human decision only.");
    if (value === "MANUAL_INVESTIGATION") return tr("Needs manual investigation or external validation.");
    return tr("Unclassified policy decision.");
  }

  function severityTone(value) {
    if (value === "critical") return "red";
    if (value === "warning" || value === "high") return "amber";
    return "blue";
  }

  function sourceTone(value) {
    if (value === "rule_based") return "green";
    if (value === "llm") return "amber";
    return "blue";
  }

  function sourceLabel(value) {
    if (value === "rule_based") return "rule_based";
    if (value === "llm") return "llm_suggestion";
    return value || "unknown_source";
  }

  function automationTone(action) {
    if (action.automation_allowed) return "green";
    if (action.source === "llm") return "amber";
    if (action.policy === "NEVER_AUTO_EXECUTE") return "red";
    return "amber";
  }

  function automationLabel(action) {
    if (action.automation_allowed) return "automation_allowed";
    if (action.source === "llm") return "llm_auto_blocked";
    return "automation_blocked";
  }

  function actionButtonLabel(action) {
    if (action.policy === "AUTO_SAFE" && action.automation_allowed) return tr("Collect Evidence");
    if (action.policy === "APPROVAL_REQUIRED") return tr("Request");
    if (action.policy === "GITOPS_PR_ONLY") return tr("PR Gate");
    if (action.policy === "NEVER_AUTO_EXECUTE") return tr("Blocked");
    return tr("Review");
  }

  function actionIcon(action) {
    if (action.policy === "AUTO_SAFE" && action.automation_allowed) return "play-circle";
    if (action.policy === "APPROVAL_REQUIRED") return "shield-check";
    if (action.policy === "GITOPS_PR_ONLY") return "git";
    if (action.policy === "NEVER_AUTO_EXECUTE") return "slash-circle";
    return "eye";
  }

  function section(report, type) {
    return (report.evidence || []).find((item) => item.type === type);
  }

  function listValue(value) {
    if (Array.isArray(value)) return value.length ? value.join(", ") : "n/a";
    if (value === null || value === undefined || value === "") return "n/a";
    return String(value);
  }

  createRoot(rootElement).render(h(App));
