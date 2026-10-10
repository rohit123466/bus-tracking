import { useEffect, useState } from 'react';
import { transitGet } from '../api/client';
export default function TransitStopPicker({ label, selected, onSelect, routeNumber = '' }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => {
      transitGet('/stops/search', { query, routeNumber }, controller.signal).then(data => { setResults(data); setError(''); })
        .catch(() => { if (!controller.signal.aborted) setError('Stop search unavailable.'); });
    }, 300);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query, routeNumber]);
  return <label className="transit-picker">{label}
    <input aria-label={`Search ${label}`} placeholder="Search stop name" value={query} onChange={e => setQuery(e.target.value)} />
    <select value={selected?.id || ''} onChange={e => onSelect(results.find(s => s.id === e.target.value) || null)}>
      <option value="">Select a stop</option>
      {selected && !results.some(s => s.id === selected.id) && <option value={selected.id}>{selected.name}</option>}
      {results.map(s => <option key={s.id} value={s.id}>{s.name} ({s.id})</option>)}
    </select>{error && <small role="status">{error}</small>}
  </label>;
}
