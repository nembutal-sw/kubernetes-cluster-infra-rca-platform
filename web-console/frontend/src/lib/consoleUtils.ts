// @ts-nocheck

export function arrayResult(result) {
  return result?.status === "fulfilled" && Array.isArray(result.value) ? result.value : [];
}

export function sortByTime(items, field) {
  return [...(items || [])].sort((a, b) => new Date(b[field] || 0).getTime() - new Date(a[field] || 0).getTime());
}

export async function copyText(text, notify) {
  await navigator.clipboard.writeText(text || "");
  notify("Copied.");
}

export function buildAuditQuery(filters) {
  const query = new URLSearchParams();
  Object.entries(filters || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      query.set(key, String(value).trim());
    }
  });
  if (!query.has("limit")) query.set("limit", "200");
  return query.toString();
}

export function auditStats(events) {
  return (events || []).reduce((stats, event) => {
    const type = String(event.event_type || "").toLowerCase();
    const outcome = String(event.outcome || "").toLowerCase();
    if (type.includes("export") || type.includes("bundle")) stats.exports += 1;
    if (outcome.includes("fail") || outcome.includes("unauthorized") || outcome.includes("forbidden")) stats.failures += 1;
    if (type.includes("approval") || type.includes("approve") || type.includes("action_request")) stats.approvals += 1;
    return stats;
  }, { exports: 0, failures: 0, approvals: 0 });
}

export function buildSignalDigest(reports, incidents) {
  const fromReports = (reports || []).flatMap((report) => {
    const candidates = report.root_cause_candidates || [];
    return candidates.slice(0, 2).map((candidate, index) => ({
      id: `${report.report_id}-${index}`,
      title: candidate.cause || report.summary?.symptom || "RCA signal",
      detail: (candidate.supporting_evidence || [])[0] || report.cluster_id,
      severity: candidate.confidence === "high" ? "critical" : "warning",
      family: inferSignalFamily(candidate.cause || report.summary?.most_likely_cause || ""),
    }));
  });
  const fromIncidents = (incidents || []).filter((incident) => incident.status === "open").map((incident) => ({
    id: incident.incident_id,
    title: incident.alert_name,
    detail: incident.root_cause || incident.cluster_id,
    severity: "critical",
    family: inferSignalFamily(`${incident.alert_name} ${incident.root_cause}`),
  }));
  return [...fromIncidents, ...fromReports];
}

export function scoreStage(stage, reports, incidents) {
  const text = JSON.stringify([reports, incidents]).toLowerCase();
  const needles = {
    disk: ["disk", "inode", "io", "filesystem", "pressure"],
    runtime: ["containerd", "runtime", "docker", "crio"],
    kubelet: ["kubelet", "node not ready", "nodeready"],
    network: ["network", "cni", "conntrack", "dns", "mtu", "tcp"],
    control: ["apiserver", "api server", "etcd", "control plane"],
    service: ["service", "coredns", "endpoint", "latency"],
  }[stage] || [];
  return needles.reduce((count, needle) => count + occurrences(text, needle), 0);
}

export function occurrences(text, needle) {
  if (!needle) return 0;
  return (text.match(new RegExp(escapeRegExp(needle), "g")) || []).length;
}

export function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function inferSignalFamily(value) {
  const text = String(value || "").toLowerCase();
  if (/disk|inode|io|filesystem|pressure/.test(text)) return "disk";
  if (/containerd|runtime|docker|crio/.test(text)) return "runtime";
  if (/kubelet|node/.test(text)) return "kubelet";
  if (/network|cni|conntrack|dns|mtu|tcp|nic/.test(text)) return "network";
  if (/api|etcd|control/.test(text)) return "control";
  return "service";
}

export function evidenceSummary(report) {
  const rows = [];
  const trigger = report.trigger || {};
  Object.entries(trigger).forEach(([key, value]) => rows.push({ label: `trigger.${key}`, value: shortValue(value) }));
  (report.root_cause_candidates || []).forEach((candidate) => {
    (candidate.supporting_evidence || []).slice(0, 2).forEach((value) => rows.push({ label: candidate.cause, value }));
  });
  const evidence = report.evidence || report.evidence_summary || {};
  Object.entries(evidence).slice(0, 8).forEach(([key, value]) => rows.push({ label: key, value: shortValue(value) }));
  return rows;
}

export function derivedSignals(report) {
  const evidence = Array.isArray(report?.evidence) ? report.evidence : [];
  const direct = evidence.find((section) => section?.type === "derived_signals");
  if (Array.isArray(direct?.signals)) return direct.signals;
  const preprocessed = evidence.find((section) => section?.type === "preprocessed_evidence");
  const nested = preprocessed?.payload?.derived_signals || preprocessed?.payload?.derivedSignals;
  return Array.isArray(nested) ? nested : [];
}

export function reportEvidenceQuality(report) {
  const evidence = Array.isArray(report?.evidence) ? report.evidence : [];
  const direct = evidence.find((section) => section?.type === "evidence_quality");
  if (direct?.quality) return direct.quality;
  const preprocessed = evidence.find((section) => section?.type === "preprocessed_evidence");
  return preprocessed?.payload?.evidence_quality || preprocessed?.payload?.evidenceQuality || null;
}

export function reportQualityGate(report) {
  const evidence = Array.isArray(report?.evidence) ? report.evidence : [];
  const direct = evidence.find((section) => section?.type === "quality_gate");
  if (direct?.gate) return direct.gate;
  const preprocessed = evidence.find((section) => section?.type === "preprocessed_evidence");
  return preprocessed?.payload?.final_quality_gate
    || preprocessed?.payload?.finalQualityGate
    || preprocessed?.payload?.quality_gate
    || preprocessed?.payload?.qualityGate
    || null;
}

export function qualityTone(value) {
  const normalized = String(value || "").toLowerCase();
  if (["complete", "healthy", "ok", "fresh"].includes(normalized)) return "green";
  if (["partial", "stale", "warning", "limited", "unknown"].includes(normalized)) return "amber";
  if (["degraded", "offline", "unauthorized", "collector_degraded", "version_mismatch", "failed", "error"].includes(normalized)) return "red";
  return "muted";
}

export function qualityGateTone(value) {
  const normalized = String(value || "").toLowerCase();
  if (normalized === "pass") return "green";
  if (normalized === "limited") return "amber";
  if (normalized === "insufficient") return "red";
  return qualityTone(value);
}

export function formatFreshness(freshness) {
  if (!freshness) return "unknown";
  const status = freshness.status || "unknown";
  const ageSeconds = freshness.age_seconds ?? freshness.ageSeconds;
  if (ageSeconds === undefined || ageSeconds === null) return status;
  if (ageSeconds < 60) return `${status} · ${ageSeconds}s`;
  if (ageSeconds < 3600) return `${status} · ${Math.round(ageSeconds / 60)}m`;
  return `${status} · ${(ageSeconds / 3600).toFixed(1)}h`;
}

export function formatPercentValue(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "n/a";
  const numeric = Number(value);
  return numeric <= 1 ? `${Math.round(numeric * 100)}%` : `${Math.round(numeric)}%`;
}

export function fallbackTimeline(report) {
  const candidates = report.root_cause_candidates || [];
  const root = candidates[0]?.cause || report.summary?.most_likely_cause;
  const items = [];
  if (root) {
    items.push({ id: "root", timestamp: report.created_at, component: inferSignalFamily(root), title: root, detail: "Most likely root cause", root_trigger: true });
  }
  (report.recommended_actions || []).slice(0, 4).forEach((action, index) => {
    items.push({ id: `action-${index}`, timestamp: report.created_at, component: action.action_key, title: action.action_key || action.policy, detail: action.reason });
  });
  return items;
}

export function shortValue(value) {
  if (value === null || value === undefined) return "n/a";
  if (typeof value === "object") return JSON.stringify(value).slice(0, 180);
  return String(value).slice(0, 180);
}

export function platformInfoRows(platformInfo, t) {
  const exportSecurity = platformInfo?.export_security || {};
  const rows = [
    { key: "platform_version", label: t("Platform version"), value: platformInfo?.platform_version || "n/a" },
    { key: "api_version", label: t("API version"), value: platformInfo?.api_version || "n/a" },
    { key: "agent_protocol_version", label: t("Agent protocol"), value: platformInfo?.agent_protocol_version || "n/a" },
    {
      key: "minimum_supported_agent_protocol_version",
      label: t("Minimum agent protocol"),
      value: platformInfo?.minimum_supported_agent_protocol_version || "n/a",
    },
    {
      key: "minimum_supported_agent_version",
      label: t("Minimum agent version"),
      value: platformInfo?.minimum_supported_agent_version || "n/a",
    },
  ];
  if (platformInfo?.export_security) {
    rows.push(
      {
        key: "export_security.max_bundle_bytes",
        label: `${t("Export security")} · ${t("Max bundle size")}`,
        value: formatBytes(exportSecurity.max_bundle_bytes),
      },
      {
        key: "export_security.hash_algorithm",
        label: `${t("Export security")} · ${t("Hash algorithm")}`,
        value: exportSecurity.hash_algorithm || "n/a",
      },
      {
        key: "export_security.bundle_signature_enabled",
        label: `${t("Export security")} · ${t("Bundle signature")}`,
        value: exportSecurity.bundle_signature_enabled ? t("Enabled") : t("Disabled"),
        tone: exportSecurity.bundle_signature_enabled ? "ok" : "muted",
      },
      {
        key: "export_security.bundle_signature_algorithm",
        label: `${t("Export security")} · ${t("Signature algorithm")}`,
        value: exportSecurity.bundle_signature_algorithm || "n/a",
      },
      {
        key: "export_security.bundle_signature_key_id",
        label: `${t("Export security")} · ${t("Signature key")}`,
        value: exportSecurity.bundle_signature_key_id || "n/a",
      },
      {
        key: "export_security.offline_verifier",
        label: `${t("Export security")} · ${t("Offline verifier")}`,
        value: exportSecurity.offline_verifier || "n/a",
      },
    );
  }
  return rows;
}

export function formatBytes(value) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) return "n/a";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let amount = bytes / 1024;
  let unitIndex = 0;
  while (amount >= 1024 && unitIndex < units.length - 1) {
    amount /= 1024;
    unitIndex += 1;
  }
  return `${amount.toFixed(amount >= 10 ? 0 : 1)} ${units[unitIndex]}`;
}

export function shortHash(value) {
  const text = String(value || "");
  return text.length > 18 ? `${text.slice(0, 12)}…${text.slice(-6)}` : text || "n/a";
}

export function formatDate(value) {
  if (!value) return "n/a";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function runConsoleLayoutAudit() {
  const doc = document.documentElement;
  const body = document.body;
  const viewportWidth = window.innerWidth || doc.clientWidth;
  const viewportHeight = window.innerHeight || doc.clientHeight;
  const allowedOverflow = ".table-responsive, .console-table-wrap, pre, code, .config-sample, .command-preview, .install-command";
  const ignored = ".diagnostics-panel, script, style, noscript";
  const issues = { offscreen: [], overflowed: [], clipped: [] };

  Array.from(document.querySelectorAll("body *")).forEach((element) => {
    if (element.closest(allowedOverflow) || element.closest(ignored)) return;
    const rect = element.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1) return;

    const style = window.getComputedStyle(element);
    if (style.display === "none" || style.visibility === "hidden") return;

    const tag = element.tagName.toLowerCase();
    const text = layoutElementText(element);
    const selector = layoutElementLabel(element);
    const hasElementOverflow = !["html", "body"].includes(tag) && element.scrollWidth > element.clientWidth + 2;
    const hidesOverflow = ["hidden", "clip"].includes(style.overflow) || ["hidden", "clip"].includes(style.overflowX) || style.textOverflow === "ellipsis";

    if (rect.left < -1 || rect.right > viewportWidth + 1) {
      issues.offscreen.push({ selector, text, reason: `${Math.round(rect.left)}..${Math.round(rect.right)}px` });
    }
    if (hasElementOverflow) {
      issues.overflowed.push({ selector, text, reason: `${element.clientWidth}/${element.scrollWidth}px` });
    }
    if (hasElementOverflow && hidesOverflow && text) {
      issues.clipped.push({ selector, text, reason: `${element.clientWidth}/${element.scrollWidth}px` });
    }
  });

  const scrollWidth = Math.max(doc.scrollWidth, body?.scrollWidth || 0);
  return {
    checked_at: new Date().toISOString(),
    viewport_width: Math.round(viewportWidth),
    viewport_height: Math.round(viewportHeight),
    scroll_width: Math.round(scrollWidth),
    page_overflow_x: scrollWidth > viewportWidth + 1,
    offscreen: issues.offscreen.slice(0, 12),
    overflowed: issues.overflowed.slice(0, 12),
    clipped: issues.clipped.slice(0, 12),
  };
}

export function layoutElementLabel(element) {
  const id = element.id ? `#${element.id}` : "";
  const className = typeof element.className === "string" ? element.className : "";
  const classes = className.trim().split(/\s+/).filter(Boolean).slice(0, 3).map((name) => `.${name}`).join("");
  return `${element.tagName.toLowerCase()}${id}${classes}`;
}

export function layoutElementText(element) {
  return String(element.textContent || "").replace(/\s+/g, " ").trim().slice(0, 160);
}

export function relativeTime(value) {
  if (!value) return "n/a";
  const diff = Date.now() - new Date(value).getTime();
  if (Number.isNaN(diff)) return String(value);
  const minutes = Math.round(diff / 60000);
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export function statusTone(value) {
  if (["healthy", "active", "completed", "resolved", "success", "accepted"].includes(String(value))) return "green";
  if (["failed", "dead_letter", "offline", "blocked", "rejected", "critical"].includes(String(value))) return "red";
  if (["open", "degraded", "pending_approval", "queued", "processing", "retry_wait", "stale"].includes(String(value))) return "amber";
  return "muted";
}

export function policyTone(policy) {
  if (policy === "AUTO_SAFE") return "green";
  if (policy === "APPROVAL_REQUIRED" || policy === "MANUAL_INVESTIGATION") return "amber";
  if (policy === "GITOPS_PR_ONLY") return "blue";
  if (policy === "NEVER_AUTO_EXECUTE") return "red";
  return "muted";
}

export function confidenceTone(value) {
  if (value === "high") return "green";
  if (value === "medium") return "amber";
  if (value === "low") return "red";
  return "muted";
}

export function severityTone(value) {
  if (["critical", "error", "high"].includes(String(value))) return "red";
  if (["warning", "medium"].includes(String(value))) return "amber";
  return "blue";
}

export function requestTone(value) {
  if (["accepted", "completed", "approved_manual"].includes(String(value))) return "green";
  if (["blocked", "rejected", "failed"].includes(String(value))) return "red";
  return "amber";
}

export function taskTone(value) {
  if (value === "completed") return "green";
  if (["failed", "dead_letter"].includes(String(value))) return "red";
  return "amber";
}

export function summarizeAgentFleet(agentHealth = [], clusters = []) {
  const summary = {
    total: Array.isArray(agentHealth) && agentHealth.length
      ? agentHealth.length
      : clusters.reduce((acc, cluster) => acc + Number(cluster.agent_count || 0), 0),
    healthy: 0,
    stale: 0,
    degraded: 0,
    offline: 0,
    unauthorized: 0,
    versionMismatch: 0,
    unknown: 0,
    unhealthy: 0,
  };
  (agentHealth || []).forEach((agent) => {
    const status = normalizedAgentStatus(agent);
    if (["healthy", "registered"].includes(status)) summary.healthy += 1;
    else if (status === "stale") summary.stale += 1;
    else if (status === "offline") summary.offline += 1;
    else if (status === "unauthorized") summary.unauthorized += 1;
    else if (status === "version_mismatch") summary.versionMismatch += 1;
    else if (["collector_degraded", "degraded"].includes(status)) summary.degraded += 1;
    else summary.unknown += 1;
  });
  summary.unhealthy = Math.max(0, summary.total - summary.healthy);
  return summary;
}

export function normalizedAgentStatus(agent) {
  return String(agent?.health_status || agent?.status || agent?.reported_status || "unknown");
}

export function agentReason(agent) {
  const reasons = agent?.reasons || agent?.health_reasons || [];
  if (Array.isArray(reasons) && reasons.length) return String(reasons[0]);
  const status = normalizedAgentStatus(agent);
  if (status === "collector_degraded") return "Collector self-check reported degraded prerequisites.";
  if (status === "version_mismatch") return "Agent version or protocol is outside the supported range.";
  if (status === "unauthorized") return "Agent reported authentication or authorization failure.";
  if (status === "stale") return "Heartbeat is older than the stale threshold.";
  if (status === "offline") return "Heartbeat exceeded the offline threshold.";
  const collectors = agent?.supported_collectors || agent?.supportedCollectors || [];
  return collectors.length ? `${collectors.length} collectors available` : "No risk reason reported.";
}

export function summarizePipeline(tasks = []) {
  const summary = { queued: 0, processing: 0, retry: 0, completed: 0, failed: 0, deadLetter: 0, backlog: 0 };
  (tasks || []).forEach((task) => {
    if (task.status === "queued") summary.queued += 1;
    else if (task.status === "processing") summary.processing += 1;
    else if (task.status === "retry_wait") summary.retry += 1;
    else if (task.status === "completed") summary.completed += 1;
    else if (task.status === "dead_letter") summary.deadLetter += 1;
    else if (task.status === "failed") summary.failed += 1;
  });
  summary.backlog = summary.queued + summary.processing + summary.retry;
  return summary;
}

export function withinHours(value, hours) {
  if (!value) return false;
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return false;
  return Date.now() - time <= hours * 60 * 60 * 1000;
}

export function auditTone(value) {
  if (String(value).includes("success")) return "green";
  if (String(value).includes("fail") || String(value).includes("denied")) return "red";
  return "amber";
}

export function agentHealthTone(agent) {
  const value = normalizedAgentStatus(agent);
  if (value === "healthy") return "green";
  if (["offline", "unauthorized", "version_mismatch"].includes(value)) return "red";
  return "amber";
}

export function signalIcon(value) {
  const family = inferSignalFamily(value);
  return {
    disk: "nvme",
    runtime: "boxes",
    kubelet: "cpu",
    network: "ethernet",
    control: "diagram-2",
    service: "activity",
  }[family] || "activity";
}

export function auditClientIp(event) {
  return event.client_ip || event.details?.client_ip || event.details?.remote_addr || "-";
}

export function auditSummary(details) {
  if (!details) return "-";
  if (typeof details === "string") return details;
  return Object.entries(details).slice(0, 4).map(([key, value]) => `${key}=${shortValue(value)}`).join(", ");
}
