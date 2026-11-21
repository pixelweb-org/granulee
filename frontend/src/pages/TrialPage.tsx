import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { QueryExplain, Trial, TrialMetric, TrialResult } from '../api/types';
import { useApi, usePolling } from '../hooks/useApi';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { BytesChart } from '../components/BytesChart';
import { Chart } from '../components/Chart';
import { CodeBlock } from '../components/CodeBlock';
import { PageHeader } from '../components/PageHeader';
import { PlanSteps } from '../components/PlanSteps';
import { ErrorState, Loading } from '../components/States';
import { Status, StatusDot, type Tone } from '../components/StatusDot';
import { ApplyTrialDialog, OptimizeTrialDialog, RemoveTrialDialog, TrialProgressCard, trialTone, useRemoveTrial } from '../components/TrialParts';
import {
  fmtBytes,
  fmtBytesPair,
  fmtChange,
  fmtClock,
  fmtDuration,
  fmtInt,
  fmtMs,
  fmtMsPair,
  fmtPct,
  fmtRatio,
  fmtRatioValue,
  fmtRows,
  fmtSignedBytes,
  fmtSpeed,
  fmtTime,
} from '../lib/format';
import { vars } from '../lib/style';

/** Did the skip indexes this trial added prune anything? Summed over the replayed queries' plans on the candidate. */
function NewIndexes({ trial, result }: { trial: Trial; result: TrialResult }) {
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
    <div className="stack" style={vars({ gap: 8 })}>
      <h3 className="subhead">Do the new skip indexes prune?</h3>
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
    </div>
  );
}

/** What EXPLAIN says about one replayed query on each table: every index that narrowed the read, and the read streams. */
function QueryPlans({ base, cand }: { base: QueryExplain | null; cand: QueryExplain | null }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="stack" style={vars({ gap: 12 })}>
      <button type="button" className="cc-btn cc-btn--link" style={{ alignSelf: 'flex-start' }} aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? 'Hide the plans' : 'Show how each table plans it'}
      </button>
      {open ? (
        <div className="grid-auto" style={vars({ min: 360, gap: 24 })}>
          <PlanSide title="Baseline" plan={base} />
          <PlanSide title="Candidate" plan={cand} />
        </div>
      ) : null}
    </div>
  );
}

function PlanSide({ title, plan }: { title: string; plan: QueryExplain | null }) {
  if (!plan) return null;
  return (
    <div className="stack" style={vars({ gap: 8 })}>
      <p className="eyebrow">
        {title}
        {plan.streams != null ? ` · ${plan.streams} read ${plan.streams === 1 ? 'stream' : 'streams'}` : ''}
      </p>
      {plan.error ? (
        <p className="body-sm muted g-wrap">EXPLAIN failed: {plan.error}</p>
      ) : plan.steps.length ? (
        <PlanSteps steps={plan.steps} />
      ) : (
        <p className="body-sm muted">No index narrows this query: it reads every granule.</p>
      )}
      {plan.pipeline ? (
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

function metricValue(m: TrialMetric): string {
  switch (m.unit) {
    case 'bytes':
      return fmtBytesPair(m.baseline, m.candidate);
    case 'ms':
      return fmtMsPair(m.baseline, m.candidate);
    case 'ratio':
      return `${fmtRatioValue(m.baseline)} → ${fmtRatioValue(m.candidate)}`;
    case 'rows':
      return `${fmtRows(m.baseline)} → ${fmtRows(m.candidate)}`;
    case 'rowsPerSec':
      return `${fmtRows(m.baseline)} → ${fmtRows(m.candidate)} rows/s`;
    default:
      return `${fmtInt(m.baseline)} → ${fmtInt(m.candidate)}`;
  }
}

function metricVerdict(m: TrialMetric): { tone: Tone; text: string } {
  if (!(m.baseline > 0)) return { tone: 'muted', text: 'no baseline' };
  const p = (m.candidate - m.baseline) / m.baseline;
  if (Math.abs(p) < 0.01) return { tone: 'muted', text: 'no change' };
  const better = m.lowerIsBetter ? p < 0 : p > 0;
  return { tone: better ? 'success' : 'error', text: `${fmtChange(m.baseline, m.candidate, 0)} · ${better ? 'better' : 'worse'}` };
}

export function TrialPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { data: trial, error, reload } = useApi(() => api.trial(id), [id]);
  const [removing, setRemoving] = useRemoveTrial();
  const [applying, setApplying] = useState(false);
  const [optimizing, setOptimizing] = useState(false);
  usePolling(trial?.status === 'running' || trial?.apply?.status === 'running' || trial?.optimize?.status === 'running', 2000, () => void reload(true));

  if (error && !trial) {
    return (
      <>
        <PageHeader eyebrow={<Link to="/trials">Trials</Link>} title={`Trial #${id}`} />
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!trial) return <Loading />;

  const r = trial.result;
  const done = trial.status === 'completed' && r != null;
  const statusWord =
    trial.status === 'completed'
      ? `Completed ${fmtClock(trial.finishedAt)}`
      : trial.status === 'running'
        ? `Running · ${Math.round(trial.progress)}%`
        : trial.status === 'cancelled'
          ? `Cancelled ${fmtClock(trial.finishedAt)}`
          : `Failed ${fmtClock(trial.finishedAt)}`;

  return (
    <>
      <PageHeader
        eyebrow={
          <Link to="/trials">
            Trials · {trial.database}.{trial.table} · #{trial.id}
          </Link>
        }
        title={trial.name}
        description={
          <span className="body-sm g-num row">
            <Status tone={trialTone(trial)}>{statusWord}</Status>
            {done ? <> · {fmtRows(r.rows)} identical rows in both tables</> : null}
            {trial.status !== 'running' ? <> · kept until you remove it · {fmtBytes(trial.scratchBytes)} in scratch</> : null}
          </span>
        }
        actions={
          trial.status === 'running' ? undefined : (
            <>
              {done ? (
                <Button variant="secondary" disabled={trial.apply?.status === 'running'} onClick={() => setApplying(true)}>
                  Apply to production
                </Button>
              ) : null}
              <Button variant="secondary" onClick={() => setRemoving(trial)}>
                Remove trial
              </Button>
            </>
          )
        }
      />

      {trial.apply ? (
        <Card as="section" style={{ gap: 8, alignItems: 'flex-start' }}>
          <Status tone={trial.apply.status === 'completed' ? 'success' : trial.apply.status === 'failed' ? 'error' : 'active'} className="subhead">
            {trial.apply.status === 'running'
              ? `Applying to ${trial.database}.${trial.table} · statement ${Math.min(trial.apply.done + 1, trial.apply.total)} of ${trial.apply.total}`
              : trial.apply.status === 'completed'
                ? `Applied to ${trial.database}.${trial.table} ${fmtClock(trial.apply.finishedAt)}`
                : `Applying to ${trial.database}.${trial.table} failed`}
          </Status>
          {trial.apply.status === 'failed' ? (
            <>
              {trial.apply.error ? <p className="body-sm muted" style={{ overflowWrap: 'anywhere' }}>{trial.apply.error}</p> : null}
              <p className="caption muted">
                {trial.apply.done} of {trial.apply.total} statements ran before the error and stay in effect. “Apply to production” shows what is still left.
              </p>
            </>
          ) : null}
          {trial.apply.status === 'running' ? <p className="caption muted">Type changes rewrite the column in every part; this page follows along.</p> : null}
          {trial.apply.status === 'completed' ? (
            <>
              {trial.optimize ? (
                <Status tone={trial.optimize.status === 'completed' ? 'success' : trial.optimize.status === 'failed' ? 'error' : 'active'} className="body-sm">
                  {trial.optimize.status === 'running'
                    ? `OPTIMIZE FINAL is running, started ${fmtClock(trial.optimize.startedAt)}`
                    : trial.optimize.status === 'completed'
                      ? `OPTIMIZE FINAL finished ${fmtClock(trial.optimize.finishedAt)}, after ${fmtDuration(
                          new Date(trial.optimize.finishedAt ?? trial.optimize.startedAt).getTime() - new Date(trial.optimize.startedAt).getTime(),
                        )}`
                      : 'OPTIMIZE FINAL failed'}
                </Status>
              ) : (
                <p className="caption muted">Parts written before the change keep their old codecs until they are merged. OPTIMIZE FINAL rewrites them now.</p>
              )}
              {trial.optimize?.status === 'failed' && trial.optimize.error ? (
                <p className="body-sm muted" style={{ overflowWrap: 'anywhere' }}>{trial.optimize.error}</p>
              ) : null}
              {trial.optimize?.status === 'running' ? (
                <p className="caption muted">Merging the whole table can take long; this page follows along and you can leave it.</p>
              ) : (
                <Button variant="secondary" onClick={() => setOptimizing(true)}>
                  {trial.optimize ? 'Run OPTIMIZE FINAL again' : 'Run OPTIMIZE FINAL'}
                </Button>
              )}
            </>
          ) : null}
        </Card>
      ) : null}

      {trial.status === 'running' ? <TrialProgressCard trial={trial} onChanged={() => void reload(true)} /> : null}

      {trial.status === 'failed' || trial.status === 'cancelled' ? (
        <Card as="section" style={{ alignItems: 'flex-start' }}>
          <Status tone={trial.status === 'failed' ? 'error' : 'muted'} className="subhead">
            {trial.status === 'failed' ? 'The trial failed' : 'The trial was cancelled'}
          </Status>
          {trial.error ? <p className="body-sm muted" style={{ overflowWrap: 'anywhere' }}>{trial.error}</p> : null}
          <p className="caption muted">Removing the trial drops whatever it left in {trial.scratchDatabase}.</p>
        </Card>
      ) : null}

      {done ? <Result trial={trial} result={r} /> : null}

      {trial.log.length ? (
        <Card as="section" style={{ gap: 12 }}>
          <h2 className="subhead">Run log</h2>
          <ol className="body-sm g-num muted stack" style={{ margin: 0, paddingLeft: 20, ...vars({ gap: 6 }) }}>
            {trial.log.map((l, i) => (
              <li key={i} style={{ overflowWrap: 'anywhere' }}>
                <span className="ink">{fmtTime(l.at)}</span> {l.message}
              </li>
            ))}
          </ol>
        </Card>
      ) : null}

      {optimizing ? (
        <OptimizeTrialDialog
          trial={trial}
          onClose={() => setOptimizing(false)}
          onStarted={() => {
            setOptimizing(false);
            void reload(true);
          }}
        />
      ) : null}
      {applying ? (
        <ApplyTrialDialog
          trial={trial}
          onClose={() => setApplying(false)}
          onStarted={() => {
            setApplying(false);
            void reload(true);
          }}
        />
      ) : null}
      {removing ? (
        <RemoveTrialDialog
          trial={removing}
          onClose={() => setRemoving(null)}
          onRemoved={() => {
            setRemoving(null);
            navigate('/trials');
          }}
        />
      ) : null}
    </>
  );
}

function Result({ trial, result: r }: { trial: Trial; result: TrialResult }) {
  const cols = r.columns;
  const totalBase = cols.reduce((a, c) => a + c.baseBytes, 0);
  const totalCand = cols.reduce((a, c) => a + c.candBytes, 0);
  const totalBaseUnc = cols.reduce((a, c) => a + c.baseUncompressedBytes, 0);
  const totalCandUnc = cols.reduce((a, c) => a + c.candUncompressedBytes, 0);
  const hasScans = cols.some((c) => c.baseScanMs != null && c.candScanMs != null);
  const scanCols = cols.filter((c) => c.candidate != null && c.baseScanMs != null && c.candScanMs != null);
  const measured = r.queries.filter((q) => q.baseMs != null && q.candMs != null);
  const top = measured.slice(0, 6);
  const delta = r.projectedCandBytes - r.projectedBaseBytes;

  return (
    <>
      <section className="band">
        <div className="stack" style={{ flex: '1 1 520px', ...vars({ gap: 8 }) }}>
          <p className="eyebrow">Verdict</p>
          <p className="subhead">{r.verdict}</p>
        </div>
        <div className="stack" style={vars({ gap: 4 })}>
          <span className="caption">Projected for {fmtRows(r.totalRows)} rows</span>
          <span className="headline g-num">{fmtBytesPair(r.projectedBaseBytes, r.projectedCandBytes)}</span>
          <span className="caption g-num">{fmtSignedBytes(delta)} on disk</span>
        </div>
      </section>

      {r.metrics.length ? (
        <section aria-label="Baseline vs candidate" className="grid-auto" style={vars({ min: 280 })}>
          {r.metrics.map((m) => {
            const v = metricVerdict(m);
            return (
              <div key={m.key} className="tile">
                <span className="caption muted">{m.label}</span>
                <span className="card-title g-num">{metricValue(m)}</span>
                <Status tone={v.tone} className="caption g-num">
                  {v.text}
                </Status>
              </div>
            );
          })}
        </section>
      ) : null}

      <section className="stack">
        <div className="between">
          <h2 className="card-title">Columns side by side</h2>
          <span className="caption muted">
            Sizes from system.columns{trial.request.optimizeFinal ? ' after full merge' : ''}
            {hasScans ? ` · scan = median of ${trial.request.runsPerQuery} full reads` : ''}
          </span>
        </div>
        <BytesChart
          type="bar"
          title="Compressed size per column"
          subtitle={`On disk, ${fmtRows(r.rows)} rows`}
          categoryLabel="Column"
          categories={cols.map((c) => c.name)}
          series={[
            { name: 'Baseline', values: cols.map((c) => c.baseBytes) },
            { name: 'Candidate', values: cols.map((c) => c.candBytes) },
          ]}
          height={240}
        />
        <Card table>
          <table className="g-tbl">
            <thead>
              <tr>
                <th>Column</th>
                <th>Baseline</th>
                <th>Candidate</th>
                <th className="r">Size before</th>
                <th className="r">Size after</th>
                <th className="r">Change</th>
                <th className="r">Ratio</th>
                {hasScans ? <th className="r">Scan time</th> : null}
              </tr>
            </thead>
            <tbody>
              {cols.map((c) => {
                const changed = c.candidate != null;
                const slower = c.baseScanMs != null && c.candScanMs != null && c.candScanMs > c.baseScanMs * 1.05;
                const moved = c.baseBytes > 0 && Math.abs(c.candBytes - c.baseBytes) / c.baseBytes >= 0.1;
                return (
                  <tr key={c.name} className={changed ? 'is-changed' : undefined}>
                    <td className="g-strong">{c.name}</td>
                    <td className="g-mono">{c.baseline}</td>
                    <td className={`g-mono${changed ? ' ink' : ''}`}>{c.candidate ?? 'unchanged'}</td>
                    <td className="r nowrap">{fmtBytes(c.baseBytes)}</td>
                    <td className="r g-strong nowrap">{fmtBytes(c.candBytes)}</td>
                    <td className={`r${changed || moved ? ' g-strong' : ''}`}>{fmtChange(c.baseBytes, c.candBytes)}</td>
                    <td className="r nowrap">
                      {fmtRatio(c.baseUncompressedBytes, c.baseBytes)} → {fmtRatio(c.candUncompressedBytes, c.candBytes)}
                    </td>
                    {hasScans ? (
                      <td className="r nowrap">
                        {c.baseScanMs != null && c.candScanMs != null ? (
                          <>
                            <span className={slower ? 'ink' : undefined}>{fmtMsPair(c.baseScanMs, c.candScanMs)}</span>
                            {slower ? <span className="caption"> slower</span> : null}
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                    ) : null}
                  </tr>
                );
              })}
              <tr>
                <td className="g-strong">Total</td>
                <td />
                <td />
                <td className="r g-strong nowrap">{fmtBytes(totalBase)}</td>
                <td className="r g-strong nowrap">{fmtBytes(totalCand)}</td>
                <td className="r g-strong">{fmtChange(totalBase, totalCand)}</td>
                <td className="r g-strong nowrap">
                  {fmtRatio(totalBaseUnc, totalBase)} → {fmtRatio(totalCandUnc, totalCand)}
                </td>
                {hasScans ? <td /> : null}
              </tr>
            </tbody>
          </table>
        </Card>
        <p className="caption muted">Highlighted rows were changed in this trial. Unchanged columns can still move when the sorting key reorders rows.</p>
      </section>

      {!r.queries.length ? <NewIndexes trial={trial} result={r} /> : null}

      {r.queries.length || scanCols.length ? (
        <section className="stack">
          <h2 className="card-title">Query performance</h2>
          <div className="grid-auto" style={vars({ min: 420, gap: 24 })}>
            {top.length ? (
              <Chart
                type="bar"
                title="Latency per query"
                subtitle={`Median of ${trial.request.runsPerQuery} runs${top.length < measured.length ? `, ${top.length} most frequent queries` : ''}`}
                categoryLabel="Query"
                categories={top.map((q) => q.label)}
                series={[
                  { name: 'Baseline', values: top.map((q) => q.baseMs ?? 0) },
                  { name: 'Candidate', values: top.map((q) => q.candMs ?? 0) },
                ]}
                format={fmtMs}
                height={220}
              />
            ) : null}
            {scanCols.length ? (
              <Chart
                type="bar"
                title="Scan time, changed columns"
                subtitle="Median full read"
                categoryLabel="Column"
                categories={scanCols.map((c) => c.name)}
                series={[
                  { name: 'Baseline', values: scanCols.map((c) => c.baseScanMs ?? 0) },
                  { name: 'Candidate', values: scanCols.map((c) => c.candScanMs ?? 0) },
                ]}
                format={fmtMs}
                height={220}
              />
            ) : null}
          </div>
          {r.queries.length ? <NewIndexes trial={trial} result={r} /> : null}
          {r.queries.length ? (
            <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, ...vars({ gap: 12 }) }}>
              {r.queries.map((q) => {
                const speed = q.baseMs != null && q.candMs != null ? fmtSpeed(q.baseMs, q.candMs) : null;
                const bad = !!speed && speed.slower && q.candMs! > q.baseMs! * 1.05;
                return (
                  <li key={q.label} className={`card card-row${bad ? ' is-worse' : ''}`}>
                    <div className="row" style={{ alignItems: 'baseline', flexWrap: 'nowrap', ...vars({ gap: 12 }) }}>
                      <span className="g-count g-num" style={{ flex: 'none' }}>
                        {q.label}
                      </span>
                      <span className="g-sql g-sql--full">{q.query}</span>
                    </div>
                    <div className="facts-row">
                      <dl className="facts">
                        <div>
                          <dt className="eyebrow">Share of workload</dt>
                          <dd>{fmtPct(q.share, 0)}</dd>
                        </div>
                        <div>
                          <dt className="eyebrow">Baseline</dt>
                          <dd>{fmtMs(q.baseMs)}</dd>
                        </div>
                        <div>
                          <dt className="eyebrow">Candidate</dt>
                          <dd className="strong">{fmtMs(q.candMs)}</dd>
                        </div>
                        <div>
                          <dt className="eyebrow">Rows read</dt>
                          <dd>{q.baseReadRows != null && q.candReadRows != null ? `${fmtRows(q.baseReadRows)} → ${fmtRows(q.candReadRows)}` : '—'}</dd>
                        </div>
                        {q.baseExplain?.granulesRead != null && q.candExplain?.granulesRead != null ? (
                          <div>
                            <dt className="eyebrow">Granules read</dt>
                            <dd>
                              {fmtInt(q.baseExplain.granulesRead)} → {fmtInt(q.candExplain.granulesRead)}
                              {q.candExplain.granulesTotal ? <span className="muted"> of {fmtInt(q.candExplain.granulesTotal)}</span> : null}
                            </dd>
                          </div>
                        ) : null}
                      </dl>
                      {q.error ? (
                        <span className="status body-sm g-wrap">
                          <StatusDot tone="muted" />
                          Not run: {q.error}
                        </span>
                      ) : speed ? (
                        <span className="status body-sm" style={{ fontWeight: 700 }}>
                          <StatusDot tone={bad ? 'error' : speed.same ? 'muted' : 'success'} />
                          {speed.text}
                        </span>
                      ) : null}
                    </div>
                    {q.baseExplain || q.candExplain ? <QueryPlans base={q.baseExplain} cand={q.candExplain} /> : null}
                  </li>
                );
              })}
            </ol>
          ) : null}
        </section>
      ) : null}

      {r.alterDdl ? (
        <section className="stack" style={vars({ gap: 12 })}>
          <h2 className="subhead" style={{ overflowWrap: 'anywhere' }}>
            Production · {trial.database}.{trial.table}
          </h2>
          <p className="body-sm muted">
            The statements that bring the production table to the candidate's schema. Granulee does not run them.
          </p>
          <CodeBlock code={r.alterDdl} copyLabel="Copy SQL" />
        </section>
      ) : null}

      <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
        <div className="stack" style={vars({ gap: 12 })}>
          <h2 className="subhead" style={{ overflowWrap: 'anywhere' }}>
            Baseline · {trial.scratchDatabase}.{trial.baseTable}
          </h2>
          <CodeBlock code={r.baseDdl} />
        </div>
        <div className="stack" style={vars({ gap: 12 })}>
          <h2 className="subhead" style={{ overflowWrap: 'anywhere' }}>
            Candidate · {trial.scratchDatabase}.{trial.candTable}
          </h2>
          <CodeBlock code={r.candDdl} />
        </div>
      </section>
    </>
  );
}
