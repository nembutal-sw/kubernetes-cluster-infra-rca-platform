import { useCallback } from "react";
import { downloadFromApi, requestApi } from "../api/client";
import type { ApiCall, AuthHeaders, AuthSession, DownloadApi } from "../types";

export function useAuthenticatedApi(session: AuthSession | null) {
  const authHeaders = useCallback((): AuthHeaders => {
    const token = session?.access_token || session?.accessToken;
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, [session]);

  const callApi = useCallback<ApiCall>(
    (path, options = {}) => requestApi(path, options, authHeaders()),
    [authHeaders],
  );

  const downloadApi = useCallback<DownloadApi>(
    (path: string, filename: string) => downloadFromApi(path, filename, authHeaders()),
    [authHeaders],
  );

  return { callApi, downloadApi };
}
