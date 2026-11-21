import type { IndexStep } from '../api/types';
import { fmtInt } from '../lib/format';

const STEP_KIND: Record<IndexStep['kind'], string> = { minmax: 'Min-max', partition: 'Partition', primary: 'Primary key', skip: 'Skip index' };

/** The index steps of one plan, in the order ClickHouse applied them. `highlight` marks one skip index by name. */
export function PlanSteps({ steps, highlight }: { steps: IndexStep[]; highlight?: string }) {
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
                {STEP_KIND[s.kind]}
              </span>
              <div className="g-mono g-wrap">{s.name}</div>
              {s.condition && s.condition !== 'true' ? <div className="g-mono g-wrap muted">{s.condition}</div> : null}
            </td>
            <td className="r nowrap">
              {fmtInt(s.partsBefore)} → {fmtInt(s.partsAfter)}
            </td>
            <td className={`r nowrap${s.granulesAfter < s.granulesBefore ? ' g-strong' : ''}`}>
              {fmtInt(s.granulesBefore)} → {fmtInt(s.granulesAfter)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
