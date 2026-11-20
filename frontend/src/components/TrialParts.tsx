import { useState, type ReactNode } from 'react';
import type { Trial, TrialStep } from '../api/types';
import { api } from '../api/client';
import { useAction, useApi } from '../hooks/useApi';
import { fmtBytes } from '../lib/format';
import { vars } from '../lib/style';
import { Button } from './Button';
import { Card } from './Card';
import { CodeBlock } from './CodeBlock';
import { Dialog } from './Dialog';
import { InlineError } from './States';
import { StatusDot, type Tone } from './StatusDot';

const STEP_TONE: Record<TrialStep['state'], Tone> = { done: 'success', running: 'active', pending: 'hollow', failed: 'error' };
const STEP_WORD: Record<TrialStep['state'], string> = { done: 'Done', running: 'Running', pending: 'Next', failed: 'Failed' };

export function trialTone(t: Trial): Tone {
  return t.status === 'completed' ? 'success' : t.status === 'failed' ? 'error' : t.status === 'running' ? 'active' : 'muted';
}

export function trialStatusLabel(t: Trial): string {
  if (t.status === 'completed') return 'Completed';
  if (t.status === 'running') return 'Running';
  if (t.status === 'cancelled') return 'Cancelled';
  return t.error ? `Failed: ${t.error}` : 'Failed';
}

/** The progress card of a running trial. */
export function TrialProgressCard({ trial, onChanged, titleTo }: { trial: Trial; onChanged: () => void; titleTo?: ReactNode }) {
  const cancel = useAction();
  return (
    <Card as="section">
      <div className="between">
        <div className="stack" style={vars({ gap: 4 })}>
          <p className="eyebrow">Running · #{trial.id}</p>
          <h2 className="subhead">{titleTo ?? `${trial.table} · ${trial.name}`}</h2>
        </div>
        <Button
          variant="link"
          busy={cancel.busy}
          onClick={async () => {
            if (await cancel.run(() => api.cancelTrial(trial.id))) onChanged();
          }}
        >
          Cancel trial
        </Button>
      </div>
      <div className="progress" role="progressbar" aria-label="Trial progress" aria-valuenow={Math.round(trial.progress)} aria-valuemin={0} aria-valuemax={100}>
        <div style={{ width: `${Math.max(0, Math.min(100, trial.progress))}%` }} />
      </div>
      <ol className="body-sm grid-auto" style={{ margin: 0, padding: 0, listStyle: 'none', ...vars({ min: 200, gap: '8px 24px' }) }}>
        {trial.steps.map((s, i) => (
          <li key={i} className={s.state === 'pending' ? 'muted' : undefined} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <StatusDot tone={STEP_TONE[s.state]} />
            <span>
              {STEP_WORD[s.state]}: {s.label}
            </span>
          </li>
        ))}
      </ol>
      <InlineError message={cancel.error} />
    </Card>
  );
}

/** Confirms removing a trial and shows the two DROP statements it runs. */
export function RemoveTrialDialog({ trial, onClose, onRemoved }: { trial: Trial; onClose: () => void; onRemoved: () => void }) {
  const remove = useAction();
  return (
    <Dialog
      title={`Remove trial #${trial.id}?`}
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            danger
            busy={remove.busy}
            onClick={async () => {
              if (await remove.run(() => api.deleteTrial(trial.id))) onRemoved();
            }}
          >
            Remove and drop tables
          </Button>
        </>
      }
    >
      <p className="body muted">
        This drops both trial tables ({fmtBytes(trial.scratchBytes)}) and deletes the result for “{trial.name}”. It can’t be undone.
      </p>
      <CodeBlock code={`DROP TABLE IF EXISTS ${trial.scratchDatabase}.${trial.baseTable} SYNC;\nDROP TABLE IF EXISTS ${trial.scratchDatabase}.${trial.candTable} SYNC;`} />
      <InlineError message={remove.error} />
    </Dialog>
  );
}

/** State holder for "which trial is being removed". */
export function useRemoveTrial() {
  return useState<Trial | null>(null);
}

/**
 * Review before anything touches production: shows the statements computed against the table as it is now.
 * ALTERs can be run from here; a rebuild is only shown.
 */
export function ApplyTrialDialog({ trial, onClose, onStarted }: { trial: Trial; onClose: () => void; onStarted: () => void }) {
  const { data: plan, error } = useApi(() => api.applyPlan(trial.id), [trial.id]);
  const apply = useAction();
  const table = `${trial.database}.${trial.table}`;
  const runnable = plan?.kind === 'alter';
  return (
    <Dialog
      wide
      title={`Apply to ${table}?`}
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose}>
            {runnable ? 'Cancel' : 'Close'}
          </Button>
          {runnable ? (
            <Button
              busy={apply.busy}
              onClick={async () => {
                if (await apply.run(() => api.applyTrial(trial.id, plan.sql))) onStarted();
              }}
            >
              {plan.statements === 1 ? 'Run 1 statement' : `Run ${plan.statements} statements`}
            </Button>
          ) : null}
        </>
      }
    >
      {error ? <InlineError message={error} /> : null}
      {!plan && !error ? <p className="body muted">Reading the table as it is now…</p> : null}
      {plan?.kind === 'none' ? <p className="body muted">{table} already matches the candidate of this trial. There is nothing to run.</p> : null}
      {plan?.kind === 'alter' ? (
        <>
          <p className="body muted">
            Review what will run on {plan.sourceName}, one statement after the other, against the production table. Type changes rewrite the column in every part and can
            take a while; a statement that has run is not undone if a later one fails.
          </p>
          <CodeBlock code={plan.sql} copyLabel="Copy SQL" />
        </>
      ) : null}
      {plan?.kind === 'rebuild' ? (
        <>
          <p className="body muted">
            This candidate changes a key, so the table has to be rebuilt. Granulee does not run that: copying every row needs writes paused and enough free disk. Run the
            script yourself when the time is right.
          </p>
          <CodeBlock code={plan.sql} copyLabel="Copy SQL" />
        </>
      ) : null}
      <InlineError message={apply.error} />
    </Dialog>
  );
}

/** Confirms OPTIMIZE … FINAL on the production table after the candidate has been applied. */
export function OptimizeTrialDialog({ trial, onClose, onStarted }: { trial: Trial; onClose: () => void; onStarted: () => void }) {
  const run = useAction();
  const table = `${trial.database}.${trial.table}`;
  return (
    <Dialog
      wide
      title={`Run OPTIMIZE FINAL on ${table}?`}
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={run.busy}
            onClick={async () => {
              if (await run.run(() => api.optimizeTrial(trial.id))) onStarted();
            }}
          >
            Run OPTIMIZE FINAL
          </Button>
        </>
      }
    >
      <p className="body muted">
        This merges every partition of {table} on {trial.sourceName} into one part each, so data written before the change is rewritten with the new codecs. It reads and
        writes the whole table: expect heavy disk and CPU load for a while, and free space for a second copy of the largest partition.
      </p>
      <CodeBlock code={`OPTIMIZE TABLE ${table} FINAL;`} />
      <InlineError message={run.error} />
    </Dialog>
  );
}
