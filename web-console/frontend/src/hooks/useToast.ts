import { useCallback, useEffect, useRef, useState } from "react";

import type { ToastState } from "../types";

export function useToast() {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timerRef = useRef<number | null>(null);

  const notify = useCallback((message: string, tone = "success") => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
    setToast({ message, tone });
    timerRef.current = window.setTimeout(() => {
      setToast(null);
      timerRef.current = null;
    }, 3200);
  }, []);

  useEffect(() => () => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
  }, []);

  return { toast, notify };
}
