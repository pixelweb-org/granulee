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
  /** Whether the last scan could use system.query_log; null before the first scan. */
  queryLog: { state: 'ok' | 'missing' | 'denied' | 'empty' | 'error'; message: string | null } | null;
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
  files: number | null; // estimated files on disk; null when the server does not report substreams
}

/** Files on disk, estimated: two per column substream in a wide part, a handful per part. */
export interface FileFacts {
  files: number;
  largestPartFiles: number;
  widestColumn: string;
  widestColumnFiles: number; // per wide part
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

/** One projection of a table. Sizes are sums over the active parts that carry it. */
export interface Projection {
  name: string;
  type: string; // Normal | Aggregate; "" where the server has no system.projections
  sortingKey: string;
  query: string;
  parts: number; // active parts in which it is built
  rows: number;
  bytesOnDisk: number;
  compressedBytes: number;
  uncompressedBytes: number;
  selects: number | null; // logged SELECTs that read it; null = query log not readable
  lastUsed: string | null;
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
  files: FileFacts | null;
  buffers: string[]; // Buffer tables in front, as database.table
  projections: Projection[];
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
  compressedBytes: number;
}

/** A skip index the candidate gets on top of the production ones. */
export interface TrialIndex {
  name: string;
  expression: string;
  type: string; // minmax, set(100), bloom_filter(0.01), …
  granularity: number;
}

export interface TrialPlanRecommendation {
  id: string;
  label: string;
  partitionKey: string | null;
  sortingKey: string | null;
  column: { name: string; type: string | null; codec: string | null } | null;
  dropIndex: string | null;
  addIndex: TrialIndex | null;
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
  indexDetails: { name: string; expression: string; type: string; compressedBytes: number }[];
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
  addIndexes: TrialIndex[];
  rows: number;
  sampling: Sampling;
  where: string | null;
  replayQueries: boolean;
  measureInserts: boolean;
  optimizeFinal: boolean;
  columnScans: boolean;
  runsPerQuery: number;
  codecBakeoff?: boolean; // try several codecs on the largest columns
  plainDefaults?: boolean; // also build the table with no skip indexes and the server's default codec
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
  plain?: number | null; // the same measure on the table with plain defaults
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
  plainBytes?: number | null; // with the server's default codec; only where the trial built a table with plain defaults
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
  plainMs?: number | null; // the same query on the table with plain defaults, where the trial built one
  plainRuns?: number[] | null;
  plainReadRows?: number | null;
  plainExplain?: QueryExplain | null;
  baseRuns?: number[] | null; // the runs baseMs is the median of, in ms; absent in trials run before 0.14
  candRuns?: number[] | null;
  baseCpuMs?: number | null; // median CPU time of those runs over all threads, from query_log
  candCpuMs?: number | null;
  baseAnalyze?: QueryAnalyze | null; // one measured run, from EXPLAIN ANALYZE; absent in trials run before 0.13
  candAnalyze?: QueryAnalyze | null;
  baseExplain: QueryExplain | null; // how the indexes narrowed the read, from EXPLAIN
  candExplain: QueryExplain | null;
}

export interface IndexStep {
  kind: 'minmax' | 'partition' | 'statistics' | 'primary' | 'skip';
  name: string; // the skip index's name, otherwise the key expressions
  condition: string | null;
  partsBefore: number;
  partsAfter: number;
  granulesBefore: number;
  granulesAfter: number;
  /** Primary key only: "binary search", or "generic exclusion search" when the filter skips the first key column. */
  searchAlgorithm: string | null;
}

export interface QueryExplain {
  steps: IndexStep[];
  granulesTotal: number | null;
  granulesRead: number | null;
  partsRead: number | null;
  streams: number | null; // parallel read streams
  pipeline: string | null; // EXPLAIN PIPELINE text
  error: string | null;
  projection: string | null; // the projection the read is answered from; then the steps are its own indexes
  readType: string | null; // Default | InOrder | InReverseOrder
  prewhere: string | null; // the conditions ClickHouse evaluates first
}

/** What the primary key or the partition key did for the logged queries that read the table itself. */
export interface KeyUsage {
  kind: 'primary' | 'partition';
  expression: string;
  shapes: number;
  share: number; // 0..1 of the table's SELECTs
  binaryShare: number | null; // primary key: narrowed by binary search
  genericShare: number | null; // primary key: narrowed by generic exclusion search
  granulesBefore: number;
  granulesAfter: number;
  queries: number[];
}

/** One step of a measured run: what went in, what came out, and how much of the run it took. */
export interface AnalyzeStep {
  name: string;
  depth: number; // 0 hands out the result; the reads are deepest
  rowsIn: number | null;
  rowsOut: number | null;
  timeMs: number | null;
  timeShare: number | null; // of the execution time; steps run at the same time, so shares can exceed 1 in sum
  threads: number | null; // average threads at work in its busiest stage
  maxThreads: number | null;
}

/** What EXPLAIN ANALYZE measured for one query on one table. */
export interface QueryAnalyze {
  planningMs: number | null;
  executionMs: number | null;
  readRows: number | null;
  peakMemoryBytes: number | null;
  steps: AnalyzeStep[];
  text: string | null;
  error: string | null;
}

/** One logged query shape and how ClickHouse plans it on the production table now. */
export interface PlannedQuery {
  query: string;
  count: number; // runs in the log window
  share: number; // 0..1 of the table's SELECTs
  plan: QueryExplain;
}

/** What the skip indexes did for the logged queries, measured with EXPLAIN on the production table. */
export interface TableIndexUsage {
  checkedShapes: number;
  failedShapes: number;
  selects: number;
  message: string | null;
  indexes: {
    name: string;
    expression: string;
    type: string;
    compressedBytes: number;
    shapes: number;
    share: number; // 0..1 of the table's SELECTs
    granulesBefore: number;
    granulesAfter: number;
    queries: number[]; // the shapes that use it, as positions in `queries`
  }[];
  /** Per projection, the shapes planned onto it now or logged as answered from it. */
  projections: { name: string; queries: number[] }[];
  queries: PlannedQuery[]; // every planned shape, most frequent first
  keys: KeyUsage[];
  proposals: TrialIndex[]; // skip indexes the scan proposes for this table
  whatIf: boolean; // the server can estimate an index that does not exist yet
}

/** One logged query shape and what it reads today, from EXPLAIN WHATIF. */
export interface WhatIfShape {
  query: string;
  count: number;
  share: number; // 0..1 of the table's SELECTs
  parts: number | null;
  granules: number | null; // after the keys and the existing skip indexes
  error: string | null;
}

/** One candidate index in one query shape. */
export interface WhatIfUse {
  shape: number; // position in TableWhatIf.shapes
  applicable: boolean;
  reason: string | null;
  granulesBefore: number | null;
  granulesAfter: number | null;
  source: string | null; // empirical | statistical | applicability_only
  sampled: boolean;
}

export interface WhatIfCandidate extends TrialIndex {
  error: string | null; // the server refused the definition
  shapes: number; // shapes it would narrow
  share: number;
  granulesBefore: number;
  granulesAfter: number;
  uses: WhatIfUse[];
}

/** Skip indexes that do not exist yet, estimated with EXPLAIN WHATIF against the logged queries. */
export interface TableWhatIf {
  message: string | null; // set when nothing could be estimated
  checkedShapes: number;
  failedShapes: number;
  selects: number;
  shapes: WhatIfShape[];
  candidates: WhatIfCandidate[];
}

/** One codec tried on one column of the sample. */
export interface CodecVariant {
  codec: string; // "" = the server's default
  compressedBytes: number;
  uncompressedBytes: number;
  scanMs: number | null; // median full read; only timed for the variants in the running
}

/** The codecs a trial compared for one column, in the candidate's row order. */
export interface CodecColumn {
  name: string;
  type: string;
  production: string; // the codec in use today
  candidate: string; // the codec this trial's candidate uses
  best: string; // the pick
  variants: CodecVariant[]; // smallest first
  defaultIs?: string | null; // the codec the server's default turned out to be
}

export interface TrialResult {
  plainDdl?: string; // CREATE TABLE of the table with plain defaults; '' or absent when the trial built none
  codecs?: CodecColumn[]; // absent in trials run before 0.17
  engine?: string | null; // engine of the trial tables: production's, without replication; absent before 0.20, when they were plain MergeTree
  copiedRows?: number | null; // rows the sample copied into each table; `rows` is what the baseline holds after its merge
  candRows?: number | null; // rows the candidate holds after its merge
  rows: number; // rows actually copied
  totalRows: number; // rows in the production table
  metrics: TrialMetric[];
  columns: TrialColumnResult[]; // sorted by baseline size, descending
  queries: TrialQueryResult[];
  baseDdl: string;
  candDdl: string;
  alterDdl: string; // ALTER TABLE (or rebuild) statements for the production table; '' when nothing changes
  projectedBaseBytes: number;
  projectedCandBytes: number;
  verdict: string; // the server's own summary; the page words its verdict from the noise-aware figures instead
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
  apply: TrialApply | null; // the last time the candidate was applied to production
  optimize: TrialOptimize | null; // the last OPTIMIZE … FINAL of the production table started here
}

/** How a table was used in the source's query-log window, read from system.query_log. */
export interface TableUsage {
  sourceId: string;
  sourceName: string;
  database: string;
  table: string;
  windowDays: number;
  available: boolean; // false: query_log cannot be read
  message: string | null;
  logSince: string | null; // oldest log entry inside the window
  selects: number;
  inserts: number;
  otherQueries: number;
  readRows: number;
  readBytes: number;
  writtenRows: number;
  writtenBytes: number;
  selectMedianMs: number;
  selectP95Ms: number;
  tableRows: number;
  wideScans: number; // SELECTs that read at least half of the table's rows
  days: { day: string; selects: number; inserts: number }[];
  queries: UsageQuery[]; // up to 40 SELECT shapes, most frequent first
  columns: UsageColumn[]; // most read first
  unreadColumns: string[];
  unreadBytes: number;
  users: { user: string; selects: number; inserts: number }[];
  via: string[]; // Buffer tables in front whose queries are counted in
}

export interface UsageQuery {
  query: string; // normalized
  count: number;
  share: number; // 0..1 of the table's SELECTs
  medianMs: number;
  p95Ms: number;
  totalMs: number;
  avgReadRows: number;
  avgReadBytes: number;
  avgResultRows: number;
  lastSeen: string;
}

export interface UsageColumn {
  name: string;
  type: string;
  compressedBytes: number;
  inSortingKey: boolean;
  readBy: number;
  readShare: number; // 0..1 of SELECTs
  filterShare: number; // 0..1 of the listed query shapes, by count
}

/** What "Apply to production" would run right now, computed against the table as it is. */
export interface ApplyPlan {
  kind: 'none' | 'alter' | 'rebuild';
  sql: string;
  statements: number; // statements Granulee would run; 0 for a rebuild
  sourceName: string;
}

export interface TrialOptimize {
  status: 'running' | 'completed' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  error: string | null;
  sql: string;
}

export interface TrialApply {
  status: 'running' | 'completed' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  error: string | null;
  sql: string;
  done: number;
  total: number;
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
