import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, whenSignedOut } from '../api/client';
import type { Me } from '../api/types';

interface AuthState {
  /** null = nobody is signed in. */
  me: Me | null;
  /** The first look at the session has not come back yet. */
  loading: boolean;
  /** The server could not be asked who is signed in. */
  error: string | null;
  retry: () => void;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  changePassword: (current: string, password: string) => Promise<void>;
}

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setMe(await api.me());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Any request that finds the session gone brings the sign-in back.
    whenSignedOut(() => setMe(null));
    void load();
  }, [load]);

  const value = useMemo<AuthState>(
    () => ({
      me,
      loading,
      error,
      retry: () => void load(),
      signIn: async (username, password) => setMe(await api.signIn(username, password)),
      signOut: async () => {
        try {
          await api.signOut();
        } finally {
          setMe(null);
        }
      },
      changePassword: async (current, password) => setMe(await api.changePassword(current, password)),
    }),
    [me, loading, error, load],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth must be used inside AuthProvider');
  return v;
}
