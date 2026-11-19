import type { ReactNode } from 'react';
import type { SourceStatus } from '../api/types';
import { fmtAgo } from '../lib/format';

export type Tone = 'success' | 'error' | 'warning' | 'muted' | 'active' | 'hollow';

export function StatusDot({ tone }: { tone: Tone }) {
  return <span aria-hidden="true" className={`dot dot-${tone}`} />;
}

export function Status({ tone, children, className }: { tone: Tone; children: ReactNode; className?: string }) {
  return (
    <span className={`status${className ? ` ${className}` : ''}`}>
      <StatusDot tone={tone} />
      {children}
    </span>
  );
}

export function sourceTone(status: SourceStatus): Tone {
  return status.state === 'healthy' ? 'success' : status.state === 'unreachable' ? 'error' : 'muted';
}

/** Short label for cards: "Healthy · 3 replicas", "Unreachable · last seen 2 h ago". */
export function sourceStatusShort(status: SourceStatus): string {
  if (status.state === 'healthy') return status.replicas && status.replicas > 1 ? `Healthy · ${status.replicas} replicas` : 'Healthy';
  if (status.state === 'unreachable') {
    const ago = fmtAgo(status.lastSeenAt);
    return ago ? `Unreachable · last seen ${ago}` : 'Unreachable';
  }
  return 'Not checked yet';
}

/** Long label for the sources table: "Unreachable: connection timed out". */
export function sourceStatusLong(status: SourceStatus): string {
  if (status.state === 'unreachable') return status.message ? `Unreachable: ${status.message}` : 'Unreachable';
  return sourceStatusShort(status);
}
