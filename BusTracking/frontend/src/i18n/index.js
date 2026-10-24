// Lightweight UI translation: one dictionary per language, keyed by message id, with English as
// the fallback for anything not yet translated. Route numbers, stop names, vehicle IDs and API
// values are data and are never passed through here. Pure module (no React) so it can be tested.
import { createPersistentStore } from '../store/persistent.js';

const en = {
  'skip': 'Skip to content',
  'nav.explore': 'Explore',
  'nav.nearby': 'Nearby',
  'nav.plan': 'Plan',
  'nav.saved': 'Saved',
  'lang.button': 'Language: English. Switch to Hindi',
  'map.locate': 'Show my location',
  'map.nearMe': 'Find buses near me',
  'map.reset': 'Reset map view',
  'map.fitRoute': 'Fit map to route {route}',
  'map.hideUnconfirmed': 'Hide buses whose route number is unconfirmed',
  'legend.live': 'Live GPS',
  'legend.delayed': 'GPS 30 s–2 min old',
  'legend.unconfirmed': 'Route unconfirmed',
  'legend.you': 'You',
};

const hi = {
  'skip': 'सामग्री पर जाएँ',
  'nav.explore': 'नक्शा',
  'nav.nearby': 'आस-पास',
  'nav.plan': 'यात्रा योजना',
  'nav.saved': 'सहेजे गए',
  'lang.button': 'भाषा: हिन्दी। अंग्रेज़ी में बदलें',
  'map.locate': 'मेरी लोकेशन दिखाएँ',
  'map.nearMe': 'मेरे पास की बसें खोजें',
  'map.reset': 'नक्शा पहले जैसा करें',
  'map.fitRoute': 'पूरा रूट {route} दिखाएँ',
  'map.hideUnconfirmed': 'जिन बसों का रूट नंबर पक्का नहीं है, उन्हें छिपाएँ',
  'legend.live': 'लाइव GPS',
  'legend.delayed': 'GPS 30 सेकंड–2 मिनट पुराना',
  'legend.unconfirmed': 'रूट पक्का नहीं',
  'legend.you': 'आप',
};

export const DICTIONARIES = { en, hi };
export const LANGUAGES = ['en', 'hi'];
export const language = createPersistentStore('dtc_lang', 'en', v => LANGUAGES.includes(v));

/** Message `key` in `lang`, English when missing, the key itself when unknown; {name} filled from vars. */
export function translate(lang, key, vars) {
  const text = DICTIONARIES[lang]?.[key] ?? en[key] ?? key;
  return vars ? text.replace(/\{(\w+)\}/g, (_, k) => (vars[k] ?? '')) : text;
}

export const cycleLanguage = () => language.set(l => LANGUAGES[(LANGUAGES.indexOf(l) + 1) % LANGUAGES.length]);

/** Keeps <html lang> in step so screen readers pronounce the interface in the chosen language. */
export function initLanguage() {
  if (typeof document === 'undefined') return;
  const apply = () => { document.documentElement.lang = language.get(); };
  apply();
  language.subscribe(apply);
}
