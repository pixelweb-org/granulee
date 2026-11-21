const e = encodeURIComponent;

export const routes = {
  explorer: (sourceId?: string | null, database?: string | null) =>
    sourceId ? (database ? `/explorer/${e(sourceId)}/${e(database)}` : `/explorer/${e(sourceId)}`) : '/explorer',
  table: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}`,
  indexes: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}/indexes`,
  usage: (sourceId: string, database: string, table: string) => `/tables/${e(sourceId)}/${e(database)}/${e(table)}/usage`,
  recommendations: (sourceId: string, database: string, table: string) => `/recommendations/${e(sourceId)}/${e(database)}/${e(table)}`,
  newTrial: (sourceId: string, database: string, table: string) => `/trials/new?source=${e(sourceId)}&database=${e(database)}&table=${e(table)}`,
  trial: (id: string, tab?: 'columns' | 'queries' | 'schema') => (tab ? `/trials/${e(id)}/${tab}` : `/trials/${e(id)}`),
};
