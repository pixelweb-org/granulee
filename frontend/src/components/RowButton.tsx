import type { ReactNode } from 'react';

/** The control in the first cell of a row that opens a panel underneath. The row itself handles the click. */
export function RowButton({ open, controls, children }: { open: boolean; controls: string; children: ReactNode }) {
  return (
    <button type="button" className="g-acc__btn" aria-expanded={open} aria-controls={open ? controls : undefined}>
      <svg className="g-acc__chev" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M3 1l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {children}
    </button>
  );
}
