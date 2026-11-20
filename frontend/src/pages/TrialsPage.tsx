import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Trial } from '../api/types';
import { useApi, usePolling } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, Loading } from '../components/States';
import { Status } from '../components/StatusDot';
import { RemoveTrialDialog, TrialProgressCard, trialStatusLabel, trialTone, useRemoveTrial } from '../components/TrialParts';
import { fmtBytes, fmtChange, fmtClock, fmtMsPair, fmtRows, median } from '../lib/format';
import { routes } from '../lib/routes';

/** Median replayed-query latency, baseline → candidate, from the per-query results. */
export function medianQuery(t: Trial): string {
  const ok = (t.result?.queries ?? []).filter((q) => q.baseMs != null && q.candMs != null);
  const b = median(ok.map((q) => q.baseMs as number));
  const c = median(ok.map((q) => q.candMs as number));
  return b != null && c != null ? fmtMsPair(b, c) : '—';
}

export function TrialsPage() {
  const { selectedId, selected, sources, loading: sourcesLoading } = useSources();
  const noSources = !sourcesLoading && !sources.length;
  const { data, error, reload } = useApi(() => api.trials(selectedId), [selectedId]);
  const [removing, setRemoving] = useRemoveTrial();

  const running = data?.trials.filter((t) => t.status === 'running') ?? [];
  const kept = data?.trials.filter((t) => t.status !== 'running') ?? [];
  usePolling(running.length > 0, 2000, () => void reload(true));

  return (
    <>
      <PageHeader
        eyebrow={`Trials · ${selected ? selected.name : 'All sources'}`}
        title="Trials"
        description="Every trial keeps its two tables and its result until you remove it. Removing drops the tables from scratch."
        actions={
          <>
            {data ? (
              <span className="body-sm g-num muted">
                Scratch in use: <strong className="ink">{fmtBytes(data.scratchUsedBytes)}</strong>
                {data.scratchFreeBytes != null ? ` of ${fmtBytes(data.scratchFreeBytes)} free` : ''}
              </span>
            ) : null}
            {noSources ? null : <Button to="/recommendations">New trial</Button>}
          </>
        }
      />

      {noSources ? (
        <EmptyState title="No data sources yet" action={<Button to="/sources">Add a data source</Button>}>
          Trials run on a connected ClickHouse server.
        </EmptyState>
      ) : error && !data ? (
        <ErrorState message={error} onRetry={() => reload()} />
      ) : !data ? (
        <Loading />
      ) : null}

      {running.map((t) => (
        <TrialProgressCard
          key={t.id}
          trial={t}
          onChanged={() => void reload(true)}
          titleTo={
            <Link to={routes.trial(t.id)} style={{ color: 'inherit', textDecoration: 'none' }}>
              {t.table} · {t.name}
            </Link>
          }
        />
      ))}

      {data && !noSources ? (
        <section className="stack">
          <h2 className="card-title">Kept trials</h2>
          {kept.length ? (
            <Card table>
              <table className="g-tbl">
                <thead>
                  <tr>
                    <th>Trial</th>
                    <th>Table</th>
                    <th>Status</th>
                    <th className="r">Rows</th>
                    <th className="r">Size change</th>
                    <th className="r">Median query</th>
                    <th className="r">Scratch used</th>
                    <th className="r">Created</th>
                    <th className="r">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {kept.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <Link className="g-link" to={routes.trial(t.id)}>
                          {t.name}
                        </Link>
                        <div className="caption muted">#{t.id}</div>
                      </td>
                      <td className="g-mono">
                        {t.database}.{t.table}
                      </td>
                      <td style={{ maxWidth: 280 }}>
                        <Status tone={trialTone(t)}>
                          <span style={{ overflowWrap: 'break-word' }}>{trialStatusLabel(t)}</span>
                        </Status>
                      </td>
                      <td className="r nowrap">{t.result ? fmtRows(t.result.rows) : '—'}</td>
                      <td className="r g-strong">{t.result ? fmtChange(t.result.projectedBaseBytes, t.result.projectedCandBytes) : '—'}</td>
                      <td className="r nowrap">{medianQuery(t)}</td>
                      <td className="r nowrap">{fmtBytes(t.scratchBytes)}</td>
                      <td className="r nowrap">{fmtClock(t.createdAt, true)}</td>
                      <td className="r">
                        <button type="button" className="cc-btn cc-btn--link is-ink" onClick={() => setRemoving(t)}>
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          ) : (
            <p className="body-sm muted">No kept trials. Scratch is clean.</p>
          )}
        </section>
      ) : null}

      {removing ? (
        <RemoveTrialDialog
          trial={removing}
          onClose={() => setRemoving(null)}
          onRemoved={() => {
            setRemoving(null);
            void reload(true);
          }}
        />
      ) : null}
    </>
  );
}
