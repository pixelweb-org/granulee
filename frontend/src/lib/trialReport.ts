import type { Trial, TrialResult } from '../api/types';
import { fmtBytes, fmtChange, fmtMs, fmtPct, fmtRows } from './format';
import { codecLabel, codecRows, metricOne, type Guide } from './trialGuide';

const cell = (text: string) => text.replace(/\|/g, '\\|').replace(/\s*\n\s*/g, ' ');
const table = (head: string[], rows: string[][]) =>
  [`| ${head.join(' | ')} |`, `| ${head.map(() => '---').join(' | ')} |`, ...rows.map((row) => `| ${row.map(cell).join(' | ')} |`)].join('\n');

/** The whole result of a trial as one Markdown file, to keep or to pass on. `guide` is the candidate against today's table. */
export function trialReport(trial: Trial, r: TrialResult, guide: Guide): string {
  const plain = r.metrics.some((m) => m.plain != null);
  const out: string[] = [
    `# ${trial.name}`,
    `Trial #${trial.id} of ${trial.database}.${trial.table} on ${trial.sourceName}${trial.finishedAt ? `, finished ${new Date(trial.finishedAt).toISOString().slice(0, 16).replace('T', ' ')} UTC` : ''}. ` +
      `${fmtRows(r.rows)} of ${fmtRows(r.totalRows)} rows, identical in every trial table.`,
    '## Verdict',
    guide.verdict,
  ];
  const items = (title: string, list: { title: string; detail: string }[]) => {
    if (list.length) out.push(`### ${title}`, list.map((i) => `- ${i.title}: ${i.detail}`).join('\n'));
  };
  items('Better', guide.better);
  items('Worse', guide.worse);
  if (guide.neutral.length) out.push('### No clear difference', guide.neutral.map((n) => `- ${n.label}: ${n.value}`).join('\n'));

  out.push(
    '## Measures',
    table(
      ['Measure', ...(plain ? ['Plain defaults'] : []), 'Today', 'Candidate', 'Change'],
      r.metrics.map((m) => [m.label, ...(plain ? [m.plain != null ? metricOne(m.unit, m.plain) : '—'] : []), metricOne(m.unit, m.baseline), metricOne(m.unit, m.candidate), fmtChange(m.baseline, m.candidate)]),
    ),
  );

  if (guide.changes.length) out.push('## What the candidate changes', guide.changes.map((c) => `- ${c.name}: \`${c.from}\` → \`${c.to}\``).join('\n'));

  out.push(
    '## Columns',
    table(
      ['Column', 'Definition', ...(plain ? ['Plain defaults'] : []), 'Today', 'Candidate', 'Change'],
      r.columns.map((c) => [
        c.name,
        c.candidate ? `${c.baseline} → ${c.candidate}` : c.baseline,
        ...(plain ? [c.plainBytes != null ? fmtBytes(c.plainBytes) : '—'] : []),
        fmtBytes(c.baseBytes),
        fmtBytes(c.candBytes),
        fmtChange(c.baseBytes, c.candBytes),
      ]),
    ),
  );

  if (r.queries.length) {
    out.push(
      '## Queries',
      table(
        ['Query', 'Share', ...(plain ? ['Plain defaults'] : []), 'Today', 'Candidate', 'Outcome'],
        r.queries.map((q) => [q.label, fmtPct(q.share), ...(plain ? [fmtMs(q.plainMs)] : []), fmtMs(q.baseMs), fmtMs(q.candMs), guide.verdicts.get(q.label)?.text ?? '—']),
      ),
      r.queries.map((q) => `- ${q.label}: \`${q.query.replace(/\s+/g, ' ').replace(/`/g, "'")}\``).join('\n'),
    );
  }

  const codecs = codecRows(r);
  if (codecs.length) {
    out.push(
      '## Codecs',
      table(
        ['Column', 'Type', 'Today', 'Best measured', 'Size today', 'Size best', 'Saving'],
        codecs.map((x) => [
          x.column.name,
          x.column.type,
          codecLabel(x.column.production, x.column.defaultIs),
          x.better ? codecLabel(x.column.best, x.column.defaultIs) : 'keep',
          x.today ? fmtBytes(x.today.compressedBytes) : '—',
          x.best ? fmtBytes(x.best.compressedBytes) : '—',
          x.better ? `−${fmtPct(x.share)}` : '—',
        ]),
      ),
    );
  }

  out.push('## Statements for production', r.alterDdl ? '```sql\n' + r.alterDdl + '\n```' : 'None: the candidate has the schema of the production table.');
  out.push('## Trial tables', '```sql\n' + [r.baseDdl, r.candDdl, r.plainDdl].filter(Boolean).join('\n\n') + '\n```');
  return out.join('\n\n') + '\n';
}
