import { useState, type ReactNode } from 'react';
import type { Trial, TrialStep } from '../api/types';
import { api } from '../api/client';
import { useAction } from '../hooks/useApi';
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
          variant="secondary"
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
