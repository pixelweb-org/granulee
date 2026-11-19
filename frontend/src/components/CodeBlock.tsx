import { useEffect, useRef, useState, type ReactNode } from 'react';

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** A secondary button that copies `text` and confirms for a moment. */
export function CopyButton({ text, children }: { text: string; children: ReactNode }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <button
      type="button"
      className="nv-btn nv-btn-secondary"
      onClick={async () => {
        const ok = await copyText(text);
        setState(ok ? 'copied' : 'failed');
        window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => setState('idle'), 1600);
      }}
    >
      <span aria-live="polite">{state === 'copied' ? 'Copied' : state === 'failed' ? 'Copy failed' : children}</span>
    </button>
  );
}

export function CodeBlock({ code, copyLabel }: { code: string; copyLabel?: string }) {
  return (
    <>
      <pre className="g-code">{code}</pre>
      {copyLabel ? (
        <div className="row">
          <CopyButton text={code}>{copyLabel}</CopyButton>
        </div>
      ) : null}
    </>
  );
}
