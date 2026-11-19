// API contract between the Spring Boot backend (/api/**) and the React frontend.
// All sizes are bytes, all times ISO-8601 strings, all durations milliseconds unless named otherwise.
// Errors: non-2xx responses carry `{ "message": string }`.

export type Health = 'healthy' | 'unreachable' | 'unknown';
export type Protocol = 'https' | 'http';
export type Schedule = 'every6h' | 'daily' | 'weekly' | 'manual';

export interface SourceStatus {
  state: Health;
  message: string | null; // error text when unreachable
  version: string | null; // ClickHouse version
  replicas: number | null; // > 1 when the server is part of a replicated cluster
  checkedAt: string | null;
  lastSeenAt: string | null;
}

export interface Source {
  id: string;
  name: string;
  protocol: Protocol;
  host: string;
  port: number;
  username: string;
  hasPassword: boolean;
  includeDatabases: string; // comma-separated, wildcards (*), empty = all
  excludeDatabases: string;
  schedule: Schedule;
  queryLogWindowDays: number;
  trialsEnabled: boolean;
  scratchDatabase: string;
  status: SourceStatus;
  lastScan: { at: string; durationMs: number; error: string | null } | null;
  nextScanAt: string | null;
  tables: number | null; // from the last scan
  compressedBytes: number | null; // from the last scan
}

export interface SourceInput {
  name: string;
  protocol: Protocol;
  host: string;
  port: number;
  username: string;
  password?: string | null; // omitted / null / '' on update keeps the stored password
  includeDatabases: string;
  excludeDatabases: string;
  schedule: Schedule;
  queryLogWindowDays: number;
  trialsEnabled: boolean;
  scratchDatabase: string;
}

export interface ConnectionTest {
  ok: boolean;
  version: string | null;
  latencyMs: number | null;
  error: string | null;
}

export interface ChartData {
  categories: string[];
  series: { name: string; values: number[] }[];
}

export interface Opportunity {
  sourceId: string;
  sourceName: string;
  database: string;
  table: string;
  compressedBytes: number;
  uncompressedBytes: number;
  findings: number;
  savingsBytes: number;
}

export interface Overview {
  totals: {
    compressedBytes: number;
    uncompressedBytes: number;
    tables: number;
    findings: number;
    savingsBytes: number;
  };
  sources: Source[]; // the sources in scope
  growth: ChartData; // compressed bytes per source, one category per scan day (up to 12)
  findingsByCategory: ChartData; // one series
  opportunities: Opportunity[]; // top 5 by savings
  lastScanAt: string | null;
  nextScanAt: string | null;
}

export interface DatabaseSummary {
  name: string;
  tables: number;
  compressedBytes: number;
  uncompressedBytes: number;
}

export interface DatabaseList {
  sourceId: string;
  sourceName: string;
  databases: DatabaseSummary[]; // sorted by compressed size, descending
  scannedAt: string | null; // null = never scanned
}

export interface TableSummary {
  database: string;
  name: string;
  engine: string;
  rows: number;
  compressedBytes: number;
  uncompressedBytes: number;
  activeParts: number;
  findings: number;
}

export interface DatabaseDetail {
  sourceId: string;
  sourceName: string;
  database: DatabaseSummary;
  tables: TableSummary[]; // sorted by compressed size, descending
  scannedAt: string | null;
}

export interface ColumnInfo {
  name: string;
  type: string;
  codec: string; // display-ready, e.g. "ZSTD(1)" or "LZ4 (default)"
  compressedBytes: number;
  uncompressedBytes: number;
  finding: string | null; // short category label, e.g. "Codec", "Type", "Sorting key"
}

export interface SkipIndex {
  name: string;
  expression: string;
  type: string;
  granularity: number;
  compressedBytes: number;
  uncompressedBytes: number;
}

export interface TableDetail {
  sourceId: string;
  sourceName: string;
  database: string;
  name: string;
  engine: string;
  engineFull: string;
  partitionKey: string;
  sortingKey: string;
  primaryKey: string;
  ttl: string | null;
  storagePolicy: string;
  comment: string;
  rows: number;
  compressedBytes: number; // column data
  uncompressedBytes: number;
  bytesOnDisk: number; // incl. marks, primary key, skip indexes
  activeParts: number;
  partitions: number;
  primaryKeyBytesInMemory: number;
  primaryKeyBytesInMemoryAllocated: number;
  marks: number;
  marksBytes: number;
  skipIndexBytes: number;
  settings: { name: string; value: string }[];
  columns: ColumnInfo[]; // sorted by compressed size, descending
  partitionStats: { partition: string; compressedBytes: number; parts: number }[]; // last 14, oldest first
  indexes: SkipIndex[];
  createQuery: string;
  findings: number;
}

export type Severity = 'high' | 'medium' | 'low' | 'review';
export type ChangeKind = 'online' | 'mutation' | 'rebuild';
export type RecommendationCategory = 'Partitioning' | 'Sorting key' | 'Codec' | 'Data type' | 'Skip index';

export interface Recommendation {
  id: string; // stable per table, e.g. "codec:properties"
  severity: Severity;
  category: RecommendationCategory;
  changeKind: ChangeKind;
  title: string;
  body: string;
  effect: string; // one line of numbers, e.g. "68.0 GiB → est. 47.6 GiB · −20.4 GiB"
  sql: string;
  savingsBytes: number;
  column: string | null;
  queries: { query: string; count: number; share: number }[]; // evidence from query_log; empty when none
}

export interface TableRecommendations {
  sourceId: string;
  sourceName: string;
  database: string;
  table: string;
  compressedBytes: number;
  items: Recommendation[]; // not dismissed, most severe first
  dismissed: number;
  savingsBytes: number;
  counts: { online: number; mutation: number; rebuild: number };
  queryLogWindowDays: number;
  queryLogQueries: number | null; // SELECTs on this table found in query_log; null = query_log not readable
  latestTrial: Trial | null; // latest completed trial for this table
}

export interface TrialPlanColumn {
  name: string;
  baselineType: string;
  baselineCodec: string; // display-ready
  suggestedType: string;
  suggestedCodec: string; // "" = server default (LZ4)
  typeOptions: string[];
  codecOptions: string[]; // "" = server default
}

export interface TrialPlanRecommendation {
  id: string;
  label: string;
  partitionKey: string | null;
  sortingKey: string | null;
  column: { name: string; type: string | null; codec: string | null } | null;
  dropIndex: string | null;
}

export interface TrialPlan {
  sourceId: string;
  sourceName: string;
  database: string;
  table: string;
  trialsEnabled: boolean;
  scratchDatabase: string;
  scratchFreeBytes: number | null;
  totalRows: number;
  compressedBytes: number;
  recommendedRows: number;
  partitionKey: string; // baseline
  sortingKey: string; // baseline
  columns: TrialPlanColumn[];
  indexes: string[];
  recommendations: TrialPlanRecommendation[]; // ticking one applies its fields to the form
  replayableQueries: number; // distinct SELECTs found in query_log (max 20)
}

export type Sampling = 'even' | 'recent' | 'where';

export interface TrialRequest {
  sourceId: string;
  database: string;
  table: string;
  name: string;
  partitionKey: string;
  sortingKey: string;
  columns: { name: string; type: string; codec: string }[]; // every column; codec "" = server default
  dropIndexes: string[];
  rows: number;
  sampling: Sampling;
  where: string | null;
  replayQueries: boolean;
  measureInserts: boolean;
  optimizeFinal: boolean;
  columnScans: boolean;
  runsPerQuery: number;
}

export type TrialStatus = 'running' | 'completed' | 'failed' | 'cancelled';

export interface TrialStep {
  label: string;
  state: 'done' | 'running' | 'pending' | 'failed';
}

export type MetricUnit = 'bytes' | 'ratio' | 'ms' | 'rows' | 'count' | 'rowsPerSec';

export interface TrialMetric {
  key: string;
  label: string;
  baseline: number;
  candidate: number;
  unit: MetricUnit;
  lowerIsBetter: boolean;
}

export interface TrialColumnResult {
  name: string;
  baseline: string; // "String · LZ4"
  candidate: string | null; // null = unchanged
  baseBytes: number;
  candBytes: number;
  baseUncompressedBytes: number;
  candUncompressedBytes: number;
  baseScanMs: number | null;
  candScanMs: number | null;
}

export interface TrialQueryResult {
  label: string; // Q1…
  query: string; // normalized
  share: number; // 0..1 of the table's SELECT workload
  baseMs: number | null;
  candMs: number | null;
  baseReadRows: number | null;
  candReadRows: number | null;
  error: string | null;
}

export interface TrialResult {
  rows: number; // rows actually copied
  totalRows: number; // rows in the production table
  metrics: TrialMetric[];
  columns: TrialColumnResult[]; // sorted by baseline size, descending
  queries: TrialQueryResult[];
  baseDdl: string;
  candDdl: string;
  projectedBaseBytes: number;
  projectedCandBytes: number;
  verdict: string;
}

export interface Trial {
  id: string; // 4 hex chars
  name: string;
  sourceId: string;
  sourceName: string;
  database: string;
  table: string;
  status: TrialStatus;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  progress: number; // 0..100
  steps: TrialStep[];
  request: TrialRequest;
  scratchDatabase: string;
  baseTable: string; // table name inside the scratch database
  candTable: string;
  scratchBytes: number; // bytes both tables use right now
  changedKeys: number;
  changedColumns: number;
  result: TrialResult | null;
  log: { at: string; message: string }[];
}

export interface TrialList {
  trials: Trial[]; // newest first
  scratchUsedBytes: number;
  scratchFreeBytes: number | null;
}

/*
Endpoints

GET    /api/sources                                    -> Source[]
POST   /api/sources            SourceInput             -> Source
PUT    /api/sources/{id}       SourceInput             -> Source
DELETE /api/sources/{id}                               -> 204
POST   /api/sources/test       SourceInput & {id?}     -> ConnectionTest   (id: reuse the stored password when none is given)
POST   /api/sources/{id}/scan                          -> Source           (runs a full scan, returns when done)
POST   /api/scan                                       -> Source[]         (scans every source)

GET    /api/overview?source={id}                       -> Overview         (source omitted = all sources)
GET    /api/opportunities?source={id}                  -> Opportunity[]    (every table with findings, by savings)

GET    /api/sources/{id}/databases                     -> DatabaseList
GET    /api/sources/{id}/databases/{db}                -> DatabaseDetail
GET    /api/sources/{id}/databases/{db}/tables/{table} -> TableDetail
GET    /api/sources/{id}/databases/{db}/tables/{table}/recommendations -> TableRecommendations
POST   /api/sources/{id}/databases/{db}/tables/{table}/recommendations/dismiss  { id: string } -> 204
DELETE /api/sources/{id}/databases/{db}/tables/{table}/recommendations/dismissed -> 204 (restore all)

GET    /api/trials?source={id}                         -> TrialList
GET    /api/trials/plan?source={id}&database=&table=   -> TrialPlan
GET    /api/trials/{id}                                -> Trial
POST   /api/trials             TrialRequest            -> Trial            (starts in the background; poll GET)
POST   /api/trials/{id}/cancel                         -> Trial
DELETE /api/trials/{id}                                -> 204              (drops both scratch tables)
*/
