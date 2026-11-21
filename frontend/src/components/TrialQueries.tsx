import { useState, type CSSProperties, type ReactNode } from 'react';
import type { QueryAnalyze, QueryExplain, Trial, TrialQueryResult, TrialResult } from '../api/types';
import { fmtBytes, fmtInt, fmtMs, fmtPct, fmtRows, fmtSpeed } from '../lib/format';
import { vars } from '../lib/style';
import { Choice } from './Button';
import { Card } from './Card';
import { PlanSteps, ReadFacts } from './PlanSteps';
import { Status, StatusDot, type Tone } from './StatusDot';

/** What the timed runs say about one query: which table is faster, or that they do not say. */
export interface QueryVerdict {
  kind: 'faster' | 'slower' | 'same' | 'unclear' | 'none';
  text: string;
  tone: Tone;
  /** Baseline median over candidate median: above 1 the candidate is faster. */
  speedup: number | null;
}

export function queryVerdict(q: TrialQueryResult): QueryVerdict {
  if (q.error || q.baseMs == null || q.candMs == null || !(q.baseMs > 0) || !(q.candMs > 0)) {
    return { kind: 'none', text: q.error ? 'not run' : '—', tone: 'muted', speedup: null };
  }
  const speed = fmtSpeed(q.baseMs, q.candMs);
  const speedup = q.baseMs / q.candMs;
  // The medians only tell the tables apart when no run of one took as long as a run of the other.
  const b = q.baseRuns ?? [];
  const c = q.candRuns ?? [];
  const overlap = b.length > 0 && c.length > 0 && Math.max(Math.min(...b), Math.min(...c)) <= Math.min(Math.max(...b), Math.max(...c));
  if (speed.same) return { kind: 'same', text: 'same', tone: 'muted', speedup };
  if (overlap) return { kind: 'unclear', text: 'no clear difference', tone: 'muted', speedup };
  if (speed.slower) return q.candMs > q.baseMs * 1.05 ? { kind: 'slower', text: speed.text, tone: 'error', speedup } : { kind: 'same', text: speed.text, tone: 'muted', speedup };
  return { kind: 'faster', text: speed.text, tone: 'success', speedup };
}

const SORTS = [
  { value: 'share', label: 'Workload share' },
  { value: 'change', label: 'Biggest change' },
  { value: 'time', label: 'Baseline time' },
] as const;
type Sort = (typeof SORTS)[number]['value'];

/** The Queries tab of a trial: how every replayed query fared, then each one in detail. */
export function TrialQueries({ trial, result: r }: { trial: Trial; result: TrialResult }) {
  const [sort, setSort] = useState<Sort>('share');
  const [open, setOpen] = useState<string | null>(null);
  const verdicts = new Map(r.queries.map((q) => [q.label, queryVerdict(q)]));
  const count = (kind: QueryVerdict['kind']) => r.queries.filter((q) => verdicts.get(q.label)!.kind === kind).length;
  const share = (kind: QueryVerdict['kind']) => r.queries.filter((q) => verdicts.get(q.label)!.kind === kind).reduce((a, q) => a + q.share, 0);
  const shift = (q: TrialQueryResult) => Math.abs(Math.log2(verdicts.get(q.label)!.speedup ?? 1));
  const sorted = [...r.queries].sort((a, b) =>
    sort === 'change' ? shift(b) - shift(a) : sort === 'time' ? (b.baseMs ?? 0) - (a.baseMs ?? 0) : b.share - a.share || a.label.localeCompare(b.label, undefined, { numeric: true }),
  );
  const show = (label: string) => {
    setOpen(label);
    requestAnimationFrame(() => document.getElementById(`query-${label}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' }));
  };

  if (!r.queries.length) {
    return (
      <>
        <NewIndexes trial={trial} result={r} />
        <p className="body-sm muted">
          No query was replayed in this trial. Replaying needs SELECTs on this table in query_log and the replay option switched on in the trial setup.
        </p>
      </>
    );
  }

  return (
    <>
      <section aria-label="Replayed queries" className="grid-auto" style={vars({ min: 200 })}>
        <Tile label="Queries replayed" value={fmtInt(r.queries.length)} sub={`${trial.request.runsPerQuery} runs each per table`} />
        <Tile label="Clearly faster" value={fmtInt(count('faster'))} sub={`${fmtPct(share('faster'), 0)} of the workload`} tone="success" />
        <Tile label="Clearly slower" value={fmtInt(count('slower'))} sub={`${fmtPct(share('slower'), 0)} of the workload`} tone={count('slower') ? 'error' : 'muted'} />
        <Tile label="No clear difference" value={fmtInt(count('unclear') + count('same'))} sub="runs overlap, or the medians match" />
      </section>

      <SpeedOverview queries={r.queries} verdicts={verdicts} onPick={show} />
      <NewIndexes trial={trial} result={r} />

      <section className="stack">
        <div className="between" style={{ alignItems: 'center' }}>
          <h2 className="card-title">Each query</h2>
          <div role="group" aria-label="Sort queries" className="row">
            {SORTS.map((s) => (
              <Choice key={s.value} pressed={sort === s.value} onClick={() => setSort(s.value)}>
                {s.label}
              </Choice>
            ))}
          </div>
        </div>
        <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, ...vars({ gap: 12 }) }}>
          {sorted.map((q) => (
            <QueryCard key={q.label} q={q} verdict={verdicts.get(q.label)!} open={open === q.label} onToggle={() => setOpen(open === q.label ? null : q.label)} />
          ))}
        </ol>
      </section>
    </>
  );
}

function Tile({ label, value, sub, tone }: { label: string; value: string; sub: string; tone?: Tone }) {
  return (
    <div className="tile">
      <span className="caption muted">{label}</span>
      <span className="headline">{value}</span>
      {tone ? (
        <Status tone={tone} className="caption g-num">
          {sub}
        </Status>
      ) : (
        <span className="caption muted g-num">{sub}</span>
      )}
    </div>
  );
}

// The overview draws a change of up to this factor in either direction; anything beyond is pinned to the edge.
const MAX_FACTOR = 8;

/**
 * Every query on one line: how much faster or slower the candidate answered it. The scale is logarithmic,
 * so twice as fast and twice as slow are the same distance from the middle.
 */
function SpeedOverview({ queries, verdicts, onPick }: { queries: TrialQueryResult[]; verdicts: Map<string, QueryVerdict>; onPick: (label: string) => void }) {
  const rows = queries.filter((q) => verdicts.get(q.label)!.speedup != null);
  if (!rows.length) return null;
  const limit = Math.log2(MAX_FACTOR);
  return (
    <figure className="bd-chart q-speed">
      <figcaption className="bd-chart-head">
        <div>
          <p className="card-title">Speed per query</p>
          <p className="caption bd-chart-sub">Candidate against baseline, by the median of the timed runs · most frequent query first</p>
        </div>
      </figcaption>
      <div className="q-speed__rows">
        <div className="q-speed__row q-speed__row--axis caption muted" aria-hidden="true">
          <span />
          <span className="q-speed__axis">
            <span>{MAX_FACTOR}× slower</span>
            <span>same</span>
            <span>{MAX_FACTOR}× faster</span>
          </span>
          <span />
        </div>
        {rows.map((q) => {
          const v = verdicts.get(q.label)!;
          const x = Math.max(-limit, Math.min(limit, Math.log2(v.speedup!)));
          const width = (Math.abs(x) / limit) * 50;
          const tone = v.kind === 'faster' ? 'faster' : v.kind === 'slower' ? 'slower' : 'unclear';
          return (
            <button
              type="button"
              key={q.label}
              className="q-speed__row"
              onClick={() => onPick(q.label)}
              title={`${q.label}: ${fmtMs(q.baseMs)} → ${fmtMs(q.candMs)}, ${v.text} · ${fmtPct(q.share, 0)} of the workload`}
            >
              <span className="q-speed__name g-num">
                <span className="ink" style={{ fontWeight: 700 }}>
                  {q.label}
                </span>{' '}
                <span className="muted">{fmtPct(q.share, 0)}</span>
              </span>
              <span className="q-speed__track">
                <span
                  className={`q-speed__bar q-speed__bar--${tone} ${x >= 0 ? 'q-speed__bar--right' : 'q-speed__bar--left'}`}
                  style={x >= 0 ? { left: '50%', width: `${width}%` } : { right: '50%', width: `${width}%` }}
                />
              </span>
              <span className="q-speed__value g-num">{v.text}</span>
            </button>
          );
        })}
      </div>
      <ul className="bd-chart-legend caption" style={{ margin: 0 }}>
        <li>
          <span className="bd-chart-key-rect q-speed__bar--faster" /> clearly faster
        </li>
        <li>
          <span className="bd-chart-key-rect q-speed__bar--slower" /> clearly slower
        </li>
        <li>
          <span className="bd-chart-key-rect q-speed__bar--unclear" /> no clear difference: the runs of the two tables overlap
        </li>
      </ul>
    </figure>
  );
}

/** One replayed query. Closed it shows the outcome and the runs; open it adds the work per run and the plans. */
function QueryCard({ q, verdict, open, onToggle }: { q: TrialQueryResult; verdict: QueryVerdict; open: boolean; onToggle: () => void }) {
  const id = `query-${q.label}`;
  return (
    <li id={id} className={`card card-row q-card${verdict.kind === 'slower' ? ' is-worse' : ''}`}>
      <button type="button" className="q-card__head" aria-expanded={open} aria-controls={`${id}-detail`} onClick={onToggle}>
        <span className="g-count g-num" style={{ flex: 'none' }}>
          {q.label}
        </span>
        <span className={open ? 'g-sql g-sql--full' : 'g-sql g-sql--clamp'}>{q.query}</span>
        <svg className="g-acc__chev q-card__chev" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
          <path d="M3 1l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      <div className="q-card__summary">
        <dl className="facts">
          <Fact label="Share of workload">{fmtPct(q.share, 0)}</Fact>
          <Fact label="Baseline">{fmtMs(q.baseMs)}</Fact>
          <Fact label="Candidate" strong>
            {fmtMs(q.candMs)}
          </Fact>
          <div>
            <dt className="eyebrow">Outcome</dt>
            <dd>
              {q.error ? (
                <span className="status body-sm g-wrap">
                  <StatusDot tone="muted" />
                  Not run
                </span>
              ) : (
                <span className="status" style={{ fontWeight: 700 }}>
                  <StatusDot tone={verdict.tone} />
                  {verdict.text}
                </span>
              )}
            </dd>
          </div>
        </dl>
        {q.baseMs != null && q.candMs != null ? <RunsPlot q={q} compact /> : null}
      </div>
      {q.error ? <p className="body-sm muted g-wrap">Not run: {q.error}</p> : null}
      {open ? (
        <div id={`${id}-detail`} className="stack q-card__detail" style={vars({ gap: 24 })}>
          {q.baseMs != null && q.candMs != null ? (
            <div className="grid-auto" style={vars({ min: 360, gap: 24 })}>
              <div className="stack" style={vars({ gap: 8 })}>
                <h3 className="eyebrow">Timed runs</h3>
                <RunsPlot q={q} />
                <p className="caption muted">
                  {(q.baseRuns?.length ?? 0) < 2
                    ? 'This trial kept only the medians, so the spread of its runs is not shown.'
                    : 'Every line is one run, the dot is the median the outcome is read from. The tables took turns, and the first run of each only warmed up.'}
                </p>
              </div>
              <div className="stack" style={vars({ gap: 8 })}>
                <h3 className="eyebrow">Work per run</h3>
                <WorkBars q={q} />
              </div>
            </div>
          ) : null}
          {q.baseExplain || q.candExplain || q.baseAnalyze || q.candAnalyze ? (
            <div className="grid-auto" style={vars({ min: 360, gap: 24 })}>
              <PlanSide title="Baseline" plan={q.baseExplain} run={q.baseAnalyze} />
              <PlanSide title="Candidate" plan={q.candExplain} run={q.candAnalyze} />
            </div>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

function Fact({ label, strong, children }: { label: string; strong?: boolean; children: ReactNode }) {
  return (
    <div>
      <dt className="eyebrow">{label}</dt>
      <dd className={strong ? 'strong' : undefined}>{children}</dd>
    </div>
  );
}

/** A round number at or above the value, for the end of an axis: 1, 2 or 5 times a power of ten. */
function niceMax(value: number): number {
  if (!(value > 0)) return 1;
  const power = Math.pow(10, Math.floor(Math.log10(value)));
  const step = [1, 2, 5, 10].find((m) => m * power >= value) ?? 10;
  return step * power;
}

/**
 * The timed runs of one query on both tables, on one time axis that starts at zero. A line per run, a band from the
 * fastest to the slowest, a dot at the median. Whether the two bands overlap is what the outcome goes by.
 */
function RunsPlot({ q, compact }: { q: TrialQueryResult; compact?: boolean }) {
  const lanes = [
    { name: 'Baseline', slot: 1, median: q.baseMs!, runs: q.baseRuns?.length ? q.baseRuns : [q.baseMs!] },
    { name: 'Candidate', slot: 2, median: q.candMs!, runs: q.candRuns?.length ? q.candRuns : [q.candMs!] },
  ];
  const max = niceMax(Math.max(...lanes.flatMap((l) => l.runs)) * 1.02);
  const at = (ms: number) => `${Math.min(100, (ms / max) * 100)}%`;
  return (
    <div className={`q-runs${compact ? ' q-runs--compact' : ''}`}>
      {lanes.map((l) => {
        const lo = Math.min(...l.runs);
        const hi = Math.max(...l.runs);
        const runs = l.runs.length > 1 ? `, runs ${l.runs.map((ms) => fmtMs(ms)).join(', ')}` : '';
        return (
          <div key={l.name} className="q-runs__lane" title={`${l.name}: median ${fmtMs(l.median)}${runs}`}>
            <span className="q-runs__name caption">
              <span className="bd-chart-key-rect" style={{ background: `var(--chart-${l.slot})` }} />
              {l.name}
            </span>
            <span className="q-runs__track" style={{ '--series': `var(--chart-${l.slot})` } as CSSProperties}>
              {l.runs.length > 1 ? <span className="q-runs__band" style={{ left: at(lo), width: `calc(${at(hi)} - ${at(lo)})` }} /> : null}
              {l.runs.length > 1 ? l.runs.map((ms, i) => <span key={i} className="q-runs__run" style={{ left: at(ms) }} />) : null}
              <span className="q-runs__median" style={{ left: at(l.median) }} />
            </span>
            <span className="q-runs__value caption g-num">{fmtMs(l.median)}</span>
          </div>
        );
      })}
      {compact ? null : (
        <div className="q-runs__lane q-runs__axis caption muted g-num" aria-hidden="true">
          <span />
          <span className="q-runs__ticks">
            <span>0</span>
            <span>{fmtMs(max / 2)}</span>
            <span>{fmtMs(max)}</span>
          </span>
          <span />
        </div>
      )}
    </div>
  );
}

/**
 * What one run costs on each table, a measure per row: time, CPU time, rows read, granules read. Each row has its
 * own scale, set by the larger of its two values, so the bars compare the tables and not the measures.
 */
function WorkBars({ q }: { q: TrialQueryResult }) {
  const rows: { name: string; base: number | null | undefined; cand: number | null | undefined; format: (v: number) => string }[] = [
    { name: 'Median time', base: q.baseMs, cand: q.candMs, format: fmtMs },
    { name: 'CPU time', base: q.baseCpuMs, cand: q.candCpuMs, format: fmtMs },
    { name: 'Rows read', base: q.baseReadRows, cand: q.candReadRows, format: fmtRows },
    { name: 'Granules read', base: q.baseExplain?.granulesRead, cand: q.candExplain?.granulesRead, format: fmtInt },
  ];
  const shown = rows.filter((r): r is typeof r & { base: number; cand: number } => r.base != null && r.cand != null);
  if (!shown.length) return null;
  return (
    <div className="q-work">
      {shown.map((r) => {
        const max = Math.max(r.base, r.cand);
        const change = r.base > 0 ? (r.cand - r.base) / r.base : 0;
        return (
          <div key={r.name} className="q-work__row" title={`${r.name}: baseline ${r.format(r.base)}, candidate ${r.format(r.cand)}`}>
            <span className="caption">{r.name}</span>
            <span className="q-work__bars">
              <span className="q-work__bar" style={{ width: max > 0 ? `${(r.base / max) * 100}%` : 0, background: 'var(--chart-1)' }} />
              <span className="q-work__bar" style={{ width: max > 0 ? `${(r.cand / max) * 100}%` : 0, background: 'var(--chart-2)' }} />
            </span>
            <span className="caption g-num q-work__value">
              {r.format(r.base)} → <span className="ink" style={{ fontWeight: 700 }}>{r.format(r.cand)}</span>
              {Math.abs(change) >= 0.005 ? <span className="muted"> · {change > 0 ? '+' : '−'}{fmtPct(Math.abs(change), 0)}</span> : null}
            </span>
          </div>
        );
      })}
      <ul className="bd-chart-legend caption" style={{ margin: 0 }}>
        <li>
          <span className="bd-chart-key-rect" style={{ background: 'var(--chart-1)' }} /> Baseline
        </li>
        <li>
          <span className="bd-chart-key-rect" style={{ background: 'var(--chart-2)' }} /> Candidate
        </li>
      </ul>
    </div>
  );
}

/** How one table plans the query and, where measured, how one run of it went. */
function PlanSide({ title, plan, run }: { title: string; plan: QueryExplain | null; run?: QueryAnalyze | null }) {
  if (!plan && !run) return null;
  return (
    <div className="stack" style={vars({ gap: 8 })}>
      <h3 className="eyebrow">
        {title} · plan
        {plan?.streams != null ? ` · ${plan.streams} read ${plan.streams === 1 ? 'stream' : 'streams'}` : ''}
      </h3>
      {!plan ? null : plan.error ? (
        <p className="body-sm muted g-wrap">EXPLAIN failed: {plan.error}</p>
      ) : plan.steps.length ? (
        <PlanSteps steps={plan.steps} />
      ) : (
        <p className="body-sm muted">No index narrows this query: it reads every granule.</p>
      )}
      {!plan || plan.error ? null : <ReadFacts plan={plan} />}
      {run ? <MeasuredRun run={run} /> : null}
      {plan?.pipeline ? (
        <details>
          <summary className="body-sm g-act" style={{ cursor: 'pointer' }}>
            EXPLAIN PIPELINE
          </summary>
          <pre className="g-code" style={{ marginTop: 8 }}>
            {plan.pipeline}
          </pre>
        </details>
      ) : null}
    </div>
  );
}

/** One run measured with EXPLAIN ANALYZE: the totals, then the steps that took time or dropped rows. */
function MeasuredRun({ run }: { run: QueryAnalyze }) {
  if (run.error) return <p className="body-sm muted g-wrap">EXPLAIN ANALYZE failed: {run.error}</p>;
  const busiest = run.steps.reduce((max, s) => Math.max(max, s.timeShare ?? 0), 0);
  // An Expression that passes every row on in no time says nothing; the rest is the run.
  const steps = run.steps.filter((s) => (s.timeShare ?? 0) >= 0.02 || (s.rowsIn != null && s.rowsOut != null && s.rowsIn !== s.rowsOut));
  // Step times are often far below a millisecond on a sample.
  const fine = (ms: number) => (ms < 0.1 ? `${Math.max(1, Math.round(ms * 1000))} µs` : fmtMs(ms));
  const read = run.steps.filter((s) => s.name.startsWith('ReadFrom')).reduce<number | null>((sum, s) => (s.rowsOut == null ? sum : (sum ?? 0) + s.rowsOut), null);
  return (
    <div className="stack" style={vars({ gap: 8 })}>
      <p className="body-sm muted">
        One more run, measured step by step: planning <span className="ink g-num">{fmtMs(run.planningMs)}</span>, execution{' '}
        <span className="ink g-num">{fmtMs(run.executionMs)}</span>
        {run.peakMemoryBytes != null ? (
          <>
            , peak memory <span className="ink g-num">{fmtBytes(run.peakMemoryBytes)}</span>
          </>
        ) : null}
        .
        {run.readRows != null ? (
          <>
            {' '}
            Read <span className="ink g-num">{fmtRows(run.readRows)}</span> rows
            {read != null && read < run.readRows * 0.999 ? (
              <>
                , of which <span className="ink g-num">{fmtRows(read)}</span> passed the filter
              </>
            ) : null}
            .
          </>
        ) : null}
      </p>
      <p className="caption muted">
        This single run is not one of the timed runs. A short query can take twice as long from one run to the next, so read the steps for where the
        time goes, not for which table is faster.
      </p>
      {steps.length ? (
        <div style={{ overflowX: 'auto' }}>
          <table className="g-tbl">
            <thead>
              <tr>
                <th>Step</th>
                <th className="r">Rows in → out</th>
                <th className="r">Time · threads</th>
              </tr>
            </thead>
            <tbody>
              {steps.map((s, i) => {
                const top = busiest > 0 && s.timeShare === busiest;
                return (
                  <tr key={i} className={top ? 'is-changed' : undefined}>
                    <td>
                      <span className="ink" style={{ fontWeight: 700 }}>
                        {s.name.split(' (')[0]}
                      </span>
                      {s.name.includes(' (') ? <div className="g-mono g-wrap muted">{s.name.slice(s.name.indexOf(' (') + 2, -1)}</div> : null}
                    </td>
                    <td className="r nowrap">{s.rowsIn != null && s.rowsOut != null ? `${fmtRows(s.rowsIn)} → ${fmtRows(s.rowsOut)}` : '—'}</td>
                    <td className="r nowrap">
                      <span className={top ? 'ink' : undefined} style={top ? { fontWeight: 700 } : undefined}>
                        {s.timeMs != null ? `${fine(s.timeMs)}${s.timeShare != null ? ` · ${fmtPct(s.timeShare, 0)}` : ''}` : '—'}
                      </span>
                      {s.timeShare != null ? (
                        <div className="g-track q-share">
                          <div className="g-fill" style={{ width: `${Math.min(100, s.timeShare * 100)}%` }} />
                        </div>
                      ) : null}
                      {s.threads != null ? (
                        <div className="muted">
                          {s.threads.toFixed(1)}
                          {s.maxThreads != null ? ` of ${fmtInt(s.maxThreads)}` : ''} threads
                        </div>
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
      {run.text ? (
        <details>
          <summary className="body-sm g-act" style={{ cursor: 'pointer' }}>
            EXPLAIN ANALYZE
          </summary>
          <pre className="g-code" style={{ marginTop: 8 }}>
            {run.text}
          </pre>
        </details>
      ) : null}
    </div>
  );
}

/** Did the skip indexes this trial added prune anything? Summed over the replayed queries' plans on the candidate. */
export function NewIndexes({ trial, result }: { trial: Trial; result: TrialResult }) {
  const added = trial.request.addIndexes ?? [];
  if (!added.length) return null;
  if (!result.queries.some((q) => q.candExplain)) {
    return (
      <p className="g-notice">
        This trial added {added.map((i) => i.name).join(', ')}, but no query was replayed, so nothing shows whether {added.length === 1 ? 'it prunes' : 'they prune'}.
        Replaying needs SELECTs on this table in query_log and the replay option switched on in the trial setup.
      </p>
    );
  }
  const rows = added.map((index) => {
    const used = result.queries.flatMap((q) => (q.candExplain?.steps ?? []).filter((s) => s.kind === 'skip' && s.name === index.name).map((s) => ({ q, s })));
    const before = used.reduce((a, u) => a + u.s.granulesBefore, 0);
    const after = used.reduce((a, u) => a + u.s.granulesAfter, 0);
    return { index, queries: used.map((u) => u.q.label), share: used.reduce((a, u) => a + u.q.share, 0), before, after, dropped: before > 0 ? 1 - after / before : 0 };
  });
  return (
    <section className="stack" style={vars({ gap: 8 })}>
      <h2 className="card-title">Do the new skip indexes prune?</h2>
      <Card table>
        <table className="g-tbl">
          <thead>
            <tr>
              <th>Index</th>
              <th>Type</th>
              <th>Used by</th>
              <th className="r">Granules in → out</th>
              <th style={{ width: '20%' }}>Granules dropped</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ index, queries, share, before, after, dropped }) => (
              <tr key={index.name}>
                <td className="g-strong">{index.name}</td>
                <td className="g-mono">{index.type}</td>
                <td>{queries.length ? `${queries.join(', ')} · ${fmtPct(share, 0)} of the workload` : 'no replayed query'}</td>
                <td className="r nowrap">{queries.length ? `${fmtInt(before)} → ${fmtInt(after)}` : '—'}</td>
                <td>
                  {queries.length ? (
                    <div className="share">
                      <div className="g-track">
                        <div className="g-fill" style={{ width: `${Math.min(100, dropped * 100)}%` }} />
                      </div>
                      <span className="g-num ink" style={{ width: 36, textAlign: 'right' }}>
                        {fmtPct(dropped, 0)}
                      </span>
                    </div>
                  ) : (
                    '—'
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      <p className="caption muted">
        From EXPLAIN on the candidate, summed over the replayed queries. An index that drops nothing costs insert time and disk for no gain on this sample.
      </p>
    </section>
  );
}
