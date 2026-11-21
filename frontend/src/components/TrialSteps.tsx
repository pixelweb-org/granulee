import { useState } from 'react';
import type { Trial, TrialResult } from '../api/types';
import { fmtBytes, fmtBytesPair, fmtChange, fmtInt, fmtMsPair, fmtPct, fmtRows } from '../lib/format';
import { clauseOf, refName, stepName, unchangedSentence, type Guide, type OverviewItem, type StepKey } from '../lib/trialGuide';
import { vars } from '../lib/style';
import { BytesChart } from './BytesChart';
import { Choice } from './Button';
import { CodeBlock } from './CodeBlock';

// ----- Step 1: Overview -----

function Items({ items, empty, onGo }: { items: OverviewItem[]; empty: string; onGo: (step: StepKey, query?: string) => void }) {
  if (!items.length) return <p className="body-sm muted tg-item">{empty}</p>;
  return (
    <>
      {items.map((item) => {
        const text = (
          <span className="tg-item__text">
            <span className="tg-item__title">{item.title}</span>
            <span className="g-num tg-item__detail">{item.detail}</span>
          </span>
        );
        return item.step ? (
          <button type="button" key={item.key} className="g-rowbtn tg-item" onClick={() => onGo(item.step!, item.query)}>
            {text}
            <span className="g-act tg-item__to">{stepName(item.step)}</span>
          </button>
        ) : (
          <div key={item.key} className="tg-item">
            {text}
          </div>
        );
      })}
    </>
  );
}

/** Everything the trial measured, sorted into what got better, what got worse and what cannot be told apart. */
export function TrialOverview({ guide, onGo }: { guide: Guide; onGo: (step: StepKey, query?: string) => void }) {
  const band = `±${fmtPct(guide.noise.band, 0)}`;
  return (
    <section className="tg-cards" aria-label={`The candidate against ${refName(guide.cmp)}`}>
      <div className="card tg-card">
        <h2 className="card-title tg-heading">
          <span aria-hidden="true" className="tg-dot tg-dot--lg" style={{ background: 'var(--verde)' }} />
          Better
        </h2>
        <Items items={guide.better} empty="Nothing came out clearly better." onGo={onGo} />
      </div>
      <div className="card tg-card">
        <h2 className="card-title tg-heading">
          <span aria-hidden="true" className="tg-dot tg-dot--lg" style={{ background: 'var(--erro)' }} />
          Worse
        </h2>
        <Items items={guide.worse} empty="Nothing came out clearly worse." onGo={onGo} />
      </div>
      <div className="card tg-card">
        <h2 className="card-title tg-heading">
          <span aria-hidden="true" className="tg-dot tg-dot--lg" style={{ background: 'var(--cinza-texto)' }} />
          No clear difference
        </h2>
        <p className="body-sm muted">
          {guide.noise.measured == null
            ? `Timings within ${band} of each other: this trial kept no single runs to measure the server's noise from.`
            : guide.noise.measured >= guide.noise.band
              ? `Inside the run-to-run noise of this server, about ${band} on timings.`
              : `Timings within ${band} of each other. Replayed queries moved about ±${fmtPct(guide.noise.measured, 0)} from run to run on this server; inserts and merges are timed once.`}{' '}
          Sizes and counts are here when they moved by less than 5%.
        </p>
        {guide.neutral.length ? (
          <ul className="body-sm g-num tg-pairs">
            {guide.neutral.map((n) => (
              <li key={n.label}>
                <span>{n.label}</span>
                <span className="muted">{n.value}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="body-sm muted tg-item">Every measure moved clearly.</p>
        )}
      </div>
    </section>
  );
}

// ----- Step 2: What changed -----

/** One card per change, with what it did to the column. Then what stayed the same, and the tables as they were built. */
export function TrialChanges({ trial, result: r, guide }: { trial: Trial; result: TrialResult; guide: Guide }) {
  const scale = r.rows > 0 ? r.totalRows / r.rows : 1;
  const same = unchangedSentence(trial, r);
  return (
    <section className="stack">
      <h2 className="card-title">What the candidate changes</h2>
      {!guide.changes.length ? (
        <p className="body-sm muted">
          Nothing: the candidate has the schema of today's table. This trial is about what the other steps show
          {r.plainDdl ? ', and how far today’s table already is from plain defaults' : ''}.
        </p>
      ) : null}
      {guide.changes.map((c) => {
        const col = c.column;
        const p = col && col.baseBytes > 0 ? (col.candBytes - col.baseBytes) / col.baseBytes : null;
        return (
          <div key={c.name} className="card tg-change">
            <div className="tg-change__text">
              <span className="tg-title g-wrap">{c.name}</span>
              <span className="g-mono g-wrap tg-change__from">{c.from}</span>
              <span className="g-mono g-wrap tg-change__to">{c.to}</span>
            </div>
            {col ? (
              <dl className="facts">
                <div>
                  <dt className="eyebrow">Size</dt>
                  <dd className="g-num strong">{fmtBytesPair(col.baseBytes, col.candBytes)}</dd>
                </div>
                <div>
                  <dt className="eyebrow">Change</dt>
                  <dd className="g-num strong tg-outcome">
                    <span
                      aria-hidden="true"
                      className="tg-dot"
                      style={{ background: p == null || Math.abs(p) < 0.0005 ? 'var(--cinza-texto)' : p < 0 ? 'var(--verde)' : 'var(--erro)' }}
                    />
                    {fmtChange(col.baseBytes, col.candBytes)}
                  </dd>
                </div>
                <div>
                  <dt className="eyebrow">Full scan</dt>
                  <dd className="g-num">{col.baseScanMs != null && col.candScanMs != null ? fmtMsPair(col.baseScanMs, col.candScanMs) : 'not timed'}</dd>
                </div>
                <div>
                  <dt className="eyebrow">On production</dt>
                  <dd className="g-num">about {fmtBytes(col.baseBytes * scale)} today</dd>
                </div>
              </dl>
            ) : null}
          </div>
        );
      })}
      {same ? <p className="body-sm muted">{same}</p> : null}
      <details className="tg-more">
        <summary className="body-sm g-act">CREATE TABLE statements of the trial tables</summary>
        <div className="grid-auto" style={{ marginTop: 16, ...vars({ min: 420, gap: 24 }) }}>
          <div className="stack" style={vars({ gap: 12 })}>
            <h3 className="subhead g-wrap">
              Today · {trial.scratchDatabase}.{trial.baseTable}
            </h3>
            <CodeBlock code={r.baseDdl} />
          </div>
          <div className="stack" style={vars({ gap: 12 })}>
            <h3 className="subhead g-wrap">
              Candidate · {trial.scratchDatabase}.{trial.candTable}
            </h3>
            <CodeBlock code={r.candDdl} />
          </div>
          {r.plainDdl ? (
            <div className="stack" style={vars({ gap: 12 })}>
              <h3 className="subhead g-wrap">
                Plain defaults · {trial.scratchDatabase}.{trial.baseTable.replace(/_base$/, '_plain')}
              </h3>
              <CodeBlock code={r.plainDdl} />
            </div>
          ) : null}
        </div>
      </details>
    </section>
  );
}

// ----- Step 3: Size -----

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
      {[grew.length ? `${columns(grew.length)} grew by ${fmtBytes(sum(grew))}` : null, shrank.length ? `${columns(shrank.length)} shrank by ${fmtBytes(sum(shrank))}` : null]
        .filter(Boolean)
        .join(', ')}
      . Columns that repeat along the old order lose most; those that follow the new one gain.
      {touched ? ' For a column whose type or codec changed as well, this trial cannot tell the two effects apart.' : ''}
    </p>
  );
}

/** A change in size as a bar from the middle: left and green for smaller, right and red for larger. A full half is ±100%. */
function ChangeBar({ from, to }: { from: number | null | undefined; to: number }) {
  const p = from != null && from > 0 ? (to - from) / from : null;
  const flat = p == null || Math.abs(p) < 0.0005;
  return (
    <div className="tg-change-cell">
      <div className="tg-div">
        {p != null ? (
          <div
            aria-hidden="true"
            className="tg-div__bar"
            style={{ background: flat ? 'var(--cinza-texto)' : p < 0 ? 'var(--verde)' : 'var(--erro)', width: `${(Math.min(Math.abs(p), 1) * 100) / 2}%`, ...(p < 0 ? { right: '50%' } : { left: '50%' }) }}
          />
        ) : null}
      </div>
      <span className="g-num tg-change-cell__pct">{p == null ? '—' : flat ? '0%' : fmtChange(from!, to)}</span>
    </div>
  );
}

const TOP = 10;

/** Every column of the three tables by size, and how the candidate's compares with the reference. */
export function TrialSize({ trial, result: r, guide }: { trial: Trial; result: TrialResult; guide: Guide }) {
  const cols = r.columns;
  const defaults = guide.cmp === 'defaults';
  const hasPlain = cols.some((c) => c.plainBytes != null);
  const changed = cols.filter((c) => c.candidate != null);
  const newKey = guide.changes.some((c) => c.kind === 'key');
  const [filter, setFilter] = useState<'changed' | 'top' | 'all'>(changed.length && !newKey ? 'changed' : 'top');
  const shown = cols.filter((c, i) => (filter === 'all' ? true : filter === 'top' ? i < TOP || c.candidate != null : c.candidate != null));
  const largest = [...cols].sort((a, b) => Math.max(b.baseBytes, b.candBytes) - Math.max(a.baseBytes, a.candBytes)).slice(0, 8);
  const sum = (f: (c: (typeof cols)[number]) => number) => cols.reduce((a, c) => a + f(c), 0);
  const total = { plain: sum((c) => c.plainBytes ?? 0), today: sum((c) => c.baseBytes), cand: sum((c) => c.candBytes) };
  const totalRef = defaults ? total.plain : total.today;
  return (
    <section className="stack">
      <RowOrderNote result={r} />
      <BytesChart
        type="bar"
        title={`Compressed size, ${largest.length === cols.length ? 'every column' : `${largest.length} largest columns`}`}
        subtitle={`On disk for ${fmtRows(r.rows)} rows`}
        categoryLabel="Column"
        categories={largest.map((c) => c.name)}
        series={[
          ...(defaults && hasPlain ? [{ name: 'Plain defaults', values: largest.map((c) => c.plainBytes ?? 0), slot: 3 }] : []),
          { name: 'Today', values: largest.map((c) => c.baseBytes), slot: 1 },
          { name: 'Candidate', values: largest.map((c) => c.candBytes), slot: 2 },
        ]}
        height={220}
      />
      <div className="tg-bar">
        <div role="group" aria-label="Which columns to show" className="row">
          {changed.length ? (
            <Choice pressed={filter === 'changed'} onClick={() => setFilter('changed')}>
              Changed {fmtInt(changed.length)}
            </Choice>
          ) : null}
          {cols.length > TOP ? (
            <Choice pressed={filter === 'top'} onClick={() => setFilter('top')}>
              {TOP} largest
            </Choice>
          ) : null}
          <Choice pressed={filter === 'all' || (filter === 'top' && cols.length <= TOP)} onClick={() => setFilter('all')}>
            All {fmtInt(cols.length)}
          </Choice>
        </div>
        <span className="caption muted">
          Change is the candidate against {refName(guide.cmp)}. Sizes from system.columns{trial.request.optimizeFinal ? ' after a full merge' : ''}.
        </span>
      </div>
      <div className="card card-table">
        <table className="g-tbl" style={{ minWidth: hasPlain ? 900 : 780 }}>
          <thead>
            <tr>
              <th>Column</th>
              <th>Definition</th>
              {hasPlain ? <th className="r">Plain defaults</th> : null}
              <th className="r">Today</th>
              <th className="r">Candidate</th>
              <th style={{ width: '24%' }}>Change</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((c) => (
              <tr key={c.name} className={c.candidate != null ? 'is-touched' : undefined}>
                <td className="g-strong">{c.name}</td>
                <td className="g-mono">{c.candidate ?? c.baseline}</td>
                {hasPlain ? <td className="r nowrap">{c.plainBytes != null ? fmtBytes(c.plainBytes) : '—'}</td> : null}
                <td className="r nowrap">{fmtBytes(c.baseBytes)}</td>
                <td className="r g-strong nowrap">{fmtBytes(c.candBytes)}</td>
                <td>
                  <ChangeBar from={defaults ? c.plainBytes : c.baseBytes} to={c.candBytes} />
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td className="g-strong" colSpan={2}>
                Total, {fmtInt(cols.length)} {cols.length === 1 ? 'column' : 'columns'}
              </td>
              {hasPlain ? <td className="r nowrap">{fmtBytes(total.plain)}</td> : null}
              <td className="r nowrap">{fmtBytes(total.today)}</td>
              <td className="r g-strong nowrap">{fmtBytes(total.cand)}</td>
              <td className="g-strong g-num" style={{ textAlign: 'right' }}>
                {Math.abs(total.cand - totalRef) < totalRef * 0.0005 ? '0%' : fmtChange(totalRef, total.cand)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      {changed.length ? <p className="caption muted">Tinted rows were changed in this trial; their definition is the candidate's.</p> : null}
    </section>
  );
}
