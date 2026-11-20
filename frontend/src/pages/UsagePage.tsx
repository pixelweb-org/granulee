import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TableUsage, UsageQuery } from '../api/types';
import { useApi } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Choice } from '../components/Button';
import { BytesChart } from '../components/BytesChart';
import { Card, StatTile } from '../components/Card';
import { Chart } from '../components/Chart';
import { TableHeader } from '../components/TableHeader';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, Loading } from '../components/States';
import { fmtAgo, fmtBytes, fmtDuration, fmtInt, fmtMs, fmtPct, fmtRows } from '../lib/format';
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

const SHOWN = 10;

/** A short name for a query shape in a chart: what it selects, cut to fit under a bar. */
function shapeLabel(query: string, rank: number): string {
  const m = /^\s*SELECT\s+(.+?)\s+FROM\b/is.exec(query);
  const what = (m ? m[1] : query).replace(/\s+/g, ' ');
  return `${rank}. ${what.length > 14 ? `${what.slice(0, 13)}…` : what}`;
}

export function UsagePage() {
  const { sourceId = '', database = '', table = '' } = useParams();
  const { select } = useSources();
  useEffect(() => select(sourceId), [select, sourceId]);
  const { data: t, error: tableError, reload: reloadTable } = useApi(() => api.table(sourceId, database, table), [sourceId, database, table]);
  const { data: u, error, reload } = useApi(() => api.tableUsage(sourceId, database, table), [sourceId, database, table]);
  const [sort, setSort] = useState<Sort>('count');
  const [open, setOpen] = useState<string | null>(null);
  const [all, setAll] = useState(false);
  const [showUnread, setShowUnread] = useState(false);
  const queries = useMemo(() => {
    const by = SORTS.find((s) => s.value === sort)!.by;
    return [...(u?.queries ?? [])].sort((a, b) => by(b) - by(a));
  }, [u, sort]);

  const failed = tableError ?? error;
  if (failed && (!t || !u)) {
    return (
      <>
        <PageHeader eyebrow={<Link to={routes.table(sourceId, database, table)}>{database}.{table}</Link>} title="Usage" />
        <ErrorState
          message={failed}
          onRetry={() => {
            void reloadTable();
            void reload();
          }}
        />
      </>
    );
  }
  if (!t || !u) return <Loading />;

  const total = u.selects + u.inserts + u.otherQueries;
  const since = u.logSince ? fmtAgo(u.logSince) : null;
  const covered = u.logSince ? Date.now() - new Date(u.logSince).getTime() : null;
  const partial = covered != null && covered < (u.windowDays - 1) * 86_400_000;
  const notes = observations(u);

  const read = u.columns.filter((c) => c.readBy > 0);
  const unread = u.columns.filter((c) => c.readBy === 0);
  const unreadBytes = unread.reduce((a, c) => a + c.compressedBytes, 0);
  const sizeOf = (list: typeof u.columns) => list.reduce((a, c) => a + c.compressedBytes, 0);
  const heaviest = [...u.queries].sort((a, b) => b.totalMs - a.totalMs).slice(0, 6);
  const shown = all ? queries : queries.slice(0, SHOWN);

  return (
    <>
      <TableHeader t={t} active="usage" />

      {!u.available ? (
        <EmptyState title="The query log is not available">
          <p className="g-wrap">{u.message}</p>
          <p>Usage is read from system.query_log. Switch it on in the server configuration and give the source's user SELECT on it.</p>
        </EmptyState>
      ) : total === 0 ? (
        <EmptyState title="No queries in this window">
          <p>{u.message ?? `system.query_log has no query on ${u.database}.${u.table} in the last ${u.windowDays} days${since ? `; the log starts ${since}` : ''}.`}</p>
        </EmptyState>
      ) : (
        <>
          {partial && covered != null ? (
            <p className="g-notice">
              Partial window: query_log on this server starts {since}, so these numbers cover {fmtDuration(covered)} of the {u.windowDays} days asked for. Treat shares as a
              first impression.
            </p>
          ) : null}

          <section aria-label="Usage totals" className="grid-auto" style={vars({ min: 200 })}>
            <StatTile label="SELECTs" value={fmtInt(u.selects)} sub="Granulee's own queries left out" />
            <StatTile label="INSERTs" value={fmtInt(u.inserts)} sub={`${fmtRows(u.writtenRows)} rows written`} />
            <StatTile label="Rows read" value={fmtRows(u.readRows)} sub={`${fmtBytes(u.readBytes)} uncompressed`} />
            <StatTile label="Median SELECT" value={u.selects > 0 ? fmtMs(u.selectMedianMs) : '—'} sub={u.selects > 0 ? `p95 ${fmtMs(u.selectP95Ms)}` : undefined} />
            <StatTile label="Wide scans" value={u.selects > 0 ? fmtPct(u.wideScans / u.selects, 0) : '—'} sub="SELECTs reading at least half the rows" />
          </section>

          {notes.length || u.via.length ? (
            <Card as="section" emphasis style={{ gap: 12 }}>
              <p className="eyebrow">What this points at</p>
              {notes.length ? (
                <ul className="body stack" style={{ margin: 0, paddingLeft: 20, maxWidth: '110ch', ...vars({ gap: 8 }) }}>
                  {notes.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              ) : null}
              <p className="caption muted">
                Observations from the log, not measured changes.
                {u.via.length ? ` Queries sent to the Buffer table ${u.via.join(', ')} in front of this table are counted in.` : ''}
              </p>
            </Card>
          ) : null}

          {u.selects > 0 ? (
            <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
              {sizeOf(u.columns) > 0 ? (
                <BytesChart
                  type="bar"
                  title="Compressed size by how often it is read"
                  subtitle="On disk, columns grouped by share of SELECTs that read them"
                  categoryLabel="Group"
                  categories={['Read by 10% or more', 'Read by under 10%', 'Not read']}
                  series={[
                    {
                      name: 'Compressed',
                      values: [sizeOf(u.columns.filter((c) => c.readShare >= 0.1)), sizeOf(u.columns.filter((c) => c.readBy > 0 && c.readShare < 0.1)), unreadBytes],
                    },
                  ]}
                  height={220}
                />
              ) : null}
              {heaviest.length ? (
                <Chart
                  type="bar"
                  title="Where the query time goes"
                  subtitle={`Total time, ${heaviest.length} heaviest query shapes`}
                  categoryLabel="Query shape"
                  categories={heaviest.map((q, i) => shapeLabel(q.query, i + 1))}
                  series={[{ name: 'Total time', values: heaviest.map((q) => q.totalMs / 1000) }]}
                  format={(v) => `${v >= 10 ? Math.round(v) : v.toFixed(1)} s`}
                  height={220}
                />
              ) : null}
            </section>
          ) : null}

          {u.columns.length ? (
            <section className="stack">
              <div className="between">
                <h2 className="card-title">Columns</h2>
                <span className="caption muted">Read by: share of all SELECTs · Filtered on: used after WHERE</span>
              </div>
              <Card table>
                {read.length ? (
                  <table className="g-tbl">
                    <thead>
                      <tr>
                        <th>Column</th>
                        <th>Type</th>
                        <th className="r">Compressed</th>
                        <th style={{ width: '22%' }}>Read by</th>
                        <th style={{ width: '22%' }}>Filtered on</th>
                      </tr>
                    </thead>
                    <tbody>
                      {read.map((c) => (
                        <tr key={c.name}>
                          <td className="g-strong">
                            <span className="row" style={vars({ gap: 8 })}>
                              {c.name}
                              {c.inSortingKey ? <span className="g-count">key</span> : null}
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
                ) : null}
                {unread.length ? (
                  <>
                    <button type="button" className="g-rowbtn" aria-expanded={showUnread} onClick={() => setShowUnread(!showUnread)} style={{ padding: '0 12px' }}>
                      <span className="body-sm g-act">{showUnread ? 'Hide' : 'Show'}</span>
                      <span className="body-sm muted">
                        {fmtInt(unread.length)} {unread.length === 1 ? 'column' : 'columns'} not read in this window
                        {unreadBytes > 0 ? ` · ${fmtBytes(unreadBytes)} compressed` : ''}
                      </span>
                    </button>
                    {showUnread ? (
                      <p className="g-mono muted" style={{ padding: '4px 12px 16px', lineHeight: 1.9 }}>
                        {unread
                          .map((c) => `${c.name}${c.inSortingKey ? ' (key)' : ''}${c.compressedBytes > 0 ? ` ${fmtBytes(c.compressedBytes)}` : ''}`)
                          .join(' · ')}
                      </p>
                    ) : null}
                  </>
                ) : null}
              </Card>
            </section>
          ) : null}

          {queries.length ? (
            <section className="stack">
              <div className="between" style={{ alignItems: 'center' }}>
                <h2 className="card-title">Query shapes</h2>
                <div role="group" aria-label="Sort query shapes" className="row">
                  {SORTS.map((s) => (
                    <Choice key={s.value} pressed={sort === s.value} onClick={() => setSort(s.value)}>
                      {s.label}
                    </Choice>
                  ))}
                </div>
              </div>
              <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, ...vars({ gap: 12 }) }}>
                {shown.map((q) => {
                  const isOpen = open === q.query;
                  return (
                    <li key={q.query} className="card card-row">
                      <span className={isOpen ? 'g-sql g-sql--full' : 'g-sql g-sql--clamp'}>{q.query}</span>
                      <div className="facts-row">
                        <dl className="facts">
                          <Fact label="Runs" strong>
                            {fmtInt(q.count)}
                          </Fact>
                          <Fact label="Share">{fmtPct(q.share, 0)}</Fact>
                          <Fact label="Median">{fmtMs(q.medianMs)}</Fact>
                          <Fact label="p95">{fmtMs(q.p95Ms)}</Fact>
                          <Fact label="Rows read">{fmtRows(q.avgReadRows)}</Fact>
                          <Fact label="Total time" strong>
                            {fmtMs(q.totalMs)}
                          </Fact>
                          <Fact label="Last run">{fmtAgo(q.lastSeen) ?? '—'}</Fact>
                        </dl>
                        <button type="button" className="cc-btn cc-btn--link" aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : q.query)}>
                          {isOpen ? 'Show less' : 'Show full query'}
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ol>
              <div className="row" style={vars({ gap: 16 })}>
                {queries.length > SHOWN ? (
                  <button type="button" className="cc-btn cc-btn--secondary" aria-expanded={all} onClick={() => setAll(!all)}>
                    {all ? `Show the first ${SHOWN}` : `Show all ${fmtInt(queries.length)} shapes`}
                  </button>
                ) : null}
                <span className="caption muted">
                  Showing {fmtInt(shown.length)} of {fmtInt(queries.length)}. Literals are replaced by ?. Rows read is the average per run.
                </span>
              </div>
            </section>
          ) : null}

          {u.users.length ? (
            <section className="stack" style={{ maxWidth: 520 }}>
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

function Fact({ label, strong, children }: { label: string; strong?: boolean; children: ReactNode }) {
  return (
    <div>
      <dt className="eyebrow">{label}</dt>
      <dd className={strong ? 'strong' : undefined}>{children}</dd>
    </div>
  );
}

function ShareBar({ share }: { share: number }) {
  return (
    <div className="share">
      <div className="g-track">
        <div className="g-fill" style={{ width: `${Math.min(100, share * 100)}%` }} />
      </div>
      <span className="g-num ink" style={{ width: 36, textAlign: 'right' }}>
        {share > 0 ? fmtPct(share, 0) : '—'}
      </span>
    </div>
  );
}
