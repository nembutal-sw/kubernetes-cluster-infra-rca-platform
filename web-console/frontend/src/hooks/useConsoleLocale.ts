import { useCallback, useEffect, useState } from "react";

import { KO, STORAGE_KEYS } from "../constants";
import type { Locale } from "../constants";
import type { TFunction } from "../types";

export function useConsoleLocale() {
  const [locale, setLocale] = useState<Locale>(() => {
    const saved = localStorage.getItem(STORAGE_KEYS.locale);
    return saved === "ko" ? "ko" : "en";
  });

  const t = useCallback<TFunction>((key) => (locale === "ko" ? KO[key] || key : key), [locale]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.locale, locale);
    document.documentElement.lang = locale === "ko" ? "ko" : "en";
  }, [locale]);

  return { locale, setLocale, t };
}
