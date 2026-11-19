const MINUS = '−';
const BYTE_UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];

/** Three significant digits without trailing zeros: 300, 22.4, 2.34, 59. */
function sig3(v: number): string {
  const a = Math.abs(v);
  if (a >= 100) return Math.round(v).toLocaleString('en-US');
  return String(+(a >= 10 ? v.toFixed(1) : v.toFixed(2)));
}

function byteUnitIndex(n: number): number {
  let i = 0;
  let v = Math.abs(n);
  while (v >= 1024 && i < BYTE_UNITS.length - 1) {
    v /= 1024;
    i++;
  }
  return i;
}

function bytesIn(n: number, unit: number): string {
  if (unit === 0) return Math.round(n).toLocaleString('en-US');
  return sig3(n / Math.pow(1024, unit));
}

export function fmtBytes(n: number | null | undefined): string {
  if (n == null || !isFinite(n)) return '—';
  const u = byteUnitIndex(n);
  return `${bytesIn(n, u)} ${BYTE_UNITS[u]}`;
}

/** "3.11 → 2.52 GiB": both values in the unit of the larger one. */
export function fmtBytesPair(a: number, b: number): string {
  const u = byteUnitIndex(Math.max(Math.abs(a), Math.abs(b)));
  return `${bytesIn(a, u)} → ${bytesIn(b, u)} ${BYTE_UNITS[u]}`;
}

/** One unit for a whole chart axis, picked from its largest value, so ticks come out round. */
export function byteAxis(max: number): { divisor: number; format: (v: number) => string } {
  const u = byteUnitIndex(max);
  const divisor = Math.pow(1024, u);
  return { divisor, format: (v) => `${u === 0 ? Math.round(v).toLocaleString('en-US') : sig3(v)} ${BYTE_UNITS[u]}` };
}

export function fmtInt(n: number | null | undefined): string {
  if (n == null || !isFinite(n)) return '—';
  return Math.round(n).toLocaleString('en-US');
}

export function fmtRows(n: number | null | undefined): string {
  if (n == null || !isFinite(n)) return '—';
  const a = Math.abs(n);
  if (a >= 1e9) return `${sig3(n / 1e9)} B`;
  if (a >= 1e6) return `${sig3(n / 1e6)} M`;
  if (a >= 1e4) return `${sig3(n / 1e3)} K`;
  return fmtInt(n);
}

export function ratioOf(uncompressed: number, compressed: number): number | null {
  return compressed > 0 ? uncompressed / compressed : null;
}

export function fmtRatioValue(r: number | null | undefined): string {
  if (r == null || !isFinite(r)) return '—';
  return `${r >= 100 ? Math.round(r) : r.toFixed(1)}×`;
}

export function fmtRatio(uncompressed: number, compressed: number): string {
  return fmtRatioValue(ratioOf(uncompressed, compressed));
}

export function fmtMs(ms: number | null | undefined): string {
  if (ms == null || !isFinite(ms)) return '—';
  if (ms < 10) return `${+ms.toFixed(1)} ms`;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${sig3(ms / 1000)} s`;
  return fmtDuration(ms);
}

export function fmtMsPair(a: number, b: number): string {
  if (Math.max(a, b) < 1000) {
    const f = (v: number) => (v < 10 ? String(+v.toFixed(1)) : String(Math.round(v)));
    return `${f(a)} → ${f(b)} ms`;
  }
  if (Math.max(a, b) < 60_000) return `${sig3(a / 1000)} → ${sig3(b / 1000)} s`;
  return `${fmtDuration(a)} → ${fmtDuration(b)}`;
}

/** "3 min 12 s", "41 s", "5 h 18 min". */
export function fmtDuration(ms: number | null | undefined): string {
  if (ms == null || !isFinite(ms)) return '—';
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return ms < 1000 ? `${Math.round(ms)} ms` : `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${String(s % 60).padStart(2, '0')} s`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} h ${m % 60} min`;
  return `${Math.floor(h / 24)} d ${h % 24} h`;
}

export function fmtPct(fraction: number | null | undefined, digits = 1): string {
  if (fraction == null || !isFinite(fraction)) return '—';
  return `${(fraction * 100).toFixed(digits)}%`;
}

/** Relative change as "−19.0%" / "+17.7%". */
export function fmtChange(base: number, next: number, digits = 1): string {
  if (!(base > 0)) return '—';
  const p = ((next - base) / base) * 100;
  const s = Math.abs(p).toFixed(digits);
  return `${p < 0 ? MINUS : '+'}${s}%`;
}

export function fmtSignedBytes(n: number): string {
  return `${n < 0 ? MINUS : '+'}${fmtBytes(Math.abs(n))}`;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

function hhmm(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "07:42" today, "Yesterday 17:20", otherwise "Nov 16". */
export function fmtClock(iso: string | null | undefined, withToday = false): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  const now = new Date();
  if (sameDay(d, now)) return withToday ? `Today ${hhmm(d)}` : hhmm(d);
  const y = new Date(now);
  y.setDate(now.getDate() - 1);
  if (sameDay(d, y)) return `Yesterday ${hhmm(d)}`;
  const base = `${MONTHS[d.getMonth()]} ${d.getDate()}`;
  return d.getFullYear() === now.getFullYear() ? base : `${base}, ${d.getFullYear()}`;
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return `${hhmm(d)}:${String(d.getSeconds()).padStart(2, '0')}`;
}

function span(ms: number): string {
  const min = Math.round(ms / 60_000);
  if (min < 1) return 'under a minute';
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  if (h < 48) return min % 60 ? `${h} h ${min % 60} min` : `${h} h`;
  return `${Math.floor(h / 24)} d`;
}

/** "in 5 h 18 min" / "due now". */
export function fmtUntil(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  if (isNaN(t)) return null;
  const diff = t - Date.now();
  return diff <= 0 ? 'due now' : `in ${span(diff)}`;
}

/** "2 h ago". */
export function fmtAgo(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  if (isNaN(t)) return null;
  return `${span(Math.max(0, Date.now() - t))} ago`;
}

/** "3.5× faster", "11% slower", "same". For durations, lower is faster. */
export function fmtSpeed(baseMs: number, candMs: number): { text: string; slower: boolean; same: boolean } {
  if (!(baseMs > 0) || !(candMs > 0)) return { text: '—', slower: false, same: true };
  const slower = candMs > baseMs;
  const factor = slower ? candMs / baseMs : baseMs / candMs;
  if (factor < 1.02) return { text: 'same', slower: false, same: true };
  const word = slower ? 'slower' : 'faster';
  if (factor >= 1.15) return { text: `${factor.toFixed(1)}× ${word}`, slower, same: false };
  return { text: `${Math.round(Math.abs(candMs - baseMs) / baseMs * 100)}% ${word}`, slower, same: false };
}

export function median(values: number[]): number | null {
  const v = values.filter((x) => isFinite(x)).sort((a, b) => a - b);
  if (!v.length) return null;
  const m = Math.floor(v.length / 2);
  return v.length % 2 ? v[m] : (v[m - 1] + v[m]) / 2;
}
