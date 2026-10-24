// Light / dark / follow-the-system theme, applied as data-theme on <html>.
import { createPersistentStore } from './persistent.js';

export const THEMES = ['system', 'light', 'dark'];
export const themePreference = createPersistentStore('dtc_theme', 'system', v => THEMES.includes(v));

const media = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null;

export const resolvedTheme = (pref = themePreference.get()) =>
  pref === 'system' ? (media?.matches ? 'dark' : 'light') : pref;

function apply() {
  if (typeof document === 'undefined') return;
  const theme = resolvedTheme();
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'dark' ? '#0f1720' : '#ffffff');
}

export function initTheme() {
  apply();
  themePreference.subscribe(apply);
  media?.addEventListener?.('change', apply);
}

export const cycleTheme = () =>
  themePreference.set(p => THEMES[(THEMES.indexOf(p) + 1) % THEMES.length]);
