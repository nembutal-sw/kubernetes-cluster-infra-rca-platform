export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonObject | JsonValue[];
export type JsonObject = { [key: string]: JsonValue };

export type AuthHeaders = Record<string, string>;
export type ApiMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface ApiRequestOptions {
  method?: ApiMethod | string;
  body?: unknown;
  headers?: Record<string, string>;
}

export type UserRole = "admin" | "operator" | "viewer" | "auditor" | "approver" | string;
export type TFunction = (key: string) => string;

export interface UserAccount {
  user_id: string;
  email?: string;
  role: UserRole;
  [key: string]: unknown;
}

export interface AuthSession {
  access_token?: string;
  accessToken?: string;
  user?: UserAccount;
  [key: string]: unknown;
}

export interface ClusterView {
  cluster_id: string;
  name: string;
  environment?: string;
  description?: string;
  status?: string;
  created_at?: string;
  last_seen_at?: string;
  agent_count?: number;
  [key: string]: unknown;
}

export interface AgentHealthView {
  agent_id?: string;
  cluster_id?: string;
  node_name: string;
  agent_version?: string;
  health_status?: string;
  status?: string;
  reported_status?: string;
  last_heartbeat_at?: string;
  supported_collectors?: string[];
  reasons?: string[];
  health_reasons?: string[];
  [key: string]: unknown;
}

export interface EvidenceRequestView {
  request_id: string;
  cluster_id?: string;
  node_name?: string;
  alert_name?: string;
  status?: string;
  created_at?: string;
  completed_at?: string;
  [key: string]: unknown;
}

export interface RcaSummary {
  symptom?: string;
  most_likely_cause?: string;
  confidence?: string;
  [key: string]: unknown;
}

export interface RootCauseCandidate {
  category?: string;
  component?: string;
  reason?: string;
  confidence_score?: number;
  evidence_refs?: string[];
  [key: string]: unknown;
}

export interface RecommendedAction {
  action_key?: string;
  action?: string;
  reason?: string;
  source?: string;
  policy?: string;
  automation_allowed?: boolean;
  risk_factors?: string[];
  execution_plan?: {
    command_preview?: string[];
    [key: string]: unknown;
  };
  [key: string]: unknown;
}

export interface RcaReport {
  report_id: string;
  incident_id?: string;
  cluster_id?: string;
  node_name?: string;
  created_at?: string;
  summary?: RcaSummary;
  trigger?: JsonObject & { alert_name?: string };
  root_cause_candidates?: RootCauseCandidate[];
  recommended_actions?: RecommendedAction[];
  additional_checks?: unknown[];
  next_steps?: unknown[];
  [key: string]: unknown;
}

export interface ActionRequestView {
  action_request_id: string;
  report_id?: string;
  action_key?: string;
  status?: string;
  created_at?: string;
  [key: string]: unknown;
}

export interface ActionExecutionView {
  action_execution_id?: string;
  action_request_id?: string;
  status?: string;
  [key: string]: unknown;
}

export interface IncidentView {
  incident_id: string;
  latest_report_id?: string;
  status?: string;
  severity?: string;
  last_seen_at?: string;
  [key: string]: unknown;
}

export interface AnalysisTaskView {
  task_id: string;
  status?: string;
  alert_name?: string;
  cluster_id?: string;
  node_name?: string;
  created_at?: string;
  [key: string]: unknown;
}

export interface AuditEventView {
  audit_event_id: string;
  created_at?: string;
  actor_type?: string;
  actor_id?: string;
  event_type?: string;
  resource_type?: string;
  resource_id?: string;
  outcome?: string;
  client_ip?: string;
  details?: JsonObject;
  [key: string]: unknown;
}

export interface EvidenceBundleManifest {
  filename?: string;
  generated_at?: string;
  generatedAt?: string;
  entry_count?: number;
  entryCount?: number;
  zip_bytes?: number;
  zipBytes?: number;
  raw_bytes?: number;
  rawBytes?: number;
  entries?: Array<{ path?: string; sha256?: string; [key: string]: unknown }>;
  [key: string]: unknown;
}

export interface ReportDetailState {
  report: RcaReport;
  actionRequests?: ActionRequestView[];
  actionExecutions?: ActionExecutionView[];
  timeline?: JsonObject | null;
  bundleManifest?: EvidenceBundleManifest | null;
}

export interface PlatformInfo {
  export_security?: JsonObject;
  exportSecurity?: JsonObject;
  [key: string]: unknown;
}
