import { useId, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Sampling, TrialIndex, TrialPlan, TrialPlanColumn, TrialPlanRecommendation, TrialRequest } from '../api/types';
import { useAction, useApi } from '../hooks/useApi';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Checkbox, Select, TextInput } from '../components/Form';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, InlineError, Loading } from '../components/States';
import { fmtBytes, fmtInt, fmtPct, fmtRows } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';
import { COMPRESSORS, ENCODINGS, buildCodec, levelsOf, parseCodec, standardLevel, type CodecParts, type Compressor } from '../lib/codec';

const normalEncoding = (text: string) => text.trim().replace(/,\s*$/, '');

/** The codec in front of the compressor, typed freely. Keeps what is being typed ("Delta, ") while the schema holds the clean value. */
function EncodingInput({ label, value, onChange, readOnly }: { label: string; value: string; onChange: (encoding: string) => void; readOnly?: boolean }) {
  const [text, setText] = useState(value);
  // The schema changed from outside (a recommendation was ticked): show that instead of the draft.
  const shown = normalEncoding(text) === value ? text : value;
  return (
    <input
      className="cc-field__input cc-field__input--sm"
      aria-label={label}
      list="codec-encodings"
      placeholder="none"
      spellCheck={false}
      autoComplete="off"
      readOnly={readOnly}
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
  addIndexes: TrialIndex[];
}

const INDEX_TYPES = ['minmax', 'set(100)', 'bloom_filter(0.01)', 'tokenbf_v1(8192, 3, 0)', 'ngrambf_v1(3, 8192, 3, 0)'];
const EMPTY_INDEX: TrialIndex = { name: '', expression: '', type: 'set(100)', granularity: 4 };

function applyRec(s: Schema, r: TrialPlanRecommendation): Schema {
  const next: Schema = { ...s, cols: { ...s.cols } };
  if (r.partitionKey != null) next.partitionKey = r.partitionKey;
  if (r.sortingKey != null) next.sortingKey = r.sortingKey;
  if (r.column && next.cols[r.column.name]) {
    const cur = next.cols[r.column.name];
    next.cols[r.column.name] = { type: r.column.type ?? cur.type, codec: r.column.codec ?? cur.codec };
  }
  if (r.dropIndex != null && !next.dropIndexes.includes(r.dropIndex)) next.dropIndexes = [...next.dropIndexes, r.dropIndex];
  if (r.addIndex && !next.addIndexes.some((i) => i.name === r.addIndex!.name)) next.addIndexes = [...next.addIndexes, r.addIndex];
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
  if (r.addIndex) next.addIndexes = next.addIndexes.filter((i) => i.name !== r.addIndex!.name);
  return next;
}

/** A new skip index is a hypothesis, not a finding with a size estimate: it waits to be ticked. */
const startsTicked = (r: TrialPlanRecommendation) => r.addIndex == null;

function initialSchema(plan: TrialPlan): Schema {
  const cols: Schema['cols'] = {};
  for (const c of plan.columns) cols[c.name] = { type: c.suggestedType, codec: c.suggestedCodec };
  return plan.recommendations.filter(startsTicked).reduce(applyRec, { partitionKey: plan.partitionKey, sortingKey: plan.sortingKey, cols, dropIndexes: [], addIndexes: [] });
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
  const [ticked, setTicked] = useState<string[]>(() => plan.recommendations.filter(startsTicked).map((r) => r.id));
  const [name, setName] = useState(`Trial of ${plan.table}`);
  const [rows, setRows] = useState(() => fmtInt(Math.min(plan.recommendedRows, plan.totalRows || plan.recommendedRows)));
  const [sampling, setSampling] = useState<Sampling>('even');
  const [where, setWhere] = useState('');
  const [replayQueries, setReplayQueries] = useState(plan.replayableQueries > 0);
  const [measureInserts, setMeasureInserts] = useState(true);
  const [optimizeFinal, setOptimizeFinal] = useState(true);
  const [columnScans, setColumnScans] = useState(true);
  const [runs, setRuns] = useState('5');
  // Columns put on the change list by hand, before they differ from the baseline.
  const [extra, setExtra] = useState<string[]>([]);
  const [adding, setAdding] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [draft, setDraft] = useState<TrialIndex>(EMPTY_INDEX);

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
  const nothingChanged = changedKeys === 0 && changedCols.length === 0 && schema.dropIndexes.length === 0 && schema.addIndexes.length === 0;
  const canStart = plan.trialsEnabled && n > 0 && !nothingChanged && name.trim() !== '' && (sampling !== 'where' || where.trim() !== '');

  const changes = [
    changedKeys ? `${changedKeys} ${changedKeys === 1 ? 'key' : 'keys'}` : null,
    changedCols.length ? `${changedCols.length} ${changedCols.length === 1 ? 'column' : 'columns'}` : null,
    schema.dropIndexes.length ? `${schema.dropIndexes.length} ${schema.dropIndexes.length === 1 ? 'index' : 'indexes'} dropped` : null,
    schema.addIndexes.length ? `${schema.addIndexes.length} ${schema.addIndexes.length === 1 ? 'index' : 'indexes'} added` : null,
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
      addIndexes: schema.addIndexes,
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

  const picked = (name: string) => extra.includes(name);
  const listed = plan.columns.filter((c) => changedCols.includes(c.name) || picked(c.name));
  const unlisted = plan.columns.filter((c) => !changedCols.includes(c.name) && !picked(c.name));
  const bySize = [...unlisted].sort((a, b) => b.compressedBytes - a.compressedBytes);
  const indexBytes = plan.indexDetails.reduce((a, i) => a + i.compressedBytes, 0);
  const sameKey = (a: string, b: string) => a.trim() === b.trim();

  function reset(c: TrialPlanColumn) {
    setCol(c.name, { type: c.baselineType, codec: baselineCodec(c) });
    setExtra((x) => x.filter((n) => n !== c.name));
    // A ticked recommendation for this column no longer holds.
    setTicked((t) => t.filter((id) => plan.recommendations.find((r) => r.id === id)?.column?.name !== c.name));
  }

  return (
    <div className="split">
      <div className="split-main stack" style={vars({ gap: 24 })}>
        {!plan.trialsEnabled ? (
          <p className="g-notice" role="status">
            Trials are switched off for {plan.sourceName}. Allow sample trials and set a scratch database in{' '}
            <Link className="g-link" to="/sources">
              Data sources
            </Link>{' '}
            to start one.
          </p>
        ) : null}

        <Card as="section" style={{ gap: 12 }}>
          <StepHead step="Step 1" title="Start from recommendations">
            Ticking one adds it to the changes below. You can edit it afterwards.
          </StepHead>
          {plan.recommendations.length ? (
            <div className="grid-auto" style={vars({ min: 300, gap: '0 24px' })}>
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

        <Card as="section" style={{ gap: 20 }}>
          <StepHead step="Step 2" title="Candidate schema">
            Only what differs from the baseline is listed. Add any other column to change its type, codec, compression or level. The codec field takes parameters and
            chains, such as Delta(4) or Delta, T64.
          </StepHead>
          <div className="grid-auto" style={vars({ min: 280, gap: 16 })}>
            <KeyInput
              label="Partition key"
              value={schema.partitionKey}
              onChange={(v) => setSchema((s) => ({ ...s, partitionKey: v }))}
              help={sameKey(schema.partitionKey, plan.partitionKey) ? 'Same as baseline' : `Baseline: ${plan.partitionKey || 'none'}`}
            />
            <KeyInput
              label="Sorting key"
              value={schema.sortingKey}
              onChange={(v) => setSchema((s) => ({ ...s, sortingKey: v }))}
              help={sameKey(schema.sortingKey, plan.sortingKey) ? 'Same as baseline' : `Baseline: ${plan.sortingKey || 'none'}`}
            />
          </div>

          <div className="stack" style={vars({ gap: 8 })}>
            <h3 className="subhead">
              Changed columns <span className="g-count g-num">{listed.length}</span>
            </h3>
            {listed.length ? (
              <div className="box-em">
                <ColumnTable>
                  {listed.map((c) => (
                    <ColumnRow key={c.name} column={c} value={schema.cols[c.name]} onChange={(patch) => setCol(c.name, patch)} onReset={() => reset(c)} />
                  ))}
                </ColumnTable>
              </div>
            ) : (
              <p className="body-sm muted">No column differs from the baseline yet. Tick a recommendation above or add a column below.</p>
            )}
            {bySize.length ? (
              <div className="row" style={{ alignItems: 'flex-end', ...vars({ gap: 12 }) }}>
                <div className="cc-field" style={{ flex: '1 1 280px', maxWidth: 420 }}>
                  <label htmlFor="add-column" className="cc-field__label">
                    Change another column
                  </label>
                  <select id="add-column" className="g-select" aria-describedby="add-column-help" value={adding} onChange={(e) => setAdding(e.target.value)}>
                    <option value="">Pick a column…</option>
                    {bySize.map((c) => (
                      <option key={c.name} value={c.name}>
                        {c.name} · {c.baselineType}
                        {c.compressedBytes > 0 ? ` · ${fmtBytes(c.compressedBytes)}` : ''}
                      </option>
                    ))}
                  </select>
                  <span id="add-column-help" className="cc-field__help">
                    Sorted by compressed size, largest first
                  </span>
                </div>
                <Button
                  variant="dark"
                  disabled={!adding}
                  onClick={() => {
                    setExtra((x) => [...x, adding]);
                    setAdding('');
                  }}
                >
                  Add column
                </Button>
              </div>
            ) : null}
          </div>

          {unlisted.length ? (
            <div className="stack" style={vars({ gap: 0 })}>
              <button type="button" className="g-rowbtn" aria-expanded={showAll} onClick={() => setShowAll(!showAll)}>
                <span className="body-sm g-act">{showAll ? 'Hide' : 'Show'}</span>
                <span className="body-sm muted">
                  {fmtInt(unlisted.length)} unchanged {unlisted.length === 1 ? 'column' : 'columns'}, same as baseline
                </span>
              </button>
              {showAll ? (
                <div style={{ overflowX: 'auto' }}>
                  <ColumnTable>
                    {unlisted.map((c) => (
                      <ColumnRow key={c.name} column={c} value={schema.cols[c.name]} />
                    ))}
                  </ColumnTable>
                </div>
              ) : null}
            </div>
          ) : null}

          <div className="stack" style={vars({ gap: 8 })}>
            <h3 className="subhead">Skip indexes</h3>
            <p className="body-sm muted">
              {plan.indexDetails.length
                ? `${fmtInt(plan.indexDetails.length)} ${plan.indexDetails.length === 1 ? 'index' : 'indexes'}, ${fmtBytes(indexBytes)} compressed. Drop one in the candidate to see what it costs on inserts and saves on reads, or add one to see whether it prunes.`
                : 'This table has none. Add one to the candidate to see whether it prunes: the result shows the granules each replayed query reads with and without it.'}
            </p>
            {plan.indexDetails.length || schema.addIndexes.length ? (
              <div style={{ overflowX: 'auto' }}>
                <table className="g-tbl" style={{ minWidth: 640 }}>
                  <thead>
                    <tr>
                      <th>Index</th>
                      <th>Expression</th>
                      <th>Type</th>
                      <th className="r">Compressed</th>
                      <th style={{ width: 200 }}>Candidate</th>
                    </tr>
                  </thead>
                  <tbody>
                    {plan.indexDetails.map((idx) => (
                      <tr key={idx.name}>
                        <td className="g-strong">{idx.name}</td>
                        <td className="g-mono g-wrap">{idx.expression}</td>
                        <td className="g-mono">{idx.type}</td>
                        <td className="r nowrap">{fmtBytes(idx.compressedBytes)}</td>
                        <td>
                          <label className="check" style={{ display: 'inline-flex', gap: 8 }}>
                            <input
                              type="checkbox"
                              checked={schema.dropIndexes.includes(idx.name)}
                              onChange={(e) =>
                                setSchema((s) => ({ ...s, dropIndexes: e.target.checked ? [...s.dropIndexes, idx.name] : s.dropIndexes.filter((i) => i !== idx.name) }))
                              }
                            />{' '}
                            Drop in candidate
                          </label>
                        </td>
                      </tr>
                    ))}
                    {schema.addIndexes.map((idx) => (
                      <tr key={`new-${idx.name}`}>
                        <td className="g-strong">
                          <span className="row" style={vars({ gap: 8 })}>
                            {idx.name}
                            <span className="g-count">new</span>
                          </span>
                        </td>
                        <td className="g-mono g-wrap">{idx.expression}</td>
                        <td className="g-mono">
                          {idx.type} · granularity {idx.granularity}
                        </td>
                        <td className="r">—</td>
                        <td>
                          <button
                            type="button"
                            className="cc-btn cc-btn--link"
                            onClick={() => {
                              setSchema((s) => ({ ...s, addIndexes: s.addIndexes.filter((i) => i.name !== idx.name) }));
                              setTicked((t) => t.filter((id) => plan.recommendations.find((r) => r.id === id)?.addIndex?.name !== idx.name));
                            }}
                          >
                            Remove
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
            <div className="row" style={{ alignItems: 'flex-end', ...vars({ gap: 12 }) }}>
              <DraftField label="Index name" width={170} value={draft.name} placeholder="idx_country" onChange={(v) => setDraft({ ...draft, name: v })} />
              <DraftField label="Expression" width={220} value={draft.expression} placeholder="country" onChange={(v) => setDraft({ ...draft, expression: v })} />
              <DraftField label="Type" width={200} value={draft.type} list="index-types" onChange={(v) => setDraft({ ...draft, type: v })} />
              <DraftField
                label="Granularity"
                width={110}
                value={String(draft.granularity)}
                type="number"
                onChange={(v) => setDraft({ ...draft, granularity: Math.max(1, parseInt(v, 10) || 1) })}
              />
              <Button
                variant="dark"
                disabled={
                  !/^[A-Za-z_][A-Za-z0-9_]*$/.test(draft.name.trim()) ||
                  !draft.expression.trim() ||
                  !draft.type.trim() ||
                  schema.addIndexes.some((i) => i.name === draft.name.trim()) ||
                  plan.indexes.includes(draft.name.trim())
                }
                onClick={() => {
                  const index = { ...draft, name: draft.name.trim(), expression: draft.expression.trim(), type: draft.type.trim() };
                  setSchema((s) => ({ ...s, addIndexes: [...s.addIndexes, index] }));
                  setDraft(EMPTY_INDEX);
                }}
              >
                Add index
              </Button>
            </div>
            <datalist id="index-types">
              {INDEX_TYPES.map((t) => (
                <option key={t} value={t} />
              ))}
            </datalist>
          </div>
          <datalist id="codec-encodings">
            {ENCODINGS.map((e) => (
              <option key={e} value={e} />
            ))}
          </datalist>
        </Card>

        <Card as="section">
          <StepHead step="Step 3" title="Sample size" />
          <div className="grid-auto" style={{ alignItems: 'start', ...vars({ min: 280, gap: 16 }) }}>
            <TextInput
              label="Rows to copy"
              numeric
              inputMode="numeric"
              value={rows}
              onChange={(e) => setRows(e.target.value)}
              help={
                <>
                  Recommended: {fmtInt(plan.recommendedRows)}
                  {plan.totalRows > 0 ? ` of ${fmtRows(plan.totalRows)} rows (${fmtPct(plan.recommendedRows / plan.totalRows, 0)})` : ''}. Below 10 M the numbers get noisy.
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
          {tooFew ? <p className="g-notice">Fewer than 10 M rows. Results may not reflect the full table.</p> : null}
          {plan.totalRows > 0 && n > plan.totalRows ? (
            <p className="body-sm muted">The table has {fmtInt(plan.totalRows)} rows, so the trial copies all of them.</p>
          ) : null}
        </Card>

        <Card as="section" style={{ gap: 8 }}>
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
          <div style={{ maxWidth: 280, marginTop: 8 }}>
            <TextInput label="Runs per query" type="number" min={1} max={20} value={runs} onChange={(e) => setRuns(e.target.value)} help="Median is reported; first run is a warm-up" />
          </div>
        </Card>
      </div>

      <Card as="aside" emphasis className="split-side">
        <h2 className="subhead">Trial summary</h2>
        <TextInput label="Name" value={name} onChange={(e) => setName(e.target.value)} />
        <dl className="summary">
          <dt>Changes</dt>
          <dd className="g-num">{changes.length ? changes.join(' · ') : 'None yet'}</dd>
          <dt>Rows</dt>
          <dd className="g-num">{fmtRows(effectiveRows)}</dd>
          <dt>Scratch space needed</dt>
          <dd className="g-num">{scratchNeeded > 0 ? `≈ ${fmtBytes(scratchNeeded)}` : '—'}</dd>
          <dt>Free in {plan.scratchDatabase || 'scratch'}</dt>
          <dd className="g-num">{plan.scratchFreeBytes != null ? fmtBytes(plan.scratchFreeBytes) : 'unknown'}</dd>
        </dl>
        {noSpace ? <p className="g-notice">The estimate is larger than the free space.</p> : null}
        <div className="stack" style={vars({ gap: 4 })}>
          <span className="caption muted">Tables, kept until you remove the trial</span>
          <span className="g-mono g-wrap">
            {plan.scratchDatabase || 'scratch'}.{plan.table}_trial_&lt;id&gt;_base
          </span>
          <span className="g-mono g-wrap">
            {plan.scratchDatabase || 'scratch'}.{plan.table}_trial_&lt;id&gt;_cand
          </span>
        </div>
        {nothingChanged ? <p className="caption muted">Change at least one key, column or index to have something to compare.</p> : null}
        <Button stretch disabled={!canStart} busy={start.busy} onClick={submit}>
          {start.busy ? 'Starting…' : 'Start trial'}
        </Button>
        <Button variant="secondary" stretch to={routes.recommendations(plan.sourceId, plan.database, plan.table)}>
          Cancel
        </Button>
        <InlineError message={start.error} />
      </Card>
    </div>
  );
}

function DraftField({
  label,
  value,
  onChange,
  width,
  placeholder,
  list,
  type,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  width: number;
  placeholder?: string;
  list?: string;
  type?: string;
}) {
  const id = useId();
  return (
    <div className="cc-field" style={{ flex: `1 1 ${width}px`, maxWidth: width * 1.6 }}>
      <label htmlFor={id} className="cc-field__label">
        {label}
      </label>
      <input
        id={id}
        className="cc-field__input cc-field__input--sm"
        type={type}
        min={type === 'number' ? 1 : undefined}
        list={list}
        placeholder={placeholder}
        spellCheck={false}
        autoComplete="off"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}

function KeyInput({ label, value, onChange, help }: { label: string; value: string; onChange: (value: string) => void; help: string }) {
  const id = useId();
  return (
    <div className="cc-field">
      <label htmlFor={id} className="cc-field__label">
        {label}
      </label>
      <input id={id} className="cc-field__input g-mono" spellCheck={false} aria-describedby={`${id}-help`} value={value} onChange={(e) => onChange(e.target.value)} />
      <span id={`${id}-help`} className="cc-field__help g-wrap">
        {help}
      </span>
    </div>
  );
}

function ColumnTable({ children }: { children: ReactNode }) {
  return (
    <table className="g-tbl" style={{ minWidth: 760 }}>
      <thead>
        <tr>
          <th>Column · baseline</th>
          <th style={{ width: '24%' }}>Type</th>
          <th style={{ width: '18%' }}>Codec</th>
          <th style={{ width: '14%' }}>Compression</th>
          <th style={{ width: '10%' }}>Level</th>
          <th style={{ width: 90 }}>
            <span className="sr-only">Reset</span>
          </th>
        </tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  );
}

/** One column of the candidate. Without `onChange` it only shows what the baseline has. */
function ColumnRow({
  column: c,
  value,
  onChange,
  onReset,
}: {
  column: TrialPlanColumn;
  value: { type: string; codec: string };
  onChange?: (patch: Partial<{ type: string; codec: string }>) => void;
  onReset?: () => void;
}) {
  const locked = !onChange;
  const types = c.typeOptions.includes(value.type) ? c.typeOptions : [value.type, ...c.typeOptions];
  const codec = parseCodec(value.codec);
  const levels = levelsOf(codec.compressor);
  // A level the server accepts but the list lacks (LZ4HC(0)) stays selectable.
  const levelOptions = codec.level != null && !levels.includes(codec.level) ? [codec.level, ...levels] : levels;
  const setCodec = (patch: Partial<CodecParts>) => onChange?.({ codec: buildCodec({ ...codec, ...patch }) });
  return (
    <tr>
      <td>
        <span className="ink g-wrap" style={{ fontWeight: 700 }}>
          {c.name}
        </span>
        <div className="g-mono muted g-wrap">
          {c.baselineType} · {c.baselineCodec}
        </div>
      </td>
      <td>
        <select className="g-select g-select--sm" aria-label={`Type for ${c.name}`} disabled={locked} value={value.type} onChange={(e) => onChange?.({ type: e.target.value })}>
          {types.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </td>
      <td>
        <EncodingInput label={`Codec for ${c.name}`} value={codec.encoding} readOnly={locked} onChange={(encoding) => setCodec({ encoding })} />
      </td>
      <td>
        <select
          className="g-select g-select--sm"
          aria-label={`Compression for ${c.name}`}
          disabled={locked}
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
      <td>
        <select
          className="g-select g-select--sm g-num"
          aria-label={`Level for ${c.name}`}
          disabled={locked || !levelOptions.length}
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
      <td className="r">
        {onReset ? (
          <button type="button" className="cc-btn cc-btn--secondary cc-btn--tight" onClick={onReset}>
            Reset
          </button>
        ) : null}
      </td>
    </tr>
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
