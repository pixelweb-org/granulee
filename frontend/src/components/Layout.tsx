import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { useSources } from '../state/sources';
import { useAuth } from '../state/auth';
import { useAction } from '../hooks/useApi';
import { Dialog } from './Dialog';
import { Button } from './Button';
import { PasswordFields, usePasswordForm } from './PasswordForm';
import { InlineError } from './States';
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
export function Logo() {
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
  const { me, signOut } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [changingPassword, setChangingPassword] = useState(false);
  const nav = me?.role === 'admin' ? [...NAV, { to: '/users', label: 'Users', match: (p: string) => p.startsWith('/users') }] : NAV;

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
          {nav.map((item) => (
            <Link key={item.to} to={item.to} aria-current={item.match(pathname) ? 'page' : undefined}>
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="sidebar-foot stack" style={{ padding: '0 12px', ['--gap' as string]: '12px' }}>
          <p className="caption muted">{scanCaption(selected ? [selected] : sources)}</p>
          <div className="account">
            <p className="body-sm g-wrap" style={{ fontWeight: 700 }}>{me?.username}</p>
            <div className="row" style={{ ['--gap' as string]: '12px' }}>
              <button type="button" className="g-link" onClick={() => setChangingPassword(true)}>
                Change password
              </button>
              <button
                type="button"
                className="g-link"
                onClick={() => {
                  // Whoever signs in next starts at the overview, not on a page that may not be theirs.
                  navigate('/');
                  void signOut();
                }}
              >
                Sign out
              </button>
            </div>
          </div>
        </div>
      </aside>
      {changingPassword ? <ChangePasswordDialog onClose={() => setChangingPassword(false)} /> : null}
      <main className="main">
        <div className="page">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

function ChangePasswordDialog({ onClose }: { onClose: () => void }) {
  const { changePassword } = useAuth();
  const form = usePasswordForm();
  const action = useAction();

  async function submit() {
    if (form.problem) return;
    if (await action.run(() => changePassword(form.current, form.password))) onClose();
  }

  return (
    <Dialog
      title="Change password"
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="password-form" busy={action.busy} disabled={!form.complete}>
            Change password
          </Button>
        </>
      }
    >
      <form
        id="password-form"
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <p className="body-sm muted">Your other sessions are signed out; this one stays.</p>
        <PasswordFields form={form} />
        <InlineError message={action.error ?? (form.touched ? form.problem : null)} />
      </form>
    </Dialog>
  );
}
