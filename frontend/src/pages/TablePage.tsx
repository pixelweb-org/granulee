import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TableIndexUsage } from '../api/types';
import { useApi } from '../hooks/useApi';
import { Button } from '../components/Button';
import { useSources } from '../state/sources';
import { Card, StatTile } from '../components/Card';
import { BytesChart } from '../components/BytesChart';
import { Chart } from '../components/Chart';
import { TableHeader } from '../components/TableHeader';
import { PageHeader } from '../components/PageHeader';
import { ErrorState, Loading } from '../components/States';
import { fmtBytes, fmtInt, fmtPct, fmtRatio, fmtRows } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

// From here on the open-files limit of a default container setup gets close.
const MANY_FILES = 20_000;
const MANY_FILES_PER_COLUMN = 1_000;

const dash = (v: string | null | undefined) => (v && v.trim() ? v : '—');

export function TablePage() {
  const { sourceId = '', database = '', table = '' } = useParams();
  const { selectedId, select, sources } = useSources();
  const { data: t, error, reload } = useApi(() => api.table(sourceId, database, table), [sourceId, database, table]);

  useEffect(() => {
    if (sourceId && sourceId !== selectedId && sources.some((s) => s.id === sourceId)) select(sourceId);
  }, [sourceId, selectedId, sources, select]);

  const [usage, setUsage] = useState<TableIndexUsage | null>(null);
  const [usageError, setUsageError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  useEffect(() => {
    setUsage(null);
    setUsageError(null);
  }, [sourceId, database, table]);
  async function checkIndexes() {
    setChecking(true);
    setUsageError(null);
    try {
      setUsage(await api.indexUsage(sourceId, database, table));
    } catch (e) {
      setUsageError(e instanceof Error ? e.message : String(e));
    } finally {
      setChecking(false);
    }
  }

  if (error && !t) {
    return (
      <>
        <PageHeader eyebrow={<Link to={routes.explorer(sourceId, database)}>{database}</Link>} title={table} />
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!t) return <Loading />;

  const cols = t.columns;
  const top5 = cols.slice(0, 5);
  const other = cols.slice(5).reduce((a, c) => a + c.compressedBytes, 0);
  const shareNames = [...top5.map((c) => c.name), ...(other > 0 ? ['Other'] : [])];
  const shareVals = [...top5.map((c) => c.compressedBytes), ...(other > 0 ? [other] : [])];
  const parts = t.partitionStats;

  return (
    <>
      <TableHeader
        t={t}
        active="structure"
        description={t.comment.trim() ? t.comment : 'Read from system.tables, system.parts, system.columns and system.data_skipping_indices.'}
      />

      <section aria-label="Table size" className="grid-auto" style={vars({ min: 170 })}>
        <StatTile label="Rows" value={fmtRows(t.rows)} />
        <StatTile label="Compressed data" value={fmtBytes(t.compressedBytes)} />
        <StatTile label="Uncompressed data" value={fmtBytes(t.uncompressedBytes)} />
        <StatTile label="Ratio" value={fmtRatio(t.uncompressedBytes, t.compressedBytes)} />
        <StatTile label="Size on disk" value={fmtBytes(t.bytesOnDisk)} sub="incl. marks, PK, skip indexes" />
        <StatTile label="Active parts · partitions" value={`${fmtInt(t.activeParts)} · ${fmtInt(t.partitions)}`} />
        {t.files ? <StatTile label="Files on disk" value={`≈ ${fmtInt(t.files.files)}`} sub={`largest part ≈ ${fmtInt(t.files.largestPartFiles)}`} /> : null}
      </section>

      {t.files && (t.files.files >= MANY_FILES || t.files.widestColumnFiles >= MANY_FILES_PER_COLUMN) ? (
        <p className="g-notice" role="note">
            {t.files.widestColumnFiles >= MANY_FILES_PER_COLUMN ? (
              <>
                Column <strong>{t.files.widestColumn}</strong> alone is about {fmtInt(t.files.widestColumnFiles)} files in every wide part.{' '}
              </>
            ) : null}
            A merge opens the files of every part it reads, and a query opens them once per thread. Keep the server's open-files limit (nofile) well above{' '}
            {fmtInt(t.files.files)}; ClickHouse recommends 262,144. With a lower limit, merges and inserts fail with “Too many open files”.
        </p>
      ) : null}

      <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
        <Card>
          <h2 className="card-title">Structure</h2>
          <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(110px, 150px) minmax(0, 1fr)', gap: '12px 16px', fontSize: 14 }}>
            <Term label="Engine" mono>{dash(t.engineFull)}</Term>
            <Term label="Partition key" mono>{dash(t.partitionKey)}</Term>
            <Term label="Sorting key" mono>{dash(t.sortingKey)}</Term>
            <Term label="Primary key" mono>{dash(t.primaryKey)}</Term>
            <Term label="TTL" mono>{dash(t.ttl)}</Term>
            <Term label="Storage policy" mono>{dash(t.storagePolicy)}</Term>
            {t.buffers.length ? <Term label="Buffer in front" mono>{t.buffers.join(', ')}</Term> : null}
          </dl>
        </Card>
        <Card>
          <h2 className="card-title">Keys and settings</h2>
          <dl style={{ display: 'grid', gridTemplateColumns: 'minmax(140px, 220px) minmax(0, 1fr)', gap: '12px 16px', fontSize: 14 }}>
            <Term label="Primary key in memory">
              {fmtBytes(t.primaryKeyBytesInMemory)} <span className="muted">({fmtBytes(t.primaryKeyBytesInMemoryAllocated)} allocated)</span>
            </Term>
            <Term label="Marks">
              {fmtRows(t.marks)} <span className="muted">· {fmtBytes(t.marksBytes)} on disk</span>
            </Term>
            <Term label="Skip indexes">
              {t.indexes.length ? `${fmtInt(t.indexes.length)} · ${fmtBytes(t.skipIndexBytes)} compressed` : 'None'}
            </Term>
            {t.settings.map((s) => (
              <Term key={s.name} label={s.name} mono>
                {s.value}
              </Term>
            ))}
          </dl>
        </Card>
      </section>

      <section className="stack">
        <div className="between">
          <h2 className="card-title">Columns</h2>
          <span className="caption muted">{fmtInt(cols.length)} columns · sorted by compressed size</span>
        </div>
        <Card table>
          <table className="g-tbl">
            <thead>
              <tr>
                <th>Column</th>
                <th>Type</th>
                <th>Codec</th>
                <th className="r">Compressed</th>
                <th className="r">Uncompressed</th>
                <th className="r">Ratio</th>
                <th style={{ width: 180 }}>Share of table</th>
                <th>Finding</th>
              </tr>
            </thead>
            <tbody>
              {cols.map((c) => {
                const share = t.compressedBytes > 0 ? c.compressedBytes / t.compressedBytes : 0;
                return (
                  <tr key={c.name}>
                    <td className="g-strong">{c.name}</td>
                    <td className="g-mono">{c.type}</td>
                    <td className="g-mono">{c.codec}</td>
                    <td className="r g-strong nowrap">{fmtBytes(c.compressedBytes)}</td>
                    <td className="r nowrap">{fmtBytes(c.uncompressedBytes)}</td>
                    <td className="r">{fmtRatio(c.uncompressedBytes, c.compressedBytes)}</td>
                    <td>
                      <div className="share">
                        <div className="g-track">
                          <div className="g-fill" style={{ width: `${Math.min(100, share * 100)}%` }} />
                        </div>
                        <span className="caption g-num">{fmtPct(share)}</span>
                      </div>
                    </td>
                    <td>
                      {c.finding ? (
                        <Link className="g-link" to={routes.recommendations(sourceId, database, table)}>
                          {c.finding}
                        </Link>
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      </section>

      {cols.length ? (
        <section className="grid-auto" style={vars({ min: 420, gap: 24 })}>
          <BytesChart
            type="bar"
            title="Compressed size by column"
            subtitle="On disk"
            categoryLabel="Column"
            categories={cols.map((c) => c.name)}
            series={[{ name: 'Compressed', values: cols.map((c) => c.compressedBytes) }]}
            height={240}
          />
          <BytesChart
            type="donut"
            title="Where the bytes go"
            subtitle={`Share of ${fmtBytes(t.compressedBytes)} compressed`}
            categoryLabel="Column"
            totalLabel="Compressed"
            categories={shareNames}
            series={[{ name: 'Compressed', values: shareVals }]}
            height={220}
          />
          {parts.length ? (
            <>
              <BytesChart
                type="area"
                title="Compressed size per partition"
                subtitle={`Last ${parts.length} of ${fmtInt(t.partitions)} partitions`}
                categoryLabel="Partition"
                categories={parts.map((p) => p.partition)}
                series={[{ name: 'Compressed', values: parts.map((p) => p.compressedBytes) }]}
                height={200}
              />
              <Chart
                type="bar"
                title="Active parts per partition"
                subtitle={`Last ${parts.length} partitions; recent ones may not be merged yet`}
                categoryLabel="Partition"
                categories={parts.map((p) => p.partition)}
                series={[{ name: 'Active parts', values: parts.map((p) => p.parts) }]}
                height={200}
              />
            </>
          ) : null}
        </section>
      ) : null}

      <section className="stack">
        <div className="between" style={{ alignItems: 'center' }}>
          <h2 className="card-title">Data skipping indexes</h2>
          {t.indexes.length ? (
            <Button variant="secondary" small busy={checking} onClick={() => void checkIndexes()}>
              {checking ? 'Checking…' : usage ? 'Check again' : 'Check against the query log'}
            </Button>
          ) : null}
        </div>
        {usageError ? <p className="g-notice g-wrap">{usageError}</p> : null}
        {usage?.message ? <p className="g-notice">{usage.message}</p> : null}
        {t.indexes.length ? (
          <Card table>
            <table className="g-tbl">
              <thead>
                <tr>
                  <th>Index</th>
                  <th>Expression</th>
                  <th>Type</th>
                  <th className="r">Granularity</th>
                  <th className="r">Compressed</th>
                  <th className="r">Uncompressed</th>
                  {usage && !usage.message ? (
                    <>
                      <th className="r">Used by</th>
                      <th style={{ width: '18%' }}>Granules dropped</th>
                    </>
                  ) : null}
                </tr>
              </thead>
              <tbody>
                {t.indexes.map((i) => {
                  const u = usage?.indexes.find((x) => x.name === i.name);
                  const dropped = u && u.granulesBefore > 0 ? 1 - u.granulesAfter / u.granulesBefore : 0;
                  return (
                    <tr key={i.name}>
                      <td className="g-strong">{i.name}</td>
                      <td className="g-mono">{i.expression}</td>
                      <td className="g-mono">{i.type}</td>
                      <td className="r">{fmtInt(i.granularity)}</td>
                      <td className="r g-strong nowrap">{fmtBytes(i.compressedBytes)}</td>
                      <td className="r nowrap">{fmtBytes(i.uncompressedBytes)}</td>
                      {usage && !usage.message ? (
                        <>
                          <td className="r nowrap">{u && u.shapes > 0 ? `${fmtPct(u.share, 0)} of SELECTs` : 'no logged query'}</td>
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
                        </>
                      ) : null}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
        ) : (
          <p className="body-sm muted">This table has no data skipping indexes.</p>
        )}
        {usage && !usage.message ? (
          <p className="caption muted">
            Planned {fmtInt(usage.checkedShapes)} logged query {usage.checkedShapes === 1 ? 'shape' : 'shapes'} with EXPLAIN indexes = 1, standing for{' '}
            {fmtInt(usage.selects)} SELECTs{usage.failedShapes ? `; ${fmtInt(usage.failedShapes)} could not be planned` : ''}. “Granules dropped” is what an index
            removed from the granules the earlier indexes left over. An index no logged query uses, or one that drops nothing, only costs inserts and disk.
          </p>
        ) : null}
      </section>
    </>
  );
}

function Term({ label, mono, children }: { label: string; mono?: boolean; children: ReactNode }) {
  return (
    <>
      <dt className="muted" style={{ overflowWrap: 'anywhere' }}>{label}</dt>
      <dd className={mono ? 'g-mono' : 'g-num'} style={{ overflowWrap: 'anywhere' }}>
        {children}
      </dd>
    </>
  );
}
