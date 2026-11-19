const e = encodeURIComponent;

export const routes = {
  explorer: (sourceId?: string | null, database?: string | null) =>
    sourceId ? (database ? `/explorer/${e(sourceId)}/${e(database)}` : `/explorer/${e(sourceId)}`) : '/explorer',
  table: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}`,
  recommendations: (sourceId: string, database: string, table: string) => `/recommendations/${e(sourceId)}/${e(database)}/${e(table)}`,
  newTrial: (sourceId: string, database: string, table: string) => `/trials/new?source=${e(sourceId)}&database=${e(database)}&table=${e(table)}`,
  trial: (id: string) => `/trials/${e(id)}`,
};
