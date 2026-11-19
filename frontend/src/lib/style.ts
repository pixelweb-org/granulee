import type { CSSProperties } from 'react';

/** CSS custom properties for the layout helpers in app.css (.stack, .row, .grid-auto, .grid-2). */
export function vars(v: { gap?: number | string; min?: number }): CSSProperties {
  const out: Record<string, string> = {};
  if (v.gap != null) out['--gap'] = typeof v.gap === 'number' ? `${v.gap}px` : v.gap;
  if (v.min != null) out['--min'] = `${v.min}px`;
  return out as CSSProperties;
}
