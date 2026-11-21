import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { PlannedQuery, TableIndexUsage } from '../api/types';
import { useApi } from '../hooks/useApi';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { PageHeader } from '../components/PageHeader';
import { PlanSteps } from '../components/PlanSteps';
import { ErrorState, Loading } from '../components/States';
import { TableHeader } from '../components/TableHeader';
import { useSources } from '../state/sources';
import { fmtAgo, fmtBytes, fmtInt, fmtPct, fmtRatio, fmtRows } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

// Queries shown in an opened row before "Show all".
const SHOWN = 3;

const dash = (v: string | null | undefined) => (v && v.trim() ? v : '—');

/** What the opened row is: a skip index or a projection, by name. */
type Open = { kind: 'index' | 'projection'; name: string } | null;

export function IndexesPage() {
  const { sourceId = '', database = '', table = '' } = useParams();
  const { selectedId, select, sources } = useSources();
  const { data: t, error, reload } = useApi(() => api.table(sourceId, database, table), [sourceId, database, table]);
  const check = useApi(() => api.indexUsage(sourceId, database, table), [sourceId, database, table]);
  const [open, setOpen] = useState<Open>(null);

  useEffect(() => {
    if (sourceId && sourceId !== selectedId && sources.some((s) => s.id === sourceId)) select(sourceId);
  }, [sourceId, selectedId, sources, select]);
  useEffect(() => setOpen(null), [sourceId, database, table]);

  if (error && !t) {
    return (
      <>
        <PageHeader eyebrow={<Link to={routes.explorer(sourceId, database)}>{database}</Link>} title={table} />
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!t) return <Loading />;

  const usage = check.data;
  const measured = usage && !usage.message ? usage : null;
  const projections = t.projections;
  const projectionBytes = projections.reduce((a, p) => a + p.bytesOnDisk, 0);
  const logDays = sources.find((s) => s.id === sourceId)?.queryLogWindowDays;
  const isOpen = (kind: 'index' | 'projection', name: string) => open?.kind === kind && open.name === name;
  const toggle = (kind: 'index' | 'projection', name: string) => setOpen(isOpen(kind, name) ? null : { kind, name });
  const panel = (kind: 'index' | 'projection', name: string, ids: number[] | undefined, empty: ReactNode) => (
    <Queries usage={usage} loading={check.loading} error={check.error} ids={ids ?? []} highlight={kind === 'index' ? name : undefined} projection={kind === 'projection' ? name : undefined} empty={empty} />
  );

  return (
    <>
      <TableHeader
        t={t}
        active="indexes"
        description="The table's skip indexes and projections, and the logged queries that use them. Open a row to see those queries and how ClickHouse plans each one."
      />

      {check.error ? <p className="g-notice g-wrap">The logged queries could not be planned: {check.error}</p> : null}
      {usage?.message ? <p className="g-notice">{usage.message}</p> : null}
      {t.indexes.length || projections.length ? (
        <div className="between" style={{ alignItems: 'center' }}>
          <p className="caption muted" style={{ flex: '1 1 320px' }}>
            {measured
              ? `Planned ${fmtInt(measured.checkedShapes)} logged query ${measured.checkedShapes === 1 ? 'shape' : 'shapes'} with EXPLAIN indexes = 1, standing for ${fmtInt(measured.selects)} SELECTs${measured.failedShapes ? `; ${fmtInt(measured.failedShapes)} could not be planned` : ''}. Nothing is executed.`
              : check.loading
                ? 'Planning the logged queries with EXPLAIN indexes = 1…'
                : ''}
          </p>
          <Button variant="secondary" small busy={check.loading} onClick={() => void check.reload()}>
            {check.loading ? 'Planning…' : 'Check again'}
          </Button>
        </div>
      ) : null}

      <section className="stack">
        <h2 className="card-title">Data skipping indexes</h2>
        {t.indexes.length ? (
          <Card table>
            <table className="g-tbl g-acc">
              <thead>
                <tr>
                  <th>Index</th>
                  <th>Expression</th>
                  <th>Type</th>
                  <th className="r">Granularity</th>
                  <th className="r">Compressed</th>
                  <th className="r">Used by</th>
                  <th style={{ width: '18%' }}>Granules dropped</th>
                </tr>
              </thead>
              <tbody>
                {t.indexes.map((i) => {
                  const u = measured?.indexes.find((x) => x.name === i.name);
                  const dropped = u && u.granulesBefore > 0 ? 1 - u.granulesAfter / u.granulesBefore : 0;
                  const shown = isOpen('index', i.name);
                  const id = `index-${i.name}`;
                  return (
                    <Fragment key={i.name}>
                      <tr className={`g-acc__row${shown ? ' is-open' : ''}`} onClick={() => toggle('index', i.name)}>
                        <td>
                          <RowButton open={shown} controls={id}>
                            {i.name}
                          </RowButton>
                        </td>
                        <td className="g-mono g-wrap">{i.expression}</td>
                        <td className="g-mono">{i.type}</td>
                        <td className="r">{fmtInt(i.granularity)}</td>
                        <td className="r g-strong nowrap">{fmtBytes(i.compressedBytes)}</td>
                        <td className="r nowrap">
                          {!measured ? (check.loading ? '…' : '—') : u && u.shapes > 0 ? `${fmtPct(u.share, 0)} of SELECTs` : 'no logged query'}
                        </td>
                        <td>
                          {u && u.shapes > 0 ? (
                            <div className="share">
                              <div className="g-track">
                                <div className="g-fill" style={{ width: `${Math.min(100, dropped * 100)}%` }} />
                              </div>
                              <span className="g-num ink" style={{ width: 36, textAlign: 'right' }}>
                                {fmtPct(dropped, 0)}
                              </span>
                            </div>
                          ) : (
                            '—'
                          )}
                        </td>
                      </tr>
                      {shown ? (
                        <tr className="g-acc__panel">
                          <td colSpan={7} id={id}>
                            {panel(
                              'index',
                              i.name,
                              u?.queries,
                              <>
                                None of the {fmtInt(measured?.checkedShapes ?? 0)} planned query shapes uses this index. It still costs{' '}
                                {fmtBytes(i.compressedBytes)} on disk and is written on every insert.
                              </>,
                            )}
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </Card>
        ) : (
          <p className="body-sm muted">This table has no data skipping indexes.</p>
        )}
        {measured && t.indexes.length ? (
          <p className="caption muted">
            “Granules dropped” is what an index removed from the granules the earlier indexes left over, summed over the query shapes that use it. An index
            no logged query uses, or one that drops nothing, only costs inserts and disk.
          </p>
        ) : null}
      </section>

      <section className="stack">
        <div className="between">
          <h2 className="card-title">Projections</h2>
          {projections.length ? (
            <span className="caption muted">
              {fmtBytes(projectionBytes)} on disk · {fmtPct(t.bytesOnDisk > 0 ? projectionBytes / t.bytesOnDisk : 0)} of the table
            </span>
          ) : null}
        </div>
        {projections.length ? (
          <Card table>
            <table className="g-tbl g-acc">
              <thead>
                <tr>
                  <th>Projection</th>
                  <th>Type</th>
                  <th>Query</th>
                  <th className="r">Rows</th>
                  <th className="r">On disk</th>
                  <th className="r">Ratio</th>
                  <th className="r">Built in</th>
                  <th className="r">Read by</th>
                </tr>
              </thead>
              <tbody>
                {projections.map((p) => {
                  const shown = isOpen('projection', p.name);
                  const id = `projection-${p.name}`;
                  return (
                    <Fragment key={p.name}>
                      <tr className={`g-acc__row${shown ? ' is-open' : ''}`} onClick={() => toggle('projection', p.name)}>
                        <td>
                          <RowButton open={shown} controls={id}>
                            {p.name}
                          </RowButton>
                        </td>
                        <td>{dash(p.type)}</td>
                        <td className="g-mono g-wrap">{dash(p.query)}</td>
                        <td className="r nowrap">{p.parts ? fmtRows(p.rows) : '—'}</td>
                        <td className="r g-strong nowrap">{p.parts ? fmtBytes(p.bytesOnDisk) : '—'}</td>
                        <td className="r">{p.parts ? fmtRatio(p.uncompressedBytes, p.compressedBytes) : '—'}</td>
                        <td className="r nowrap">
                          {p.parts < t.activeParts ? <strong>{fmtInt(p.parts)}</strong> : fmtInt(p.parts)} of {fmtInt(t.activeParts)} parts
                        </td>
                        <td className="r nowrap">
                          {p.selects === null
                            ? '—'
                            : p.selects > 0
                              ? `${fmtInt(p.selects)} ${p.selects === 1 ? 'SELECT' : 'SELECTs'} · last ${fmtAgo(p.lastUsed) ?? '—'}`
                              : 'no logged query'}
                        </td>
                      </tr>
                      {shown ? (
                        <tr className="g-acc__panel">
                          <td colSpan={8} id={id}>
                            {panel(
                              'projection',
                              p.name,
                              measured?.projections.find((x) => x.name === p.name)?.queries,
                              <>
                                No logged SELECT was answered from this projection, and ClickHouse would not pick it for any of the{' '}
                                {fmtInt(measured?.checkedShapes ?? 0)} planned query shapes. It still costs {fmtBytes(p.bytesOnDisk)} on disk and is written on
                                every insert and merge.
                              </>,
                            )}
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </Card>
        ) : (
          <p className="body-sm muted">This table has no projections.</p>
        )}
        {projections.some((p) => p.parts < t.activeParts) ? (
          <p className="g-notice" role="note">
            A projection that is not built in every part is skipped for the parts that lack it; those are read from the table itself.{' '}
            <span className="g-mono">ALTER TABLE … MATERIALIZE PROJECTION</span> builds it in the existing parts.
          </p>
        ) : null}
        {projections.length ? (
          <p className="caption muted">
            A projection is a second copy of the data inside each part, sorted or aggregated differently, that ClickHouse picks on its own when a query
            fits. Every insert and merge writes it too.
            {projections.some((p) => p.selects !== null)
              ? ` “Read by” counts the SELECTs in the query log${logDays ? ` of the last ${fmtInt(logDays)} days` : ''} that ClickHouse answered from the projection.`
              : ' The query log is not readable on this source, so it is unknown which queries read them.'}
          </p>
        ) : null}
      </section>
    </>
  );
}

function RowButton({ open, controls, children }: { open: boolean; controls: string; children: ReactNode }) {
  return (
    <button type="button" className="g-acc__btn" aria-expanded={open} aria-controls={open ? controls : undefined}>
      <svg className="g-acc__chev" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M3 1l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {children}
    </button>
  );
}

/** The query shapes behind one index or projection, each with its plan. */
function Queries({
  usage,
  loading,
  error,
  ids,
  highlight,
  projection,
  empty,
}: {
  usage: TableIndexUsage | null;
  loading: boolean;
  error: string | null;
  ids: number[];
  highlight?: string;
  projection?: string;
  empty: ReactNode;
}) {
  const [all, setAll] = useState(false);
  if (!usage) return <p className="body-sm muted g-wrap">{loading ? 'Planning the logged queries with EXPLAIN…' : (error ?? 'The logged queries were not planned.')}</p>;
  if (usage.message) return <p className="body-sm muted">{usage.message}</p>;
  const queries = ids.map((i) => usage.queries[i]).filter(Boolean);
  if (!queries.length) return <p className="body-sm muted">{empty}</p>;
  const shown = all ? queries : queries.slice(0, SHOWN);
  return (
    <div className="stack" style={vars({ gap: 12 })}>
      <p className="eyebrow">
        {fmtInt(queries.length)} query {queries.length === 1 ? 'shape' : 'shapes'} · most frequent first
      </p>
      <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, ...vars({ gap: 12 }) }}>
        {shown.map((q) => (
          <QueryPlan key={q.query} q={q} highlight={highlight} projection={projection} />
        ))}
      </ol>
      {queries.length > SHOWN ? (
        <button type="button" className="cc-btn cc-btn--link" style={{ alignSelf: 'flex-start' }} aria-expanded={all} onClick={() => setAll(!all)}>
          {all ? 'Show fewer' : `Show all ${fmtInt(queries.length)}`}
        </button>
      ) : null}
    </div>
  );
}

function QueryPlan({ q, highlight, projection }: { q: PlannedQuery; highlight?: string; projection?: string }) {
  const [full, setFull] = useState(false);
  const plan = q.plan;
  const step = highlight ? plan.steps.find((s) => s.kind === 'skip' && s.name === highlight) : undefined;
  const long = q.query.length > 160 || q.query.includes('\n');
  return (
    <li className="g-plan">
      <span className={full || !long ? 'g-sql g-sql--full' : 'g-sql g-sql--clamp'}>{q.query}</span>
      <dl className="facts">
        <Fact label="Runs">{fmtInt(q.count)}</Fact>
        <Fact label="Share of SELECTs">{fmtPct(q.share, 0)}</Fact>
        {step ? (
          <Fact label="This index">
            {fmtInt(step.granulesBefore)} → {fmtInt(step.granulesAfter)} granules
            {step.granulesBefore > 0 ? ` (−${fmtPct(1 - step.granulesAfter / step.granulesBefore, 0)})` : ''}
          </Fact>
        ) : null}
        {plan.granulesRead != null && plan.granulesTotal != null ? (
          <Fact label={plan.projection ? 'Reads of the projection' : 'Reads of the table'}>
            {fmtInt(plan.granulesRead)} of {fmtInt(plan.granulesTotal)} granules
            {plan.partsRead != null ? ` · ${fmtInt(plan.partsRead)} ${plan.partsRead === 1 ? 'part' : 'parts'}` : ''}
          </Fact>
        ) : null}
        <Fact label="Answered from">{plan.error ? '—' : plan.projection ? `projection ${plan.projection}` : 'the table itself'}</Fact>
      </dl>
      {projection && !plan.error && plan.projection !== projection ? (
        <p className="body-sm muted">
          The log says this query was answered from {projection}. Planned now, ClickHouse {plan.projection ? `picks ${plan.projection}` : 'reads the table itself'}.
        </p>
      ) : null}
      {plan.error ? (
        <p className="body-sm muted g-wrap">EXPLAIN failed: {plan.error}</p>
      ) : plan.steps.length ? (
        <PlanSteps steps={plan.steps} highlight={highlight} />
      ) : (
        <p className="body-sm muted">No index narrows this query: it reads every granule.</p>
      )}
      {long ? (
        <button type="button" className="cc-btn cc-btn--link" style={{ alignSelf: 'flex-start' }} aria-expanded={full} onClick={() => setFull(!full)}>
          {full ? 'Show less' : 'Show full query'}
        </button>
      ) : null}
    </li>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="caption muted">{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
