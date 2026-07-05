import { useCallback } from "react";
import { downloadFromApi, requestApi } from "../api/client";
import type { ApiRequestOptions, AuthHeaders, AuthSession } from "../types";

export function useAuthenticatedApi(session: AuthSession | null) {
  const authHeaders = useCallback((): AuthHeaders => {
    const token = session?.access_token || session?.accessToken;
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, [session]);

  const callApi = useCallback(
    <T = unknown>(path: string, options: ApiRequestOptions = {}) => requestApi<T>(path, options, authHeaders()),
    [authHeaders],
  );

  const downloadApi = useCallback(
    (path: string, filename: string) => downloadFromApi(path, filename, authHeaders()),
    [authHeaders],
  );

  return { callApi, downloadApi };
}
