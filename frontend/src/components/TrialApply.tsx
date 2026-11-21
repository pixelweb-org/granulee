import { useState } from 'react';
import { api } from '../api/client';
import type { Trial, TrialResult } from '../api/types';
import { useAction, useApi } from '../hooks/useApi';
import { fmtBytes, fmtBytesPair, fmtClock, fmtDuration, fmtInt, fmtPct, fmtRows, fmtSignedBytes, fmtTime } from '../lib/format';
import { Button } from './Button';
import { CopyButton } from './CodeBlock';
import { InlineError } from './States';
import { Status } from './StatusDot';

const list = (names: string[]) => (names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`);

/** What the statements do to the production table, said from what this trial changed. */
function notes(trial: Trial, r: TrialResult): string[] {
  const scale = r.rows > 0 ? r.totalRows / r.rows : 1;
  const type = (definition: string) => definition.split(' · ')[0];
  const changed = r.columns.filter((c) => c.candidate != null);
  const retyped = changed.filter((c) => type(c.baseline) !== type(c.candidate!));
  const recoded = changed.filter((c) => type(c.baseline) === type(c.candidate!));
  const added = trial.request.addIndexes ?? [];
  const dropped = trial.request.dropIndexes ?? [];
  const out: string[] = [];
  if (retyped.length) {
    out.push(
      `Changing the type of ${list(retyped.map((c) => c.name))} rewrites ${retyped.length === 1 ? 'that column' : 'those columns'} in every part as a mutation: about ${fmtBytes(
        retyped.reduce((a, c) => a + c.baseBytes, 0) * scale,
      )} on production.`,
    );
  }
  if (recoded.length) {
    out.push(
      `${list(recoded.map((c) => c.name))} ${recoded.length === 1 ? 'only changes its codec' : 'only change their codec'}. New parts use it at once, existing parts when they merge.`,
    );
  }
  if (added.length) out.push(`${list(added.map((i) => i.name))} ${added.length === 1 ? 'is' : 'are'} added and then materialized, which reads the indexed columns of every part.`);
  if (dropped.length) out.push(`${list(dropped)} ${dropped.length === 1 ? 'is' : 'are'} dropped: queries stop using ${dropped.length === 1 ? 'it' : 'them'} at once.`);
  out.push('The statements run one after the other. One that has run is not undone if a later one fails.');
  return out;
}

/** How the last apply of this trial went, and OPTIMIZE FINAL after it. */
function ApplyStatus({ trial, onOptimize }: { trial: Trial; onOptimize: () => void }) {
  const a = trial.apply;
  if (!a) return null;
  const table = `${trial.database}.${trial.table}`;
  const o = trial.optimize;
  return (
    <div className="card" style={{ gap: 8, alignItems: 'flex-start' }}>
      <Status tone={a.status === 'completed' ? 'success' : a.status === 'failed' ? 'error' : 'active'} className="subhead">
        {a.status === 'running'
          ? `Applying to ${table} · statement ${Math.min(a.done + 1, a.total)} of ${a.total}`
          : a.status === 'completed'
            ? `Applied to ${table} ${fmtClock(a.finishedAt)}`
            : `Applying to ${table} failed`}
      </Status>
      {a.status === 'failed' ? (
        <>
          {a.error ? <p className="body-sm muted g-wrap">{a.error}</p> : null}
          <p className="caption muted">
            {a.done} of {a.total} statements ran before the error and stay in effect. What is still left is shown below.
          </p>
        </>
      ) : null}
      {a.status === 'running' ? <p className="caption muted">Type changes rewrite the column in every part; this page follows along.</p> : null}
      {a.status === 'completed' ? (
        <>
          {o ? (
            <Status tone={o.status === 'completed' ? 'success' : o.status === 'failed' ? 'error' : 'active'} className="body-sm">
              {o.status === 'running'
                ? `OPTIMIZE FINAL is running, started ${fmtClock(o.startedAt)}`
                : o.status === 'completed'
                  ? `OPTIMIZE FINAL finished ${fmtClock(o.finishedAt)}, after ${fmtDuration(new Date(o.finishedAt ?? o.startedAt).getTime() - new Date(o.startedAt).getTime())}`
                  : 'OPTIMIZE FINAL failed'}
            </Status>
          ) : (
            <p className="caption muted">Parts written before the change keep their old codecs until they are merged. OPTIMIZE FINAL rewrites them now.</p>
          )}
          {o?.status === 'failed' && o.error ? <p className="body-sm muted g-wrap">{o.error}</p> : null}
          {o?.status === 'running' ? (
            <p className="caption muted">Merging the whole table can take long; this page follows along and you can leave it.</p>
          ) : (
            <Button variant="secondary" onClick={onOptimize}>
              {o ? 'Run OPTIMIZE FINAL again' : 'Run OPTIMIZE FINAL'}
            </Button>
          )}
        </>
      ) : null}
    </div>
  );
}

/**
 * The Apply step of a trial: the statements for the production table, computed against the table as it is now,
 * a confirmation before Granulee runs them, and what the change is projected to save.
 */
export function TrialApply({ trial, result: r, onChanged, onOptimize }: { trial: Trial; result: TrialResult; onChanged: () => void; onOptimize: () => void }) {
  const { data: plan, error } = useApi(() => api.applyPlan(trial.id), [trial.id, trial.apply?.status]);
  const apply = useAction();
  const [confirmed, setConfirmed] = useState(false);
  const [logOpen, setLogOpen] = useState(false);
  const table = `${trial.database}.${trial.table}`;
  const running = trial.apply?.status === 'running';
  const delta = r.projectedCandBytes - r.projectedBaseBytes;
  const sample = r.totalRows > 0 ? r.rows / r.totalRows : 1;
  const took = trial.finishedAt ? new Date(trial.finishedAt).getTime() - new Date(trial.createdAt).getTime() : null;
  return (
    <section className="tg-apply">
      <div className="tg-apply__main">
        <h2 className="card-title">Apply to production</h2>
        <ApplyStatus trial={trial} onOptimize={onOptimize} />
        {!plan && !error ? <p className="body-sm muted">Reading {table} as it is now…</p> : null}
        {error ? (
          <>
            <InlineError message={`${table} could not be read just now: ${error}`} />
            {r.alterDdl ? (
              <>
                <p className="body-sm muted">These are the statements as they were when the trial finished. They can only be run from here once the table can be read.</p>
                <pre className="g-code">{r.alterDdl}</pre>
                <div className="row">
                  <CopyButton text={r.alterDdl}>Copy SQL</CopyButton>
                </div>
              </>
            ) : null}
          </>
        ) : null}
        {plan?.kind === 'none' ? (
          <p className="body-sm muted">
            {table} already matches the candidate of this trial. There is nothing to run.
          </p>
        ) : null}
        {plan?.kind === 'alter' ? (
          <>
            <pre className="g-code">{plan.sql}</pre>
            <ul className="body-sm tg-notes">
              {notes(trial, r).map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
            <label className="body-sm tg-check" style={{ border: 0, paddingTop: 0 }}>
              <input type="checkbox" checked={confirmed} disabled={running} onChange={(e) => setConfirmed(e.target.checked)} /> I have read the statements and want
              Granulee to run them on {plan.sourceName}
            </label>
            <div className="row">
              <Button
                busy={apply.busy}
                disabled={!confirmed || running}
                onClick={async () => {
                  if (await apply.run(() => api.applyTrial(trial.id, plan.sql))) {
                    setConfirmed(false);
                    onChanged();
                  }
                }}
              >
                Run on production
              </Button>
              <CopyButton text={plan.sql}>Copy SQL</CopyButton>
            </div>
            <InlineError message={apply.error} />
          </>
        ) : null}
        {plan?.kind === 'rebuild' ? (
          <>
            <p className="body-sm muted">
              This candidate changes a key, so the table has to be rebuilt. Granulee does not run that: copying every row needs writes paused and enough free disk.
              Run the script yourself when the time is right.
            </p>
            <pre className="g-code">{plan.sql}</pre>
            <div className="row">
              <CopyButton text={plan.sql}>Copy SQL</CopyButton>
            </div>
          </>
        ) : null}
      </div>
      <aside className="card tg-apply__aside">
        <h2 className="subhead">Projected for {fmtRows(r.totalRows)} rows</h2>
        <span className="g-num headline">{fmtBytesPair(r.projectedBaseBytes, r.projectedCandBytes)}</span>
        <span className="body-sm g-num muted">
          {fmtSignedBytes(delta)} on disk{sample < 0.999 ? `, scaled up from the ${fmtPct(sample, sample < 0.1 ? 1 : 0)} sample` : ''}
        </span>
        {trial.log.length ? (
          <>
            <button type="button" className="g-rowbtn tg-log" aria-expanded={logOpen} onClick={() => setLogOpen(!logOpen)}>
              <span className="body-sm g-act">{logOpen ? 'Hide' : 'Show'}</span>
              <span className="body-sm muted">
                run log, {fmtInt(trial.log.length)} {trial.log.length === 1 ? 'step' : 'steps'}
                {took != null ? `, ${fmtDuration(took)}` : ''}
              </span>
            </button>
            {logOpen ? (
              <ol className="body-sm g-num muted tg-notes">
                {trial.log.map((l, i) => (
                  <li key={i} className="g-wrap">
                    <span className="ink">{fmtTime(l.at)}</span> {l.message}
                  </li>
                ))}
              </ol>
            ) : null}
          </>
        ) : null}
      </aside>
    </section>
  );
}
