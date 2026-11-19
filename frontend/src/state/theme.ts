import { useCallback, useState } from 'react';

export type Theme = 'light' | 'dark';
const KEY = 'granulee.theme';

function current(): Theme {
  return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
}

export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(current);
  const toggle = useCallback(() => {
    const next: Theme = current() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try {
      localStorage.setItem(KEY, next);
    } catch {
      /* storage unavailable: the choice lasts for this page only */
    }
    setTheme(next);
  }, []);
  return { theme, toggle };
}
