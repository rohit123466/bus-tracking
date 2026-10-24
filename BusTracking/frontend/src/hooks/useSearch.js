import { useEffect, useRef, useState } from 'react';
import { classifyError, isAbort } from '../api/errors';

/**
 * Debounced search: runs `search(term, signal)` once typing pauses, cancelling the previous request
 * so an older, slower answer can never replace a newer one.
 */
export default function useSearch(query, search, { delay = 300, minChars = 1 } = {}) {
  const [state, setState] = useState({ results: [], loading: false, error: null });
  const searchRef = useRef(search);
  useEffect(() => { searchRef.current = search; });

  useEffect(() => {
    const term = query.trim();
    if (term.length < minChars) { setState({ results: [], loading: false, error: null }); return undefined; }
    const ctrl = new AbortController();
    setState(s => ({ ...s, loading: true, error: null }));
    const timer = setTimeout(async () => {
      try {
        const results = await searchRef.current(term, ctrl.signal);
        if (!ctrl.signal.aborted) setState({ results, loading: false, error: null });
      } catch (err) {
        if (!ctrl.signal.aborted && !isAbort(err)) setState({ results: [], loading: false, error: classifyError(err) });
      }
    }, delay);
    return () => { clearTimeout(timer); ctrl.abort(); };
  }, [query, delay, minChars]);

  return state;
}
