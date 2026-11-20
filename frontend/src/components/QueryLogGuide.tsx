import { Link } from 'react-router-dom';
import type { Source } from '../api/types';
import { Card } from './Card';
import { CodeBlock } from './CodeBlock';

type State = NonNullable<Source['queryLog']>['state'];

/** True when the last scan could not use system.query_log on this source. */
export const lacksQueryLog = (s: Source | null | undefined): s is Source => !!s?.queryLog && s.queryLog.state !== 'ok';

const HEADLINE: Record<Exclude<State, 'ok'>, (name: string) => string> = {
  missing: (n) => `The query log is switched off on ${n}`,
  denied: (n) => `Granulee may not read the query log on ${n}`,
  empty: (n) => `The query log on ${n} has no entries`,
  error: (n) => `The query log on ${n} could not be read`,
};

const MISSING = ['Sorting-key recommendations', 'Proposals for new skip indexes, and the check of existing ones', 'The Usage tab of every table', 'Query replay in trials'];

const CONFIG = `<!-- /etc/clickhouse-server/config.d/query_log.xml -->
<clickhouse>
    <query_log>
        <database>system</database>
        <table>query_log</table>
        <partition_by>toYYYYMM(event_date)</partition_by>
        <ttl>event_date + INTERVAL 30 DAY DELETE</ttl>
        <flush_interval_milliseconds>7500</flush_interval_milliseconds>
    </query_log>
</clickhouse>`;

/** One line for pages whose findings depend on the query log, pointing at the guide. */
export function QueryLogNote({ source }: { source: Source | null | undefined }) {
  if (!lacksQueryLog(source)) return null;
  return (
    <p className="g-notice">
      {HEADLINE[source.queryLog!.state as Exclude<State, 'ok'>](source.name)}, so part of the analysis is missing: sorting-key and skip-index findings, table usage
      and query replay in trials.{' '}
      <Link className="g-link" to="/sources#query-log">
        How to switch it on
      </Link>
    </p>
  );
}

/** What is missing without system.query_log, and the steps to get it, fitted to why it could not be read. */
export function QueryLogGuide({ source }: { source: Source | null | undefined }) {
  if (!lacksQueryLog(source)) return null;
  const state = source.queryLog!.state as Exclude<State, 'ok'>;
  const user = source.username || 'granulee_ro';
  const grant = `GRANT SELECT ON system.query_log TO ${user};`;
  return (
    <Card as="section" emphasis>
      <div className="stack" style={{ gap: 4 }}>
        <p className="eyebrow">Query log · {source.name}</p>
        <h2 className="card-title">{HEADLINE[state](source.name)}</h2>
      </div>
      <div className="grid-auto" style={{ ['--min' as string]: '320px', ['--gap' as string]: '24px', alignItems: 'start' }}>
        <div className="stack" style={{ gap: 8 }}>
          <p className="body-sm muted">
            Granulee reads how tables are queried from system.query_log. Without it the scan still covers sizes, codecs, data types, partitioning and file counts, and
            trials still measure size, inserts and merges. Not available on this source:
          </p>
          <ul className="body-sm stack" style={{ margin: 0, paddingLeft: 20, gap: 4 }}>
            {MISSING.map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
          {source.queryLog!.message && state !== 'empty' ? <p className="caption muted g-wrap">ClickHouse said: {source.queryLog!.message}</p> : null}
        </div>
        <div className="stack" style={{ gap: 12 }}>
          {state === 'missing' ? (
            <ol className="body-sm stack" style={{ margin: 0, paddingLeft: 20, gap: 12 }}>
              <li>
                Add the query log to the server configuration. If a file in config.d removes it with <span className="g-mono">&lt;query_log remove="1"/&gt;</span>, delete
                that line instead.
                <div style={{ marginTop: 8 }}>
                  <CodeBlock code={CONFIG} copyLabel="Copy config" />
                </div>
              </li>
              <li>Restart ClickHouse. The table system.query_log appears with the first query after that; the TTL keeps 30 days.</li>
              <li>
                Let {user} read it, unless it already may read all of system:
                <div style={{ marginTop: 8 }}>
                  <CodeBlock code={grant} copyLabel="Copy SQL" />
                </div>
              </li>
              <li>Scan the source again. Usage-based findings fill in as the log collects queries.</li>
            </ol>
          ) : state === 'denied' ? (
            <ol className="body-sm stack" style={{ margin: 0, paddingLeft: 20, gap: 12 }}>
              <li>
                The server keeps a query log, but {user} has no right to read it. Grant it:
                <div style={{ marginTop: 8 }}>
                  <CodeBlock code={grant} copyLabel="Copy SQL" />
                </div>
              </li>
              <li>Scan the source again.</li>
            </ol>
          ) : state === 'empty' ? (
            <ol className="body-sm stack" style={{ margin: 0, paddingLeft: 20, gap: 12 }}>
              <li>
                The table exists but holds nothing from the last {source.queryLogWindowDays} days. Check that queries are logged for the users your applications connect
                with:
                <div style={{ marginTop: 8 }}>
                  <CodeBlock code={"SELECT name, value, changed FROM system.settings WHERE name IN ('log_queries', 'log_queries_probability');"} copyLabel="Copy SQL" />
                </div>
              </li>
              <li>
                <span className="g-mono">log_queries</span> has to be 1 in their settings profile. Entries reach the table a few seconds after a query ends.
              </li>
              <li>Scan the source again once queries have run.</li>
            </ol>
          ) : (
            <p className="body-sm muted">Fix what ClickHouse reports, then scan the source again.</p>
          )}
        </div>
      </div>
    </Card>
  );
}
