import { useCallback, useEffect, useRef, useState } from 'react';

export interface ApiState<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  /** Fetch again. `silent` keeps the current data on screen and does not flip `loading`. */
  reload: (silent?: boolean) => Promise<void>;
}

/**
 * Loads `fn()` on mount and whenever `deps` change. Pass `null` as fn result by
 * returning a resolved promise when there is nothing to load.
 */
export function useApi<T>(fn: () => Promise<T>, deps: readonly unknown[]): ApiState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const run = useCallback(async (silent?: boolean) => {
    const mine = ++seq.current;
    if (!silent) {
      setLoading(true);
      setError(null);
    }
    try {
      const result = await fnRef.current();
      if (mine !== seq.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (mine !== seq.current) return;
      if (!silent) setData(null);
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void run();
    return () => {
      seq.current++;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, error, loading, reload: run };
}

/** Calls `tick` every `ms` while `active` is true. */
export function usePolling(active: boolean, ms: number, tick: () => void): void {
  const ref = useRef(tick);
  ref.current = tick;
  useEffect(() => {
    if (!active) return;
    const id = window.setInterval(() => ref.current(), ms);
    return () => window.clearInterval(id);
  }, [active, ms]);
}

/** Runs an action with busy + error state, for buttons. */
export function useAction(): {
  busy: boolean;
  error: string | null;
  run: (fn: () => Promise<unknown>) => Promise<boolean>;
  clearError: () => void;
} {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, error, run, clearError: useCallback(() => setError(null), []) };
}
