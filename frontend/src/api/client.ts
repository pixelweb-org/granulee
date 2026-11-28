import type {
  ApplyPlan,
  ConnectionTest,
  DatabaseDetail,
  DatabaseList,
  Me,
  Opportunity,
  Overview,
  QueryExplain,
  Source,
  SourceInput,
  TableDetail,
  TableIndexUsage,
  TableRecommendations,
  TableUsage,
  TableWhatIf,
  Trial,
  TrialIndex,
  TrialList,
  TrialPlan,
  TrialRequest,
  User,
} from './types';

let onSignedOut: () => void = () => {};

/** Called when the server no longer knows the session: it ran out, or the account was deactivated. */
export function whenSignedOut(listener: () => void): void {
  onSignedOut = listener;
}

/** The CSRF token the server hands out as a cookie; every request that changes something sends it back. */
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const token = method === 'GET' ? null : csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
    res = await fetch(`/api${path}`, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new Error('Cannot reach the Granulee server.');
  }
  // A wrong password at the sign-in is a 401 too, and is that form's own business.
  if (res.status === 401 && path !== '/auth/login') onSignedOut();
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data: unknown = await res.json();
      if (data && typeof data === 'object' && 'message' in data && typeof (data as { message: unknown }).message === 'string') {
        message = (data as { message: string }).message;
      }
    } catch {
      /* no JSON body */
    }
    throw new Error(message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const e = encodeURIComponent;
const qs = (params: Record<string, string | null | undefined>) => {
  const parts = Object.entries(params).filter(([, v]) => v != null && v !== '').map(([k, v]) => `${k}=${e(v as string)}`);
  return parts.length ? `?${parts.join('&')}` : '';
};
const tablePath = (sourceId: string, database: string, table: string) =>
  `/sources/${e(sourceId)}/databases/${e(database)}/tables/${e(table)}`;

export const api = {
  /** The signed-in account, or null when nobody is. */
  me: async (): Promise<Me | null> => {
    const res = await fetch('/api/auth/me', { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).catch(() => null);
    if (!res) throw new Error('Cannot reach the Granulee server.');
    if (res.status === 401) return null;
    if (!res.ok) throw new Error(`Request failed (${res.status})`);
    return (await res.json()) as Me;
  },
  signIn: (username: string, password: string) => request<Me>('POST', '/auth/login', { username, password }),
  signOut: () => request<void>('POST', '/auth/logout'),
  changePassword: (current: string, password: string) => request<Me>('POST', '/auth/password', { current, password }),

  users: () => request<User[]>('GET', '/users'),
  createUser: (username: string, password: string, role: string) => request<User>('POST', '/users', { username, password, role }),
  activateUser: (id: string) => request<User>('POST', `/users/${e(id)}/activate`),
  deactivateUser: (id: string) => request<User>('POST', `/users/${e(id)}/deactivate`),
  resetPassword: (id: string, password: string) => request<User>('POST', `/users/${e(id)}/password`, { password }),

  sources: () => request<Source[]>('GET', '/sources'),
  createSource: (input: SourceInput) => request<Source>('POST', '/sources', input),
  updateSource: (id: string, input: SourceInput) => request<Source>('PUT', `/sources/${e(id)}`, input),
  deleteSource: (id: string) => request<void>('DELETE', `/sources/${e(id)}`),
  testSource: (input: SourceInput & { id?: string }) => request<ConnectionTest>('POST', '/sources/test', input),
  scanSource: (id: string) => request<Source>('POST', `/sources/${e(id)}/scan`),
  scanAll: () => request<Source[]>('POST', '/scan'),

  overview: (source?: string | null) => request<Overview>('GET', `/overview${qs({ source })}`),
  opportunities: (source?: string | null) => request<Opportunity[]>('GET', `/opportunities${qs({ source })}`),

  databases: (sourceId: string) => request<DatabaseList>('GET', `/sources/${e(sourceId)}/databases`),
  database: (sourceId: string, database: string) =>
    request<DatabaseDetail>('GET', `/sources/${e(sourceId)}/databases/${e(database)}`),
  table: (sourceId: string, database: string, table: string) =>
    request<TableDetail>('GET', tablePath(sourceId, database, table)),
  recommendations: (sourceId: string, database: string, table: string) =>
    request<TableRecommendations>('GET', `${tablePath(sourceId, database, table)}/recommendations`),
  dismissRecommendation: (sourceId: string, database: string, table: string, id: string) =>
    request<void>('POST', `${tablePath(sourceId, database, table)}/recommendations/dismiss`, { id }),
  restoreDismissed: (sourceId: string, database: string, table: string) =>
    request<void>('DELETE', `${tablePath(sourceId, database, table)}/recommendations/dismissed`),

  tableUsage: (source: string, database: string, table: string) =>
    request<TableUsage>('GET', `/sources/${e(source)}/databases/${e(database)}/tables/${e(table)}/usage`),
  /** Plans the latest run of one logged query shape with EXPLAIN. Nothing is executed. */
  queryPlan: (source: string, database: string, table: string, hash: string) =>
    request<QueryExplain>('GET', `${tablePath(source, database, table)}/query-plan${qs({ hash })}`),
  indexUsage: (source: string, database: string, table: string) =>
    request<TableIndexUsage>('GET', `/sources/${e(source)}/databases/${e(database)}/tables/${e(table)}/index-usage`),
  indexWhatIf: (source: string, database: string, table: string, candidates: TrialIndex[]) =>
    request<TableWhatIf>('POST', `/sources/${e(source)}/databases/${e(database)}/tables/${e(table)}/index-whatif`, { candidates }),
  trials: (source?: string | null) => request<TrialList>('GET', `/trials${qs({ source })}`),
  trialPlan: (source: string, database: string, table: string) =>
    request<TrialPlan>('GET', `/trials/plan${qs({ source, database, table })}`),
  trial: (id: string) => request<Trial>('GET', `/trials/${e(id)}`),
  startTrial: (req: TrialRequest) => request<Trial>('POST', '/trials', req),
  applyPlan: (id: string) => request<ApplyPlan>('GET', `/trials/${e(id)}/apply`),
  applyTrial: (id: string, sql: string) => request<Trial>('POST', `/trials/${e(id)}/apply`, { sql }),
  optimizeTrial: (id: string) => request<Trial>('POST', `/trials/${e(id)}/optimize`),
  cancelTrial: (id: string) => request<Trial>('POST', `/trials/${e(id)}/cancel`),
  deleteTrial: (id: string) => request<void>('DELETE', `/trials/${e(id)}`),
};
