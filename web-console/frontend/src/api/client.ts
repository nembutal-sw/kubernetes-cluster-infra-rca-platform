import type { ApiRequestOptions, AuthHeaders, UserAccount } from "../types";

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
    const text = await response.text();
    throw new Error(text || `${response.status} ${response.statusText}`);
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
    throw new Error(await response.text());
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
