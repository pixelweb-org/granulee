import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useSources } from '../state/sources';
import { fmtClock, fmtUntil } from '../lib/format';
import { routes } from '../lib/routes';
import type { Source } from '../api/types';

const NAV: { to: string; label: string; match: (path: string) => boolean }[] = [
  { to: '/', label: 'Overview', match: (p) => p === '/' },
  { to: '/explorer', label: 'Explorer', match: (p) => p.startsWith('/explorer') || p.startsWith('/tables') },
  { to: '/recommendations', label: 'Recommendations', match: (p) => p.startsWith('/recommendations') },
  { to: '/trials', label: 'Trials', match: (p) => p.startsWith('/trials') },
  { to: '/sources', label: 'Data sources', match: (p) => p.startsWith('/sources') },
];

function scanCaption(sources: Source[]): string {
  const last = sources.map((s) => s.lastScan?.at).filter((v): v is string => !!v).sort().pop();
  const next = sources.map((s) => s.nextScanAt).filter((v): v is string => !!v).sort()[0];
  if (!sources.length) return 'No data sources yet';
  if (!last) return 'Not scanned yet';
  const until = fmtUntil(next);
  return `Last full scan ${fmtClock(last)}${until ? ` · next ${until}` : ''}`;
}

/** The mark: a dark tile, a white G and one orange granule. */
function Logo() {
  return (
    <svg aria-hidden="true" width="32" height="32" viewBox="0 0 64 64">
      <rect x="0" y="0" width="64" height="64" rx="9" fill="#232f3e" />
      <text x="29" y="46" textAnchor="middle" fontFamily="Verdana, Arial, sans-serif" fontWeight="700" fontSize="40" fill="#ffffff">
        G
      </text>
      <rect x="44" y="44" width="12" height="12" rx="2" fill="#ff9900" />
    </svg>
  );
}

export function Layout() {
  const { sources, selectedId, selected, select } = useSources();
  const { pathname } = useLocation();
  const navigate = useNavigate();

  function onSource(id: string) {
    select(id || null);
    // Pages that live under one source follow the selection.
    if (pathname.startsWith('/explorer') || pathname.startsWith('/tables')) navigate(routes.explorer(id || null));
    else if (pathname.startsWith('/recommendations/')) navigate('/recommendations');
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <Link to="/" className="brand">
          <Logo />
          Granulee
        </Link>
        <div className="sidebar-source">
          <label htmlFor="source-select" className="caption muted">
            Data source
          </label>
          <select id="source-select" className="g-select sm" value={selectedId ?? ''} onChange={(e) => onSource(e.target.value)}>
            <option value="">All sources</option>
            {sources.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </div>
        <nav className="g-nav" aria-label="Main">
          {NAV.map((item) => (
            <Link key={item.to} to={item.to} aria-current={item.match(pathname) ? 'page' : undefined}>
              {item.label}
            </Link>
          ))}
        </nav>
        <p className="caption muted sidebar-foot" style={{ padding: '0 12px' }}>
          {scanCaption(selected ? [selected] : sources)}
        </p>
      </aside>
      <main className="main">
        <div className="page">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
