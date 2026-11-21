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
import { Status, type Tone } from '../components/StatusDot';
import { TrialQueries } from '../components/TrialQueries';
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
  fmtRatio,
  fmtRatioValue,
  fmtRows,
  fmtSignedBytes,
  fmtTime,
} from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

/** "ORDER BY (…)" or "PARTITION BY …" of a trial table's CREATE statement. */
function clauseOf(ddl: string, keyword: 'ORDER BY' | 'PARTITION BY'): string {
  const line = ddl.split('\n').find((l) => l.startsWith(`${keyword} `));
  return line ? line.slice(keyword.length + 1).trim() : '';
}

/**
 * A new sorting or partition key stores every row somewhere else, so columns the trial did not touch change size too.
 * This says so, and by how much, where it happened.
 */
function RowOrderNote({ result: r }: { result: TrialResult }) {
  const order = [clauseOf(r.baseDdl, 'ORDER BY'), clauseOf(r.candDdl, 'ORDER BY')];
  const partition = [clauseOf(r.baseDdl, 'PARTITION BY'), clauseOf(r.candDdl, 'PARTITION BY')];
  const newOrder = order[0] !== order[1];
  const newPartition = partition[0] !== partition[1];
  if (!newOrder && !newPartition) return null;
  // A column counts as moved when it changed by more than a rounding difference between two merges.
  const untouched = r.columns.filter((c) => c.candidate == null && c.baseBytes > 0);
  const grew = untouched.filter((c) => c.candBytes > c.baseBytes * 1.02);
  const shrank = untouched.filter((c) => c.candBytes < c.baseBytes * 0.98);
  if (!grew.length && !shrank.length) return null;
  const sum = (list: typeof untouched) => list.reduce((a, c) => a + Math.abs(c.candBytes - c.baseBytes), 0);
  const columns = (n: number) => `${fmtInt(n)} ${n === 1 ? 'column' : 'columns'}`;
  const touched = r.columns.some((c) => c.candidate != null);
  return (
    <p className="g-notice" role="note">
      {newOrder ? (
        <>
          The sorting key changes from <span className="g-mono">{order[0]}</span> to <span className="g-mono">{order[1]}</span>
          {newPartition ? ', and the partition key changes too' : ''}.{' '}
        </>
      ) : (
        <>
          The partition key changes from <span className="g-mono">{partition[0] || 'none'}</span> to <span className="g-mono">{partition[1] || 'none'}</span>.{' '}
        </>
      )}
      That stores the rows in a different order, and a column compresses by how alike its neighbouring values are. So columns this trial left as they are
      change size as well:{' '}
      {[
        grew.length ? `${columns(grew.length)} grew by ${fmtBytes(sum(grew))}` : null,
        shrank.length ? `${columns(shrank.length)} shrank by ${fmtBytes(sum(shrank))}` : null,
      ]
        .filter(Boolean)
        .join(', ')}
      . Columns that repeat along the old order lose most; those that follow the new one gain.
      {touched ? ' For a column whose type or codec changed as well, this trial cannot tell the two effects apart.' : ''}
    </p>
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
  const { id = '', tab: tabParam } = useParams();
  const tab: Tab = TABS.includes(tabParam as Tab) ? (tabParam as Tab) : 'summary';
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

      {done ? (
        <nav className="g-tabs" aria-label="Trial sections" style={{ marginTop: -8 }}>
          <Link to={routes.trial(trial.id)} aria-current={tab === 'summary' ? 'page' : undefined}>
            Summary
          </Link>
          <Link to={routes.trial(trial.id, 'columns')} aria-current={tab === 'columns' ? 'page' : undefined}>
            Columns <span className="g-count g-num">{fmtInt(r.columns.length)}</span>
          </Link>
          <Link to={routes.trial(trial.id, 'queries')} aria-current={tab === 'queries' ? 'page' : undefined}>
            Queries <span className="g-count g-num">{fmtInt(r.queries.length)}</span>
          </Link>
          <Link to={routes.trial(trial.id, 'schema')} aria-current={tab === 'schema' ? 'page' : undefined}>
            Schema
          </Link>
        </nav>
      ) : null}

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

      {done && tab === 'summary' ? <Summary trial={trial} result={r} /> : null}
      {done && tab === 'columns' ? <Columns trial={trial} result={r} /> : null}
      {done && tab === 'queries' ? <TrialQueries trial={trial} result={r} /> : null}
      {done && tab === 'schema' ? <Schema trial={trial} result={r} /> : null}

      {trial.log.length && (!done || tab === 'summary') ? (
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

const TABS = ['summary', 'columns', 'queries', 'schema'] as const;
type Tab = (typeof TABS)[number];

/** What the candidate does differently from the baseline: keys, columns, indexes. */
function Changes({ trial, result: r }: { trial: Trial; result: TrialResult }) {
  const order = [clauseOf(r.baseDdl, 'ORDER BY'), clauseOf(r.candDdl, 'ORDER BY')];
  const partition = [clauseOf(r.baseDdl, 'PARTITION BY'), clauseOf(r.candDdl, 'PARTITION BY')];
  const columns = r.columns.filter((c) => c.candidate != null);
  const added = trial.request.addIndexes ?? [];
  const dropped = trial.request.dropIndexes ?? [];
  const rows: { what: string; from: string; to: string }[] = [];
  if (order[0] !== order[1]) rows.push({ what: 'Sorting key', from: order[0], to: order[1] });
  if (partition[0] !== partition[1]) rows.push({ what: 'Partition key', from: partition[0] || 'none', to: partition[1] || 'none' });
  columns.forEach((c) => rows.push({ what: `Column ${c.name}`, from: c.baseline, to: c.candidate! }));
  added.forEach((i) => rows.push({ what: `Skip index ${i.name}`, from: 'none', to: `${i.type} on ${i.expression}, granularity ${i.granularity}` }));
  dropped.forEach((name) => rows.push({ what: `Skip index ${name}`, from: 'exists', to: 'dropped' }));
  if (!rows.length) return null;
  return (
    <section className="stack">
      <h2 className="card-title">What the candidate changes</h2>
      <Card table>
        <table className="g-tbl">
          <thead>
            <tr>
              <th>Change</th>
              <th>Baseline</th>
              <th>Candidate</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.what}>
                <td className="g-strong">{row.what}</td>
                <td className="g-mono g-wrap">{row.from}</td>
                <td className="g-mono g-wrap ink">{row.to}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </section>
  );
}

/** The outcome at a glance: the verdict, every measure side by side and what was changed. */
function Summary({ trial, result: r }: { trial: Trial; result: TrialResult }) {
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

      <Changes trial={trial} result={r} />
    </>
  );
}

/** Every column on both tables: size, compression and, where measured, the time of a full read. */
function Columns({ trial, result: r }: { trial: Trial; result: TrialResult }) {
  const cols = r.columns;
  const totalBase = cols.reduce((a, c) => a + c.baseBytes, 0);
  const totalCand = cols.reduce((a, c) => a + c.candBytes, 0);
  const totalBaseUnc = cols.reduce((a, c) => a + c.baseUncompressedBytes, 0);
  const totalCandUnc = cols.reduce((a, c) => a + c.candUncompressedBytes, 0);
  const hasScans = cols.some((c) => c.baseScanMs != null && c.candScanMs != null);
  const scanCols = cols.filter((c) => c.candidate != null && c.baseScanMs != null && c.candScanMs != null);
  return (
    <>
      <RowOrderNote result={r} />
      <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
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
        {scanCols.length ? (
          <Chart
            type="bar"
            title="Scan time, changed columns"
            subtitle={`Median of ${trial.request.runsPerQuery} full reads`}
            categoryLabel="Column"
            categories={scanCols.map((c) => c.name)}
            series={[
              { name: 'Baseline', values: scanCols.map((c) => c.baseScanMs ?? 0) },
              { name: 'Candidate', values: scanCols.map((c) => c.candScanMs ?? 0) },
            ]}
            format={fmtMs}
            height={240}
          />
        ) : null}
      </section>
      <section className="stack">
        <div className="between">
          <h2 className="card-title">Columns side by side</h2>
          <span className="caption muted">
            Sizes from system.columns{trial.request.optimizeFinal ? ' after full merge' : ''}
            {hasScans ? ` · scan = median of ${trial.request.runsPerQuery} full reads` : ''}
          </span>
        </div>
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
        <p className="caption muted">Highlighted rows were changed in this trial.</p>
      </section>
    </>
  );
}

/** The statements: what would bring production to the candidate, and how the two trial tables were created. */
function Schema({ trial, result: r }: { trial: Trial; result: TrialResult }) {
  return (
    <>
      {r.alterDdl ? (
        <section className="stack" style={vars({ gap: 12 })}>
          <h2 className="card-title" style={{ overflowWrap: 'anywhere' }}>
            Production · {trial.database}.{trial.table}
          </h2>
          <p className="body-sm muted">
            The statements that bring the production table to the candidate's schema. “Apply to production” shows them again, computed against the table as
            it is then.
          </p>
          <CodeBlock code={r.alterDdl} copyLabel="Copy SQL" />
        </section>
      ) : (
        <p className="body-sm muted">The candidate has the same schema as the production table, so there is nothing to apply.</p>
      )}
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
