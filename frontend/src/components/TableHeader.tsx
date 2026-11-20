import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { TableDetail } from '../api/types';
import { fmtInt } from '../lib/format';
import { routes } from '../lib/routes';
import { Button } from './Button';
import { CopyButton } from './CodeBlock';
import { PageHeader } from './PageHeader';

/** The header every page of one table shares: name, engine, the two actions and the section tabs. */
export function TableHeader({ t, active, description }: { t: TableDetail; active: 'structure' | 'usage'; description?: ReactNode }) {
  const args = [t.sourceId, t.database, t.name] as const;
  return (
    <>
      <PageHeader
        eyebrow={
          <Link to={routes.explorer(t.sourceId, t.database)}>
            {t.sourceName} / {t.database}
          </Link>
        }
        title={t.name}
        titleAside={<span className="eyebrow cc-badge">{t.engine}</span>}
        description={description}
        actions={
          <>
            <CopyButton text={t.createQuery}>Copy DDL</CopyButton>
            <Button to={routes.newTrial(...args)}>Set up a trial</Button>
          </>
        }
      />
      <nav className="g-tabs" aria-label="Table sections" style={{ marginTop: description ? -8 : -16 }}>
        <Link to={routes.table(...args)} aria-current={active === 'structure' ? 'page' : undefined}>
          Structure
        </Link>
        <Link to={routes.usage(...args)} aria-current={active === 'usage' ? 'page' : undefined}>
          Usage
        </Link>
        <Link to={routes.recommendations(...args)}>
          Recommendations {t.findings > 0 ? <span className="g-count g-num">{fmtInt(t.findings)}</span> : null}
        </Link>
      </nav>
    </>
  );
}
