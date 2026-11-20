import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { QueryLogNote } from '../components/QueryLogGuide';
import { useSources } from '../state/sources';
import type { ChangeKind, Recommendation, RecommendationCategory, Severity } from '../api/types';
import { useAction, useApi } from '../hooks/useApi';
import { Button, Choice } from '../components/Button';
import { Card, StatTile } from '../components/Card';
import { BytesChart } from '../components/BytesChart';
import { CopyButton } from '../components/CodeBlock';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, InlineError, Loading } from '../components/States';
import { Status, type Tone } from '../components/StatusDot';
import { fmtBytes, fmtBytesPair, fmtClock, fmtInt, fmtPct, fmtRows } from '../lib/format';
import { download } from '../lib/download';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';

const SEVERITY: Record<Severity, { label: string; tone: Tone }> = {
  high: { label: 'High', tone: 'error' },
  medium: { label: 'Medium', tone: 'warning' },
  low: { label: 'Low', tone: 'muted' },
  review: { label: 'Review', tone: 'muted' },
};
const CHANGE: Record<ChangeKind, string> = { online: 'Online ALTER', mutation: 'Mutation', rebuild: 'Table rebuild' };

type Group = 'keys' | 'codecs' | 'types' | 'indexes';
const GROUP_OF: Record<RecommendationCategory, Group> = {
  Partitioning: 'keys',
  'Sorting key': 'keys',
  Codec: 'codecs',
  'Data type': 'types',
  'Skip index': 'indexes',
};
const GROUPS: { key: Group; label: string }[] = [
  { key: 'keys', label: 'Partitioning and keys' },
  { key: 'codecs', label: 'Codecs' },
  { key: 'types', label: 'Data types' },
  { key: 'indexes', label: 'Skip indexes' },
];

export function RecommendationsPage() {
  const { sourceId = '', database = '', table = '' } = useParams();
  const { data, error, reload } = useApi(() => api.recommendations(sourceId, database, table), [sourceId, database, table]);
  const { sources } = useSources();
  const [group, setGroup] = useState<Group | 'all'>('all');
  const action = useAction();

  const counts = useMemo(() => {
    const c: Record<Group, number> = { keys: 0, codecs: 0, types: 0, indexes: 0 };
    for (const r of data?.items ?? []) c[GROUP_OF[r.category]]++;
    return c;
  }, [data]);

  const eyebrow = (
    <Link to={routes.table(sourceId, database, table)}>
      Recommendations · {database}.{table}
    </Link>
  );

  if (error && !data) {
    return (
      <>
        <PageHeader eyebrow={eyebrow} title="Recommendations" />
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!data) return <Loading />;

  const activeGroup = group !== 'all' && counts[group] === 0 ? 'all' : group;
  const visible = data.items.filter((r) => activeGroup === 'all' || GROUP_OF[r.category] === activeGroup);
  const n = data.items.length;
  const pct = data.compressedBytes > 0 ? data.savingsBytes / data.compressedBytes : 0;
  const trial = data.latestTrial;
  const changed = trial?.result?.columns.filter((c) => c.candidate != null) ?? [];

  const queryLog =
    data.queryLogQueries == null
      ? 'query_log was not readable, so sorting-key and index checks are limited'
      : `${data.queryLogWindowDays} days of query_log (${fmtInt(data.queryLogQueries)} SELECTs on this table)`;

  return (
    <>
      <PageHeader
        eyebrow={eyebrow}
        title={`${fmtInt(n)} ${n === 1 ? 'recommendation' : 'recommendations'}`}
        description={
          <>
            Checked against ClickHouse schema best practices and {queryLog}.
            {data.savingsBytes > 0 ? ` Estimated savings ≈ ${fmtBytes(data.savingsBytes)} (${fmtPct(pct)}) of compressed data.` : ''}
          </>
        }
        actions={
          <>
            {visible.length ? (
              <Button
                variant="secondary"
                onClick={() =>
                  download(
                    `${database}.${table}.recommendations.sql`,
                    visible.map((r) => `-- ${r.title} (${r.category}, ${CHANGE[r.changeKind]})\n${r.sql.trim()}\n`).join('\n'),
                  )
                }
              >
                Export as SQL
              </Button>
            ) : null}
            <Button to={routes.newTrial(sourceId, database, table)}>Set up a trial</Button>
          </>
        }
      />

      <QueryLogNote source={sources.find((s) => s.id === sourceId)} />

      <section aria-label="Summary" className="grid-auto" style={vars({ min: 200 })}>
        <StatTile emphasis label="Estimated savings" value={data.savingsBytes > 0 ? `≈ ${fmtBytes(data.savingsBytes)}` : '—'} />
        <StatTile label="Online ALTER" value={fmtInt(data.counts.online)} />
        <StatTile label="Mutation (rewrites column)" value={fmtInt(data.counts.mutation)} />
        <StatTile label="Table rebuild" value={fmtInt(data.counts.rebuild)} />
      </section>

      {trial?.result ? (
        <Card as="section">
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start', ...vars({ gap: 16 }) }}>
            <div className="stack" style={{ maxWidth: 760, ...vars({ gap: 6 }) }}>
              <p className="eyebrow">
                Latest trial · finished {fmtClock(trial.finishedAt)} ·{' '}
                <Link className="g-link" to={routes.trial(trial.id)} style={{ textDecoration: 'none' }}>
                  Open full result
                </Link>
              </p>
              <h2 className="card-title">Measured on {fmtRows(trial.result.rows)} rows</h2>
              <p className="body-sm muted">
                Granulee copied the same {fmtRows(trial.result.rows)} rows into {trial.scratchDatabase}.{trial.baseTable} with the current schema and into{' '}
                {trial.scratchDatabase}.{trial.candTable} with the changes applied, then compared sizes in system.columns. The projection scales the sample to{' '}
                {fmtRows(trial.result.totalRows)} rows.
              </p>
            </div>
            <div className="row" style={vars({ gap: 24 })}>
              <Stat label="Projected, current" value={fmtBytes(trial.result.projectedBaseBytes)} />
              <Stat label="Projected, with changes" value={fmtBytes(trial.result.projectedCandBytes)} />
              <Stat label="Sample size" value={fmtBytesPair(sum(trial.result.columns.map((c) => c.baseBytes)), sum(trial.result.columns.map((c) => c.candBytes)))} />
            </div>
          </div>
          {changed.length ? (
            <BytesChart
              type="bar"
              bare
              title="Current vs. with changes"
              subtitle="Compressed size per changed column in the sample"
              categoryLabel="Column"
              categories={changed.map((c) => c.name)}
              series={[
                { name: 'Current', values: changed.map((c) => c.baseBytes) },
                { name: 'With changes', values: changed.map((c) => c.candBytes) },
              ]}
              height={220}
            />
          ) : null}
        </Card>
      ) : null}

      {n ? (
        <div role="group" aria-label="Filter recommendations" className="row">
          <Choice pressed={activeGroup === 'all'} onClick={() => setGroup('all')}>
            All {n}
          </Choice>
          {GROUPS.filter((g) => counts[g.key] > 0).map((g) => (
            <Choice key={g.key} pressed={activeGroup === g.key} onClick={() => setGroup(g.key)}>
              {g.label} {counts[g.key]}
            </Choice>
          ))}
        </div>
      ) : null}

      <InlineError message={action.error} />

      <section aria-label="Recommendations" className="stack">
        {!n ? (
          <EmptyState title="No open recommendations">
            {data.dismissed > 0 ? 'Every finding for this table has been dismissed.' : 'The checks found nothing to change on this table.'}
          </EmptyState>
        ) : null}
        {visible.map((r) => (
          <RecommendationCard
            key={r.id}
            rec={r}
            busy={action.busy}
            onDismiss={async () => {
              if (await action.run(() => api.dismissRecommendation(sourceId, database, table, r.id))) await reload(true);
            }}
          />
        ))}
        {data.dismissed > 0 ? (
          <div className="row">
            <Button
              variant="secondary"
              busy={action.busy}
              onClick={async () => {
                if (await action.run(() => api.restoreDismissed(sourceId, database, table))) await reload(true);
              }}
            >
              Restore {fmtInt(data.dismissed)} dismissed
            </Button>
          </div>
        ) : null}
      </section>
    </>
  );
}

const sum = (v: number[]) => v.reduce((a, b) => a + b, 0);

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stack" style={vars({ gap: 4 })}>
      <span className="caption muted">{label}</span>
      <span className="subhead g-num">{value}</span>
    </div>
  );
}

function RecommendationCard({ rec, busy, onDismiss }: { rec: Recommendation; busy: boolean; onDismiss: () => void }) {
  const [showQueries, setShowQueries] = useState(false);
  const sev = SEVERITY[rec.severity];
  return (
    <article className="card" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 24 }}>
      <div className="stack" style={{ flex: '1 1 420px', ...vars({ gap: 12 }) }}>
        <div className="row" style={vars({ gap: 12 })}>
          <Status tone={sev.tone} className="caption">
            {sev.label}
          </Status>
          <span className="caption muted">
            {rec.category} · {CHANGE[rec.changeKind]}
          </span>
        </div>
        <h3 className="subhead">{rec.title}</h3>
        <p className="body-sm muted">{rec.body}</p>
        {rec.effect ? <p className="body-sm g-num">{rec.effect}</p> : null}
      </div>
      <div className="stack" style={{ flex: '1 1 420px', ...vars({ gap: 12 }) }}>
        <pre className="g-code">{rec.sql}</pre>
        <div className="row" style={vars({ gap: 16 })}>
          <CopyButton text={rec.sql}>Copy SQL</CopyButton>
          {rec.queries.length ? (
            <Button variant="secondary" aria-expanded={showQueries} onClick={() => setShowQueries(!showQueries)}>
              {showQueries ? 'Hide queries' : 'Show queries'}
            </Button>
          ) : null}
          <Button variant="link" disabled={busy} onClick={onDismiss}>
            Dismiss
          </Button>
        </div>
      </div>
      {showQueries ? (
        <div style={{ flex: '1 1 100%', minWidth: 0, overflowX: 'auto' }}>
          <table className="g-tbl">
            <thead>
              <tr>
                <th>Query (normalized)</th>
                <th className="r">Runs</th>
                <th className="r">Share</th>
              </tr>
            </thead>
            <tbody>
              {rec.queries.map((q, i) => (
                <tr key={i}>
                  <td className="g-mono" style={{ overflowWrap: 'anywhere' }}>{q.query}</td>
                  <td className="r">{fmtInt(q.count)}</td>
                  <td className="r">{fmtPct(q.share, 0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </article>
  );
}
