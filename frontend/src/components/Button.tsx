import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';

type Variant = 'primary' | 'secondary';

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className'> {
  variant?: Variant;
  /** Renders a router link instead of a button. */
  to?: string;
  danger?: boolean;
  busy?: boolean;
  stretch?: boolean;
  children: ReactNode;
}

export function Button({ variant = 'primary', to, danger, busy, stretch, children, disabled, style, ...rest }: Props) {
  const cls = `ee-btn ee-btn-${variant}${danger ? ' ee-btn-danger' : ''}`;
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
