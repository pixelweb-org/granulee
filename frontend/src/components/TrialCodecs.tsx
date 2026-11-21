import { useRef, useState } from 'react';
import type { Trial, TrialResult } from '../api/types';
import { fmtBytes, fmtInt, fmtMs, fmtPct, fmtRatio } from '../lib/format';
import { routes } from '../lib/routes';
import { CODEC_WORTH, clauseOf, codecLabel, codecRows } from '../lib/trialGuide';
import { Button, Choice } from './Button';
import { CopyButton } from './CodeBlock';

const quote = (name: string) => '`' + name.replace(/\\/g, '\\\\').replace(/`/g, '\\`') + '`';

/**
 * The Codecs step of a trial: the columns for which a smaller codec was measured, each with every variant that was
 * built, and the ones to take into a follow-up trial.
 */
export function TrialCodecs({
  trial,
  result: r,
  picks,
  onPicks,
}: {
  trial: Trial;
  result: TrialResult;
  picks: Record<string, boolean>;
  onPicks: (picks: Record<string, boolean>) => void;
}) {
  const rows = codecRows(r);
  const better = rows.filter((x) => x.better);
  const [filter, setFilter] = useState<'better' | 'all'>(better.length ? 'better' : 'all');
  const [selected, setSelected] = useState<string | null>(null);
  const detail = useRef<HTMLDivElement>(null);
  if (!rows.length) {
    return (
      <p className="body-sm muted">
        This trial did not compare codecs. Switch on “Compare codecs for the largest columns” when you set up a trial to see, per column, which codec is
        smallest on your data.
      </p>
    );
  }
  const shown = filter === 'better' ? better : rows;
  const picked = rows.find((x) => x.column.name === selected) ?? shown[0] ?? rows[0];
  const c = picked.column;
  const variants = rows.reduce((a, x) => a + x.column.variants.length, 0);
  const maxSaved = Math.max(...better.map((x) => x.saved), 1);
  const largest = Math.max(...c.variants.map((v) => v.compressedBytes), 1);
  const timed = c.variants.some((v) => v.scanMs != null);
  const taken = better.filter((x) => picks[x.column.name]);
  const newOrder = clauseOf(r.baseDdl, 'ORDER BY') !== clauseOf(r.candDdl, 'ORDER BY');
  const table = `${quote(trial.database)}.${quote(trial.table)}`;
  const sql = taken.map(({ column: k }) => `ALTER TABLE ${table}\n  MODIFY COLUMN ${quote(k.name)} ${k.best ? `CODEC(${k.best})` : 'REMOVE CODEC'};`).join('\n');
  const followUp =
    routes.newTrial(trial.sourceId, trial.database, trial.table) +
    `&from=${encodeURIComponent(trial.id)}&codecs=${encodeURIComponent(JSON.stringify(Object.fromEntries(taken.map((x) => [x.column.name, x.column.best]))))}`;
  const pick = (name: string) => {
    setSelected(name);
    requestAnimationFrame(() => {
      const box = detail.current?.getBoundingClientRect();
      if (box && (box.top > window.innerHeight * 0.7 || box.bottom < 0)) detail.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    });
  };

  return (
    <section className="stack">
      <div className="tg-bar" style={{ alignItems: 'baseline' }}>
        <h2 className="card-title">Codecs worth a follow-up</h2>
        <span className="caption muted">
          {fmtInt(variants)} variants built for the {fmtInt(rows.length)} largest columns, on the same rows in the candidate's order. Largest saving first.
          {newOrder ? ' This trial changes the sorting key: under today’s key the ranking can come out differently.' : ''}
        </span>
      </div>
      <div role="group" aria-label="Which columns to show" className="row">
        <Choice pressed={filter === 'better'} onClick={() => setFilter('better')}>
          Better found {fmtInt(better.length)}
        </Choice>
        <Choice pressed={filter === 'all'} onClick={() => setFilter('all')}>
          All {fmtInt(rows.length)}
        </Choice>
      </div>
      <div className="tg-split">
        <div className="card tg-list">
          <div className="eyebrow tg-list__head" aria-hidden="true">
            <span className="tg-codec__name">Column</span>
            <span style={{ flex: 1 }}>Today → best measured</span>
            <span className="tg-codec__save" style={{ justifyContent: 'flex-end' }}>
              Saving on the sample
            </span>
          </div>
          {shown.map((x) => {
            const k = x.column;
            return (
              <button type="button" key={k.name} className="g-rowbtn tg-pick" aria-pressed={k.name === c.name} onClick={() => pick(k.name)}>
                <span className="tg-codec__name g-wrap">
                  {k.name}
                  {picks[k.name] && x.better ? (
                    <span className="tg-codec__taken" title="Taken into the follow-up trial">
                      {' '}
                      ✓<span className="sr-only"> taken into the follow-up trial</span>
                    </span>
                  ) : null}
                </span>
                <span className="g-mono g-wrap" style={{ flex: 1 }}>
                  <span className="muted">{codecLabel(k.production, k.defaultIs)} →</span> {x.better ? codecLabel(k.best, k.defaultIs) : 'keep'}
                </span>
                <span className="tg-codec__save">
                  {x.better ? (
                    <>
                      <span className="g-track">
                        <span aria-hidden="true" className="g-fill" style={{ display: 'block', width: `${(x.saved / maxSaved) * 100}%` }} />
                      </span>
                      <span className="g-num tg-codec__value">
                        −{fmtBytes(x.saved)} · −{fmtPct(x.share, 0)}
                      </span>
                    </>
                  ) : (
                    <span className="g-num tg-codec__value muted">—</span>
                  )}
                </span>
              </button>
            );
          })}
          {!shown.length ? <p className="body-sm muted tg-list__note">No column has a codec that is clearly smaller than today's.</p> : null}
          <p className="caption muted tg-list__note">
            {rows.length - better.length === 0
              ? `A smaller codec was measured for every one of the ${fmtInt(rows.length)} compared columns.`
              : `${fmtInt(rows.length - better.length)} of the ${fmtInt(rows.length)} compared columns keep today's codec: nothing measured was at least ${fmtPct(CODEC_WORTH, 0)} smaller.`}
          </p>
        </div>
        <div className="card tg-detail" aria-live="polite" ref={detail} style={{ gap: 12 }}>
          <div className="row" style={{ alignItems: 'baseline', gap: 12 }}>
            <span className="tg-title">{c.name}</span>
            <span className="g-mono muted">{c.type}</span>
          </div>
          <span className="eyebrow">Every variant, smallest first</span>
          {c.variants.map((v) => {
            const isPick = v.codec === c.best;
            const isToday = v.codec === c.production;
            const tags = [isPick ? 'pick' : null, isToday ? 'today' : null, v.codec === c.candidate && c.candidate !== c.production ? 'candidate' : null].filter(Boolean);
            return (
              <div key={v.codec} className="tg-variant">
                <span className="g-mono g-wrap tg-variant__name">
                  {codecLabel(v.codec, c.defaultIs)} {tags.length ? <span className="muted">· {tags.join(', ')}</span> : null}
                </span>
                <span className="tg-variant__track">
                  <span
                    aria-hidden="true"
                    className="tg-variant__bar"
                    style={{ width: `${(v.compressedBytes / largest) * 100}%`, background: isPick ? 'var(--chart-1)' : isToday ? 'var(--chart-2)' : 'var(--chart-other)' }}
                  />
                </span>
                <span className="g-num tg-variant__value">
                  <strong>{fmtBytes(v.compressedBytes)}</strong>{' '}
                  <span className="muted">
                    · {fmtRatio(v.uncompressedBytes, v.compressedBytes)}
                    {timed ? ` · ${v.scanMs != null ? `read ${fmtMs(v.scanMs)}` : 'not timed'}` : ''}
                  </span>
                </span>
              </div>
            );
          })}
          <p className="caption muted">
            Size on the sample, compression ratio{timed ? ' and the median time of a full read' : ''}. The pick is the cheapest to read among those within{' '}
            {fmtPct(CODEC_WORTH, 0)} of the smallest; the codec in place stays when it is one of them.
          </p>
          {picked.better ? (
            <label className="body-sm tg-check">
              <input type="checkbox" checked={!!picks[c.name]} onChange={(e) => onPicks({ ...picks, [c.name]: e.target.checked })} /> Take this codec into a follow-up
              trial
            </label>
          ) : (
            <p className="body-sm tg-check">Today's codec stays: nothing measured was at least {fmtPct(CODEC_WORTH, 0)} smaller.</p>
          )}
        </div>
      </div>
      <div className="row" style={{ gap: 16 }}>
        <Button to={followUp} disabled={!taken.length}>
          {taken.length ? `Set up a trial with ${fmtInt(taken.length)} ${taken.length === 1 ? 'pick' : 'picks'}` : 'Pick a codec first'}
        </Button>
        {taken.length ? <CopyButton text={sql}>Copy as ALTER statements</CopyButton> : null}
        <span className="body-sm muted" style={{ flex: '1 1 320px' }}>
          Codec sizes here are measured one column at a time. A follow-up trial shows what they do to inserts and queries together.
        </span>
      </div>
    </section>
  );
}
