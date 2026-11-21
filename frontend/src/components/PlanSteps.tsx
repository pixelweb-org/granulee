import type { IndexStep, QueryExplain } from '../api/types';
import { fmtInt } from '../lib/format';

const STEP_KIND: Record<IndexStep['kind'], string> = {
  minmax: 'Min-max',
  partition: 'Partition',
  statistics: 'Column statistics',
  primary: 'Primary key',
  skip: 'Skip index',
};

/** The index steps of one plan, in the order ClickHouse applied them. `highlight` marks one skip index by name. */
export function PlanSteps({ steps, highlight }: { steps: IndexStep[]; highlight?: string }) {
  // Everything the table has, before any index: what the bars under the granules are a share of.
  const total = steps[0]?.granulesBefore ?? 0;
  return (
    <table className="g-tbl">
      <thead>
        <tr>
          <th>Index</th>
          <th className="r">Parts</th>
          <th className="r">Granules</th>
        </tr>
      </thead>
      <tbody>
        {steps.map((s, i) => (
          <tr key={i} className={highlight && s.kind === 'skip' && s.name === highlight ? 'is-changed' : undefined}>
            <td>
              <span className="ink" style={{ fontWeight: 700 }}>
                {STEP_KIND[s.kind] ?? s.kind}
              </span>
              {s.searchAlgorithm ? <span className="muted"> · {s.searchAlgorithm}</span> : null}
              <div className="g-mono g-wrap">{s.name}</div>
              {s.condition && s.condition !== 'true' ? <div className="g-mono g-wrap muted">{s.condition}</div> : null}
            </td>
            <td className="r nowrap">
              {fmtInt(s.partsBefore)} → {fmtInt(s.partsAfter)}
            </td>
            <td className={`r nowrap${s.granulesAfter < s.granulesBefore ? ' g-strong' : ''}`}>
              {fmtInt(s.granulesBefore)} → {fmtInt(s.granulesAfter)}
              {total > 0 ? (
                <div className="g-track q-share" title={`${fmtInt(s.granulesAfter)} of ${fmtInt(total)} granules left`}>
                  <div className="g-fill" style={{ width: `${Math.min(100, (s.granulesAfter / total) * 100)}%` }} />
                </div>
              ) : null}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** How the read itself runs: in key order or not, and what ClickHouse filters on before it reads the other columns. */
export function ReadFacts({ plan }: { plan: QueryExplain }) {
  const inOrder = plan.readType === 'InOrder' || plan.readType === 'InReverseOrder';
  if (!inOrder && !plan.prewhere) return null;
  return (
    <div className="stack" style={{ gap: 4 }}>
      {inOrder ? (
        <p className="body-sm muted">
          Reads in {plan.readType === 'InReverseOrder' ? 'reverse ' : ''}sorting-key order, so the ORDER BY needs no sort and a LIMIT can stop early.
        </p>
      ) : null}
      {plan.prewhere ? (
        <p className="body-sm muted g-wrap">
          PREWHERE: <span className="g-mono">{plan.prewhere}</span>
        </p>
      ) : null}
    </div>
  );
}
