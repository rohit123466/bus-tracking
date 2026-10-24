import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { getConnection, subscribeConnection } from '../api/connection';
import { language, translate } from '../i18n';

/** Current value of a store from store/persistent.js, re-rendering on change. */
export const useStore = store => useSyncExternalStore(store.subscribe, store.get, store.get);

/** t(key, vars) in the chosen interface language; re-renders when the language changes. */
export function useT() {
  const lang = useStore(language);
  return useCallback((key, vars) => translate(lang, key, vars), [lang]);
}

export const useConnection = () => useSyncExternalStore(subscribeConnection, getConnection, getConnection);

/** Epoch seconds, refreshed every `ms`, so ages and freshness labels keep moving between polls. */
export function useNow(ms = 5000) {
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now() / 1000), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

export function useMediaQuery(query) {
  const get = () => typeof window !== 'undefined' && window.matchMedia(query).matches;
  const [matches, setMatches] = useState(get);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const onChange = () => setMatches(mq.matches);
    onChange();
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [query]);
  return matches;
}
