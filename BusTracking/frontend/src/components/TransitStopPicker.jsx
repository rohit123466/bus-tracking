import { useEffect, useId, useState } from 'react';
import { transitGet } from '../api/client';

export default function TransitStopPicker({ label, selected, onSelect, routeNumber = '' }) {
  const id = useId();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);

  useEffect(() => {
    setQuery(selected?.name || '');
    setOpen(false);
  }, [selected]);

  useEffect(() => {
    const controller = new AbortController();
    setResults([]);
    setActive(-1);
    setLoading(true);
    setError('');
    const timer = setTimeout(async () => {
      try {
        const data = await transitGet('/stops/search', { query: query.trim(), routeNumber }, controller.signal);
        if (!controller.signal.aborted) {
          const term = query.trim().toLowerCase();
          const score = stop => {
            const name = stop.name.toLowerCase();
            return name.startsWith(term) ? 0 : name.split(/[\s(]+/).some(word => word.startsWith(term)) ? 1 : 2;
          };
          setResults([...data].sort((a, b) => score(a) - score(b) || a.name.localeCompare(b.name)));
        }
      } catch {
        if (!controller.signal.aborted) setError('Stop search unavailable. Please try again.');
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }, 300);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query, routeNumber]);

  useEffect(() => {
    if (open && active >= 0) document.getElementById(`${id}-option-${active}`)?.scrollIntoView({ block: 'nearest' });
  }, [active, id, open]);

  const choose = stop => {
    setQuery(stop?.name || '');
    setOpen(false);
    setActive(-1);
    onSelect(stop);
  };
  const showSuggestions = open && query.trim().length > 0;
  const onKeyDown = event => {
    if (event.key === 'Escape') { setOpen(false); return; }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      if (results.length) setActive(previous => event.key === 'ArrowDown'
        ? (previous + 1) % results.length
        : (previous <= 0 ? results.length - 1 : previous - 1));
    } else if (event.key === 'Enter' && showSuggestions && results.length) {
      event.preventDefault();
      choose(results[active >= 0 ? active : 0]);
    }
  };

  return <div className="transit-picker">
    <label htmlFor={`${id}-search`}>{label}</label>
    <div className="stop-autocomplete" onBlur={event => {
      if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
    }}>
      <input id={`${id}-search`} role="combobox" aria-label={`Search ${label}`}
        aria-autocomplete="list" aria-expanded={showSuggestions} aria-controls={`${id}-suggestions`}
        aria-activedescendant={showSuggestions && active >= 0 ? `${id}-option-${active}` : undefined}
        autoComplete="off" placeholder="Search bus stop name" value={query}
        onFocus={() => setOpen(true)} onKeyDown={onKeyDown}
        onChange={event => { setQuery(event.target.value); setActive(-1); setOpen(true); }} />
      {showSuggestions && <div className="stop-suggestions">
        <ul id={`${id}-suggestions`} role="listbox" aria-label={`${label} suggestions`}>
          {results.map((stop, index) => <li id={`${id}-option-${index}`} key={stop.id}
            role="option" aria-selected={active === index}
            onMouseDown={event => event.preventDefault()}
            onMouseEnter={() => setActive(index)} onClick={() => choose(stop)}>
            <span>{stop.name}</span><small>Stop ID: {stop.id}</small>
          </li>)}
        </ul>
        {(loading || error || !results.length) && <p role="status">
          {loading ? 'Searching bus stops...' : error || 'No matching bus stops found.'}
        </p>}
      </div>}
    </div>
    <select aria-label={`Selected ${label}`} value={selected?.id || ''}
      onChange={event => choose(results.find(stop => stop.id === event.target.value) || null)}>
      <option value="">Select a stop</option>
      {selected && !results.some(stop => stop.id === selected.id) && <option value={selected.id}>{selected.name}</option>}
      {results.map(stop => <option key={stop.id} value={stop.id}>{stop.name} ({stop.id})</option>)}
    </select>
    {!showSuggestions && error && <small role="status">{error}</small>}
  </div>;
}
