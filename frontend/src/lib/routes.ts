const e = encodeURIComponent;

export const routes = {
  explorer: (sourceId?: string | null, database?: string | null) =>
    sourceId ? (database ? `/explorer/${e(sourceId)}/${e(database)}` : `/explorer/${e(sourceId)}`) : '/explorer',
  table: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}`,
  indexes: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}/indexes`,
  usage: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}/usage`,
  recommendations: (sourceId: string, database: string, table: string) => `/recommendations/${e(sourceId)}/${e(database)}/${e(table)}`,
  newTrial: (sourceId: string, database: string, table: string) => `/trials/new?source=${e(sourceId)}&database=${e(database)}&table=${e(table)}`,
  // A finished trial is read step by step; without a step the address is its overview.
  trial: (id: string, step?: 'changes' | 'size' | 'codecs' | 'speed' | 'apply') => (step ? `/trials/${e(id)}/${step}` : `/trials/${e(id)}`),
};
