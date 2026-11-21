import { Fragment, useState } from 'react';
import type { CodecColumn, Trial, TrialResult } from '../api/types';
import { fmtBytes, fmtInt, fmtMs, fmtPct, fmtRatio, fmtRows } from '../lib/format';
import { vars } from '../lib/style';
import { Card } from './Card';
import { CodeBlock } from './CodeBlock';
import { RowButton } from './RowButton';

// A better codec is worth a statement from this saving on; below it the two count as equal.
const WORTH = 0.03;

const label = (codec: string, defaultIs?: string | null) => codec || (defaultIs ? `server default (${defaultIs})` : 'server default');
const quote = (name: string) => '`' + name.replace(/\\/g, '\\\\').replace(/`/g, '\\`') + '`';

/** "ORDER BY (…)" of a trial table's CREATE statement. */
function orderOf(ddl: string): string {
  const line = ddl.split('\n').find((l) => l.startsWith('ORDER BY '));
  return line ? line.slice('ORDER BY '.length).trim() : '';
}

function sizes(c: CodecColumn) {
  const today = c.variants.find((v) => v.codec === c.production) ?? null;
  const best = c.variants.find((v) => v.codec === c.best) ?? null;
  const saved = today && best ? today.compressedBytes - best.compressedBytes : 0;
  const share = today && today.compressedBytes > 0 ? saved / today.compressedBytes : 0;
  return { today, best, saved, share, change: c.best !== c.production && share >= WORTH };
}

/** The Codecs tab of a trial: for each of the largest columns, every codec that was tried and the one to use. */
export function TrialCodecs({ trial, result: r }: { trial: Trial; result: TrialResult }) {
  const [open, setOpen] = useState<string | null>(null);
  const columns = r.codecs ?? [];
  if (!columns.length) {
    return (
      <p className="body-sm muted">
        This trial did not compare codecs. Switch on “Compare codecs for the largest columns” when you set up a trial to see, per column, which codec is
        smallest on your data.
      </p>
    );
  }
  const rows = columns.map((c) => ({ c, ...sizes(c) }));
  const changes = rows.filter((x) => x.change);
  const saved = changes.reduce((a, x) => a + x.saved, 0);
  const todayTotal = rows.reduce((a, x) => a + (x.today?.compressedBytes ?? 0), 0);
  const scale = r.rows > 0 ? r.totalRows / r.rows : 1;
  const order = [orderOf(r.baseDdl), orderOf(r.candDdl)];
  const table = `${quote(trial.database)}.${quote(trial.table)}`;
  const sql = changes
    .map(({ c }) => `ALTER TABLE ${table}\n  MODIFY COLUMN ${quote(c.name)} ${c.best ? `CODEC(${c.best})` : 'REMOVE CODEC'};`)
    .join('\n');

  return (
    <>
      <section aria-label="Codec comparison" className="grid-auto" style={vars({ min: 200 })}>
        <div className="tile">
          <span className="caption muted">Columns compared</span>
          <span className="headline">{fmtInt(columns.length)}</span>
          <span className="caption muted g-num">the largest, {fmtInt(columns.reduce((a, c) => a + c.variants.length, 0))} codec variants</span>
        </div>
        <div className="tile">
          <span className="caption muted">Better codec found</span>
          <span className="headline">{fmtInt(changes.length)}</span>
          <span className="caption muted g-num">at least {fmtPct(WORTH, 0)} smaller than today's</span>
        </div>
        <div className="tile">
          <span className="caption muted">Saving on the sample</span>
          <span className="headline">{fmtBytes(saved)}</span>
          <span className="caption muted g-num">{todayTotal > 0 ? `${fmtPct(saved / todayTotal, 0)} of these columns` : '—'}</span>
        </div>
        <div className="tile">
          <span className="caption muted">Projected for the table</span>
          <span className="headline">{fmtBytes(saved * scale)}</span>
          <span className="caption muted g-num">{fmtRows(r.totalRows)} rows, scaled from the sample</span>
        </div>
      </section>

      <p className="caption muted">
        Every variant holds the same {fmtRows(r.rows)} rows, stored in the candidate's order <span className="g-mono">{order[1]}</span>. A codec is only as
        good as its fit to the values and to the order they lie in, so each one was built and weighed.
        {order[0] !== order[1] ? ' This trial changes the sorting key: under today’s key the ranking can come out differently.' : ''}
      </p>

      <section className="stack">
        <h2 className="card-title">Codec per column</h2>
        <Card table>
          <table className="g-tbl g-acc">
            <thead>
              <tr>
                <th>Column</th>
                <th>Type</th>
                <th>Codec today</th>
                <th>Best measured</th>
                <th className="r">Size today → best</th>
                <th style={{ width: '16%' }}>Saving</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ c, today, best, share, change }) => {
                const shown = open === c.name;
                const id = `codec-${c.name}`;
                return (
                  <Fragment key={c.name}>
                    <tr className={`g-acc__row${shown ? ' is-open' : ''}`} onClick={() => setOpen(shown ? null : c.name)}>
                      <td>
                        <RowButton open={shown} controls={id}>
                          {c.name}
                        </RowButton>
                      </td>
                      <td className="g-mono">{c.type}</td>
                      <td className="g-mono">{label(c.production, c.defaultIs)}</td>
                      <td className={`g-mono${change ? ' ink' : ''}`} style={change ? { fontWeight: 700 } : undefined}>
                        {change ? label(c.best, c.defaultIs) : 'keep'}
                      </td>
                      <td className="r nowrap">
                        {today ? fmtBytes(today.compressedBytes) : '—'} → <span className="g-strong ink">{best ? fmtBytes(best.compressedBytes) : '—'}</span>
                      </td>
                      <td>
                        {change ? (
                          <div className="share">
                            <div className="g-track">
                              <div className="g-fill" style={{ width: `${Math.min(100, share * 100)}%` }} />
                            </div>
                            <span className="g-num ink" style={{ width: 40, textAlign: 'right' }}>
                              −{fmtPct(share, 0)}
                            </span>
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                    {shown ? (
                      <tr className="g-acc__panel">
                        <td colSpan={6} id={id}>
                          <Variants column={c} />
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </Card>
        <p className="caption muted">
          “Best measured” is the cheapest codec to read back among those within {fmtPct(WORTH, 0)} of the smallest; the codec in place stays when it is one
          of them. Open a column to see every variant.
        </p>
      </section>

      {sql ? (
        <section className="stack" style={vars({ gap: 12 })}>
          <h2 className="card-title" style={{ overflowWrap: 'anywhere' }}>
            Statements for {trial.database}.{trial.table}
          </h2>
          <p className="body-sm muted">
            Changing a codec only touches metadata. New parts use it at once, existing parts as they merge, and OPTIMIZE … FINAL rewrites them right away.
            Granulee does not run these.
          </p>
          <CodeBlock code={sql} copyLabel="Copy SQL" />
        </section>
      ) : (
        <p className="body-sm muted">No column has a codec that is at least {fmtPct(WORTH, 0)} smaller than the one it uses today.</p>
      )}
    </>
  );
}

/** Every codec tried for one column, smallest first, the pick drawn in colour. */
function Variants({ column: c }: { column: CodecColumn }) {
  const largest = Math.max(...c.variants.map((v) => v.compressedBytes), 1);
  const timed = c.variants.some((v) => v.scanMs != null);
  return (
    <div className="stack" style={vars({ gap: 8 })}>
      <div className="q-codecs">
        {c.variants.map((v) => {
          const tags = [v.codec === c.best ? 'pick' : null, v.codec === c.production ? 'today' : null, v.codec === c.candidate && c.candidate !== c.production ? 'candidate' : null].filter(Boolean);
          return (
            <div
              key={v.codec}
              className="q-codecs__row"
              title={`${label(v.codec, c.defaultIs)}: ${fmtBytes(v.compressedBytes)}, ratio ${fmtRatio(v.uncompressedBytes, v.compressedBytes)}${v.scanMs != null ? `, full read ${fmtMs(v.scanMs)}` : ''}`}
            >
              <span className="g-mono g-wrap">
                <span className={v.codec === c.best ? 'ink' : undefined} style={v.codec === c.best ? { fontWeight: 700 } : undefined}>
                  {label(v.codec, c.defaultIs)}
                </span>
                {tags.length ? <span className="muted"> · {tags.join(', ')}</span> : null}
              </span>
              <span className="q-codecs__bars">
                <span className={`q-codecs__bar${v.codec === c.best ? ' q-codecs__bar--pick' : ''}`} style={{ width: `${(v.compressedBytes / largest) * 100}%` }} />
              </span>
              <span className="caption g-num q-codecs__value">
                <span className="ink" style={{ fontWeight: 700 }}>
                  {fmtBytes(v.compressedBytes)}
                </span>{' '}
                <span className="muted">
                  · {fmtRatio(v.uncompressedBytes, v.compressedBytes)}
                  {timed ? ` · ${v.scanMs != null ? `read ${fmtMs(v.scanMs)}` : 'not timed'}` : ''}
                </span>
              </span>
            </div>
          );
        })}
      </div>
      <p className="caption muted">
        Compressed size on the sample, then the compression ratio{timed ? ' and the median time of a full read of the column' : ''}.
        {timed ? ' Only variants close to the smallest, and the one in use, were timed.' : ''}
      </p>
    </div>
  );
}
