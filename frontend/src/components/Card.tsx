import type { CSSProperties, ReactNode } from 'react';

interface CardProps {
  as?: 'div' | 'section' | 'aside' | 'article' | 'form' | 'nav';
  emphasis?: boolean;
  /** A card that only wraps a wide table: tight padding, scrolls sideways. */
  table?: boolean;
  className?: string;
  style?: CSSProperties;
  children: ReactNode;
  'aria-label'?: string;
}

export function Card({ as: Tag = 'div', emphasis, table, className, children, ...rest }: CardProps) {
  const cls = ['card', emphasis && 'card-em', table && 'card-table', className].filter(Boolean).join(' ');
  return (
    <Tag className={cls} {...rest}>
      {children}
    </Tag>
  );
}

export function StatTile({ label, value, sub, emphasis, small }: { label: string; value: ReactNode; sub?: ReactNode; emphasis?: boolean; small?: boolean }) {
  return (
    <div className={`tile${emphasis ? ' card-em' : ''}`}>
      <span className="caption muted">{label}</span>
      <span className={`${small ? 'card-title' : 'headline'} g-num`}>{value}</span>
      {sub ? <span className="caption muted g-num">{sub}</span> : null}
    </div>
  );
}
