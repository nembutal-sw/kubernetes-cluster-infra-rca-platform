import type {
  AgentHealthView,
  AnalysisTaskView,
  AuditEventView,
  ClusterView,
  IncidentView,
  JsonObject,
  PlatformInfo,
  RcaReport,
  TFunction,
} from "../types";

type NotifyFunction = (message: string, tone?: string) => void;
type SortableRecord = Record<string, unknown>;
type Tone = "green" | "amber" | "red" | "blue" | "muted" | "ok";
type SignalFamily = "disk" | "runtime" | "kubelet" | "network" | "control" | "service";

interface AuditStats {
  exports: number;
  failures: number;
  approvals: number;
}

interface SignalDigestItem {
  id: string;
  title: string;
  detail: string;
  severity: "critical" | "warning";
  family: SignalFamily;
}

interface SummaryRow {
  key?: string;
  label: string;
  value: string;
  tone?: Tone;
}

interface LayoutIssue {
  selector: string;
  text: string;
  reason: string;
}

interface LayoutAuditResult {
  checked_at: string;
  viewport_width: number;
  viewport_height: number;
  scroll_width: number;
  page_overflow_x: boolean;
  offscreen: LayoutIssue[];
  overflowed: LayoutIssue[];
  clipped: LayoutIssue[];
}

interface AgentFleetSummary {
  total: number;
  healthy: number;
  stale: number;
  degraded: number;
  offline: number;
  unauthorized: number;
  versionMismatch: number;
  unknown: number;
  unhealthy: number;
}

interface PipelineSummary {
  queued: number;
  processing: number;
  retry: number;
  completed: number;
  failed: number;
  deadLetter: number;
  backlog: number;
}

export function arrayResult<T>(result: PromiseSettledResult<T[]>): T[] {
  return result.status === "fulfilled" && Array.isArray(result.value) ? result.value : [];
}

export function sortByTime<T extends SortableRecord>(items: T[] = [], field: keyof T): T[] {
  return [...items].sort((a, b) => new Date(String(b[field] || 0)).getTime() - new Date(String(a[field] || 0)).getTime());
}

export async function copyText(text: string | null | undefined, notify: NotifyFunction): Promise<void> {
  await navigator.clipboard.writeText(text || "");
  notify("Copied.");
}

export function buildAuditQuery(filters: object | null | undefined): string {
  const query = new URLSearchParams();
  Object.entries(filters || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      query.set(key, String(value).trim());
    }
  });
  if (!query.has("limit")) query.set("limit", "200");
  return query.toString();
}

export function auditStats(events: AuditEventView[] = []): AuditStats {
  return events.reduce((stats, event) => {
    const type = String(event.event_type || "").toLowerCase();
    const outcome = String(event.outcome || "").toLowerCase();
    if (type.includes("export") || type.includes("bundle")) stats.exports += 1;
    if (outcome.includes("fail") || outcome.includes("unauthorized") || outcome.includes("forbidden")) stats.failures += 1;
    if (type.includes("approval") || type.includes("approve") || type.includes("action_request")) stats.approvals += 1;
    return stats;
  }, { exports: 0, failures: 0, approvals: 0 });
}

export function buildSignalDigest(reports: RcaReport[] = [], incidents: IncidentView[] = []): SignalDigestItem[] {
  const fromReports = reports.flatMap((report) => {
    const candidates = report.root_cause_candidates || [];
    return candidates.slice(0, 2).map((candidate, index) => {
      const title = candidate.cause || candidate.reason || report.summary?.symptom || "RCA signal";
      const detail = stringArray(candidate.supporting_evidence || candidate.evidence_refs)[0] || report.cluster_id || "";
      const severity: SignalDigestItem["severity"] = candidate.confidence === "high" ? "critical" : "warning";
      return {
        id: `${report.report_id}-${index}`,
        title,
        detail,
        severity,
        family: inferSignalFamily(candidate.cause || report.summary?.most_likely_cause || ""),
      };
    });
  });
  const fromIncidents = incidents
    .filter((incident) => incident.status === "open")
    .map((incident) => ({
      id: incident.incident_id,
      title: incident.alert_name || "Open incident",
      detail: incident.root_cause || incident.cluster_id || "",
      severity: "critical" as const,
      family: inferSignalFamily(`${incident.alert_name || ""} ${incident.root_cause || ""}`),
    }));
  return [...fromIncidents, ...fromReports];
}

export function scoreStage(stage: string, reports: RcaReport[] = [], incidents: IncidentView[] = []): number {
  const text = JSON.stringify([reports, incidents]).toLowerCase();
  const needles = ({
    disk: ["disk", "inode", "io", "filesystem", "pressure"],
    runtime: ["containerd", "runtime", "docker", "crio"],
    kubelet: ["kubelet", "node not ready", "nodeready"],
    network: ["network", "cni", "conntrack", "dns", "mtu", "tcp"],
    control: ["apiserver", "api server", "etcd", "control plane"],
    service: ["service", "coredns", "endpoint", "latency"],
  } as Record<string, string[]>)[stage] || [];
  return needles.reduce((count, needle) => count + occurrences(text, needle), 0);
}

export function occurrences(text: string, needle: string): number {
  if (!needle) return 0;
  return (text.match(new RegExp(escapeRegExp(needle), "g")) || []).length;
}

export function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function inferSignalFamily(value: unknown): SignalFamily {
  const text = String(value || "").toLowerCase();
  if (/disk|inode|io|filesystem|pressure/.test(text)) return "disk";
  if (/containerd|runtime|docker|crio/.test(text)) return "runtime";
  if (/kubelet|node/.test(text)) return "kubelet";
  if (/network|cni|conntrack|dns|mtu|tcp|nic/.test(text)) return "network";
  if (/api|etcd|control/.test(text)) return "control";
  return "service";
}

export function evidenceSummary(report: RcaReport): Array<{ label: string; value: string }> {
  const rows: Array<{ label: string; value: string }> = [];
  Object.entries(recordValue(report.trigger)).forEach(([key, value]) => rows.push({ label: `trigger.${key}`, value: shortValue(value) }));
  (report.root_cause_candidates || []).forEach((candidate) => {
    stringArray(candidate.supporting_evidence || candidate.evidence_refs).slice(0, 2)
      .forEach((value) => rows.push({ label: candidate.cause || candidate.reason || "candidate", value }));
  });
  const evidence = Array.isArray(report.evidence)
    ? Object.fromEntries(report.evidence.map((value, index) => [`section_${index}`, value]))
    : recordValue(report.evidence || report.evidence_summary);
  Object.entries(evidence).slice(0, 8).forEach(([key, value]) => rows.push({ label: key, value: shortValue(value) }));
  return rows;
}

export function derivedSignals(report: RcaReport): unknown[] {
  const evidence = Array.isArray(report?.evidence) ? report.evidence : [];
  const direct = evidence.find((section) => recordValue(section).type === "derived_signals");
  if (Array.isArray(recordValue(direct).signals)) return recordValue(direct).signals as unknown[];
  const preprocessed = evidence.find((section) => recordValue(section).type === "preprocessed_evidence");
  const payload = recordValue(recordValue(preprocessed).payload);
  const nested = payload.derived_signals || payload.derivedSignals;
  return Array.isArray(nested) ? nested : [];
}

export function reportEvidenceQuality(report: RcaReport): unknown {
  const evidence = Array.isArray(report?.evidence) ? report.evidence : [];
  const direct = evidence.find((section) => recordValue(section).type === "evidence_quality");
  if (recordValue(direct).quality) return recordValue(direct).quality;
  const preprocessed = evidence.find((section) => recordValue(section).type === "preprocessed_evidence");
  const payload = recordValue(recordValue(preprocessed).payload);
  return payload.evidence_quality || payload.evidenceQuality || null;
}

export function reportQualityGate(report: RcaReport): unknown {
  const evidence = Array.isArray(report?.evidence) ? report.evidence : [];
  const direct = evidence.find((section) => recordValue(section).type === "quality_gate");
  if (recordValue(direct).gate) return recordValue(direct).gate;
  const preprocessed = evidence.find((section) => recordValue(section).type === "preprocessed_evidence");
  const payload = recordValue(recordValue(preprocessed).payload);
  return payload.final_quality_gate
    || payload.finalQualityGate
    || payload.quality_gate
    || payload.qualityGate
    || null;
}

export function qualityTone(value: unknown): Tone {
  const normalized = String(value || "").toLowerCase();
  if (["complete", "healthy", "ok", "fresh"].includes(normalized)) return "green";
  if (["partial", "stale", "warning", "limited", "unknown"].includes(normalized)) return "amber";
  if (["degraded", "offline", "unauthorized", "collector_degraded", "version_mismatch", "failed", "error"].includes(normalized)) return "red";
  return "muted";
}

export function qualityGateTone(value: unknown): Tone {
  const normalized = String(value || "").toLowerCase();
  if (normalized === "pass") return "green";
  if (normalized === "limited") return "amber";
  if (normalized === "insufficient") return "red";
  return qualityTone(value);
}

export function formatFreshness(freshness: unknown): string {
  if (!freshness) return "unknown";
  const value = recordValue(freshness);
  const status = String(value.status || "unknown");
  const ageSeconds = numberOrNull(value.age_seconds ?? value.ageSeconds);
  if (ageSeconds === null) return status;
  if (ageSeconds < 60) return `${status} · ${ageSeconds}s`;
  if (ageSeconds < 3600) return `${status} · ${Math.round(ageSeconds / 60)}m`;
  return `${status} · ${(ageSeconds / 3600).toFixed(1)}h`;
}

export function formatPercentValue(value: unknown): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return "n/a";
  const numeric = Number(value);
  return numeric <= 1 ? `${Math.round(numeric * 100)}%` : `${Math.round(numeric)}%`;
}

export function fallbackTimeline(report: RcaReport): Array<Record<string, unknown>> {
  const candidates = report.root_cause_candidates || [];
  const root = candidates[0]?.cause || report.summary?.most_likely_cause;
  const items: Array<Record<string, unknown>> = [];
  if (root) {
    items.push({ id: "root", timestamp: report.created_at, component: inferSignalFamily(root), title: root, detail: "Most likely root cause", root_trigger: true });
  }
  (report.recommended_actions || []).slice(0, 4).forEach((action, index) => {
    items.push({ id: `action-${index}`, timestamp: report.created_at, component: action.action_key, title: action.action_key || action.policy, detail: action.reason });
  });
  return items;
}

export function shortValue(value: unknown): string {
  if (value === null || value === undefined) return "n/a";
  if (typeof value === "object") return JSON.stringify(value).slice(0, 180);
  return String(value).slice(0, 180);
}

export function platformInfoRows(platformInfo: PlatformInfo | null | undefined, t: TFunction): SummaryRow[] {
  const exportSecurity = recordValue(platformInfo?.export_security || platformInfo?.exportSecurity);
  const catalog = recordValue(platformInfo?.catalog);
  const rows: SummaryRow[] = [
    { key: "platform_version", label: t("Platform version"), value: String(platformInfo?.platform_version || "n/a") },
    { key: "api_version", label: t("API version"), value: String(platformInfo?.api_version || "n/a") },
    { key: "agent_protocol_version", label: t("Agent protocol"), value: String(platformInfo?.agent_protocol_version || "n/a") },
    {
      key: "minimum_supported_agent_protocol_version",
      label: t("Minimum agent protocol"),
      value: String(platformInfo?.minimum_supported_agent_protocol_version || "n/a"),
    },
    {
      key: "minimum_supported_agent_version",
      label: t("Minimum agent version"),
      value: String(platformInfo?.minimum_supported_agent_version || "n/a"),
    },
  ];
  if (platformInfo?.export_security || platformInfo?.exportSecurity) {
    rows.push(
      {
        key: "export_security.max_bundle_bytes",
        label: `${t("Export security")} · ${t("Max bundle size")}`,
        value: formatBytes(exportSecurity.max_bundle_bytes),
      },
      {
        key: "export_security.hash_algorithm",
        label: `${t("Export security")} · ${t("Hash algorithm")}`,
        value: String(exportSecurity.hash_algorithm || "n/a"),
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
        value: String(exportSecurity.bundle_signature_algorithm || "n/a"),
      },
      {
        key: "export_security.bundle_signature_key_id",
        label: `${t("Export security")} · ${t("Signature key")}`,
        value: String(exportSecurity.bundle_signature_key_id || "n/a"),
      },
      {
        key: "export_security.offline_verifier",
        label: `${t("Export security")} · ${t("Offline verifier")}`,
        value: String(exportSecurity.offline_verifier || "n/a"),
      },
    );
  }
  if (platformInfo?.catalog) {
    rows.push(
      { key: "catalog.version", label: `${t("Catalog")} / ${t("Version")}`, value: String(catalog.version || "n/a") },
      { key: "catalog.source", label: `${t("Catalog")} / ${t("Source")}`, value: String(catalog.source || "n/a") },
      { key: "catalog.checksum", label: `${t("Catalog")} / ${t("Checksum")}`, value: String(catalog.checksum || "n/a") },
      { key: "catalog.collectors", label: `${t("Catalog")} / ${t("Collectors")}`, value: String(catalog.collector_count || "0") },
      { key: "catalog.actions", label: `${t("Catalog")} / ${t("Actions")}`, value: String(catalog.action_count || "0") },
      { key: "catalog.rules", label: `${t("Catalog")} / ${t("Rules")}`, value: String(catalog.rule_count || "0") },
    );
  }
  return rows;
}

export function formatBytes(value: unknown): string {
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

export function shortHash(value: unknown): string {
  const text = String(value || "");
  return text.length > 18 ? `${text.slice(0, 12)}...${text.slice(-6)}` : text || "n/a";
}

export function formatDate(value: unknown): string {
  if (!value) return "n/a";
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function runConsoleLayoutAudit(): LayoutAuditResult {
  const doc = document.documentElement;
  const body = document.body;
  const viewportWidth = window.innerWidth || doc.clientWidth;
  const viewportHeight = window.innerHeight || doc.clientHeight;
  const allowedOverflow = ".table-responsive, .console-table-wrap, pre, code, .config-sample, .command-preview, .install-command";
  const ignored = ".diagnostics-panel, script, style, noscript";
  const issues: { offscreen: LayoutIssue[]; overflowed: LayoutIssue[]; clipped: LayoutIssue[] } = {
    offscreen: [],
    overflowed: [],
    clipped: [],
  };

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

export function layoutElementLabel(element: Element): string {
  const id = element.id ? `#${element.id}` : "";
  const className = typeof element.className === "string" ? element.className : "";
  const classes = className.trim().split(/\s+/).filter(Boolean).slice(0, 3).map((name) => `.${name}`).join("");
  return `${element.tagName.toLowerCase()}${id}${classes}`;
}

export function layoutElementText(element: Element): string {
  return String(element.textContent || "").replace(/\s+/g, " ").trim().slice(0, 160);
}

export function relativeTime(value: unknown): string {
  if (!value) return "n/a";
  const diff = Date.now() - new Date(String(value)).getTime();
  if (Number.isNaN(diff)) return String(value);
  const minutes = Math.round(diff / 60000);
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export function statusTone(value: unknown): Tone {
  if (["healthy", "active", "completed", "resolved", "success", "accepted"].includes(String(value))) return "green";
  if (["failed", "dead_letter", "offline", "blocked", "rejected", "critical"].includes(String(value))) return "red";
  if (["open", "degraded", "pending_approval", "queued", "processing", "retry_wait", "stale"].includes(String(value))) return "amber";
  return "muted";
}

export function policyTone(policy: unknown): Tone {
  if (policy === "AUTO_SAFE") return "green";
  if (policy === "APPROVAL_REQUIRED" || policy === "MANUAL_INVESTIGATION") return "amber";
  if (policy === "GITOPS_PR_ONLY") return "blue";
  if (policy === "NEVER_AUTO_EXECUTE") return "red";
  return "muted";
}

export function confidenceTone(value: unknown): Tone {
  if (value === "high") return "green";
  if (value === "medium") return "amber";
  if (value === "low") return "red";
  return "muted";
}

export function severityTone(value: unknown): Tone {
  if (["critical", "error", "high"].includes(String(value))) return "red";
  if (["warning", "medium"].includes(String(value))) return "amber";
  return "blue";
}

export function requestTone(value: unknown): Tone {
  if (["accepted", "completed", "approved_manual"].includes(String(value))) return "green";
  if (["blocked", "rejected", "failed"].includes(String(value))) return "red";
  return "amber";
}

export function taskTone(value: unknown): Tone {
  if (value === "completed") return "green";
  if (["failed", "dead_letter"].includes(String(value))) return "red";
  return "amber";
}

export function summarizeAgentFleet(agentHealth: AgentHealthView[] = [], clusters: ClusterView[] = []): AgentFleetSummary {
  const summary: AgentFleetSummary = {
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
  agentHealth.forEach((agent) => {
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

export function normalizedAgentStatus(agent: Partial<AgentHealthView> | null | undefined): string {
  return String(agent?.health_status || agent?.status || agent?.reported_status || "unknown");
}

export function agentReason(agent: Partial<AgentHealthView> | null | undefined): string {
  const reasons = agent?.reasons || agent?.health_reasons || [];
  if (Array.isArray(reasons) && reasons.length) return String(reasons[0]);
  const status = normalizedAgentStatus(agent);
  if (status === "collector_degraded") return "Collector self-check reported degraded prerequisites.";
  if (status === "version_mismatch") return "Agent version or protocol is outside the supported range.";
  if (status === "unauthorized") return "Agent reported authentication or authorization failure.";
  if (status === "stale") return "Heartbeat is older than the stale threshold.";
  if (status === "offline") return "Heartbeat exceeded the offline threshold.";
  const collectors = agent?.supported_collectors || [];
  return collectors.length ? `${collectors.length} collectors available` : "No risk reason reported.";
}

export function summarizePipeline(tasks: AnalysisTaskView[] = []): PipelineSummary {
  const summary: PipelineSummary = { queued: 0, processing: 0, retry: 0, completed: 0, failed: 0, deadLetter: 0, backlog: 0 };
  tasks.forEach((task) => {
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

export function withinHours(value: unknown, hours: number): boolean {
  if (!value) return false;
  const time = new Date(String(value)).getTime();
  if (Number.isNaN(time)) return false;
  return Date.now() - time <= hours * 60 * 60 * 1000;
}

export function auditTone(value: unknown): Tone {
  if (String(value).includes("success")) return "green";
  if (String(value).includes("fail") || String(value).includes("denied")) return "red";
  return "amber";
}

export function agentHealthTone(agent: Partial<AgentHealthView>): Tone {
  const value = normalizedAgentStatus(agent);
  if (value === "healthy") return "green";
  if (["offline", "unauthorized", "version_mismatch"].includes(value)) return "red";
  return "amber";
}

export function signalIcon(value: unknown): string {
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

export function auditClientIp(event: AuditEventView): string {
  return event.client_ip || stringValue(recordValue(event.details).client_ip) || stringValue(recordValue(event.details).remote_addr) || "-";
}

export function auditSummary(details: JsonObject | string | null | undefined): string {
  if (!details) return "-";
  if (typeof details === "string") return details;
  return Object.entries(details).slice(0, 4).map(([key, value]) => `${key}=${shortValue(value)}`).join(", ");
}

function recordValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

function numberOrNull(value: unknown): number | null {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function stringValue(value: unknown): string {
  return value === null || value === undefined ? "" : String(value);
}
