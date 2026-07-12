import type { ApiErrorDetails, ApiRequestOptions, AuthHeaders, UserAccount } from "../types";

interface ApiErrorPayload {
  code?: unknown;
  error_code?: unknown;
  title?: unknown;
  detail?: unknown;
  suggestion?: unknown;
  trace_id?: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  readonly suggestion?: string;
  readonly traceId?: string;

  constructor(details: ApiErrorDetails) {
    super(details.detail || details.title);
    this.name = "ApiError";
    this.status = details.status;
    this.code = details.code;
    this.title = details.title;
    this.detail = details.detail;
    this.suggestion = details.suggestion;
    this.traceId = details.trace_id;
  }

  toDetails(): ApiErrorDetails {
    return {
      status: this.status,
      code: this.code,
      title: this.title,
      detail: this.detail,
      suggestion: this.suggestion,
      trace_id: this.traceId,
    };
  }

  static invalidResponse(detail: string): ApiError {
    return new ApiError({
      status: 0,
      code: "invalid_response",
      title: "Invalid API response",
      detail,
      suggestion: "Retry the request and inspect the platform API response.",
    });
  }
}

export function apiErrorDetails(error: unknown, fallback: string): ApiErrorDetails {
  if (error instanceof ApiError) return error.toDetails();
  const detail = error instanceof Error && error.message ? error.message : fallback;
  return {
    status: 0,
    code: "network_error",
    title: "API request failed",
    detail,
    suggestion: "Check platform connectivity and retry the request.",
  };
}

export async function requestApi<T = unknown>(
  path: string,
  options: ApiRequestOptions = {},
  authHeaders: AuthHeaders = {},
): Promise<T> {
  const headers = {
    Accept: "application/json",
    ...authHeaders,
    ...(options.body ? { "Content-Type": "application/json" } : {}),
    ...(options.headers || {}),
  };
  const response = await fetch(path, {
    method: options.method || "GET",
    credentials: "same-origin",
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!response.ok) {
    throw await responseError(response);
  }
  if (response.status === 204) return null as T;
  const contentType = response.headers.get("content-type") || "";
  return (contentType.includes("application/json") ? response.json() : response.text()) as Promise<T>;
}

export async function downloadFromApi(
  path: string,
  filename: string,
  authHeaders: AuthHeaders = {},
): Promise<void> {
  const response = await fetch(path, {
    credentials: "same-origin",
    headers: { ...authHeaders },
  });
  if (!response.ok) {
    throw await responseError(response);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

async function responseError(response: Response): Promise<ApiError> {
  const text = await response.text();
  let payload: ApiErrorPayload = {};
  if (text) {
    try {
      const parsed = JSON.parse(text);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        payload = parsed as ApiErrorPayload;
      }
    } catch {
      payload = {};
    }
  }
  const detail = stringValue(payload.detail) || text || `${response.status} ${response.statusText}`;
  return new ApiError({
    status: response.status,
    code: stringValue(payload.code) || stringValue(payload.error_code) || `http_${response.status}`,
    title: stringValue(payload.title) || response.statusText || "API request failed",
    detail,
    suggestion: stringValue(payload.suggestion),
    trace_id: stringValue(payload.trace_id) || response.headers.get("X-Request-ID") || undefined,
  });
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

export async function requestCurrentUser(): Promise<UserAccount> {
  const response = await fetch("/api/auth/me", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error("not authenticated");
  }
  return response.json();
}
