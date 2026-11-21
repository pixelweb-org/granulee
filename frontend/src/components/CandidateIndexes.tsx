import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { TableWhatIf, TrialIndex, WhatIfUse } from '../api/types';
import { fmtInt, fmtPct } from '../lib/format';
import { routes } from '../lib/routes';
import { vars } from '../lib/style';
import { Button } from './Button';
import { Card } from './Card';
import { TextInput } from './Form';
import { RowButton } from './RowButton';

const INDEX_TYPES = ['minmax', 'set(100)', 'bloom_filter(0.01)', 'tokenbf_v1(8192, 3, 0)', 'ngrambf_v1(3, 8192, 3, 0)'];
const EMPTY: TrialIndex = { name: '', expression: '', type: 'set(100)', granularity: 4 };
// Queries shown in an opened row before "Show all".
const SHOWN = 3;

const SOURCE: Record<string, string> = {
  empirical: 'built in memory over the granules read today',
  statistical: 'from column statistics, not from the data',
  applicability_only: 'applicable, but not measured',
};

interface Props {
  sourceId: string;
  database: string;
  table: string;
  /** Skip indexes the scan proposes for this table. */
  proposals: TrialIndex[];
  /** Names of the indexes the table already has. */
  taken: string[];
  isOpen: (name: string) => boolean;
  onToggle: (name: string) => void;
}

/**
 * Skip indexes that do not exist yet, estimated with EXPLAIN WHATIF against the logged queries.
 * The scan's proposals are listed to start with; any other index can be added.
 */
export function CandidateIndexes({ sourceId, database, table, proposals, taken, isOpen, onToggle }: Props) {
  const [list, setList] = useState<TrialIndex[]>(proposals);
  const [draft, setDraft] = useState<TrialIndex>(EMPTY);
  const [result, setResult] = useState<TableWhatIf | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const proposed = JSON.stringify(proposals);
  useEffect(() => {
    setList(JSON.parse(proposed) as TrialIndex[]);
    setDraft(EMPTY);
    setResult(null);
    setError(null);
  }, [sourceId, database, table, proposed]);

  const draftName = draft.name.trim() || (draft.expression.trim() ? `idx_${draft.expression.trim().replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}` : '');
  const draftProblem = !draft.expression.trim()
    ? null
    : !/^[A-Za-z_][A-Za-z0-9_]*$/.test(draftName)
      ? 'The index name may only contain letters, digits and underscores.'
      : !draft.type.trim()
        ? 'Pick an index type, such as minmax or set(100).'
        : taken.includes(draftName) || list.some((i) => i.name === draftName)
          ? `An index named ${draftName} already exists. Give this one another name.`
          : null;
  // A complete draft counts without "Add": the button only makes room for another one.
  const pending: TrialIndex | null =
    draft.expression.trim() && !draftProblem ? { name: draftName, expression: draft.expression.trim(), type: draft.type.trim(), granularity: draft.granularity } : null;
  const candidates = pending ? [...list, pending] : list;

  async function estimate() {
    setBusy(true);
    setError(null);
    try {
      const answer = await api.indexWhatIf(sourceId, database, table, candidates);
      setList(candidates);
      setDraft(EMPTY);
      setResult(answer);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="stack">
      <div className="between" style={{ alignItems: 'center' }}>
        <h2 className="card-title">Candidate skip indexes</h2>
        <Button variant="secondary" small busy={busy} disabled={!candidates.length} onClick={() => void estimate()}>
          {busy ? 'Estimating…' : result ? 'Estimate again' : 'Estimate with EXPLAIN WHATIF'}
        </Button>
      </div>
      <p className="caption muted">
        Indexes the table does not have yet. ClickHouse builds each one in memory over the granules a logged query reads today and says how many it would
        skip. That reads table data, up to 10 million rows per query and candidate, then a sample. Nothing is written to the table.
      </p>
      {error ? <p className="g-notice g-wrap">{error}</p> : null}
      {result?.message ? <p className="g-notice">{result.message}</p> : null}

      {list.length ? (
        <Card table>
          <table className="g-tbl g-acc">
            <thead>
              <tr>
                <th>Candidate</th>
                <th>Expression</th>
                <th>Type</th>
                <th className="r">Granularity</th>
                <th className="r">Applies to</th>
                <th style={{ width: '18%' }}>Granules dropped</th>
                <th>
                  <span className="sr-only">Remove</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {list.map((c) => {
                const r = result && !result.message ? result.candidates.find((x) => x.name === c.name && x.expression === c.expression && x.type === c.type) : undefined;
                const dropped = r && r.granulesBefore > 0 ? 1 - r.granulesAfter / r.granulesBefore : 0;
                const shown = isOpen(c.name);
                const id = `candidate-${c.name}`;
                return (
                  <Fragment key={c.name}>
                    <tr className={`g-acc__row${shown ? ' is-open' : ''}`} onClick={() => onToggle(c.name)}>
                      <td>
                        <RowButton open={shown} controls={id}>
                          {c.name}
                        </RowButton>
                      </td>
                      <td className="g-mono g-wrap">{c.expression}</td>
                      <td className="g-mono">{c.type}</td>
                      <td className="r">{fmtInt(c.granularity)}</td>
                      <td className="r nowrap">{!r ? '—' : r.error ? 'refused' : r.shapes > 0 ? `${fmtPct(r.share, 0)} of SELECTs` : 'no logged query'}</td>
                      <td>
                        {r && r.shapes > 0 ? (
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
                      <td className="r">
                        <button
                          type="button"
                          className="cc-btn cc-btn--link"
                          aria-label={`Remove candidate ${c.name}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            setList(list.filter((x) => x.name !== c.name));
                          }}
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                    {shown ? (
                      <tr className="g-acc__panel">
                        <td colSpan={7} id={id}>
                          {!r ? (
                            <p className="body-sm muted">Not estimated yet. “Estimate with EXPLAIN WHATIF” runs it against the logged queries.</p>
                          ) : r.error ? (
                            <p className="body-sm muted g-wrap">ClickHouse does not accept this index: {r.error}</p>
                          ) : (
                            <Uses result={result!} uses={r.uses} trialTo={routes.newTrial(sourceId, database, table)} />
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
        <p className="body-sm muted">The last scan proposes no new skip index for this table. Add one below to estimate it.</p>
      )}

      <div className="row" style={{ alignItems: 'flex-end', ...vars({ gap: 12 }) }}>
        <div style={{ flex: '1 1 200px', maxWidth: 280 }}>
          <TextInput label="Expression" value={draft.expression} placeholder="country" onChange={(e) => setDraft({ ...draft, expression: e.target.value })} />
        </div>
        <div style={{ flex: '1 1 180px', maxWidth: 240 }}>
          <TextInput label="Type" value={draft.type} list="candidate-index-types" onChange={(e) => setDraft({ ...draft, type: e.target.value })} />
        </div>
        <div style={{ flex: '0 1 120px' }}>
          <TextInput
            label="Granularity"
            type="number"
            numeric
            min={1}
            value={String(draft.granularity)}
            onChange={(e) => setDraft({ ...draft, granularity: Math.max(1, parseInt(e.target.value, 10) || 1) })}
          />
        </div>
        <Button
          variant="dark"
          disabled={!pending}
          onClick={() => {
            if (!pending) return;
            setList([...list, pending]);
            setDraft(EMPTY);
          }}
        >
          Add another
        </Button>
        <datalist id="candidate-index-types">
          {INDEX_TYPES.map((t) => (
            <option key={t} value={t} />
          ))}
        </datalist>
      </div>
      {draftProblem ? <p className="g-notice">{draftProblem}</p> : null}
      {pending ? (
        <p className="caption muted">
          {pending.name} ({pending.type} on {pending.expression}) is part of the next estimate. “Add another” keeps it and clears the fields for one more.
        </p>
      ) : null}
      {result && !result.message ? (
        <p className="caption muted">
          Estimated against {fmtInt(result.checkedShapes)} logged query {result.checkedShapes === 1 ? 'shape' : 'shapes'}, standing for{' '}
          {fmtInt(result.selects)} SELECTs{result.failedShapes ? `; ${fmtInt(result.failedShapes)} could not be estimated` : ''}. The estimate is an upper
          bound: a built index may skip a little less. A trial builds it on a sample and replays the queries.
        </p>
      ) : null}
    </section>
  );
}

/** The query shapes one candidate was estimated for: the ones it narrows first. */
function Uses({ result, uses, trialTo }: { result: TableWhatIf; uses: WhatIfUse[]; trialTo: string }) {
  const [all, setAll] = useState(false);
  if (!uses.length) return <p className="body-sm muted">None of the logged query shapes could be estimated with this index.</p>;
  const helped = uses.filter((u) => u.applicable).length;
  const shown = all ? uses : uses.slice(0, SHOWN);
  return (
    <div className="stack" style={vars({ gap: 12 })}>
      <p className="eyebrow">
        Applies to {fmtInt(helped)} of {fmtInt(uses.length)} query {uses.length === 1 ? 'shape' : 'shapes'}
      </p>
      <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, ...vars({ gap: 12 }) }}>
        {shown.map((u) => {
          const q = result.shapes[u.shape];
          if (!q) return null;
          const before = u.granulesBefore ?? 0;
          const after = u.granulesAfter ?? 0;
          return (
            <li key={u.shape} className="g-plan">
              <span className="g-sql g-sql--clamp">{q.query}</span>
              <dl className="facts">
                <Fact label="Runs">{fmtInt(q.count)}</Fact>
                <Fact label="Share of SELECTs">{fmtPct(q.share, 0)}</Fact>
                <Fact label="Reads today">
                  {q.granules != null ? `${fmtInt(q.granules)} granules` : '—'}
                  {q.parts != null ? ` · ${fmtInt(q.parts)} ${q.parts === 1 ? 'part' : 'parts'}` : ''}
                </Fact>
                {u.applicable ? (
                  <Fact label="With this index">
                    {fmtInt(after)} granules{before > 0 ? ` (−${fmtPct(1 - after / before, 0)})` : ''}
                  </Fact>
                ) : null}
              </dl>
              {u.applicable ? (
                <p className="body-sm muted">
                  Estimate: {SOURCE[u.source ?? ''] ?? u.source ?? 'unknown'}
                  {u.sampled ? ', from a sample of the table' : ''}.
                </p>
              ) : (
                <p className="body-sm muted g-wrap">Not applicable: {u.reason ?? 'the index cannot filter this query'}.</p>
              )}
            </li>
          );
        })}
      </ol>
      <div className="row" style={vars({ gap: 16 })}>
        {uses.length > SHOWN ? (
          <button type="button" className="cc-btn cc-btn--link" aria-expanded={all} onClick={() => setAll(!all)}>
            {all ? 'Show fewer' : `Show all ${fmtInt(uses.length)}`}
          </button>
        ) : null}
        <Button variant="link" to={trialTo}>
          Measure it in a trial
        </Button>
      </div>
    </div>
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
