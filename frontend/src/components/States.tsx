import type { ReactNode } from 'react';
import { Button } from './Button';

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="card" style={{ alignItems: 'flex-start', padding: 32 }}>
      <h2 className="card-title">{title}</h2>
      {children ? <div className="body muted">{children}</div> : null}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="card" role="alert" style={{ alignItems: 'flex-start' }}>
      <p className="status body">
        <span aria-hidden="true" className="dot dot-error" />
        Something went wrong
      </p>
      <p className="body-sm muted" style={{ overflowWrap: 'anywhere' }}>{message}</p>
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <p className="body muted" role="status">
      {label}
    </p>
  );
}

/** Inline error line under a button row. */
export function InlineError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="status body-sm" role="alert" style={{ overflowWrap: 'anywhere' }}>
      <span aria-hidden="true" className="dot dot-error" />
      {message}
    </p>
  );
}
