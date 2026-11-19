import type {
  ConnectionTest,
  DatabaseDetail,
  DatabaseList,
  Opportunity,
  Overview,
  Source,
  SourceInput,
  TableDetail,
  TableRecommendations,
  ApplyPlan,
  Trial,
  TrialList,
  TrialPlan,
  TrialRequest,
} from './types';

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method,
      headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error('Cannot reach the Granulee server.');
  }
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

  trials: (source?: string | null) => request<TrialList>('GET', `/trials${qs({ source })}`),
  trialPlan: (source: string, database: string, table: string) =>
    request<TrialPlan>('GET', `/trials/plan${qs({ source, database, table })}`),
  trial: (id: string) => request<Trial>('GET', `/trials/${e(id)}`),
  startTrial: (req: TrialRequest) => request<Trial>('POST', '/trials', req),
  applyPlan: (id: string) => request<ApplyPlan>('GET', `/trials/${e(id)}/apply`),
  applyTrial: (id: string, sql: string) => request<Trial>('POST', `/trials/${e(id)}/apply`, { sql }),
  cancelTrial: (id: string) => request<Trial>('POST', `/trials/${e(id)}/cancel`),
  deleteTrial: (id: string) => request<void>('DELETE', `/trials/${e(id)}`),
};
