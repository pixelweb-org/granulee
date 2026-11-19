import { Link } from 'react-router-dom';
import type { Opportunity } from '../api/types';
import { fmtBytes, fmtInt, fmtRatio } from '../lib/format';
import { routes } from '../lib/routes';
import { Card } from './Card';

/** Tables with open findings. `linkTo` picks where the table name leads. */
export function OpportunitiesTable({ items, linkTo }: { items: Opportunity[]; linkTo: 'table' | 'recommendations' }) {
  return (
    <Card table>
      <table className="g-tbl">
        <thead>
          <tr>
            <th>Table</th>
            <th>Source</th>
            <th className="r">Compressed</th>
            <th className="r">Ratio</th>
            <th className="r">Findings</th>
            <th className="r">Est. savings</th>
          </tr>
        </thead>
        <tbody>
          {items.map((o) => (
            <tr key={`${o.sourceId}/${o.database}/${o.table}`}>
              <td>
                <Link className="g-link" to={(linkTo === 'table' ? routes.table : routes.recommendations)(o.sourceId, o.database, o.table)}>
                  {o.database}.{o.table}
                </Link>
              </td>
              <td>{o.sourceName}</td>
              <td className="r nowrap">{fmtBytes(o.compressedBytes)}</td>
              <td className="r">{fmtRatio(o.uncompressedBytes, o.compressedBytes)}</td>
              <td className="r">{fmtInt(o.findings)}</td>
              <td className="r g-strong nowrap">{o.savingsBytes > 0 ? `≈ ${fmtBytes(o.savingsBytes)}` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
