export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonObject | JsonValue[];
export type JsonObject = { [key: string]: JsonValue };

export type AuthHeaders = Record<string, string>;
export type ApiMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface ApiRequestOptions {
  method?: ApiMethod | string;
  body?: unknown;
  headers?: Record<string, string>;
  handleUnauthorized?: boolean;
}

export type ApiCall = <T = unknown>(path: string, options?: ApiRequestOptions) => Promise<T>;
export type DownloadApi = (path: string, filename: string) => Promise<void>;
export type MaybePromise<T = void> = T | Promise<T>;

export interface ApiErrorDetails {
  status: number;
  code: string;
  title: string;
  detail: string;
  suggestion?: string;
  trace_id?: string;
}

export interface LoadState<T> {
  data: T;
  loading: boolean;
  error?: ApiErrorDetails;
  loadedAt?: string;
  stale: boolean;
}

export interface CursorPageResponse<T> {
  items: T[];
  next_cursor?: string | null;
  has_more: boolean;
  total: number;
  limit: number;
}

export type ConsoleDataSource =
  | "overviewSummary"
  | "clusters"
  | "actionRequests"
  | "agentHealth"
  | "demoScenarios"
  | "platformInfo"
  | "catalogDetail"
  | "catalogOverrideDrafts"
  | "auditEvents"
  | "notificationHistory"
  | "llmDiagnostics"
  | "llmSetupGuide";

export type ConsoleLoadStates = Record<ConsoleDataSource, LoadState<unknown>>;

export type UserRole = "admin" | "operator" | "viewer" | "auditor" | "approver" | string;
export type TFunction = (key: string) => string;
export type NotifyFunction = (message: string, tone?: string) => void;

export interface ToastState {
  tone?: string;
  message: string;
}

export interface LoginForm {
  username: string;
  password: string;
}

export interface ClusterCreateForm {
  name: string;
  environment: string;
  description?: string;
  backend_url?: string;
}

export interface PasswordChangeForm {
  current_password: string;
  new_password: string;
}

export interface LoginIdChangeForm {
  current_password: string;
  new_username: string;
}

export interface AuditSearchFilters {
  q?: string;
  client_ip?: string;
  event_type?: string;
  outcome?: string;
  limit?: number;
  [key: string]: string | number | undefined;
}

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
  agent_protocol_version?: string;
  platform_protocol_version?: string;
  health_status?: string;
  status?: string;
  reported_status?: string;
  last_heartbeat_at?: string;
  heartbeat_age_seconds?: number;
  supported_collectors?: string[];
  health?: JsonObject;
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
  cause?: string;
  reason?: string;
  confidence?: string;
  confidence_score?: number;
  evidence_refs?: string[];
  supporting_evidence_ids?: string[];
  evidence_paths?: string[];
  supporting_evidence?: string[];
  score_reason?: string;
  [key: string]: unknown;
}

export interface OverviewSummary {
  cluster_count: number;
  report_count: number;
  open_incident_count: number;
  reports_last_24_hours: number;
  analysis_backlog_count: number;
  analysis_queued_count: number;
  analysis_processing_count: number;
  analysis_retry_count: number;
  analysis_dead_letter_count: number;
  action_request_count: number;
  pending_approval_count: number;
  manual_action_count: number;
  blocked_action_count: number;
  agent_count: number;
  healthy_agent_count: number;
  stale_agent_count: number;
  degraded_agent_count: number;
  offline_agent_count: number;
  recent_clusters: ClusterView[];
  recent_reports: RcaReport[];
  recent_incidents: IncidentView[];
}

export interface InstallCommandView {
  cluster_id?: string;
  namespace?: string;
  commands?: string[];
  notes?: string[];
  [key: string]: unknown;
}

export interface ClusterDetailState {
  agents: AgentHealthView[];
  evidence: EvidenceRequestView[];
  topology: JsonObject | null;
  thresholds?: ClusterThresholdSettings | null;
  enrollment?: AgentEnrollmentProfile | null;
}

export type AgentEnrollmentMode = "bootstrap_token" | "kubernetes_token_review";

export interface AgentEnrollmentProfile {
  cluster_id: string;
  mode: AgentEnrollmentMode;
  configured: boolean;
  api_server_url?: string | null;
  ca_sha256?: string | null;
  audience?: string | null;
  namespace?: string | null;
  service_account?: string | null;
  bootstrap_fallback_allowed: boolean;
  bootstrap_token_rotation_required: boolean;
  updated_at?: string | null;
}

export interface AgentEnrollmentUpdate {
  mode: AgentEnrollmentMode;
  api_server_url?: string;
  ca_bundle_pem?: string;
  audience?: string;
  namespace?: string;
  service_account?: string;
  bootstrap_fallback_allowed?: boolean;
}

export interface ClusterThresholdSettings {
  cluster_id?: string;
  defaults?: Record<string, number>;
  overrides?: Record<string, number>;
  effective?: Record<string, number>;
  supported_keys?: string[];
  definitions?: ThresholdDefinition[];
  updated_at?: string;
  [key: string]: unknown;
}

export interface ThresholdDefinition {
  key: string;
  label?: string;
  unit?: string;
  minimum?: number;
  maximum?: number | null;
  severity?: string;
  paired_key?: string | null;
  [key: string]: unknown;
}

export interface CatalogCollectorDefinition {
  description?: string;
  enabled?: boolean;
  permission_modes?: string[];
  [key: string]: unknown;
}

export interface CatalogActionPlanDefinition {
  command_key?: string;
  parameters?: Record<string, string>;
  command_preview?: string[];
  yaml_patch?: string;
  executable?: boolean;
  timeout_seconds?: number;
  [key: string]: unknown;
}

export interface CatalogActionDefinition {
  action?: string;
  reason?: string;
  policy?: string;
  automation_mode?: string;
  risks?: string[];
  triggers?: JsonObject;
  plan?: CatalogActionPlanDefinition;
  [key: string]: unknown;
}

export interface CatalogRuleDefinition {
  detector?: string;
  enabled?: boolean;
  component?: string;
  signals?: string[];
  [key: string]: unknown;
}

export interface OperationalCatalogDetail {
  summary?: JsonObject;
  collectors?: Record<string, CatalogCollectorDefinition>;
  collector_selection?: {
    default_collectors?: string[];
    alerts?: Record<string, string[]>;
    [key: string]: unknown;
  };
  actions?: Record<string, CatalogActionDefinition>;
  rules?: Record<string, CatalogRuleDefinition>;
  [key: string]: unknown;
}

export interface CatalogDiffEntry {
  path?: string;
  change_type?: string;
  current_value?: JsonValue;
  proposed_value?: JsonValue;
  [key: string]: unknown;
}

export interface CatalogOverridePreviewResponse {
  valid?: boolean;
  message?: string;
  summary?: JsonObject;
  diff?: CatalogDiffEntry[];
  diff_count?: number;
  diff_truncated?: boolean;
  [key: string]: unknown;
}

export interface CatalogOverrideDraft {
  draft_id: string;
  status?: string;
  override_json?: string;
  preview_summary?: JsonObject;
  diff?: CatalogDiffEntry[];
  diff_truncated?: boolean;
  validation_message?: string;
  reason?: string;
  requested_by?: string;
  reviewed_by?: string;
  decision_note?: string;
  created_at?: string;
  updated_at?: string;
  reviewed_at?: string;
  [key: string]: unknown;
}

export interface CatalogOverrideHandoff {
  draft_id?: string;
  status?: string;
  recommendation?: string;
  runbook_steps?: string[];
  files?: Record<string, string>;
  pull_request_title?: string;
  pull_request_body?: string;
  [key: string]: unknown;
}

export type GitOpsDeploymentState = "pending" | "in_progress" | "succeeded" | "failed" | "rolled_back";

export interface GitOpsChange {
  change_id: string;
  source_type?: string;
  source_id?: string;
  provider?: string;
  repository?: string;
  branch?: string;
  base_branch?: string;
  file_path?: string;
  pull_request_number?: number;
  pull_request_url?: string;
  pull_request_state?: string;
  head_sha?: string;
  deployment_state?: GitOpsDeploymentState;
  verification_result?: string;
  rollback_reference?: string;
  error_message?: string;
  retry_count?: number;
  last_attempt_at?: string;
  last_failure_at?: string;
  last_reconciled_at?: string;
  requested_by?: string;
  created_at?: string;
  updated_at?: string;
  deployment_started_at?: string;
  deployment_completed_at?: string;
  [key: string]: unknown;
}

export interface AgentTokenRotateResponse {
  agent_token?: string;
  issued_at?: string;
  expires_at?: string;
  note?: string;
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
  policy?: string;
  source?: string;
  status?: string;
  created_at?: string;
  [key: string]: unknown;
}

export interface ActionExecutionView {
  action_execution_id?: string;
  action_request_id?: string;
  command_key?: string;
  status?: string;
  [key: string]: unknown;
}

export interface ActionDialogState {
  report: RcaReport;
  action: RecommendedAction;
  index: number;
}

export interface DeleteClusterDialogState {
  cluster: ClusterView;
}

export interface IncidentView {
  incident_id: string;
  latest_report_id?: string;
  cluster_id?: string;
  node_name?: string;
  node_names?: string[];
  alert_name?: string;
  root_cause?: string;
  status?: string;
  severity?: string;
  occurrence_count?: number;
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

export interface DemoScenarioView {
  key: string;
  name?: string;
  alert_name?: string;
  alertName?: string;
  description?: string;
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
  llm?: LlmConfigurationInfo;
  notification?: NotificationConfigurationInfo;
  catalog?: JsonObject;
  gitops?: JsonObject;
  thresholds?: JsonObject;
  operations?: JsonObject;
  [key: string]: unknown;
}

export interface NotificationConfigurationInfo {
  enabled?: boolean;
  slack_configured?: boolean;
  slackConfigured?: boolean;
  webhook_configured?: boolean;
  webhookConfigured?: boolean;
  webhook_token_configured?: boolean;
  webhookTokenConfigured?: boolean;
  minimum_severity?: string;
  minimumSeverity?: string;
  max_attempts?: number;
  maxAttempts?: number;
  timeout_seconds?: number;
  timeoutSeconds?: number;
  channels?: string[];
  delivery_mode?: string;
  deliveryMode?: string;
  queue_depth?: number;
  queueDepth?: number;
  dead_letter_count?: number;
  deadLetterCount?: number;
  [key: string]: unknown;
}

export interface NotificationDeliveryResult {
  channel?: string;
  outcome?: string;
  attempts?: number;
  status_code?: number;
  statusCode?: number;
  error?: string;
  [key: string]: unknown;
}

export interface NotificationTestResponse {
  outcome?: string;
  message?: string;
  results?: NotificationDeliveryResult[];
  [key: string]: unknown;
}

export interface LlmDiagnosticCheck {
  key?: string;
  status?: string;
  message?: string;
  remediation?: string;
  [key: string]: unknown;
}

export interface LlmDiagnosticResponse {
  outcome?: string;
  configuration?: LlmConfigurationInfo;
  checks?: LlmDiagnosticCheck[];
  [key: string]: unknown;
}

export interface LlmProviderSetupOption {
  provider?: string;
  display_name?: string;
  displayName?: string;
  spring_ai_chat_model?: string;
  springAiChatModel?: string;
  credential_env?: string;
  credentialEnv?: string;
  base_url_env?: string;
  baseUrlEnv?: string;
  credential_required?: boolean;
  credentialRequired?: boolean;
  base_url_required?: boolean;
  baseUrlRequired?: boolean;
  model_examples?: string[];
  modelExamples?: string[];
  note?: string;
  [key: string]: unknown;
}

export interface LlmSetupGuideResponse {
  docs_path?: string;
  docsPath?: string;
  restart_required?: boolean;
  restartRequired?: boolean;
  secret_storage?: string;
  secretStorage?: string;
  providers?: LlmProviderSetupOption[];
  [key: string]: unknown;
}

export interface LlmTestResponse {
  outcome?: string;
  message?: string;
  provider?: string;
  model?: string;
  prompt_version?: string;
  promptVersion?: string;
  latency_ms?: number;
  latencyMs?: number;
  response_chars?: number;
  responseChars?: number;
  error?: string;
  [key: string]: unknown;
}

export interface LlmConfigurationInfo {
  enabled?: boolean;
  provider?: string;
  model?: string;
  spring_ai_chat_model?: string;
  springAiChatModel?: string;
  credential_required?: boolean;
  credentialRequired?: boolean;
  credential_configured?: boolean;
  credentialConfigured?: boolean;
  credential_property?: string;
  credentialProperty?: string;
  credential_env?: string;
  credentialEnv?: string;
  base_url_configured?: boolean;
  baseUrlConfigured?: boolean;
  base_url_required?: boolean;
  baseUrlRequired?: boolean;
  base_url_property?: string;
  baseUrlProperty?: string;
  base_url_env?: string;
  baseUrlEnv?: string;
  timeout_seconds?: number;
  timeoutSeconds?: number;
  max_attempts?: number;
  maxAttempts?: number;
  max_output_tokens?: number;
  maxOutputTokens?: number;
  failure_threshold?: number;
  failureThreshold?: number;
  cooldown_seconds?: number;
  cooldownSeconds?: number;
  input_cost_per_million_tokens?: number;
  inputCostPerMillionTokens?: number;
  output_cost_per_million_tokens?: number;
  outputCostPerMillionTokens?: number;
  cost_estimation_enabled?: boolean;
  costEstimationEnabled?: boolean;
  [key: string]: unknown;
}
