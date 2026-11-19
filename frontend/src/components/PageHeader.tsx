import type { ReactNode } from 'react';
import { vars } from '../lib/style';

interface Props {
  eyebrow?: ReactNode;
  title: ReactNode;
  /** Sits next to the title, e.g. the engine name. */
  titleAside?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({ eyebrow, title, titleAside, description, actions }: Props) {
  return (
    <header className="page-header">
      <div className="page-header-text">
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <div className="row" style={vars({ gap: 12 })}>
          <h1 className="display-md">{title}</h1>
          {titleAside}
        </div>
        {description ? <div className="body muted">{description}</div> : null}
      </div>
      {actions ? (
        <div className="row" style={vars({ gap: 16 })}>
          {actions}
        </div>
      ) : null}
    </header>
  );
}
