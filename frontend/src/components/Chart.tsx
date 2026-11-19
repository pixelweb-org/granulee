import { useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode, type RefObject } from 'react';

/* Chart: bar · line · area · pie · donut. A typed port of the design system's chart renderer. */

export interface ChartSeries {
  name: string;
  values: number[];
  /** Fixed colour slot 1–6; defaults to the series' position. */
  slot?: number;
}

export interface ChartProps {
  type?: 'bar' | 'line' | 'area' | 'pie' | 'donut';
  title?: string;
  subtitle?: string;
  /** Names the category axis in the table view and for screen readers. */
  categoryLabel?: string;
  /** Caption under the donut total. */
  totalLabel?: string;
  categories: string[];
  series: ChartSeries[];
  format?: (v: number) => string;
  height?: number;
  /** No card around the chart. */
  bare?: boolean;
  stacked?: boolean;
  /** Draw this series in colour and mute the others. */
  highlight?: string;
  className?: string;
}

const SLOTS = 6;
const slotVar = (slot: number) => `var(--chart-${slot})`;

function compact(v: number): string {
  const a = Math.abs(v);
  if (a >= 1e9) return `${+(v / 1e9).toFixed(1)}B`;
  if (a >= 1e6) return `${+(v / 1e6).toFixed(1)}M`;
  if (a >= 1e3) return `${+(v / 1e3).toFixed(1)}K`;
  return String(+v.toFixed(2));
}
const full = (v: number) => Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 });

function niceStep(span: number, count: number): number {
  const raw = span / Math.max(1, count);
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const n = raw / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
}
function niceTicks(min: number, max: number, count: number): number[] {
  if (min === max) max = min + 1;
  const step = niceStep(max - min, count);
  const lo = Math.floor(min / step) * step;
  const hi = Math.ceil(max / step) * step;
  const out: number[] = [];
  for (let v = lo; v <= hi + step / 2; v += step) out.push(+v.toFixed(10));
  return out;
}

function useWidth(ref: RefObject<HTMLDivElement | null>, fallback: number): number {
  const [w, setW] = useState(fallback);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    setW(el.clientWidth || fallback);
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((e) => setW(Math.round(e[0].contentRect.width) || fallback));
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref, fallback]);
  return w;
}

/** A bar with a 4px rounded data end and a square baseline end. */
function barPath(x: number, w: number, yBase: number, yEnd: number, radius: number): string {
  const hgt = Math.abs(yEnd - yBase);
  const r = Math.min(radius, w / 2, hgt);
  if (hgt < 0.5) return '';
  if (yEnd <= yBase) return `M${x},${yBase}V${yEnd + r}A${r},${r} 0 0 1 ${x + r},${yEnd}H${x + w - r}A${r},${r} 0 0 1 ${x + w},${yEnd + r}V${yBase}Z`;
  return `M${x},${yBase}V${yEnd - r}A${r},${r} 0 0 0 ${x + r},${yEnd}H${x + w - r}A${r},${r} 0 0 0 ${x + w},${yEnd - r}V${yBase}Z`;
}

function arcPath(cx: number, cy: number, r0: number, r1: number, a0: number, a1in: number): string {
  let a1 = a1in;
  const large = a1 - a0 > Math.PI ? 1 : 0;
  const p = (r: number, a: number) => `${cx + r * Math.sin(a)},${cy - r * Math.cos(a)}`;
  if (a1 - a0 >= Math.PI * 2 - 1e-6) a1 = a0 + Math.PI * 2 - 1e-4;
  let d = `M${p(r1, a0)}A${r1},${r1} 0 ${large} 1 ${p(r1, a1)}`;
  d += r0 > 0 ? `L${p(r0, a1)}A${r0},${r0} 0 ${large} 0 ${p(r0, a0)}Z` : `L${cx},${cy}Z`;
  return d;
}

const cls = (...parts: (string | false | null | undefined)[]) => parts.filter(Boolean).join(' ');

interface Slice {
  label: string;
  value: number;
  color: string;
  a0: number;
  a1: number;
  share: number;
}

export function Chart(props: ChartProps) {
  const type = props.type ?? 'bar';
  const isPie = type === 'pie' || type === 'donut';
  // One series, or one highlighted among muted ones, takes the accent instead of a categorical slot.
  const single = !isPie && (props.series.length === 1 || props.highlight != null);
  const fmt = props.format ?? full;
  const tickFmt = props.format ?? compact;
  const plotH = props.height ?? 240;
  const wrapRef = useRef<HTMLDivElement>(null);
  const width = useWidth(wrapRef, 600);
  const [active, setActive] = useState<number | null>(null);
  const [table, setTable] = useState(false);
  const [pointer, setPointer] = useState<[number, number] | null>(null);

  const categories = props.categories;
  /* colour follows the entity: an explicit slot wins; otherwise the series' position. */
  const series = props.series.slice(0, SLOTS).map((s, i) => {
    const muted = props.highlight != null && s.name !== props.highlight;
    const color = muted ? 'var(--chart-muted)' : props.highlight != null ? slotVar(1) : slotVar(s.slot ?? i + 1);
    return { ...s, color, muted };
  });

  /* ---- pie / donut data ---- */
  let slices: Slice[] = [];
  if (isPie) {
    const vals = series[0]?.values ?? [];
    let items = categories
      .map((c, i) => ({ label: c, value: Math.max(0, +vals[i] || 0), color: slotVar(i + 1) }))
      .filter((d) => d.value > 0);
    if (items.length > SLOTS) {
      const rest = items.slice(SLOTS - 1);
      items = items.slice(0, SLOTS - 1);
      items.push({ label: 'Other', value: rest.reduce((a, d) => a + d.value, 0), color: 'var(--chart-muted)' });
    }
    const total = items.reduce((a, d) => a + d.value, 0) || 1;
    let acc = 0;
    slices = items.map((d) => {
      const a0 = (acc / total) * Math.PI * 2;
      acc += d.value;
      return { ...d, a0, a1: (acc / total) * Math.PI * 2, share: d.value / total };
    });
  }

  /* ---- cartesian scales ---- */
  const stacked = !!props.stacked && type === 'bar';
  const n = categories.length;
  let dataMin = 0;
  let dataMax = 0;
  if (!isPie) {
    for (let ci = 0; ci < n; ci++) {
      let pos = 0;
      let neg = 0;
      for (const s of series) {
        const v = +s.values[ci] || 0;
        if (stacked) {
          if (v >= 0) pos += v;
          else neg += v;
        } else {
          dataMax = Math.max(dataMax, v);
          dataMin = Math.min(dataMin, v);
        }
      }
      if (stacked) {
        dataMax = Math.max(dataMax, pos);
        dataMin = Math.min(dataMin, neg);
      }
    }
  }
  const ticks = isPie ? [] : niceTicks(dataMin, dataMax, Math.max(3, Math.round(plotH / 56)));
  const yMin = ticks[0] ?? 0;
  const yMax = ticks[ticks.length - 1] ?? 1;
  const labelW = isPie ? 0 : Math.max(...ticks.map((t) => String(tickFmt(t)).length)) * 7 + 12;
  const endLabels = (type === 'line' || type === 'area') && series.length <= 4;
  const M = { t: 12, r: endLabels ? 64 : 8, b: 28, l: labelW };
  const pw = Math.max(40, width - M.l - M.r);
  const y = (v: number) => M.t + ((yMax - v) / (yMax - yMin || 1)) * plotH;
  const band = n ? pw / n : pw;
  const xBand = (i: number) => M.l + band * i;
  const xPoint = (i: number) => M.l + (n <= 1 ? pw / 2 : (pw * i) / (n - 1));
  const xCenter = (i: number) => (type === 'bar' ? xBand(i) + band / 2 : xPoint(i));

  function idxFromEvent(e: PointerEvent<SVGRectElement>): number {
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    if (type === 'bar') return Math.max(0, Math.min(n - 1, Math.floor(px / band)));
    return Math.max(0, Math.min(n - 1, Math.round(px / (n <= 1 ? 1 : pw / (n - 1)))));
  }
  function trackPointer(e: PointerEvent<SVGElement>) {
    const r = wrapRef.current?.getBoundingClientRect();
    if (r) setPointer([e.clientX - r.left, e.clientY - r.top]);
  }
  function onKey(e: KeyboardEvent<SVGSVGElement>) {
    const count = isPie ? slices.length : n;
    if (!count) return;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      e.preventDefault();
      setPointer(null);
      setActive(active == null ? 0 : (active + 1) % count);
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      e.preventDefault();
      setPointer(null);
      setActive(active == null ? count - 1 : (active - 1 + count) % count);
    } else if (e.key === 'Escape') setActive(null);
  }

  const svgH = isPie ? Math.min(plotH, width) : plotH + M.t + M.b;
  const kids: ReactNode[] = [];

  if (isPie) {
    const cx = width / 2;
    const cy = svgH / 2;
    const R = Math.max(1, svgH / 2 - 4);
    const r0 = type === 'donut' ? R * 0.62 : 0;
    slices.forEach((s, i) => {
      kids.push(
        <path
          key={`s${i}`}
          d={arcPath(cx, cy, r0, R, s.a0, s.a1)}
          fill={s.color}
          className={cls('bd-chart-mark', active === i && 'bd-chart-on')}
          stroke="var(--bd-chart-bg)"
          strokeWidth={2}
          strokeLinejoin="round"
          onPointerMove={(e) => {
            trackPointer(e);
            setActive(i);
          }}
        />,
      );
    });
    if (type === 'donut') {
      kids.push(
        <text key="tv" x={cx} y={cy + 2} textAnchor="middle" className="bd-chart-center">
          {fmt(slices.reduce((a, d) => a + d.value, 0))}
        </text>,
        <text key="tl" x={cx} y={cy + 22} textAnchor="middle" className="bd-chart-axis">
          {props.totalLabel ?? 'Total'}
        </text>,
      );
    }
  } else {
    /* grid + y ticks: hairline, solid, recessive */
    ticks.forEach((t, i) => {
      kids.push(
        <line key={`g${i}`} x1={M.l} x2={M.l + pw} y1={y(t)} y2={y(t)} className={t === 0 ? 'bd-chart-base' : 'bd-chart-grid'} />,
        <text key={`yt${i}`} x={M.l - 8} y={y(t) + 4} textAnchor="end" className="bd-chart-axis">
          {tickFmt(t)}
        </text>,
      );
    });
    /* x labels, thinned so they never collide */
    const maxChars = Math.max(1, ...categories.map((c) => String(c).length));
    const every = Math.max(1, Math.ceil((maxChars * 7 + 12) / (type === 'bar' ? band : pw / Math.max(1, n - 1))));
    categories.forEach((c, i) => {
      if (i % every && i !== n - 1) return;
      if (i === n - 1 && n > 1 && every > 1 && (n - 1) % every && (n - 1) % every < every / 2) return;
      const anchor = type === 'bar' ? 'middle' : i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle';
      kids.push(
        <text key={`xt${i}`} x={xCenter(i)} y={M.t + plotH + 20} textAnchor={anchor} className="bd-chart-axis">
          {c}
        </text>,
      );
    });
    if (active != null && type === 'bar') {
      kids.push(<rect key="band" x={xBand(active)} y={M.t} width={band} height={plotH} className="bd-chart-band" />);
    }

    if (type === 'bar') {
      const k = series.length;
      const gap = 2;
      const thick = stacked ? Math.min(24, band * 0.6) : Math.max(1, Math.min(24, (band * 0.7 - (k - 1) * gap) / Math.max(1, k)));
      const groupW = stacked ? thick : thick * k + gap * (k - 1);
      let maxIdx = -1;
      let maxVal = -Infinity;
      for (let i = 0; i < n; i++) {
        const x0 = xBand(i) + (band - groupW) / 2;
        let upTop = 0;
        let downTop = 0;
        let lastUp = -1;
        let lastDown = -1;
        if (stacked) {
          series.forEach((s, j) => {
            const v = +s.values[i] || 0;
            if (v > 0) lastUp = j;
            if (v < 0) lastDown = j;
          });
        }
        series.forEach((s, j) => {
          const v = +s.values[i] || 0;
          let d: string;
          if (stacked) {
            const from = v >= 0 ? upTop : downTop;
            const to = from + v;
            if (v >= 0) upTop = to;
            else downTop = to;
            /* 2px surface gap between stacked segments; only the outermost segment gets the rounded end */
            const outer = j === (v >= 0 ? lastUp : lastDown);
            const ya = from === 0 ? y(from) : y(from) + (v >= 0 ? -1 : 1);
            const yb = y(to) + (outer ? 0 : v >= 0 ? 1 : -1);
            d = barPath(x0, thick, ya, yb, outer ? 4 : 0);
          } else {
            d = barPath(x0 + j * (thick + gap), thick, y(0), y(v), 4);
            if (k === 1 && v > maxVal) {
              maxVal = v;
              maxIdx = i;
            }
          }
          if (d) kids.push(<path key={`b${i}-${j}`} d={d} fill={s.color} className={cls('bd-chart-mark', active === i && 'bd-chart-on')} />);
        });
        if (stacked && upTop > maxVal) {
          maxVal = upTop;
          maxIdx = i;
        }
      }
      /* selective direct label: the extreme value only */
      if (maxIdx >= 0 && maxVal > 0 && (k === 1 || stacked)) {
        kids.push(
          <text key="peak" x={xCenter(maxIdx)} y={y(maxVal) - 8} textAnchor="middle" className="bd-chart-value">
            {fmt(maxVal)}
          </text>,
        );
      }
    } else if (n > 0) {
      series.forEach((s, j) => {
        const pts = categories.map((_, i) => [xPoint(i), y(+s.values[i] || 0)] as const);
        const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0]},${p[1]}`).join('');
        const floor = y(Math.max(0, yMin));
        if (type === 'area') {
          kids.push(<path key={`a${j}`} d={`${line}L${pts[pts.length - 1][0]},${floor}L${pts[0][0]},${floor}Z`} fill={s.color} fillOpacity={s.muted ? 0.05 : 0.1} />);
        }
        kids.push(<path key={`l${j}`} d={line} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />);
      });
      /* end dots with a 2px surface ring, drawn above every line */
      series.forEach((s, j) => {
        kids.push(<circle key={`e${j}`} cx={xPoint(n - 1)} cy={y(+s.values[n - 1] || 0)} r={4} fill={s.color} stroke="var(--bd-chart-bg)" strokeWidth={2} />);
      });
      if (endLabels) {
        const placed: number[] = [];
        series
          .map((s, j) => ({ j, s, yy: y(+s.values[n - 1] || 0) }))
          .sort((a, b) => (a.s.muted ? 1 : 0) - (b.s.muted ? 1 : 0))
          .forEach((o) => {
            /* converging labels are not nudged: they drop to the legend + tooltip */
            if (placed.some((p) => Math.abs(p - o.yy) < 14)) return;
            placed.push(o.yy);
            kids.push(
              <text key={`el${o.j}`} x={xPoint(n - 1) + 10} y={o.yy + 4} className={o.s.muted ? 'bd-chart-axis' : 'bd-chart-value'}>
                {tickFmt(+o.s.values[n - 1] || 0)}
              </text>,
            );
          });
      }
      if (active != null) {
        kids.push(<line key="xh" x1={xPoint(active)} x2={xPoint(active)} y1={M.t} y2={M.t + plotH} className="bd-chart-cross" />);
        series.forEach((s, j) => {
          kids.push(<circle key={`h${j}`} cx={xPoint(active)} cy={y(+s.values[active] || 0)} r={4} fill={s.color} stroke="var(--bd-chart-bg)" strokeWidth={2} />);
        });
      }
    }
    /* the hit layer: the whole plot, so the reader aims at a category, not a 2px line */
    if (n > 0) {
      kids.push(
        <rect
          key="hit"
          x={M.l}
          y={M.t}
          width={pw}
          height={plotH}
          fill="transparent"
          onPointerMove={(e) => {
            trackPointer(e);
            setActive(idxFromEvent(e));
          }}
        />,
      );
    }
  }

  /* ---- legend: always for 2+ series (and every pie); never for one ---- */
  const legendItems: { color: string; name: string; extra?: string; shape: 'rect' | 'line' }[] = isPie
    ? slices.map((s) => ({ color: s.color, name: s.label, extra: `${(s.share * 100).toFixed(0)}%`, shape: 'rect' }))
    : series.length > 1
      ? series.map((s) => ({ color: s.color, name: s.name, shape: type === 'line' ? 'line' : 'rect' }))
      : [];
  const legendH = legendItems.length ? 30 : 0;

  /* ---- tooltip: values lead, series names follow ---- */
  let tip: ReactNode = null;
  if (active != null && (isPie ? slices[active] : active < n)) {
    let head: string;
    let rows: { color: string; name: string; value: string }[];
    if (isPie) {
      const s = slices[active];
      head = s.label;
      rows = [{ color: s.color, name: `${(s.share * 100).toFixed(1)}%`, value: fmt(s.value) }];
    } else {
      head = categories[active];
      rows = series.map((s) => ({ color: s.color, name: s.name, value: fmt(+s.values[active] || 0) }));
      if (stacked) rows.reverse();
    }
    let tx: number;
    let ty: number;
    if (pointer) {
      [tx, ty] = pointer;
    } else if (isPie) {
      const mid = (slices[active].a0 + slices[active].a1) / 2;
      tx = width / 2 + (Math.sin(mid) * svgH) / 3;
      ty = svgH / 2 - (Math.cos(mid) * svgH) / 3 + legendH;
    } else {
      tx = xCenter(active);
      ty = M.t + plotH / 3 + legendH;
    }
    const left = tx + 16 + 180 > width ? Math.max(0, tx - 196) : tx + 16;
    tip = (
      <div className="bd-chart-tip" style={{ left, top: Math.max(0, ty - 12) }}>
        <div className="bd-chart-tip-head caption">{head}</div>
        {rows.map((r, i) => (
          <div key={i} className="bd-chart-tip-row">
            <span className="bd-chart-key-line" style={{ background: r.color }} />
            <strong className="body-sm">{r.value}</strong>
            <span className="caption bd-chart-tip-name">{r.name}</span>
          </div>
        ))}
      </div>
    );
  }

  /* ---- table view: the accessible twin of every chart ---- */
  const tableEl = (
    <div className="bd-chart-tablewrap">
      <table className="g-tbl">
        <thead>
          <tr>
            <th scope="col">{props.categoryLabel ?? ''}</th>
            {isPie ? (
              <>
                <th scope="col" className="r">{series[0]?.name ?? 'Value'}</th>
                <th scope="col" className="r">Share</th>
              </>
            ) : (
              series.map((s, j) => (
                <th key={j} scope="col" className="r">
                  {s.name}
                </th>
              ))
            )}
          </tr>
        </thead>
        <tbody>
          {isPie
            ? slices.map((s, i) => (
                <tr key={i}>
                  <td className="g-strong">{s.label}</td>
                  <td className="r">{fmt(s.value)}</td>
                  <td className="r">{(s.share * 100).toFixed(1)}%</td>
                </tr>
              ))
            : categories.map((c, i) => (
                <tr key={i}>
                  <td className="g-strong">{c}</td>
                  {series.map((s, j) => (
                    <td key={j} className="r">
                      {fmt(+s.values[i] || 0)}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
      </table>
    </div>
  );

  const label = `${props.title ?? 'Chart'}. ${isPie ? 'Use arrow keys to step through slices.' : `Use arrow keys to step through ${props.categoryLabel ?? 'categories'}.`}`;
  return (
    <figure className={cls('bd-chart', props.bare && 'bd-chart-bare', single && 'nv-single', props.className)}>
      <div className="bd-chart-head">
        <div>
          {props.title ? <figcaption className="subhead">{props.title}</figcaption> : null}
          {props.subtitle ? <p className="bd-chart-sub caption">{props.subtitle}</p> : null}
        </div>
        <button type="button" className="bd-chart-toggle" aria-pressed={table} onClick={() => setTable(!table)}>
          {table ? 'Chart' : 'Table'}
        </button>
      </div>
      {table ? tableEl : null}
      <div
        className="bd-chart-body"
        ref={wrapRef}
        hidden={table}
        onPointerLeave={() => {
          setActive(null);
          setPointer(null);
        }}
      >
        {legendItems.length ? (
          <ul className="bd-chart-legend caption">
            {legendItems.map((l, i) => (
              <li key={i}>
                <span className={l.shape === 'line' ? 'bd-chart-key-line' : 'bd-chart-key-rect'} style={{ background: l.color }} />
                <span>{l.name}</span>
                {l.extra ? <span className="bd-chart-legend-extra">{l.extra}</span> : null}
              </li>
            ))}
          </ul>
        ) : null}
        <svg
          width={width}
          height={svgH}
          viewBox={`0 0 ${width} ${svgH}`}
          tabIndex={0}
          role="group"
          aria-roledescription="chart"
          aria-label={label}
          onKeyDown={onKey}
          onBlur={() => setActive(null)}
          className="bd-chart-svg"
        >
          {kids}
        </svg>
        <div aria-live="polite" className="bd-chart-live">
          {tip}
        </div>
      </div>
    </figure>
  );
}
