import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Opportunity } from '../api/types';
import { useAction, useApi } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { BytesChart } from '../components/BytesChart';
import { Card, StatTile } from '../components/Card';
import { Chart } from '../components/Chart';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, InlineError, Loading } from '../components/States';
import { download } from '../lib/download';
import { fmtBytes, fmtInt, fmtPct, fmtRatio } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

const keyOf = (o: Opportunity) => `${o.sourceId}/${o.database}.${o.table}`;

/** Every table with open findings: what could be saved, and where to go from there. */
export function RecommendationsListPage() {
  const { sources, loading, selectedId, selected } = useSources();
  const { data, error, reload } = useApi(() => api.opportunities(selectedId), [selectedId]);
  const { data: overview } = useApi(() => api.overview(selectedId), [selectedId]);
  const [filter, setFilter] = useState('');
  const [sizedOnly, setSizedOnly] = useState(false);
  const exporting = useAction();

  const items = useMemo(() => [...(data ?? [])].sort((a, b) => b.savingsBytes - a.savingsBytes || b.findings - a.findings), [data]);
  const sized = items.filter((o) => o.savingsBytes > 0);
  const findings = items.reduce((a, o) => a + o.findings, 0);
  const savings = items.reduce((a, o) => a + o.savingsBytes, 0);
  const top = sized[0]?.savingsBytes ?? 0;
  const several = new Set(items.map((o) => o.sourceId)).size > 1;
  const needle = filter.trim().toLowerCase();
  const rows = items.filter((o) => (!sizedOnly || o.savingsBytes > 0) && (!needle || `${o.database}.${o.table}`.toLowerCase().includes(needle)));
  const totalBytes = overview?.totals.compressedBytes ?? 0;
  const categories = overview?.findingsByCategory;

  async function exportAll() {
    const parts: string[] = [];
    for (const o of items) {
      const recs = await api.recommendations(o.sourceId, o.database, o.table);
      if (!recs.items.length) continue;
      parts.push(`-- ===== ${o.sourceName}: ${o.database}.${o.table} =====`);
      for (const r of recs.items) parts.push(`-- ${r.title} (${r.category})\n${r.sql.trim()}\n`);
    }
    download(`${selected ? selected.name : 'all-sources'}.recommendations.sql`, parts.join('\n'));
  }

  return (
    <>
      <PageHeader
        eyebrow={`Recommendations · ${selected ? selected.name : 'All sources'}`}
        title="Recommendations"
        description={`Every table with open findings ${selected ? 'on this server' : 'across all sources'}. Pick a table to read its recommendations, or go straight to a trial.`}
        actions={
          items.length ? (
            <Button variant="dark" busy={exporting.busy} onClick={() => void exporting.run(exportAll)}>
              {exporting.busy ? 'Exporting…' : 'Export all as SQL'}
            </Button>
          ) : undefined
        }
      />
      <InlineError message={exporting.error} />

      {!loading && !sources.length ? (
        <EmptyState title="No data sources yet" action={<Button to="/sources">Add a data source</Button>}>
          Recommendations come from scanning a ClickHouse server.
        </EmptyState>
      ) : error && !data ? (
        <ErrorState message={error} onRetry={() => reload()} />
      ) : !data ? (
        <Loading />
      ) : !items.length ? (
        <EmptyState title="No open findings">No table has open findings. If you have not scanned yet, run a scan from the overview.</EmptyState>
      ) : (
        <>
          <section aria-label="Totals" className="grid-auto" style={vars({ min: 220 })}>
            <StatTile
              emphasis
              label="Estimated savings"
              value={savings > 0 ? `≈ ${fmtBytes(savings)}` : '—'}
              sub={savings > 0 && totalBytes > 0 ? `${fmtPct(savings / totalBytes)} of ${fmtBytes(totalBytes)} compressed` : undefined}
            />
            <StatTile
              label="Open findings"
              value={fmtInt(findings)}
              sub={overview ? `in ${fmtInt(items.length)} of ${fmtInt(overview.totals.tables)} tables` : `in ${fmtInt(items.length)} tables`}
            />
            <StatTile label="With measurable savings" value={fmtInt(sized.length)} sub={sized.length === 1 ? 'table' : 'tables'} />
            <StatTile label="Review only" value={fmtInt(items.length - sized.length)} sub="tables, no size estimate" />
          </section>

          <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
            {sized.length ? (
              <BytesChart
                type="bar"
                title="Estimated savings by table"
                subtitle="Compressed, tables with a size estimate"
                categoryLabel="Table"
                categories={sized.slice(0, 8).map((o) => o.table)}
                series={[{ name: 'Est. savings', values: sized.slice(0, 8).map((o) => o.savingsBytes) }]}
                height={220}
              />
            ) : null}
            {categories?.categories.length ? (
              <Chart
                type="bar"
                title="Findings by category"
                subtitle={`${fmtInt(findings)} findings across ${fmtInt(items.length)} tables`}
                categoryLabel="Category"
                categories={categories.categories}
                series={categories.series}
                height={220}
              />
            ) : null}
          </section>

          <section className="stack">
            <div className="page-header">
              <div className="cc-field" style={{ flex: '1 1 320px', maxWidth: 420 }}>
                <label htmlFor="recs-filter" className="cc-field__label">
                  Filter tables
                </label>
                <input id="recs-filter" className="cc-field__input" placeholder="Database or table" value={filter} onChange={(e) => setFilter(e.target.value)} />
              </div>
              <label className="check" style={{ minHeight: 44, gap: 8 }}>
                <input type="checkbox" checked={sizedOnly} onChange={(e) => setSizedOnly(e.target.checked)} /> Only tables with a size estimate
              </label>
            </div>
            <Card table>
              <table className="g-tbl">
                <thead>
                  <tr>
                    <th>Table</th>
                    <th className="r">Compressed</th>
                    <th className="r">Ratio</th>
                    <th className="r">Findings</th>
                    <th className="r">Est. savings</th>
                    <th style={{ width: '20%' }}>Share of savings</th>
                    <th className="r">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((o) => (
                    <tr key={keyOf(o)}>
                      <td>
                        <Link className="g-link g-wrap" to={routes.recommendations(o.sourceId, o.database, o.table)} style={{ fontWeight: 700 }}>
                          {o.database}.{o.table}
                        </Link>
                        {several ? <div className="caption">{o.sourceName}</div> : null}
                      </td>
                      <td className="r nowrap">{fmtBytes(o.compressedBytes)}</td>
                      <td className="r">{fmtRatio(o.uncompressedBytes, o.compressedBytes)}</td>
                      <td className="r g-strong">{fmtInt(o.findings)}</td>
                      <td className="r g-strong nowrap">{o.savingsBytes > 0 ? `≈ ${fmtBytes(o.savingsBytes)}` : '—'}</td>
                      <td>
                        {o.savingsBytes > 0 && top > 0 ? (
                          <div className="share">
                            <div className="g-track">
                              <div className="g-fill" style={{ width: `${Math.max(1, (o.savingsBytes / top) * 100)}%` }} />
                            </div>
                          </div>
                        ) : (
                          <span className="caption">Review only</span>
                        )}
                      </td>
                      <td className="r nowrap">
                        <Link className="g-link" to={routes.newTrial(o.sourceId, o.database, o.table)}>
                          Set up a trial
                        </Link>
                      </td>
                    </tr>
                  ))}
                  {!rows.length ? (
                    <tr>
                      <td colSpan={7}>No tables match the filter.</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </Card>
            <p className="caption muted">
              Review-only findings need a check on your side first, such as counting NULLs, so Granulee gives no size estimate for them.
            </p>
          </section>
        </>
      )}
    </>
  );
}
