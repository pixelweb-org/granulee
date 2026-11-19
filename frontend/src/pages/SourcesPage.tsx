import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { ConnectionTest, Protocol, Schedule, Source, SourceInput } from '../api/types';
import { useAction } from '../hooks/useApi';
import { useSources } from '../state/sources';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { CodeBlock } from '../components/CodeBlock';
import { Dialog } from '../components/Dialog';
import { Checkbox, Select, TextInput } from '../components/Form';
import { PageHeader } from '../components/PageHeader';
import { ErrorState, InlineError, Loading } from '../components/States';
import { Status, sourceStatusLong, sourceTone } from '../components/StatusDot';
import { fmtClock, fmtDuration } from '../lib/format';
import { vars } from '../lib/style';

const DEFAULT_PORT: Record<Protocol, number> = { https: 8443, http: 8123 };
const SCHEDULE_SHORT: Record<Schedule, string> = { every6h: 'Every 6 h', daily: 'Daily', weekly: 'Weekly', manual: 'Manual' };

interface FormState {
  name: string;
  protocol: Protocol;
  host: string;
  port: string;
  username: string;
  password: string;
  includeDatabases: string;
  excludeDatabases: string;
  schedule: Schedule;
  queryLogWindowDays: string;
  trialsEnabled: boolean;
  scratchDatabase: string;
}

const EMPTY: FormState = {
  name: '',
  protocol: 'https',
  host: '',
  port: '8443',
  username: 'granulee_ro',
  password: '',
  includeDatabases: '',
  excludeDatabases: 'system, information_schema, INFORMATION_SCHEMA',
  schedule: 'every6h',
  queryLogWindowDays: '30',
  trialsEnabled: true,
  scratchDatabase: 'scratch',
};

function fromSource(s: Source): FormState {
  return {
    name: s.name,
    protocol: s.protocol,
    host: s.host,
    port: String(s.port),
    username: s.username,
    password: '',
    includeDatabases: s.includeDatabases,
    excludeDatabases: s.excludeDatabases,
    schedule: s.schedule,
    queryLogWindowDays: String(s.queryLogWindowDays),
    trialsEnabled: s.trialsEnabled,
    scratchDatabase: s.scratchDatabase,
  };
}

function toInput(f: FormState): SourceInput {
  return {
    name: f.name.trim(),
    protocol: f.protocol,
    host: f.host.trim(),
    port: parseInt(f.port, 10) || DEFAULT_PORT[f.protocol],
    username: f.username.trim(),
    password: f.password === '' ? null : f.password,
    includeDatabases: f.includeDatabases.trim(),
    excludeDatabases: f.excludeDatabases.trim(),
    schedule: f.schedule,
    queryLogWindowDays: Math.max(1, parseInt(f.queryLogWindowDays, 10) || 30),
    trialsEnabled: f.trialsEnabled,
    scratchDatabase: f.scratchDatabase.trim(),
  };
}

export function SourcesPage() {
  const { sources, loading, error, reload, select, selectedId } = useSources();
  const [form, setForm] = useState<FormState>(EMPTY);
  const [editing, setEditing] = useState<Source | null>(null);
  const [test, setTest] = useState<ConnectionTest | null>(null);
  const [deleting, setDeleting] = useState<Source | null>(null);
  const [scanningId, setScanningId] = useState<string | null>(null);
  const save = useAction();
  const testing = useAction();
  const scan = useAction();
  const remove = useAction();

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    setTest(null);
  };

  function startEdit(s: Source) {
    setEditing(s);
    setForm(fromSource(s));
    setTest(null);
    save.clearError();
    document.getElementById('source-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  function reset() {
    setEditing(null);
    setForm(EMPTY);
    setTest(null);
    save.clearError();
  }

  const valid = form.name.trim() !== '' && form.host.trim() !== '' && form.username.trim() !== '';

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    const ok = await save.run(() => (editing ? api.updateSource(editing.id, toInput(form)) : api.createSource(toInput(form))));
    if (ok) {
      reset();
      await reload();
    }
  }

  async function onTest() {
    setTest(null);
    await testing.run(async () => {
      setTest(await api.testSource({ ...toInput(form), ...(editing ? { id: editing.id } : {}) }));
    });
  }

  const scratch = form.scratchDatabase.trim() || 'scratch';
  const user = form.username.trim() || 'granulee_ro';
  const grant = [
    `CREATE USER ${user}`,
    `  IDENTIFIED WITH sha256_password BY '…';`,
    `GRANT SELECT ON system.* TO ${user};`,
    ...(form.trialsEnabled
      ? [
          '',
          '-- Only if trials are enabled',
          '-- (copies a sample of the table):',
          `GRANT SELECT ON <database>.* TO ${user};`,
          'GRANT CREATE TABLE, DROP TABLE,',
          `      INSERT, SELECT, OPTIMIZE ON ${scratch}.*`,
          `  TO ${user};`,
        ]
      : []),
  ].join('\n');

  return (
    <>
      <PageHeader
        eyebrow="Settings"
        title="Data sources"
        description="ClickHouse servers Granulee scans. Credentials are encrypted at rest on the Granulee server."
      />

      {error && !sources.length ? (
        <ErrorState message={error} onRetry={() => void reload()} />
      ) : loading ? (
        <Loading />
      ) : sources.length ? (
        <>
          <Card table>
            <table className="g-tbl">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Endpoint</th>
                  <th>Version</th>
                  <th>Status</th>
                  <th>Schedule</th>
                  <th className="r">Last scan</th>
                  <th className="r">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {sources.map((s) => (
                  <tr key={s.id}>
                    <td className="g-strong">{s.name}</td>
                    <td className="g-mono">
                      {s.protocol}://{s.host}:{s.port}
                    </td>
                    <td>{s.status.version ?? '—'}</td>
                    <td style={{ maxWidth: 320 }}>
                      <Status tone={sourceTone(s.status)}>
                        <span style={{ overflowWrap: 'break-word' }}>{sourceStatusLong(s.status)}</span>
                      </Status>
                    </td>
                    <td className="nowrap">{SCHEDULE_SHORT[s.schedule]}</td>
                    <td className="r nowrap">
                      {s.lastScan ? (
                        s.lastScan.error ? (
                          <span title={s.lastScan.error}>{fmtClock(s.lastScan.at)} · failed</span>
                        ) : (
                          `${fmtClock(s.lastScan.at)} · ${fmtDuration(s.lastScan.durationMs)}`
                        )
                      ) : (
                        'Never'
                      )}
                    </td>
                    <td className="r nowrap">
                      <span className="row" style={{ justifyContent: 'flex-end', flexWrap: 'nowrap', ...vars({ gap: 16 }) }}>
                        <button
                          type="button"
                          className="g-link"
                          disabled={scan.busy}
                          onClick={async () => {
                            setScanningId(s.id);
                            await scan.run(() => api.scanSource(s.id));
                            setScanningId(null);
                            await reload();
                          }}
                        >
                          {scanningId === s.id ? 'Scanning…' : 'Scan'}
                        </button>
                        <button type="button" className="g-link" onClick={() => startEdit(s)}>
                          Edit
                        </button>
                        <button type="button" className="g-link" onClick={() => setDeleting(s)}>
                          Delete
                        </button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <InlineError message={scan.error} />
        </>
      ) : (
        <p className="body muted">No data sources yet — add one below.</p>
      )}

      <section className="split">
        <form id="source-form" className="card split-main" style={{ padding: 32, gap: 24 }} onSubmit={onSubmit} noValidate>
          <div className="stack" style={vars({ gap: 6 })}>
            <p className="eyebrow">{editing ? `Edit source · ${editing.name}` : 'New source'}</p>
            <h2 className="card-title">{editing ? 'Edit this ClickHouse server' : 'Add a ClickHouse server'}</h2>
          </div>

          <div className="grid-2">
            <TextInput label="Name" placeholder="prod-analytics-03" value={form.name} onChange={(e) => set('name', e.target.value)} required />
            <Select
              label="Protocol"
              value={form.protocol}
              onChange={(e) => {
                const protocol = e.target.value as Protocol;
                setForm((f) => ({ ...f, protocol, port: String(DEFAULT_PORT[protocol]) }));
                setTest(null);
              }}
            >
              <option value="https">HTTPS (8443)</option>
              <option value="http">HTTP (8123)</option>
            </Select>
            <TextInput label="Host" placeholder="ch.example.internal" value={form.host} onChange={(e) => set('host', e.target.value)} required autoCapitalize="none" spellCheck={false} />
            <TextInput label="Port" type="number" min={1} max={65535} value={form.port} onChange={(e) => set('port', e.target.value)} />
            <TextInput
              label="User"
              value={form.username}
              onChange={(e) => set('username', e.target.value)}
              help="Needs SELECT on system tables only"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
            />
            <TextInput
              label="Password"
              type="password"
              placeholder={editing?.hasPassword ? 'Unchanged' : '••••••••'}
              value={form.password}
              onChange={(e) => set('password', e.target.value)}
              help={editing?.hasPassword ? 'Leave empty to keep the stored password' : undefined}
              autoComplete="new-password"
            />
            <TextInput
              label="Include databases"
              placeholder="All except system"
              value={form.includeDatabases}
              onChange={(e) => set('includeDatabases', e.target.value)}
              help="Comma-separated, wildcards allowed"
            />
            <TextInput label="Exclude databases" value={form.excludeDatabases} onChange={(e) => set('excludeDatabases', e.target.value)} />
            <Select label="Scan schedule" value={form.schedule} onChange={(e) => set('schedule', e.target.value as Schedule)}>
              <option value="every6h">Every 6 hours</option>
              <option value="daily">Daily</option>
              <option value="weekly">Weekly</option>
              <option value="manual">Manual only</option>
            </Select>
            <TextInput
              label="Query log window (days)"
              type="number"
              min={1}
              max={365}
              value={form.queryLogWindowDays}
              onChange={(e) => set('queryLogWindowDays', e.target.value)}
              help="Used to judge sorting keys and skip indexes"
            />
          </div>

          <fieldset className="group">
            <legend>Trials</legend>
            <Checkbox checked={form.trialsEnabled} onChange={(v) => set('trialsEnabled', v)}>
              Allow sample trials on this server
            </Checkbox>
            <TextInput
              label="Scratch database"
              value={form.scratchDatabase}
              onChange={(e) => set('scratchDatabase', e.target.value)}
              disabled={!form.trialsEnabled}
              help="Granulee creates and drops trial tables here; needs CREATE and DROP on this database only"
            />
          </fieldset>

          <div className="row" style={vars({ gap: 16 })}>
            <button type="submit" className="ee-btn ee-btn-primary" disabled={!valid || save.busy} aria-busy={save.busy || undefined}>
              {save.busy ? 'Saving…' : editing ? 'Save changes' : 'Save source'}
            </button>
            <Button variant="secondary" busy={testing.busy} disabled={!form.host.trim()} onClick={onTest}>
              {testing.busy ? 'Testing…' : 'Test connection'}
            </Button>
            {editing ? (
              <Button variant="secondary" onClick={reset}>
                Cancel edit
              </Button>
            ) : null}
            <span aria-live="polite" className="body-sm">
              {test ? (
                test.ok ? (
                  <Status tone="success">
                    Connected{test.version ? ` · ClickHouse ${test.version}` : ''}
                    {test.latencyMs != null ? ` · ${Math.round(test.latencyMs)} ms` : ''}
                  </Status>
                ) : (
                  <Status tone="error">
                    <span style={{ overflowWrap: 'anywhere' }}>{test.error ?? 'Connection failed'}</span>
                  </Status>
                )
              ) : null}
            </span>
          </div>
          <InlineError message={save.error ?? testing.error} />
        </form>
        <Card as="aside" className="split-side" style={{ gap: 12, flexBasis: 320 }}>
          <h2 className="subhead">Read-only user</h2>
          <p className="body-sm muted">Scans read only system tables. Create a dedicated user so Granulee never touches your data.</p>
          <CodeBlock code={grant} copyLabel="Copy SQL" />
          <p className="caption muted">
            Reads system.tables, system.parts, system.columns, system.parts_columns, system.data_skipping_indices and system.query_log.
          </p>
        </Card>
      </section>

      {deleting ? (
        <Dialog
          title={`Delete ${deleting.name}?`}
          onClose={() => setDeleting(null)}
          actions={
            <>
              <Button variant="secondary" onClick={() => setDeleting(null)}>
                Cancel
              </Button>
              <Button
                danger
                busy={remove.busy}
                onClick={async () => {
                  const target = deleting;
                  if (await remove.run(() => api.deleteSource(target.id))) {
                    if (selectedId === target.id) select(null);
                    if (editing?.id === target.id) reset();
                    setDeleting(null);
                    await reload();
                  }
                }}
              >
                Delete data source
              </Button>
            </>
          }
        >
          <p className="body muted">
            Granulee forgets this server, its stored credentials and its scan results. Nothing changes on the ClickHouse server itself; trial tables left in its scratch
            database stay there.
          </p>
          <InlineError message={remove.error} />
        </Dialog>
      ) : null}
    </>
  );

}
