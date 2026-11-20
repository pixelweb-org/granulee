import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { QueryLogNote } from '../components/QueryLogGuide';
import { useAction, useApi } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { StatTile } from '../components/Card';
import { BytesChart } from '../components/BytesChart';
import { Chart } from '../components/Chart';
import { OpportunitiesTable } from '../components/OpportunitiesTable';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, InlineError, Loading } from '../components/States';
import { Status, sourceStatusShort, sourceTone } from '../components/StatusDot';
import { fmtBytes, fmtInt, fmtRatio } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

export function OverviewPage() {
  const { sources, loading: sourcesLoading, selectedId, selected, reload: reloadSources } = useSources();
  const overview = useApi(() => api.overview(selectedId), [selectedId]);
  const scan = useAction();
  const data = overview.data;

  async function scanNow() {
    const ok = await scan.run(() => (selectedId ? api.scanSource(selectedId) : api.scanAll()));
    await Promise.all([reloadSources(), overview.reload(true)]);
    return ok;
  }

  const header = (
    <PageHeader
      eyebrow="Overview"
      title={selected ? selected.name : 'All sources'}
      description={
        selected
          ? 'Storage, compression and open findings on this ClickHouse server.'
          : 'Storage, compression and open findings across every connected ClickHouse server.'
      }
      actions={
        sources.length ? (
          <Button busy={scan.busy} onClick={scanNow}>
            {scan.busy ? 'Scanning…' : 'Scan now'}
          </Button>
        ) : undefined
      }
    />
  );

  if (!sourcesLoading && !sources.length) {
    return (
      <>
        {header}
        <EmptyState title="No data sources yet" action={<Button to="/sources">Add a data source</Button>}>
          Connect a ClickHouse server and Granulee will scan its system tables for storage, compression and schema findings.
        </EmptyState>
      </>
    );
  }

  if (overview.error && !data) {
    return (
      <>
        {header}
        <ErrorState message={overview.error} onRetry={() => overview.reload()} />
      </>
    );
  }
  if (!data) {
    return (
      <>
        {header}
        <Loading />
      </>
    );
  }

  const t = data.totals;
  const scanned = data.lastScanAt != null;
  const hasFindings = data.findingsByCategory.series.some((s) => s.values.some((v) => v > 0));

  return (
    <>
      {header}
      <InlineError message={scan.error} />

      {!scanned ? (
        <EmptyState title="Not scanned yet">Run a scan to read table sizes, compression and schema findings from the system tables.</EmptyState>
      ) : (
        <>
          {(selected ? [selected] : sources).map((s) => (
            <QueryLogNote key={s.id} source={s} />
          ))}
        <section aria-label="Totals" className="grid-auto" style={vars({ min: 200 })}>
          <StatTile label="Compressed on disk" value={fmtBytes(t.compressedBytes)} />
          <StatTile label="Uncompressed" value={fmtBytes(t.uncompressedBytes)} />
          <StatTile label="Compression ratio" value={fmtRatio(t.uncompressedBytes, t.compressedBytes)} />
          <StatTile label="Tables scanned" value={fmtInt(t.tables)} />
          <StatTile label="Open findings" value={fmtInt(t.findings)} />
          <StatTile emphasis label="Potential savings" value={t.savingsBytes > 0 ? `≈ ${fmtBytes(t.savingsBytes)}` : '—'} />
        </section>
        </>
      )}

      <section className="stack">
        <div className="between">
          <h2 className="card-title">Data sources</h2>
          <Link className="g-link body-sm" to="/sources">
            Manage sources
          </Link>
        </div>
        <div className="grid-auto" style={vars({ min: 240 })}>
          {data.sources.map((s) => (
            <Link key={s.id} to={s.status.state === 'unreachable' ? '/sources' : routes.explorer(s.id)} className="card card-link">
              <Status tone={sourceTone(s.status)} className={`caption${s.status.state === 'unreachable' ? '' : ' muted'}`}>
                {sourceStatusShort(s.status)}
              </Status>
              <span className="subhead" style={{ overflowWrap: 'anywhere' }}>{s.name}</span>
              <span className="caption g-num muted">
                {[
                  s.status.version ? `ClickHouse ${s.status.version}` : null,
                  s.tables != null ? `${fmtInt(s.tables)} tables` : 'not scanned yet',
                  s.compressedBytes != null ? fmtBytes(s.compressedBytes) : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            </Link>
          ))}
        </div>
      </section>

      {scanned ? (
        <>
          <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
            {data.growth.categories.length ? (
              <BytesChart
                type="line"
                title="Compressed storage"
                subtitle={`On disk per source, last ${data.growth.categories.length} scan ${data.growth.categories.length === 1 ? 'day' : 'days'}`}
                categoryLabel="Day"
                categories={data.growth.categories}
                series={data.growth.series}
                height={240}
              />
            ) : (
              <div className="card">
                <h2 className="subhead">Compressed storage</h2>
                <p className="body-sm muted">No scan history yet.</p>
              </div>
            )}
            {hasFindings ? (
              <Chart
                type="donut"
                title="Open findings by category"
                subtitle={`${fmtInt(t.findings)} findings across ${fmtInt(t.tables)} tables`}
                categoryLabel="Category"
                totalLabel="Findings"
                categories={data.findingsByCategory.categories}
                series={data.findingsByCategory.series}
                height={220}
              />
            ) : (
              <div className="card">
                <h2 className="subhead">Open findings by category</h2>
                <p className="body-sm muted">No open findings in {fmtInt(t.tables)} tables.</p>
              </div>
            )}
          </section>

          <section className="stack">
            <div className="between">
              <h2 className="card-title">Biggest opportunities</h2>
              <Link className="g-link body-sm" to="/recommendations">
                All recommendations
              </Link>
            </div>
            {data.opportunities.length ? (
              <OpportunitiesTable items={data.opportunities} linkTo="table" />
            ) : (
              <p className="body-sm muted">No tables with findings.</p>
            )}
          </section>
        </>
      ) : null}
    </>
  );
}
