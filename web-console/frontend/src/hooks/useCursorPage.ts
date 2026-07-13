import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { apiErrorDetails } from "../api/client";
import type { ApiCall, ApiErrorDetails, CursorPageResponse } from "../types";

type PageFilterValue = string | number | undefined | null;

interface CursorNavigation {
  filterKey: string;
  cursor: string | null;
  history: Array<string | null>;
}

const EMPTY_PAGE: CursorPageResponse<never> = {
  items: [],
  has_more: false,
  total: 0,
  limit: 50,
};

export function useCursorPage<T>(
  callApi: ApiCall,
  path: string,
  filters: Record<string, PageFilterValue>,
  refreshToken?: string,
  limit = 50,
) {
  const filterKey = JSON.stringify(filters);
  const requestFilters = useMemo(
    () => JSON.parse(filterKey) as Record<string, PageFilterValue>,
    [filterKey],
  );
  const [navigation, setNavigation] = useState<CursorNavigation>({
    filterKey,
    cursor: null,
    history: [],
  });
  const [page, setPage] = useState<CursorPageResponse<T>>(EMPTY_PAGE as CursorPageResponse<T>);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiErrorDetails>();
  const requestSequence = useRef(0);

  const load = useCallback(async () => {
    const requestId = ++requestSequence.current;
    const parameters = new URLSearchParams({ limit: String(limit) });
    Object.entries(requestFilters).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value).trim()) {
        parameters.set(key, String(value).trim());
      }
    });
    if (navigation.cursor) parameters.set("cursor", navigation.cursor);
    setLoading(true);
    try {
      const response = await callApi<CursorPageResponse<T>>(`${path}?${parameters.toString()}`);
      if (requestId !== requestSequence.current) return;
      if (!response || !Array.isArray(response.items)) {
        throw new Error("Paged API response is invalid.");
      }
      setPage(response);
      setError(undefined);
    } catch (requestError) {
      if (requestId !== requestSequence.current) return;
      setError(apiErrorDetails(requestError, "Failed to load page."));
    } finally {
      if (requestId === requestSequence.current) setLoading(false);
    }
  }, [callApi, limit, navigation.cursor, path, requestFilters]);

  useEffect(() => {
    if (navigation.filterKey !== filterKey) {
      requestSequence.current += 1;
      setNavigation({ filterKey, cursor: null, history: [] });
      return;
    }
    void load();
  }, [filterKey, load, navigation.filterKey, refreshToken]);

  useEffect(() => () => {
    requestSequence.current += 1;
  }, []);

  const next = useCallback(() => {
    if (!page.has_more || !page.next_cursor) return;
    setNavigation((current) => ({
      ...current,
      cursor: page.next_cursor || null,
      history: [...current.history, current.cursor],
    }));
  }, [page.has_more, page.next_cursor]);

  const previous = useCallback(() => {
    setNavigation((current) => {
      if (!current.history.length) return current;
      const history = current.history.slice(0, -1);
      return {
        ...current,
        cursor: current.history[current.history.length - 1] || null,
        history,
      };
    });
  }, []);

  return {
    page,
    loading,
    error,
    pageNumber: navigation.history.length + 1,
    canPrevious: navigation.history.length > 0,
    canNext: page.has_more && Boolean(page.next_cursor),
    next,
    previous,
    refresh: load,
  };
}

export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [delayMs, value]);
  return debounced;
}
