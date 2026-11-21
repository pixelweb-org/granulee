import { useRef, useState, type CSSProperties, type ReactNode, type Ref } from 'react';
import type { QueryAnalyze, QueryExplain, Trial, TrialQueryResult, TrialResult } from '../api/types';
import { fmtBytes, fmtInt, fmtMs, fmtMsPair, fmtPct, fmtRows } from '../lib/format';
import { refLabel, refName, mentioned, verdictColor, type Guide, type QueryVerdict } from '../lib/trialGuide';
import { vars } from '../lib/style';
import { Choice } from './Button';
import { Card } from './Card';
import { PlanSteps, ReadFacts } from './PlanSteps';

const share = (q: TrialQueryResult) => fmtPct(q.share, q.share < 0.1 && q.share > 0 ? 1 : 0);

/**
 * The Speed step of a trial: every replayed query on one line, how much faster or slower the candidate answered it,
 * and next to the list the one that is picked: its runs, what it read and what that says.
 */
export function TrialSpeed({
  trial,
  result: r,
  guide,
  selected,
  onSelect,
}: {
  trial: Trial;
  result: TrialResult;
  guide: Guide;
  selected: string | null;
  onSelect: (label: string) => void;
}) {
  const [filter, setFilter] = useState<'all' | 'clear'>('all');
  const [plansFor, setPlansFor] = useState<string | null>(null);
  const detail = useRef<HTMLDivElement>(null);
  const plansAt = useRef<HTMLElement>(null);
  const verdict = (q: TrialQueryResult) => guide.verdicts.get(q.label)!;
  const clear = r.queries.filter((q) => verdict(q).kind === 'faster' || verdict(q).kind === 'slower');
  const picked = r.queries.find((q) => q.label === selected) ?? clear[0] ?? r.queries[0];
  const plans = picked != null && plansFor === picked.label;

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

  // The scale is logarithmic, so twice as fast and twice as slow are the same distance from the middle.
  const shifts = r.queries.map((q) => Math.abs(Math.log2(verdict(q).speedup ?? 1)));
  const factor = [2, 4, 8, 16, 32, 64, 128].find((f) => Math.log2(f) >= Math.max(...shifts) - 1e-9) ?? 128;
  const limit = Math.log2(factor);
  const shown = filter === 'clear' ? clear : r.queries;
  const pick = (label: string) => {
    onSelect(label);
    // Stacked on a narrow screen the detail sits below the list: bring it into view.
    requestAnimationFrame(() => {
      const box = detail.current?.getBoundingClientRect();
      if (box && (box.top > window.innerHeight * 0.7 || box.bottom < 0)) detail.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    });
  };
  const hasPlans = !!(picked.baseExplain || picked.candExplain || picked.baseAnalyze || picked.candAnalyze);
  const ref = guide.cmp === 'defaults' ? { plan: picked.plainExplain ?? null, run: null } : { plan: picked.baseExplain, run: picked.baseAnalyze };

  return (
    <>
      <section className="stack">
        <div className="tg-bar">
          <div role="group" aria-label="Which queries to show" className="row">
            <Choice pressed={filter === 'all'} onClick={() => setFilter('all')}>
              All {fmtInt(r.queries.length)}
            </Choice>
            <Choice pressed={filter === 'clear'} onClick={() => setFilter('clear')}>
              Clearly different {fmtInt(clear.length)}
            </Choice>
          </div>
          <span className="caption muted">
            {fmtInt(r.queries.length)} {r.queries.length === 1 ? 'query' : 'queries'} replayed, {trial.request.runsPerQuery} runs per table, taking turns. The candidate
            against {refName(guide.cmp)}; pick one to see its detail.
          </span>
        </div>
        <div className="tg-split">
          <div className="card tg-list">
            <div className="eyebrow tg-list__head" aria-hidden="true">
              <span className="tg-pick__id">Query · share</span>
              <span className="tg-list__scale">
                <span>{factor}× slower</span>
                <span>same</span>
                <span>{factor}× faster</span>
              </span>
              <span className="tg-pick__out">Outcome</span>
            </div>
            {shown.map((q) => {
              const v = verdict(q);
              const x = Math.max(-limit, Math.min(limit, Math.log2(v.speedup ?? 1)));
              const width = Math.max((Math.abs(x) / limit) * 50, v.speedup == null ? 0 : 1);
              return (
                <button
                  type="button"
                  key={q.label}
                  className="g-rowbtn tg-pick"
                  aria-pressed={q.label === picked.label}
                  onClick={() => pick(q.label)}
                  title={v.refMs != null && q.candMs != null ? `${q.label}: ${fmtMsPair(v.refMs, q.candMs)}` : q.label}
                >
                  <span className="g-num tg-pick__id">
                    <strong>{q.label}</strong> <span className="muted">{share(q)}</span>
                  </span>
                  <span className="tg-div tg-div--lg">
                    <span className="tg-div__bar" style={{ background: verdictColor(v.kind), width: `${width}%`, ...(x >= 0 ? { left: '50%' } : { right: '50%' }) }} />
                  </span>
                  <span className="g-num tg-pick__out">{v.text}</span>
                </button>
              );
            })}
            {!shown.length ? <p className="body-sm muted tg-list__note">No query is clearly faster or slower.</p> : null}
            <p className="caption muted tg-list__note">Grey bars: the runs of the two tables overlap, so the difference is not reliable.</p>
          </div>
          <QueryDetail key={picked.label} q={picked} verdict={verdict(picked)} result={r} guide={guide} trial={trial} boxRef={detail}>
            {hasPlans ? (
              <button
                type="button"
                className="cc-btn cc-btn--link"
                style={{ alignSelf: 'flex-start' }}
                aria-expanded={plans}
                onClick={() => {
                  setPlansFor(plans ? null : picked.label);
                  if (!plans) requestAnimationFrame(() => plansAt.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }));
                }}
              >
                {plans ? 'Hide the query plans' : 'Show both query plans'}
              </button>
            ) : null}
          </QueryDetail>
        </div>
      </section>

      {plans && hasPlans ? (
        <section className="stack tg-anchor" ref={plansAt} aria-label={`Query plans of ${picked.label}`}>
          <h2 className="card-title">Query plans of {picked.label}</h2>
          <div className="grid-auto" style={vars({ min: 360, gap: 24 })}>
            <PlanSide title={refLabel(guide.cmp)} plan={ref.plan} run={ref.run} />
            <PlanSide title="Candidate" plan={picked.candExplain} run={picked.candAnalyze} />
          </div>
        </section>
      ) : null}

      <NewIndexes trial={trial} result={r} />
    </>
  );
}

/** One sentence or two on why a query came out as it did: what it touches of the change, and what the runs show. */
function why(q: TrialQueryResult, v: QueryVerdict, r: TrialResult, guide: Guide, trial: Trial): string {
  if (v.kind === 'none') return q.error ? `Not run: ${q.error}` : 'Not measured on both tables.';
  const added = (trial.request.addIndexes ?? []).map((i) => i.name);
  const used = (q.candExplain?.steps ?? []).find((s) => s.kind === 'skip' && added.includes(s.name));
  const names = mentioned(q, r);
  const columns = guide.changes.filter((c) => c.kind === 'column').length;
  const touches =
    guide.cmp === 'defaults'
      ? 'Plain defaults have no skip indexes or projections and the default codec on every column.'
      : used
        ? used.granulesAfter < used.granulesBefore
          ? `Uses the new skip index ${used.name}: ${fmtInt(used.granulesBefore)} → ${fmtInt(used.granulesAfter)} granules.`
          : `Asks the new skip index ${used.name}, which drops none of the ${fmtInt(used.granulesBefore)} granules.`
        : names.length
          ? `Mentions ${names.join(', ')}, which this trial changed.`
          : guide.changes.some((c) => c.kind === 'key')
            ? 'The candidate stores the rows in another order, which changes what every query has to read.'
            : columns
              ? `Mentions ${columns === 1 ? 'not the changed column' : 'none of the changed columns'}.`
              : '';
  const c = q.candRuns ?? [];
  const where = refName(guide.cmp);
  let runs: string;
  if (v.refRuns.length < 2 || c.length < 2) runs = 'This trial kept only the medians, so how far the runs spread is not known.';
  else if (v.kind === 'faster') runs = `Every candidate run was faster than the fastest run on ${where}.`;
  else if (v.kind === 'slower') runs = `Every candidate run was slower than the slowest run on ${where}.`;
  else if (v.kind === 'same') runs = 'The medians match.';
  else {
    const lo = Math.max(Math.min(...v.refRuns), Math.min(...c));
    const hi = Math.min(Math.max(...v.refRuns), Math.max(...c));
    runs =
      Math.abs(Math.log2(v.speedup ?? 1)) > Math.log2(1.3)
        ? `The medians differ a lot, but both tables had runs between ${fmtMsPair(lo, hi).replace(' → ', ' and ')}. Not reliable.`
        : 'The runs overlap, so this is noise.';
  }
  return [touches, runs].filter(Boolean).join(' ');
}

function QueryDetail({
  q,
  verdict: v,
  result: r,
  guide,
  trial,
  boxRef,
  children,
}: {
  q: TrialQueryResult;
  verdict: QueryVerdict;
  result: TrialResult;
  guide: Guide;
  trial: Trial;
  boxRef: Ref<HTMLDivElement>;
  children?: ReactNode;
}) {
  const defaults = guide.cmp === 'defaults';
  const lanes = [
    ...(q.plainMs != null ? [{ name: 'Plain defaults', slot: 3, median: q.plainMs, runs: q.plainRuns ?? [] }] : []),
    ...(q.baseMs != null ? [{ name: 'Today', slot: 1, median: q.baseMs, runs: q.baseRuns ?? [] }] : []),
    ...(q.candMs != null ? [{ name: 'Candidate', slot: 2, median: q.candMs, runs: q.candRuns ?? [] }] : []),
  ];
  const max = Math.max(...lanes.flatMap((l) => [l.median, ...l.runs]), 0) * 1.15 || 1;
  const at = (ms: number) => `${Math.min(100, (ms / max) * 100)}%`;
  const refRows = defaults ? q.plainReadRows : q.baseReadRows;
  const refPlan = defaults ? q.plainExplain : q.baseExplain;
  const pair = (a: number | null | undefined, b: number | null | undefined, f: (v: number) => string) => (a != null && b != null ? `${f(a)} → ${f(b)}` : null);
  const facts = [
    ['Rows read', pair(refRows, q.candReadRows, fmtRows)],
    ['Granules read', pair(refPlan?.granulesRead, q.candExplain?.granulesRead, fmtInt)],
    ['Read streams', pair(refPlan?.streams, q.candExplain?.streams, fmtInt)],
    ['CPU time', defaults ? null : pair(q.baseCpuMs, q.candCpuMs, fmtMs)],
  ].filter((f): f is [string, string] => f[1] != null);
  return (
    <div className="card tg-detail" aria-live="polite" ref={boxRef}>
      <div className="row" style={vars({ gap: 12 })}>
        <span className="g-count g-num">{q.label}</span>
        <span className="tg-outcome">
          <span aria-hidden="true" className="tg-dot" style={{ background: verdictColor(v.kind) }} />
          {v.text}
        </span>
        <span className="body-sm g-num muted">{share(q)} of the workload</span>
      </div>
      <span className="g-sql g-sql--full">{q.query}</span>
      {lanes.length ? (
        <div className="tg-block tg-runs">
          <span className="eyebrow">Timed runs, fastest to slowest, dot = median</span>
          {lanes.map((l) => {
            const lo = Math.min(...l.runs);
            const hi = Math.max(...l.runs);
            const spread = l.runs.length > 1;
            return (
              <div
                key={l.name}
                className="tg-run"
                style={{ '--series': `var(--chart-${l.slot})` } as CSSProperties}
                title={`${l.name}: median ${fmtMs(l.median)}${spread ? `, runs ${l.runs.map((ms) => fmtMs(ms)).join(', ')}` : ''}`}
              >
                <span className="body-sm tg-run__name">{l.name}</span>
                <span className="tg-run__track">
                  {spread ? <span aria-hidden="true" className="tg-run__range" style={{ left: at(lo), width: `calc(${at(hi)} - ${at(lo)})` }} /> : null}
                  <span aria-hidden="true" className="tg-run__dot" style={{ left: at(l.median) }} />
                </span>
                <span className="g-num tg-run__value">
                  <strong>{fmtMs(l.median)}</strong> {spread ? <span className="muted">{fmtMsPair(lo, hi).replace(' → ', ' to ')}</span> : null}
                </span>
              </div>
            );
          })}
        </div>
      ) : null}
      {facts.length ? (
        <dl className="facts tg-block">
          {facts.map(([label, value]) => (
            <div key={label}>
              <dt className="eyebrow">{label}</dt>
              <dd className="g-num">{value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      <p className="body-sm g-wrap">{why(q, v, r, guide, trial)}</p>
      {children}
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
