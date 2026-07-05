// @ts-nocheck

import { useCallback } from "react";
import { downloadFromApi, requestApi } from "../api/client";

export function useAuthenticatedApi(session) {
  const authHeaders = useCallback(() => {
    const token = session?.access_token || session?.accessToken;
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, [session]);

  const callApi = useCallback(
    (path, options = {}) => requestApi(path, options, authHeaders()),
    [authHeaders],
  );

  const downloadApi = useCallback(
    (path, filename) => downloadFromApi(path, filename, authHeaders()),
    [authHeaders],
  );

  return { callApi, downloadApi };
}
