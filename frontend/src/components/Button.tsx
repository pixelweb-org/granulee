import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';

type Variant = 'primary' | 'secondary' | 'dark' | 'link';

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className'> {
  variant?: Variant;
  /** Renders a router link instead of a button. */
  to?: string;
  danger?: boolean;
  busy?: boolean;
  stretch?: boolean;
  small?: boolean;
  children: ReactNode;
}

export function Button({ variant = 'primary', to, danger, busy, stretch, small, children, disabled, style, ...rest }: Props) {
  const cls = `cc-btn cc-btn--${danger ? 'danger' : variant}${small ? ' cc-btn--sm' : ''}`;
  const st = stretch ? { alignSelf: 'stretch', ...style } : style;
  if (to && !disabled) {
    return (
      <Link to={to} className={cls} style={st}>
        {children}
      </Link>
    );
  }
  return (
    <button type="button" className={cls} style={st} disabled={disabled || busy} aria-busy={busy || undefined} {...rest}>
      {children}
    </button>
  );
}

/** One of a row of filter or sort choices: the pressed one is dark, the others outlined. */
export function Choice({ pressed, onClick, children }: { pressed: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" className={`cc-btn cc-btn--sm ${pressed ? 'cc-btn--dark' : 'cc-btn--secondary'}`} aria-pressed={pressed} onClick={onClick}>
      {children}
    </button>
  );
}
