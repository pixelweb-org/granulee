import type { Trial, TrialColumnResult, TrialMetric, TrialQueryResult, TrialResult } from '../api/types';
import { fmtBytes, fmtBytesPair, fmtChange, fmtClock, fmtInt, fmtMs, fmtMsPair, fmtPct, fmtRatioValue, fmtRows, fmtSignedBytes, fmtSpeed, median } from './format';

/** The steps a finished trial is read in, in order. The key is the last part of the page's address. */
export const STEPS = [
  { key: 'overview', label: 'Overview' },
  { key: 'changes', label: 'What changed' },
  { key: 'size', label: 'Size' },
  { key: 'codecs', label: 'Codecs' },
  { key: 'speed', label: 'Speed' },
  { key: 'apply', label: 'Apply' },
] as const;
export type StepKey = (typeof STEPS)[number]['key'];

// Addresses of the report before it was guided keep working.
const OLD_TABS: Record<string, StepKey> = { summary: 'overview', columns: 'size', queries: 'speed', schema: 'apply' };

export function stepOf(tab: string | undefined): StepKey {
  if (!tab) return 'overview';
  return STEPS.find((s) => s.key === tab)?.key ?? OLD_TABS[tab] ?? 'overview';
}

/** What the candidate is held against: today's table, or the same rows with no tuning at all. */
export type Compare = 'today' | 'defaults';

export const hasPlain = (r: TrialResult) => !!r.plainDdl && r.metrics.some((m) => m.plain != null);
export const refName = (cmp: Compare) => (cmp === 'defaults' ? 'plain defaults' : "today's table");
export const refLabel = (cmp: Compare) => (cmp === 'defaults' ? 'Plain defaults' : 'Today');

/** "ORDER BY (…)" or "PARTITION BY …" of a trial table's CREATE statement. */
export function clauseOf(ddl: string, keyword: 'ORDER BY' | 'PARTITION BY'): string {
  const line = ddl.split('\n').find((l) => l.startsWith(`${keyword} `));
  return line ? line.slice(keyword.length + 1).trim() : '';
}

const plural = (n: number, one: string, many = `${one}s`) => `${fmtInt(n)} ${n === 1 ? one : many}`;

// ----- Run-to-run noise -----

/** A timing counts as better or worse only outside `band`; `measured` is what the replayed queries themselves showed. */
export interface Noise {
  band: number;
  measured: number | null;
}

// Inserts and merges are timed once, so a few percent between them say nothing, however steady the queries ran.
const NOISE_FLOOR = 0.1;

/**
 * How far a timing on this server moves from one run to the next without anything having changed: half the span
 * between the fastest and the slowest run of a query, relative to its median, taken over all replayed queries.
 */
export function noiseOf(r: TrialResult): Noise {
  const spreads: number[] = [];
  for (const q of r.queries) {
    for (const runs of [q.baseRuns, q.candRuns]) {
      if (!runs || runs.length < 2) continue;
      const mid = median(runs);
      if (mid && mid > 0) spreads.push((Math.max(...runs) - Math.min(...runs)) / 2 / mid);
    }
  }
  const mid = median(spreads);
  if (mid == null) return { band: 0.15, measured: null };
  return { band: Math.min(0.5, Math.max(NOISE_FLOOR, mid)), measured: mid };
}

// ----- Queries -----

/** What the timed runs say about one query: which table is faster, or that they do not say. */
export interface QueryVerdict {
  kind: 'faster' | 'slower' | 'same' | 'unclear' | 'none';
  text: string;
  /** Reference median over candidate median: above 1 the candidate is faster. */
  speedup: number | null;
  refMs: number | null;
  refRuns: number[];
}

export function queryVerdict(q: TrialQueryResult, cmp: Compare = 'today'): QueryVerdict {
  const refMs = (cmp === 'defaults' ? q.plainMs : q.baseMs) ?? null;
  const refRuns = (cmp === 'defaults' ? q.plainRuns : q.baseRuns) ?? [];
  if (q.error || refMs == null || q.candMs == null || !(refMs > 0) || !(q.candMs > 0)) {
    return { kind: 'none', text: q.error ? 'not run' : 'not measured', speedup: null, refMs, refRuns };
  }
  const speed = fmtSpeed(refMs, q.candMs);
  const speedup = refMs / q.candMs;
  const c = q.candRuns ?? [];
  // The medians only tell the tables apart when no run of one took as long as a run of the other.
  const overlap = refRuns.length > 0 && c.length > 0 && Math.max(Math.min(...refRuns), Math.min(...c)) <= Math.min(Math.max(...refRuns), Math.max(...c));
  const base = { speedup, refMs, refRuns };
  if (speed.same) return { kind: 'same', text: 'same', ...base };
  if (overlap) return { kind: 'unclear', text: 'no clear difference', ...base };
  if (speed.slower) return q.candMs > refMs * 1.05 ? { kind: 'slower', text: speed.text, ...base } : { kind: 'same', text: speed.text, ...base };
  return { kind: 'faster', text: speed.text, ...base };
}

export const verdictColor = (kind: QueryVerdict['kind']) => (kind === 'faster' ? 'var(--verde)' : kind === 'slower' ? 'var(--erro)' : 'var(--chart-other)');

// ----- Measures -----

export function metricOne(unit: TrialMetric['unit'], v: number): string {
  switch (unit) {
    case 'bytes':
      return fmtBytes(v);
    case 'ms':
      return fmtMs(v);
    case 'ratio':
      return fmtRatioValue(v);
    case 'rows':
      return fmtRows(v);
    case 'rowsPerSec':
      return `${fmtRows(v)} rows/s`;
    default:
      return fmtInt(v);
  }
}

export function metricPair(unit: TrialMetric['unit'], a: number, b: number): string {
  switch (unit) {
    case 'bytes':
      return fmtBytesPair(a, b);
    case 'ms':
      return fmtMsPair(a, b);
    case 'rowsPerSec':
      return `${fmtRows(a)} → ${fmtRows(b)} rows/s`;
    default:
      return `${metricOne(unit, a)} → ${metricOne(unit, b)}`;
  }
}

export type Outcome = 'better' | 'worse' | 'same';

export interface Measure {
  metric: TrialMetric;
  ref: number;
  outcome: Outcome;
  /** Candidate against the reference as a fraction; null where the reference is zero. */
  change: number | null;
  pair: string;
}

/** A measure of the candidate against the chosen reference; null where the reference was not measured. */
export function measure(m: TrialMetric, cmp: Compare, noise: number): Measure | null {
  const ref = cmp === 'defaults' ? m.plain : m.baseline;
  if (ref == null) return null;
  const pair = metricPair(m.unit, ref, m.candidate);
  if (!(ref > 0)) {
    const outcome: Outcome = m.candidate > 0 ? (m.lowerIsBetter ? 'worse' : 'better') : 'same';
    return { metric: m, ref, outcome, change: null, pair };
  }
  const change = (m.candidate - ref) / ref;
  const timing = m.unit === 'ms' || m.unit === 'rowsPerSec';
  // Timings move by themselves; sizes and counts do not, but a few percent of a small number is not a finding.
  const threshold = timing ? noise : m.key === 'size' ? 0.01 : 0.05;
  const outcome: Outcome = Math.abs(change) < threshold ? 'same' : (m.lowerIsBetter ? change < 0 : change > 0) ? 'better' : 'worse';
  return { metric: m, ref, outcome, change, pair };
}

// ----- What the candidate changes -----

export interface Change {
  kind: 'key' | 'column' | 'index';
  name: string;
  from: string;
  to: string;
  column?: TrialColumnResult;
}

export function changesOf(trial: Trial, r: TrialResult): Change[] {
  const order = [clauseOf(r.baseDdl, 'ORDER BY'), clauseOf(r.candDdl, 'ORDER BY')];
  const partition = [clauseOf(r.baseDdl, 'PARTITION BY'), clauseOf(r.candDdl, 'PARTITION BY')];
  const out: Change[] = [];
  if (order[0] !== order[1]) out.push({ kind: 'key', name: 'Sorting key', from: order[0] || 'none', to: order[1] || 'none' });
  if (partition[0] !== partition[1]) out.push({ kind: 'key', name: 'Partition key', from: partition[0] || 'none', to: partition[1] || 'none' });
  r.columns.filter((c) => c.candidate != null).forEach((c) => out.push({ kind: 'column', name: c.name, from: c.baseline, to: c.candidate!, column: c }));
  (trial.request.addIndexes ?? []).forEach((i) => out.push({ kind: 'index', name: `Skip index ${i.name}`, from: 'none', to: `${i.type} on ${i.expression}, granularity ${i.granularity}` }));
  (trial.request.dropIndexes ?? []).forEach((name) => out.push({ kind: 'index', name: `Skip index ${name}`, from: 'exists', to: 'dropped' }));
  return out;
}

/** "34 columns, the partition key, the sorting key and all 9 skip indexes are the same in both tables." */
export function unchangedSentence(trial: Trial, r: TrialResult): string {
  const same = r.columns.filter((c) => c.candidate == null).length;
  const indexes = r.baseDdl.split('\n').filter((l) => /^\s*INDEX\s/.test(l)).length;
  const touchedIndexes = (trial.request.addIndexes?.length ?? 0) + (trial.request.dropIndexes?.length ?? 0) > 0;
  const parts = [
    same ? (same === r.columns.length ? `All ${plural(same, 'column')}` : plural(same, 'column')) : null,
    clauseOf(r.baseDdl, 'PARTITION BY') === clauseOf(r.candDdl, 'PARTITION BY') ? 'the partition key' : null,
    clauseOf(r.baseDdl, 'ORDER BY') === clauseOf(r.candDdl, 'ORDER BY') ? 'the sorting key' : null,
    !touchedIndexes && indexes ? (indexes === 1 ? 'the skip index' : `all ${fmtInt(indexes)} skip indexes`) : null,
  ].filter(Boolean) as string[];
  if (!parts.length) return '';
  const list = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
  return `${list} ${parts.length === 1 && same === 1 ? 'is' : 'are'} the same in both tables.`;
}

/** The changed columns a query names. A name can also stand for an alias, so this says "mentions", not "reads". */
export function mentioned(q: TrialQueryResult, r: TrialResult): string[] {
  return r.columns
    .filter((c) => c.candidate != null)
    .map((c) => c.name)
    .filter((name) => new RegExp(`(^|[^A-Za-z0-9_])${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^A-Za-z0-9_]|$)`).test(q.query));
}

// ----- Codecs -----

/** A better codec is worth a follow-up from this saving on; below it the two count as equal. */
export const CODEC_WORTH = 0.03;

export function codecRows(r: TrialResult) {
  return (r.codecs ?? [])
    .map((c) => {
      const today = c.variants.find((v) => v.codec === c.production) ?? null;
      const best = c.variants.find((v) => v.codec === c.best) ?? null;
      const saved = today && best ? today.compressedBytes - best.compressedBytes : 0;
      const share = today && today.compressedBytes > 0 ? saved / today.compressedBytes : 0;
      return { column: c, today, best, saved, share, better: c.best !== c.production && share >= CODEC_WORTH };
    })
    .sort((a, b) => Number(b.better) - Number(a.better) || b.saved - a.saved);
}

export const codecLabel = (codec: string, defaultIs?: string | null) => codec || (defaultIs ? `default (${defaultIs})` : 'server default');

// ----- Apply -----

/** What the statements for production are, read from the SQL the trial wrote down when it finished. */
export function applyKind(r: TrialResult): { kind: 'none' | 'alter' | 'rebuild'; statements: number } {
  const sql = r.alterDdl ?? '';
  if (!sql.trim()) return { kind: 'none', statements: 0 };
  if (/^EXCHANGE TABLES /m.test(sql)) return { kind: 'rebuild', statements: 0 };
  return { kind: 'alter', statements: (sql.match(/^ALTER TABLE /gm) ?? []).length };
}

// ----- The whole picture -----

export interface OverviewItem {
  key: string;
  title: string;
  detail: string;
  /** The step that shows more, and the query to open there. */
  step?: StepKey;
  query?: string;
}

export interface Guide {
  cmp: Compare;
  noise: Noise;
  verdict: string;
  kpis: { size: string; queries: string; inserts: string };
  subs: Record<StepKey, string>;
  better: OverviewItem[];
  worse: OverviewItem[];
  neutral: { label: string; value: string }[];
  verdicts: Map<string, QueryVerdict>;
  changes: Change[];
}

const stepLabel = (key: StepKey) => STEPS.find((s) => s.key === key)!.label;
export const stepName = stepLabel;

function count(n: number, word: string) {
  return `${fmtInt(n)} ${word}`;
}

/** The verdict against plain defaults: how much of the distance today's tuning already covers. */
function defaultsVerdict(r: TrialResult): string {
  const size = r.metrics.find((m) => m.key === 'size');
  if (!size || size.plain == null || !(size.plain > 0)) return r.verdict;
  const cand = 1 - size.candidate / size.plain;
  const today = 1 - size.baseline / size.plain;
  const first = `Against plain defaults the candidate is ${fmtPct(Math.abs(cand))} ${cand >= 0 ? 'smaller' : 'larger'}.`;
  const step = size.baseline > 0 ? (size.candidate - size.baseline) / size.baseline : 0;
  if (today <= 0) return `${first} Today's table is ${fmtPct(Math.abs(today))} larger than plain defaults.`;
  const second =
    Math.abs(step) < 0.01
      ? `Today's tuning already gives ${fmtPct(today, 0)}; this trial leaves that as it is.`
      : step < 0
        ? `Today's tuning already gives ${fmtPct(today, 0)}; this trial adds the rest.`
        : `Today's tuning gives ${fmtPct(today, 0)}; this trial gives some of that back.`;
  return `${first} ${second}`;
}

export function buildGuide(trial: Trial, r: TrialResult, compare: Compare): Guide {
  const cmp: Compare = compare === 'defaults' && hasPlain(r) ? 'defaults' : 'today';
  const noise = noiseOf(r);
  const changes = changesOf(trial, r);
  const verdicts = new Map(r.queries.map((q) => [q.label, queryVerdict(q, cmp)]));
  const kind = (q: TrialQueryResult) => verdicts.get(q.label)!.kind;
  const faster = r.queries.filter((q) => kind(q) === 'faster');
  const slower = r.queries.filter((q) => kind(q) === 'slower');
  const undecided = r.queries.filter((q) => kind(q) === 'unclear' || kind(q) === 'same');

  const better: OverviewItem[] = [];
  const worse: OverviewItem[] = [];
  const neutral: { label: string; value: string }[] = [];

  for (const m of r.metrics) {
    const x = measure(m, cmp, noise.band);
    if (!x) continue;
    // The ratio says the same as the size, and each query speaks for itself below.
    if (m.key === 'ratio') continue;
    if (x.outcome === 'same') {
      neutral.push({ label: m.label, value: x.pair });
      continue;
    }
    const pct = x.change != null ? ` ${fmtChange(x.ref, m.candidate)}` : '';
    const extra =
      m.key === 'size' && cmp === 'today'
        ? ` in the sample, about ${fmtSignedBytes(r.projectedCandBytes - r.projectedBaseBytes)} on the full table`
        : m.key === 'size'
          ? ' in the sample'
          : '';
    const item: OverviewItem = {
      key: `metric-${m.key}`,
      title: `${m.label.replace(/ \(.*\)$/, '').replace(/, \d+ replayed$/, '')}${pct}`,
      detail: `${x.pair}${extra}`,
      step: m.key === 'size' ? 'size' : m.key === 'query' || m.key === 'read' ? 'speed' : undefined,
    };
    (x.outcome === 'better' ? better : worse).push(item);
  }

  const queryItem = (q: TrialQueryResult): OverviewItem => {
    const v = verdicts.get(q.label)!;
    const names = mentioned(q, r);
    return {
      key: `query-${q.label}`,
      title: `${q.label} runs ${v.text}`,
      detail: `${fmtMsPair(v.refMs!, q.candMs!)} · ${fmtPct(q.share, q.share < 0.1 ? 1 : 0)} of the workload${names.length ? ` · mentions ${names.join(', ')}` : ''}`,
      step: 'speed',
      query: q.label,
    };
  };
  const SHOWN = 4;
  for (const [list, into, word] of [
    [faster, better, 'faster'],
    [slower, worse, 'slower'],
  ] as const) {
    const byShare = [...list].sort((a, b) => b.share - a.share);
    byShare.slice(0, SHOWN).forEach((q) => into.push(queryItem(q)));
    if (byShare.length > SHOWN) {
      const rest = byShare.slice(SHOWN);
      into.push({ key: `more-${word}`, title: `${plural(rest.length, 'more query', 'more queries')} clearly ${word}`, detail: rest.map((q) => q.label).join(', '), step: 'speed', query: rest[0].label });
    }
  }

  // Full reads of the changed columns were only timed on today's table and the candidate.
  if (cmp === 'today') {
    for (const c of r.columns) {
      if (c.candidate == null || c.baseScanMs == null || c.candScanMs == null || !(c.baseScanMs > 0) || !(c.candScanMs > 0)) continue;
      const change = (c.candScanMs - c.baseScanMs) / c.baseScanMs;
      if (Math.abs(change) < noise.band) continue;
      (change < 0 ? better : worse).push({
        key: `scan-${c.name}`,
        title: `Column ${c.name} scans ${fmtSpeed(c.baseScanMs, c.candScanMs).text}`,
        detail: `${fmtMsPair(c.baseScanMs, c.candScanMs)} for a full read`,
        step: 'changes',
      });
    }
  }
  if (undecided.length) neutral.push({ label: `${fmtInt(undecided.length)} of ${plural(r.queries.length, 'replayed query', 'replayed queries')}`, value: 'runs overlap or medians match' });

  const size = r.metrics.find((m) => m.key === 'size');
  const sizeRef = size ? (cmp === 'defaults' ? size.plain : size.baseline) : null;
  const sizePct = size && sizeRef != null && sizeRef > 0 ? (Math.abs(size.candidate - sizeRef) / sizeRef < 0.0005 ? '0%' : fmtChange(sizeRef, size.candidate)) : '—';
  const insert = r.metrics.find((m) => m.key === 'insert');
  // Inserts were only timed into today's table and the candidate.
  const insertMeasure = insert ? measure(insert, cmp, noise.band) : null;
  const queriesWord = !r.queries.length
    ? 'not replayed'
    : faster.length || slower.length
      ? [faster.length ? count(faster.length, 'faster') : null, slower.length ? count(slower.length, 'slower') : null].filter(Boolean).join(' · ')
      : 'no clear difference';

  const columns = changes.filter((c) => c.kind === 'column').length;
  const keys = changes.filter((c) => c.kind === 'key').length;
  const indexes = changes.filter((c) => c.kind === 'index').length;
  const codecs = codecRows(r);
  const apply = applyKind(r);
  const applied = trial.apply;

  return {
    cmp,
    noise,
    verdict: cmp === 'defaults' ? defaultsVerdict(r) : r.verdict,
    kpis: {
      size: sizePct,
      queries: queriesWord,
      inserts: !insertMeasure ? 'not measured' : insertMeasure.outcome === 'same' ? 'no clear difference' : `${fmtChange(insertMeasure.ref, insert!.candidate, 0)} rows/s`,
    },
    subs: {
      overview: `${count(better.length, 'better')} · ${count(worse.length, 'worse')}`,
      changes: [keys ? plural(keys, 'key') : null, columns ? plural(columns, 'column') : null, indexes ? plural(indexes, 'index', 'indexes') : null].filter(Boolean).join(' · ') || 'same schema',
      size: `${sizePct} vs. ${cmp === 'defaults' ? 'defaults' : 'today'}`,
      codecs: !codecs.length ? 'not compared' : `${fmtInt(codecs.filter((c) => c.better).length)} better found`,
      speed: r.queries.length && !faster.length && !slower.length ? `${plural(r.queries.length, 'query', 'queries')}, none clear` : queriesWord,
      apply: applied
        ? applied.status === 'running'
          ? `applying ${Math.min(applied.done + 1, applied.total)} of ${applied.total}`
          : applied.status === 'completed'
            ? `applied ${fmtClock(applied.finishedAt)}`
            : 'apply failed'
        : apply.kind === 'none'
          ? 'nothing to apply'
          : apply.kind === 'rebuild'
            ? 'rebuild script'
            : plural(apply.statements, 'statement'),
    },
    better,
    worse,
    neutral,
    verdicts,
    changes,
  };
}
