import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { Source } from '../api/types';

/** The selection is remembered per user: one browser, several accounts. */
const key = (userId: string) => `granulee.source.${userId}`;

interface SourcesState {
  sources: Source[];
  loading: boolean;
  error: string | null;
  /** null = "All sources". */
  selectedId: string | null;
  selected: Source | null;
  select: (id: string | null) => void;
  reload: () => Promise<void>;
}

const Ctx = createContext<SourcesState | null>(null);

function stored(userId: string): string | null {
  try {
    return localStorage.getItem(key(userId)) || null;
  } catch {
    return null;
  }
}

export function SourcesProvider({ userId, children }: { userId: string; children: ReactNode }) {
  const [sources, setSources] = useState<Source[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(() => stored(userId));

  const reload = useCallback(async () => {
    try {
      const list = await api.sources();
      setSources(list);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const select = useCallback((id: string | null) => {
    setSelectedId(id);
    try {
      if (id) localStorage.setItem(key(userId), id);
      else localStorage.removeItem(key(userId));
    } catch {
      /* ignore */
    }
  }, [userId]);

  const value = useMemo<SourcesState>(() => {
    const selected = sources.find((s) => s.id === selectedId) ?? null;
    return {
      sources,
      loading,
      error,
      // A stored id that no longer exists falls back to "All sources" once the list is known.
      selectedId: loading || selected ? selectedId : null,
      selected,
      select,
      reload,
    };
  }, [sources, loading, error, selectedId, select, reload]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSources(): SourcesState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSources must be used inside SourcesProvider');
  return v;
}
