/* A column codec as the trial setup edits it: an encoding (Delta, Gorilla(4), …), a compressor and its level. */

export type Compressor = 'LZ4' | 'LZ4HC' | 'ZSTD' | 'NONE';

export interface CodecParts {
  /** Everything in front of the compressor, as typed: "", "Delta", "Delta(4), T64". */
  encoding: string;
  compressor: Compressor;
  /** Only for LZ4HC and ZSTD. */
  level: number | null;
}

export const COMPRESSORS: { value: Compressor; label: string }[] = [
  { value: 'LZ4', label: 'LZ4' },
  { value: 'LZ4HC', label: 'LZ4HC' },
  { value: 'ZSTD', label: 'ZSTD' },
  { value: 'NONE', label: 'None' },
];

export const ENCODINGS = ['Delta', 'DoubleDelta', 'Gorilla', 'T64', 'GCD', 'FPC'];

/** Every level the server accepts, and the one it uses when none is given. */
const LEVELS: Record<Compressor, { max: number; standard: number } | null> = {
  LZ4: null,
  NONE: null,
  LZ4HC: { max: 12, standard: 9 },
  ZSTD: { max: 22, standard: 1 },
};

export function levelsOf(compressor: Compressor): number[] {
  const range = LEVELS[compressor];
  return range ? Array.from({ length: range.max }, (_, i) => i + 1) : [];
}

export const standardLevel = (compressor: Compressor): number | null => LEVELS[compressor]?.standard ?? null;

function splitTopLevel(text: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  for (const ch of text) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) {
      parts.push(current.trim());
      current = '';
    } else current += ch;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

/** "" is the server default, LZ4. A codec list without a compressor at its end ("Delta") stores uncompressed. */
export function parseCodec(codec: string): CodecParts {
  const parts = splitTopLevel(codec);
  const last = parts[parts.length - 1] ?? '';
  const m = /^(LZ4HC|ZSTD|LZ4|NONE)(?:\((\d+)\))?$/i.exec(last);
  if (!m) return { encoding: parts.join(', '), compressor: parts.length ? 'NONE' : 'LZ4', level: null };
  const compressor = m[1].toUpperCase() as Compressor;
  const level = LEVELS[compressor] ? (m[2] != null ? Number(m[2]) : standardLevel(compressor)) : null;
  return { encoding: parts.slice(0, -1).join(', '), compressor, level };
}

export function buildCodec({ encoding, compressor, level }: CodecParts): string {
  const enc = encoding.trim().replace(/,\s*$/, '');
  const parts = enc ? [enc] : [];
  if (compressor === 'LZ4') {
    if (enc) parts.push('LZ4');
  } else if (compressor === 'NONE') {
    if (!enc) parts.push('NONE');
  } else parts.push(`${compressor}(${level ?? standardLevel(compressor)})`);
  return parts.join(', ');
}
