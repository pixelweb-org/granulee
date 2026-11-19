import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useAction, useApi } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { BytesChart } from '../components/BytesChart';
import { Checkbox, TextInput } from '../components/Form';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, InlineError, Loading } from '../components/States';
import { fmtBytes, fmtClock, fmtInt, fmtRatio, fmtRows } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

export function ExplorerPage() {
  const { sourceId, database } = useParams();
  const { sources, loading, selectedId, select, reload: reloadSources } = useSources();

  // The sidebar select follows the source in the URL.
  useEffect(() => {
    if (sourceId && sourceId !== selectedId && sources.some((s) => s.id === sourceId)) select(sourceId);
  }, [sourceId, selectedId, sources, select]);

  if (!sourceId) {
    if (loading) return <Loading />;
    const target = selectedId ?? sources[0]?.id;
    if (target) return <Navigate to={routes.explorer(target)} replace />;
    return (
      <>
        <PageHeader eyebrow="Explorer" title="Explorer" />
        <EmptyState title="No data sources yet" action={<Button to="/sources">Add a data source</Button>}>
          Connect a ClickHouse server to browse its databases and tables.
        </EmptyState>
      </>
    );
  }
  return <Explorer key={sourceId} sourceId={sourceId} database={database} onScanned={reloadSources} />;
}

function Explorer({ sourceId, database, onScanned }: { sourceId: string; database?: string; onScanned: () => Promise<void> }) {
  const list = useApi(() => api.databases(sourceId), [sourceId]);
  const current = database ?? list.data?.databases[0]?.name;
  const detail = useApi(() => (current ? api.database(sourceId, current) : Promise.resolve(null)), [sourceId, current]);
  const scan = useAction();
  const [filter, setFilter] = useState('');
  const [onlyFindings, setOnlyFindings] = useState(false);

  async function rescan() {
    await scan.run(() => api.scanSource(sourceId));
    await Promise.all([onScanned(), list.reload(true), detail.reload(true)]);
  }

  const d = detail.data;
  const tables = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return (d?.tables ?? []).filter(
      (t) => (!onlyFindings || t.findings > 0) && (!q || t.name.toLowerCase().includes(q) || t.engine.toLowerCase().includes(q)),
    );
  }, [d, filter, onlyFindings]);

  const engines = useMemo(() => {
    const m = new Map<string, number>();
    for (const t of d?.tables ?? []) m.set(t.engine, (m.get(t.engine) ?? 0) + t.compressedBytes);
    return [...m.entries()].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);
  }, [d]);

  const sourceName = list.data?.sourceName ?? d?.sourceName ?? '';
  const scanButton = (
    <Button variant="secondary" busy={scan.busy} onClick={rescan}>
      {scan.busy ? 'Scanning…' : 'Rescan database'}
    </Button>
  );

  if (list.error && !list.data) {
    return (
      <>
        <PageHeader eyebrow="Explorer" title="Explorer" />
        <ErrorState message={list.error} onRetry={() => list.reload()} />
      </>
    );
  }
  if (!list.data) return <Loading />;

  if (!list.data.databases.length) {
    return (
      <>
        <PageHeader eyebrow={`Explorer · ${sourceName}`} title={sourceName || 'Explorer'} />
        <EmptyState
          title={list.data.scannedAt ? 'No databases found' : 'Not scanned yet'}
          action={
            <Button busy={scan.busy} onClick={rescan}>
              {scan.busy ? 'Scanning…' : 'Scan now'}
            </Button>
          }
        >
          {list.data.scannedAt
            ? 'The last scan found no tables. Check the include and exclude lists of this data source.'
            : 'Run a scan to read this server’s databases and tables from the system tables.'}
        </EmptyState>
        <InlineError message={scan.error} />
      </>
    );
  }

  const top = (d?.tables ?? []).slice(0, 8);

  return (
    <>
      <PageHeader
        eyebrow={`Explorer · ${sourceName}`}
        title={current ?? ''}
        description={
          d ? (
            <span className="g-num">
              {fmtInt(d.database.tables)} tables · {fmtBytes(d.database.compressedBytes)} compressed · {fmtBytes(d.database.uncompressedBytes)} uncompressed
              {d.scannedAt ? ` · scanned ${fmtClock(d.scannedAt)}` : ''}
            </span>
          ) : undefined
        }
        actions={scanButton}
      />
      <InlineError message={scan.error} />

      <div className="split">
        <Card as="nav" aria-label="Databases" className="split-side" style={{ padding: 12, gap: 4, flexBasis: 220 }}>
          <p className="eyebrow" style={{ padding: '8px 12px' }}>
            Databases
          </p>
          {list.data.databases.map((db) => {
            const on = db.name === current;
            return (
              <Link
                key={db.name}
                to={routes.explorer(sourceId, db.name)}
                aria-current={on ? 'page' : undefined}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  gap: 8,
                  padding: '10px 12px',
                  borderRadius: 8,
                  background: on ? 'var(--surface-2)' : undefined,
                  color: on ? 'var(--ink)' : 'var(--ink-muted)',
                  textDecoration: 'none',
                  fontSize: 14,
                }}
              >
                <span style={{ overflowWrap: 'anywhere' }}>{db.name}</span>
                <span className="g-num muted" style={{ whiteSpace: 'nowrap' }}>{fmtBytes(db.compressedBytes)}</span>
              </Link>
            );
          })}
        </Card>

        <div className="split-main stack" style={vars({ gap: 24 })}>
          {detail.error && !d ? (
            <ErrorState message={detail.error} onRetry={() => detail.reload()} />
          ) : !d ? (
            <Loading />
          ) : !d.tables.length ? (
            <EmptyState title="No tables">This database has no tables in the last scan.</EmptyState>
          ) : (
            <>
              <div className="grid-auto" style={vars({ min: 380, gap: 24 })}>
                <BytesChart
                  type="bar"
                  title="Compressed size by table"
                  subtitle={top.length < d.tables.length ? `On disk, ${top.length} largest tables` : 'On disk'}
                  categoryLabel="Table"
                  categories={top.map((t) => t.name)}
                  series={[{ name: 'Compressed', values: top.map((t) => t.compressedBytes) }]}
                  height={220}
                />
                <BytesChart
                  type="donut"
                  title="Storage by engine"
                  subtitle={`Share of ${fmtBytes(d.database.compressedBytes)} compressed`}
                  categoryLabel="Engine"
                  totalLabel="Compressed"
                  categories={engines.map(([name]) => name)}
                  series={[{ name: 'Compressed', values: engines.map(([, v]) => v) }]}
                  height={200}
                />
              </div>

              <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end', ...vars({ gap: 16 }) }}>
                <div style={{ flex: '1 1 320px', maxWidth: 420 }}>
                  <TextInput label="Filter tables" placeholder="Name or engine" value={filter} onChange={(e) => setFilter(e.target.value)} />
                </div>
                <Checkbox checked={onlyFindings} onChange={setOnlyFindings}>
                  Only tables with findings
                </Checkbox>
              </div>

              <Card table>
                <table className="g-tbl">
                  <thead>
                    <tr>
                      <th>Table</th>
                      <th>Engine</th>
                      <th className="r">Rows</th>
                      <th className="r">Compressed</th>
                      <th className="r">Uncompressed</th>
                      <th className="r">Ratio</th>
                      <th className="r">Active parts</th>
                      <th className="r">Findings</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tables.map((t) => (
                      <tr key={t.name}>
                        <td>
                          <Link className="g-link" to={routes.table(sourceId, t.database, t.name)}>
                            {t.name}
                          </Link>
                        </td>
                        <td>{t.engine}</td>
                        <td className="r nowrap">{fmtRows(t.rows)}</td>
                        <td className="r g-strong nowrap">{fmtBytes(t.compressedBytes)}</td>
                        <td className="r nowrap">{fmtBytes(t.uncompressedBytes)}</td>
                        <td className="r">{fmtRatio(t.uncompressedBytes, t.compressedBytes)}</td>
                        <td className="r">{fmtInt(t.activeParts)}</td>
                        <td className={`r${t.findings > 0 ? ' g-strong' : ''}`}>{fmtInt(t.findings)}</td>
                      </tr>
                    ))}
                    {!tables.length ? (
                      <tr>
                        <td colSpan={8}>No tables match the filter.</td>
                      </tr>
                    ) : null}
                  </tbody>
                </table>
              </Card>
              <p className="caption muted">
                Showing {fmtInt(tables.length)} of {fmtInt(d.tables.length)} tables. Sizes from system.parts (active parts only).
              </p>
            </>
          )}
        </div>
      </div>
    </>
  );
}
