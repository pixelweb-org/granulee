import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Trial, TrialMetric, TrialResult } from '../api/types';
import { useApi, usePolling } from '../hooks/useApi';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { BytesChart } from '../components/BytesChart';
import { Chart } from '../components/Chart';
import { CodeBlock } from '../components/CodeBlock';
import { PageHeader } from '../components/PageHeader';
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
          {r.queries.length ? (
            <Card table>
              <table className="g-tbl">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Query (normalized)</th>
                    <th className="r">Share</th>
                    <th className="r">Baseline</th>
                    <th className="r">Candidate</th>
                    <th className="r">Rows read</th>
                    <th>Result</th>
                  </tr>
                </thead>
                <tbody>
                  {r.queries.map((q) => {
                    const speed = q.baseMs != null && q.candMs != null ? fmtSpeed(q.baseMs, q.candMs) : null;
                    const bad = !!speed && speed.slower && q.candMs! > q.baseMs! * 1.05;
                    return (
                      <tr key={q.label} className={bad ? 'is-changed' : undefined}>
                        <td>{q.label}</td>
                        <td className="g-mono" style={{ maxWidth: 460, minWidth: 240, overflowWrap: 'anywhere' }}>
                          {q.query}
                        </td>
                        <td className="r">{fmtPct(q.share, 0)}</td>
                        <td className="r nowrap">{fmtMs(q.baseMs)}</td>
                        <td className="r g-strong nowrap">{fmtMs(q.candMs)}</td>
                        <td className="r nowrap">
                          {q.baseReadRows != null && q.candReadRows != null ? `${fmtRows(q.baseReadRows)} → ${fmtRows(q.candReadRows)}` : '—'}
                        </td>
                        <td className="caption ink">
                          {q.error ? (
                            <span className="status" style={{ overflowWrap: 'anywhere' }}>
                              <StatusDot tone="muted" />
                              Not run: {q.error}
                            </span>
                          ) : speed ? (
                            <span className="status nowrap">
                              {bad ? <StatusDot tone="error" /> : null}
                              {speed.text}
                            </span>
                          ) : (
                            '—'
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </Card>
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
