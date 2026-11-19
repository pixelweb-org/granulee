import { useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Sampling, TrialPlan, TrialPlanColumn, TrialPlanRecommendation, TrialRequest } from '../api/types';
import { useAction, useApi } from '../hooks/useApi';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Checkbox, Select, TextInput } from '../components/Form';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, InlineError, Loading } from '../components/States';
import { StatusDot } from '../components/StatusDot';
import { fmtBytes, fmtInt, fmtRows } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';
import { COMPRESSORS, ENCODINGS, buildCodec, levelsOf, parseCodec, standardLevel, type CodecParts, type Compressor } from '../lib/codec';

const normalEncoding = (text: string) => text.trim().replace(/,\s*$/, '');

/** The codec in front of the compressor, typed freely. Keeps what is being typed ("Delta, ") while the schema holds the clean value. */
function EncodingInput({ label, value, onChange }: { label: string; value: string; onChange: (encoding: string) => void }) {
  const [text, setText] = useState(value);
  // The schema changed from outside (a recommendation was ticked): show that instead of the draft.
  const shown = normalEncoding(text) === value ? text : value;
  return (
    <input
      className="nv-input cell g-mono"
      aria-label={label}
      list="codec-encodings"
      placeholder="none"
      spellCheck={false}
      autoComplete="off"
      value={shown}
      onChange={(e) => {
        setText(e.target.value);
        onChange(normalEncoding(e.target.value));
      }}
    />
  );
}
/** The plan's baseline codec is display-ready; "" stands for the server default in requests. */
const baselineCodec = (c: TrialPlanColumn) => (/default/i.test(c.baselineCodec) ? '' : c.baselineCodec);

interface Schema {
  partitionKey: string;
  sortingKey: string;
  cols: Record<string, { type: string; codec: string }>;
  dropIndexes: string[];
}

function applyRec(s: Schema, r: TrialPlanRecommendation): Schema {
  const next: Schema = { ...s, cols: { ...s.cols } };
  if (r.partitionKey != null) next.partitionKey = r.partitionKey;
  if (r.sortingKey != null) next.sortingKey = r.sortingKey;
  if (r.column && next.cols[r.column.name]) {
    const cur = next.cols[r.column.name];
    next.cols[r.column.name] = { type: r.column.type ?? cur.type, codec: r.column.codec ?? cur.codec };
  }
  if (r.dropIndex != null && !next.dropIndexes.includes(r.dropIndex)) next.dropIndexes = [...next.dropIndexes, r.dropIndex];
  return next;
}

function revertRec(s: Schema, r: TrialPlanRecommendation, plan: TrialPlan): Schema {
  const next: Schema = { ...s, cols: { ...s.cols } };
  if (r.partitionKey != null) next.partitionKey = plan.partitionKey;
  if (r.sortingKey != null) next.sortingKey = plan.sortingKey;
  if (r.column) {
    const base = plan.columns.find((c) => c.name === r.column!.name);
    const cur = next.cols[r.column.name];
    if (base && cur) {
      next.cols[r.column.name] = {
        type: r.column.type != null ? base.baselineType : cur.type,
        codec: r.column.codec != null ? baselineCodec(base) : cur.codec,
      };
    }
  }
  if (r.dropIndex != null) next.dropIndexes = next.dropIndexes.filter((i) => i !== r.dropIndex);
  return next;
}

function initialSchema(plan: TrialPlan): Schema {
  const cols: Schema['cols'] = {};
  for (const c of plan.columns) cols[c.name] = { type: c.suggestedType, codec: c.suggestedCodec };
  return plan.recommendations.reduce(applyRec, { partitionKey: plan.partitionKey, sortingKey: plan.sortingKey, cols, dropIndexes: [] });
}

export function TrialSetupPage() {
  const [params] = useSearchParams();
  const source = params.get('source') ?? '';
  const database = params.get('database') ?? '';
  const table = params.get('table') ?? '';
  const ready = !!(source && database && table);
  const { data: plan, error, reload } = useApi(() => (ready ? api.trialPlan(source, database, table) : Promise.resolve(null)), [source, database, table]);

  const header = (
    <PageHeader
      eyebrow={<Link to="/trials">Trials{ready ? ` · ${database}.${table}` : ''}</Link>}
      title="New trial"
      description="Granulee builds two tables in the scratch database: a baseline with today's schema and a candidate with your changes. Both get exactly the same rows, then they are merged, measured and queried side by side."
    />
  );

  if (!ready) {
    return (
      <>
        {header}
        <EmptyState title="Pick a table first" action={<Button to="/recommendations">Choose a table</Button>}>
          A trial runs against one table. Start it from that table’s recommendations.
        </EmptyState>
      </>
    );
  }
  if (error && !plan) {
    return (
      <>
        {header}
        <ErrorState message={error} onRetry={() => reload()} />
      </>
    );
  }
  if (!plan) {
    return (
      <>
        {header}
        <Loading />
      </>
    );
  }
  return (
    <>
      {header}
      <TrialForm key={`${plan.sourceId}/${plan.database}/${plan.table}`} plan={plan} />
    </>
  );
}

function TrialForm({ plan }: { plan: TrialPlan }) {
  const navigate = useNavigate();
  const start = useAction();
  const [schema, setSchema] = useState<Schema>(() => initialSchema(plan));
  const [ticked, setTicked] = useState<string[]>(() => plan.recommendations.map((r) => r.id));
  const [name, setName] = useState(`Trial of ${plan.table}`);
  const [rows, setRows] = useState(() => fmtInt(Math.min(plan.recommendedRows, plan.totalRows || plan.recommendedRows)));
  const [sampling, setSampling] = useState<Sampling>('even');
  const [where, setWhere] = useState('');
  const [replayQueries, setReplayQueries] = useState(plan.replayableQueries > 0);
  const [measureInserts, setMeasureInserts] = useState(true);
  const [optimizeFinal, setOptimizeFinal] = useState(true);
  const [columnScans, setColumnScans] = useState(true);
  const [runs, setRuns] = useState('5');

  function toggleRec(rec: TrialPlanRecommendation, on: boolean) {
    const nextTicked = on ? [...ticked, rec.id] : ticked.filter((id) => id !== rec.id);
    setTicked(nextTicked);
    setSchema((s) => {
      if (on) return applyRec(s, rec);
      // Revert this one, then re-apply the others still ticked so shared fields keep their values.
      return plan.recommendations.filter((r) => nextTicked.includes(r.id)).reduce(applyRec, revertRec(s, rec, plan));
    });
  }
  function setCol(colName: string, patch: Partial<{ type: string; codec: string }>) {
    setSchema((s) => ({ ...s, cols: { ...s.cols, [colName]: { ...s.cols[colName], ...patch } } }));
  }

  const changedCols = useMemo(
    () => plan.columns.filter((c) => schema.cols[c.name].type !== c.baselineType || schema.cols[c.name].codec !== baselineCodec(c)).map((c) => c.name),
    [plan, schema],
  );
  const changedKeys = (schema.partitionKey.trim() !== plan.partitionKey.trim() ? 1 : 0) + (schema.sortingKey.trim() !== plan.sortingKey.trim() ? 1 : 0);
  const n = parseInt(rows.replace(/[^0-9]/g, ''), 10) || 0;
  const effectiveRows = plan.totalRows > 0 ? Math.min(n, plan.totalRows) : n;
  const tooFew = n > 0 && n < 10e6 && plan.totalRows > 10e6;
  const scratchNeeded = plan.totalRows > 0 ? effectiveRows * (plan.compressedBytes / plan.totalRows) * 2 : 0;
  const noSpace = plan.scratchFreeBytes != null && scratchNeeded > plan.scratchFreeBytes;
  const runsN = Math.max(1, parseInt(runs, 10) || 1);
  const nothingChanged = changedKeys === 0 && changedCols.length === 0 && schema.dropIndexes.length === 0;
  const canStart = plan.trialsEnabled && n > 0 && !nothingChanged && name.trim() !== '' && (sampling !== 'where' || where.trim() !== '');

  const changes = [
    changedKeys ? `${changedKeys} ${changedKeys === 1 ? 'key' : 'keys'}` : null,
    changedCols.length ? `${changedCols.length} ${changedCols.length === 1 ? 'column' : 'columns'}` : null,
    schema.dropIndexes.length ? `${schema.dropIndexes.length} ${schema.dropIndexes.length === 1 ? 'index' : 'indexes'} dropped` : null,
  ].filter(Boolean);

  async function submit() {
    const req: TrialRequest = {
      sourceId: plan.sourceId,
      database: plan.database,
      table: plan.table,
      name: name.trim(),
      partitionKey: schema.partitionKey.trim(),
      sortingKey: schema.sortingKey.trim(),
      columns: plan.columns.map((c) => ({ name: c.name, type: schema.cols[c.name].type, codec: schema.cols[c.name].codec })),
      dropIndexes: schema.dropIndexes,
      rows: effectiveRows,
      sampling,
      where: sampling === 'where' ? where.trim() : null,
      replayQueries,
      measureInserts,
      optimizeFinal,
      columnScans,
      runsPerQuery: runsN,
    };
    await start.run(async () => {
      const created = await api.startTrial(req);
      navigate(routes.trial(created.id));
    });
  }

  return (
    <div className="split">
      <div className="split-main stack" style={{ flexBasis: 640, ...vars({ gap: 24 }) }}>
        {!plan.trialsEnabled ? (
          <div className="notice body-sm" role="status">
            <span style={{ paddingTop: 6 }}>
              <StatusDot tone="warning" />
            </span>
            <span>
              Trials are switched off for {plan.sourceName}. Allow sample trials and set a scratch database in{' '}
              <Link className="g-link" to="/sources">
                Data sources
              </Link>{' '}
              to start one.
            </span>
          </div>
        ) : null}

        <Card as="section">
          <StepHead step="Step 1" title="Start from recommendations">
            Ticking one fills in the column and key changes below. You can edit them afterwards.
          </StepHead>
          {plan.recommendations.length ? (
            <div className="grid-auto" style={vars({ min: 300, gap: '4px 24px' })}>
              {plan.recommendations.map((r) => (
                <Checkbox key={r.id} checked={ticked.includes(r.id)} onChange={(on) => toggleRec(r, on)}>
                  {r.label}
                </Checkbox>
              ))}
            </div>
          ) : (
            <p className="body-sm muted">No open recommendations for this table. Set the candidate schema by hand below.</p>
          )}
        </Card>

        <Card as="section">
          <StepHead step="Step 2" title="Candidate schema">
            Set type, codec, compression and level per column, recommended or not. The codec takes parameters and chains, such as Delta(4) or Delta, T64. Changed columns carry a dot.
          </StepHead>
          <div className="grid-2" style={vars({ gap: 16 })}>
            <TextInput
              label="Partition key"
              value={schema.partitionKey}
              onChange={(e) => setSchema((s) => ({ ...s, partitionKey: e.target.value }))}
              help={`Baseline: ${plan.partitionKey || 'none'}`}
            />
            <TextInput
              label="Sorting key"
              value={schema.sortingKey}
              onChange={(e) => setSchema((s) => ({ ...s, sortingKey: e.target.value }))}
              help={`Baseline: ${plan.sortingKey || 'none'}`}
            />
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table className="g-tbl dense">
              <thead>
                <tr>
                  <th>Column · baseline</th>
                  <th>Type</th>
                  <th>Codec</th>
                  <th>Compression</th>
                  <th>Level</th>
                </tr>
              </thead>
              <tbody>
                {plan.columns.map((c) => {
                  const cur = schema.cols[c.name];
                  const changed = changedCols.includes(c.name);
                  const types = c.typeOptions.includes(cur.type) ? c.typeOptions : [cur.type, ...c.typeOptions];
                  const codec = parseCodec(cur.codec);
                  const levels = levelsOf(codec.compressor);
                  // A level the server accepts but the list lacks (LZ4HC(0)) stays selectable.
                  const levelOptions = codec.level != null && !levels.includes(codec.level) ? [codec.level, ...levels] : levels;
                  const setCodec = (patch: Partial<CodecParts>) => setCol(c.name, { codec: buildCodec({ ...codec, ...patch }) });
                  return (
                    <tr key={c.name} className={changed ? 'is-changed' : undefined}>
                      <td>
                        <div className="row ink" style={vars({ gap: 6 })}>
                          <strong style={{ overflowWrap: 'anywhere' }}>{c.name}</strong>
                          {changed ? (
                            <span className="dot dot-success" title="Changed">
                              <span className="sr-only">Changed</span>
                            </span>
                          ) : null}
                        </div>
                        <div className="g-mono cell-sub">
                          {c.baselineType} · {c.baselineCodec}
                        </div>
                      </td>
                      <td style={{ minWidth: 150 }}>
                        <select className="g-select cell" aria-label={`Type for ${c.name}`} value={cur.type} onChange={(e) => setCol(c.name, { type: e.target.value })}>
                          {types.map((t) => (
                            <option key={t} value={t}>
                              {t}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td style={{ minWidth: 120 }}>
                        <EncodingInput label={`Codec for ${c.name}`} value={codec.encoding} onChange={(encoding) => setCodec({ encoding })} />
                      </td>
                      <td style={{ minWidth: 92 }}>
                        <select
                          className="g-select cell"
                          aria-label={`Compression for ${c.name}`}
                          value={codec.compressor}
                          onChange={(e) => {
                            const compressor = e.target.value as Compressor;
                            setCodec({ compressor, level: standardLevel(compressor) });
                          }}
                        >
                          {COMPRESSORS.map((o) => (
                            <option key={o.value} value={o.value}>
                              {o.label}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td style={{ minWidth: 64 }}>
                        <select
                          className="g-select cell g-num"
                          aria-label={`Compression level for ${c.name}`}
                          disabled={!levelOptions.length}
                          value={codec.level ?? ''}
                          onChange={(e) => setCodec({ level: Number(e.target.value) })}
                        >
                          {levelOptions.length ? null : <option value="">—</option>}
                          {levelOptions.map((l) => (
                            <option key={l} value={l}>
                              {l}
                            </option>
                          ))}
                        </select>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <datalist id="codec-encodings">
              {ENCODINGS.map((e) => (
                <option key={e} value={e} />
              ))}
            </datalist>
          </div>
          {plan.indexes.length ? (
            <div className="grid-auto" style={vars({ min: 300, gap: '4px 24px' })}>
              {plan.indexes.map((idx) => (
                <Checkbox
                  key={idx}
                  checked={schema.dropIndexes.includes(idx)}
                  onChange={(on) => setSchema((s) => ({ ...s, dropIndexes: on ? [...s.dropIndexes, idx] : s.dropIndexes.filter((i) => i !== idx) }))}
                >
                  Drop skip index {idx}
                </Checkbox>
              ))}
            </div>
          ) : null}
        </Card>

        <Card as="section">
          <StepHead step="Step 3" title="Sample size" />
          <div className="grid-2" style={vars({ gap: 16 })}>
            <TextInput
              label="Rows to copy"
              numeric
              inputMode="numeric"
              value={rows}
              onChange={(e) => setRows(e.target.value)}
              help={
                <>
                  Recommended: {fmtInt(plan.recommendedRows)}
                  {plan.totalRows > 0 ? ` of ${fmtRows(plan.totalRows)} rows` : ''}. Codecs and sorting keys only show their real effect on enough rows; below 10 M the
                  numbers get noisy.
                </>
              }
            />
            <Select
              label="How rows are picked"
              value={sampling}
              onChange={(e) => setSampling(e.target.value as Sampling)}
              help={sampling === 'even' ? 'Even spread keeps the data mix close to production.' : sampling === 'recent' ? 'Copies whole partitions, newest first.' : 'Rows matching your condition, up to the row count.'}
            >
              <option value="even">Spread evenly across all partitions</option>
              <option value="recent">Most recent partitions first</option>
              <option value="where">Custom WHERE clause</option>
            </Select>
          </div>
          {sampling === 'where' ? (
            <TextInput label="WHERE clause" value={where} onChange={(e) => setWhere(e.target.value)} placeholder="event_date >= today() - 30" help="Without the WHERE keyword." />
          ) : null}
          {tooFew ? (
            <p className="status body-sm">
              <StatusDot tone="warning" />
              Warning: fewer than 10 M rows. Results may not reflect the full table.
            </p>
          ) : null}
          {plan.totalRows > 0 && n > plan.totalRows ? (
            <p className="body-sm muted">The table has {fmtInt(plan.totalRows)} rows, so the trial copies all of them.</p>
          ) : null}
        </Card>

        <Card as="section" style={{ gap: 12 }}>
          <StepHead step="Step 4" title="Performance test" />
          <Checkbox checked={replayQueries} onChange={setReplayQueries} disabled={plan.replayableQueries === 0}>
            {plan.replayableQueries > 0
              ? `Replay the ${plan.replayableQueries} most frequent ${plan.replayableQueries === 1 ? 'query' : 'queries'} from query_log`
              : 'Replay queries from query_log (none found for this table)'}
          </Checkbox>
          <Checkbox checked={measureInserts} onChange={setMeasureInserts}>
            Measure insert throughput
          </Checkbox>
          <Checkbox checked={optimizeFinal} onChange={setOptimizeFinal}>
            Merge both tables fully before measuring (OPTIMIZE FINAL)
          </Checkbox>
          <Checkbox checked={columnScans} onChange={setColumnScans}>
            Full scan per column to compare read speed
          </Checkbox>
          <div style={{ maxWidth: 280 }}>
            <TextInput label="Runs per query" type="number" min={1} max={20} value={runs} onChange={(e) => setRuns(e.target.value)} help="Median is reported; first run is a warm-up" />
          </div>
        </Card>
      </div>

      <Card as="aside" emphasis className="split-side">
        <h2 className="subhead">Trial summary</h2>
        <TextInput label="Name" value={name} onChange={(e) => setName(e.target.value)} />
        <dl className="summary">
          <dt className="muted">Changes</dt>
          <dd className="g-num">{changes.length ? changes.join(' · ') : 'None yet'}</dd>
          <dt className="muted">Rows</dt>
          <dd className="g-num">{fmtRows(effectiveRows)}</dd>
          <dt className="muted">Scratch space needed</dt>
          <dd className="g-num">{scratchNeeded > 0 ? `≈ ${fmtBytes(scratchNeeded)}` : '—'}</dd>
          <dt className="muted">Free in {plan.scratchDatabase || 'scratch'}</dt>
          <dd className="g-num">{plan.scratchFreeBytes != null ? fmtBytes(plan.scratchFreeBytes) : 'unknown'}</dd>
        </dl>
        {noSpace ? (
          <p className="status body-sm">
            <StatusDot tone="error" />
            The estimate is larger than the free space.
          </p>
        ) : null}
        <p className="caption muted">
          Tables: {plan.scratchDatabase || 'scratch'}.{plan.table}_trial_&lt;id&gt;_base and _cand. They stay until you remove the trial.
        </p>
        {nothingChanged ? <p className="caption muted">Change at least one key, column or index to have something to compare.</p> : null}
        <Button stretch disabled={!canStart} busy={start.busy} onClick={submit}>
          {start.busy ? 'Starting…' : 'Start trial'}
        </Button>
        <Button variant="ghost" stretch to={routes.recommendations(plan.sourceId, plan.database, plan.table)}>
          Cancel
        </Button>
        <InlineError message={start.error} />
      </Card>
    </div>
  );
}

function StepHead({ step, title, children }: { step: string; title: string; children?: ReactNode }) {
  return (
    <div className="stack" style={vars({ gap: 4 })}>
      <p className="eyebrow">{step}</p>
      <h2 className="card-title">{title}</h2>
      {children ? <p className="body-sm muted">{children}</p> : null}
    </div>
  );
}
