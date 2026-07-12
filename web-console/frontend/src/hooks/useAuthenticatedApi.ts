import { useCallback, useEffect, useRef } from "react";
import { ApiError, downloadFromApi, requestApi } from "../api/client";
import type { ApiCall, AuthHeaders, AuthSession, DownloadApi } from "../types";

export function useAuthenticatedApi(session: AuthSession | null, onUnauthorized?: () => void) {
  const unauthorizedHandled = useRef(false);
  const sessionIdentity = session
    ? String(session.access_token || session.accessToken || session.user?.user_id || "cookie-session")
    : "";

  useEffect(() => {
    if (sessionIdentity) unauthorizedHandled.current = false;
  }, [sessionIdentity]);

  const handleError = useCallback((error: unknown, enabled = true) => {
    if (
      enabled
      && session
      && error instanceof ApiError
      && error.status === 401
      && !unauthorizedHandled.current
    ) {
      unauthorizedHandled.current = true;
      onUnauthorized?.();
    }
    throw error;
  }, [onUnauthorized, session]);

  const authHeaders = useCallback((): AuthHeaders => {
    const token = session?.access_token || session?.accessToken;
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, [session]);

  const callApi = useCallback<ApiCall>(
    async (path, options = {}) => {
      try {
        return await requestApi(path, options, authHeaders());
      } catch (error) {
        return handleError(error, options.handleUnauthorized !== false);
      }
    },
    [authHeaders, handleError],
  );

  const downloadApi = useCallback<DownloadApi>(
    async (path: string, filename: string) => {
      try {
        await downloadFromApi(path, filename, authHeaders());
      } catch (error) {
        handleError(error);
      }
    },
    [authHeaders, handleError],
  );

  return { callApi, downloadApi };
}
