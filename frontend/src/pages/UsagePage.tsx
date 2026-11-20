import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TableUsage, UsageQuery } from '../api/types';
import { useApi } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { Card, StatTile } from '../components/Card';
import { Chart } from '../components/Chart';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, Loading } from '../components/States';
import { fmtAgo, fmtBytes, fmtInt, fmtMs, fmtPct, fmtRows } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

type Sort = 'count' | 'time' | 'rows';
const SORTS: { value: Sort; label: string; by: (q: UsageQuery) => number }[] = [
  { value: 'count', label: 'Most frequent', by: (q) => q.count },
  { value: 'time', label: 'Most total time', by: (q) => q.totalMs },
  { value: 'rows', label: 'Most rows read', by: (q) => q.avgReadRows * q.count },
];

/** What the numbers point at. Each line is an observation, not yet a recommendation. */
function observations(u: TableUsage): string[] {
  const out: string[] = [];
  if (u.selects > 0) {
    const unkeyed = u.columns.filter((c) => !c.inSortingKey && c.filterShare >= 0.5);
    for (const c of unkeyed.slice(0, 3)) {
      out.push(`${c.name} is filtered on in ${fmtPct(c.filterShare, 0)} of the SELECTs but is not part of the sorting key, so those queries cannot skip granules by it.`);
    }
    const keyUnused = u.columns.filter((c) => c.inSortingKey && c.filterShare < 0.05);
    if (keyUnused.length && unkeyed.length) {
      out.push(`${keyUnused.map((c) => c.name).join(', ')} ${keyUnused.length === 1 ? 'is' : 'are'} in the sorting key but hardly ever filtered on.`);
    }
    if (u.wideScans / u.selects >= 0.2) {
      out.push(`${fmtPct(u.wideScans / u.selects, 0)} of the SELECTs read at least half of the table's ${fmtRows(u.tableRows)} rows: the sorting key and partitioning do not narrow them.`);
    }
    if (u.unreadColumns.length) {
      const names = u.unreadColumns.slice(0, 6).join(', ') + (u.unreadColumns.length > 6 ? ` and ${u.unreadColumns.length - 6} more` : '');
      out.push(
        `No SELECT read ${names} in this window${u.unreadBytes > 0 ? ` (${fmtBytes(u.unreadBytes)} compressed)` : ''}. Rarely read columns are where a stronger codec costs the least.`,
      );
    }
  }
  if (u.inserts >= 20 && u.selects * 20 < u.inserts) {
    out.push(`The table is mostly written: ${fmtInt(u.inserts)} INSERTs against ${fmtInt(u.selects)} SELECTs. Heavy codecs are paid on every insert here.`);
  }
  if (u.selects >= 20 && u.inserts === 0) out.push('Nothing was inserted in this window: the data is static, so a one-off recompression or re-sort pays off for good.');
  return out;
}

export function UsagePage() {
  const { sourceId = '', database = '', table = '' } = useParams();
  const { select } = useSources();
  useEffect(() => select(sourceId), [select, sourceId]);
  const { data: u, error, reload } = useApi(() => api.tableUsage(sourceId, database, table), [sourceId, database, table]);
  const [sort, setSort] = useState<Sort>('count');
  const queries = useMemo(() => {
    const by = SORTS.find((s) => s.value === sort)!.by;
    return [...(u?.queries ?? [])].sort((a, b) => by(b) - by(a));
  }, [u, sort]);

  const tableTo = routes.table(sourceId, database, table);
  if (error && !u) {
    return (
      <>
        <PageHeader eyebrow={<Link to={tableTo}>{database}.{table}</Link>} title="Usage" />
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!u) return <Loading />;

  const total = u.selects + u.inserts + u.otherQueries;
  const since = u.logSince ? fmtAgo(u.logSince) : null;
  const notes = observations(u);

  return (
    <>
      <PageHeader
        eyebrow={
          <Link to={tableTo}>
            {u.sourceName} / {u.database} / {u.table}
          </Link>
        }
        title="Usage"
        description={
          <>
            How {u.database}.{u.table} was queried in the last {u.windowDays} days, from system.query_log{since ? ` (the log starts ${since})` : ''}. Granulee's own queries are
            left out.
            {u.via.length ? ` Queries sent to the Buffer table ${u.via.join(', ')} in front of it are counted in.` : ''}
          </>
        }
        actions={
          <>
            <Button variant="secondary" to={tableTo}>
              Table
            </Button>
            <Button to={routes.recommendations(sourceId, database, table)}>Recommendations</Button>
          </>
        }
      />

      {!u.available ? (
        <EmptyState title="The query log is not available">
          <p style={{ overflowWrap: 'anywhere' }}>{u.message}</p>
          <p>Usage is read from system.query_log. Switch it on in the server configuration and give the source's user SELECT on it.</p>
        </EmptyState>
      ) : total === 0 ? (
        <EmptyState title="No queries in this window">
          <p>{u.message ?? `system.query_log has no query on ${u.database}.${u.table} in the last ${u.windowDays} days${since ? `; the log starts ${since}` : ''}.`}</p>
        </EmptyState>
      ) : (
        <>
          <section aria-label="Totals" className="grid-auto" style={vars({ min: 200 })}>
            <StatTile label="SELECTs" value={fmtInt(u.selects)} sub={u.otherQueries > 0 ? `${fmtInt(u.otherQueries)} other statements` : undefined} />
            <StatTile label="INSERTs" value={fmtInt(u.inserts)} sub={u.inserts > 0 ? `${fmtRows(u.writtenRows)} rows written` : undefined} />
            <StatTile label="Rows read" value={fmtRows(u.readRows)} sub={`${fmtBytes(u.readBytes)} uncompressed`} />
            <StatTile label="Median SELECT" value={u.selects > 0 ? fmtMs(u.selectMedianMs) : '—'} sub={u.selects > 0 ? `p95 ${fmtMs(u.selectP95Ms)}` : undefined} />
            <StatTile
              label="Wide scans"
              value={u.selects > 0 ? fmtPct(u.wideScans / u.selects, 0) : '—'}
              sub="SELECTs reading ≥ half the rows"
            />
          </section>

          {notes.length ? (
            <Card as="section" emphasis>
              <h2 className="card-title">What this points at</h2>
              <ul className="body-sm stack" style={{ margin: 0, paddingLeft: 20, ...vars({ gap: 8 }) }}>
                {notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
              <p className="caption muted">
                Observations from the log, not measured changes. <Link className="g-link" to={routes.newTrial(sourceId, database, table)}>Set up a trial</Link> to measure one.
              </p>
            </Card>
          ) : null}

          {u.days.length > 1 ? (
            <Chart
              type="bar"
              title="Queries per day"
              subtitle="SELECTs and INSERTs that finished"
              categoryLabel="Day"
              categories={u.days.map((d) => d.day.slice(5))}
              series={[
                { name: 'SELECT', values: u.days.map((d) => d.selects) },
                { name: 'INSERT', values: u.days.map((d) => d.inserts) },
              ]}
              height={200}
            />
          ) : null}

          {u.columns.length ? (
            <section className="stack">
              <div className="between">
                <h2 className="card-title">Columns</h2>
                <span className="caption muted">Read by: share of all SELECTs · Filtered on: after WHERE, among the query shapes below</span>
              </div>
              <Card table>
                <table className="g-tbl">
                  <thead>
                    <tr>
                      <th>Column</th>
                      <th>Type</th>
                      <th className="r">Compressed</th>
                      <th style={{ width: 220 }}>Read by</th>
                      <th style={{ width: 220 }}>Filtered on</th>
                    </tr>
                  </thead>
                  <tbody>
                    {u.columns.map((c) => (
                      <tr key={c.name}>
                        <td>
                          <span className="row" style={vars({ gap: 8 })}>
                            <strong>{c.name}</strong>
                            {c.inSortingKey ? <span className="caption nv-badge">key</span> : null}
                          </span>
                        </td>
                        <td className="g-mono">{c.type || '—'}</td>
                        <td className="r nowrap">{c.compressedBytes > 0 ? fmtBytes(c.compressedBytes) : '—'}</td>
                        <td>
                          <ShareBar share={c.readShare} />
                        </td>
                        <td>
                          <ShareBar share={c.filterShare} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
            </section>
          ) : null}

          {queries.length ? (
            <section className="stack">
              <div className="between" style={{ alignItems: 'center' }}>
                <h2 className="card-title">Query shapes</h2>
                <label className="row caption muted" style={vars({ gap: 8 })}>
                  Sort
                  <select className="g-select cell" style={{ width: 'auto' }} value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
                    {SORTS.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <Card table>
                <table className="g-tbl">
                  <thead>
                    <tr>
                      <th>Query</th>
                      <th className="r">Runs</th>
                      <th className="r">Share</th>
                      <th className="r">Median</th>
                      <th className="r">p95</th>
                      <th className="r">Rows read</th>
                      <th className="r">Rows returned</th>
                      <th className="r">Last run</th>
                    </tr>
                  </thead>
                  <tbody>
                    {queries.map((q) => (
                      <tr key={q.query}>
                        <td className="g-mono" style={{ minWidth: 320, overflowWrap: 'anywhere' }}>
                          {q.query}
                        </td>
                        <td className="r g-strong">{fmtInt(q.count)}</td>
                        <td className="r">{fmtPct(q.share, 0)}</td>
                        <td className="r nowrap">{fmtMs(q.medianMs)}</td>
                        <td className="r nowrap">{fmtMs(q.p95Ms)}</td>
                        <td className="r nowrap">{fmtRows(q.avgReadRows)}</td>
                        <td className="r nowrap">{fmtRows(q.avgResultRows)}</td>
                        <td className="r nowrap">{fmtAgo(q.lastSeen) ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
              <p className="caption muted">
                Up to 40 shapes; literals are replaced by ?. Rows read and returned are averages per run.
              </p>
            </section>
          ) : null}

          {u.users.length ? (
            <section className="stack">
              <h2 className="card-title">Who queries it</h2>
              <Card table>
                <table className="g-tbl">
                  <thead>
                    <tr>
                      <th>User</th>
                      <th className="r">SELECTs</th>
                      <th className="r">INSERTs</th>
                    </tr>
                  </thead>
                  <tbody>
                    {u.users.map((x) => (
                      <tr key={x.user}>
                        <td className="g-strong">{x.user}</td>
                        <td className="r">{fmtInt(x.selects)}</td>
                        <td className="r">{fmtInt(x.inserts)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
            </section>
          ) : null}
        </>
      )}
    </>
  );
}

function ShareBar({ share }: { share: number }) {
  return (
    <div className="share">
      <div className="share-track">
        <div className="share-fill" style={{ width: `${Math.min(100, share * 100)}%` }} />
      </div>
      <span className="caption g-num" style={{ minWidth: 40, textAlign: 'right' }}>
        {share > 0 ? fmtPct(share, 0) : '—'}
      </span>
    </div>
  );
}
