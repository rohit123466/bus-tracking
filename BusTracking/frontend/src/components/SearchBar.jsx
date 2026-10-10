import { useEffect, useState } from 'react';
import { searchRoutes, transitGet } from '../api/client';

export default function SearchBar({ onSelectRoute, publicRoutes = false }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      return;
    }
    let cancelled = false;
    const handle = setTimeout(() => {
      (publicRoutes ? transitGet('/routes/search', { query }).then(rows => rows.map(n => ({ id: n, routeCode: n }))) : searchRoutes(query))
        .then(rows => { if (!cancelled) setResults(rows); })
        .catch(() => { if (!cancelled) setResults([]); });
    }, 300);
    return () => { cancelled = true; clearTimeout(handle); };
  }, [query, publicRoutes]);

  return (
    <div className="search-bar">
      <input
        aria-label="Public route number"
        onKeyDown={e => { if (e.key === 'Enter' && publicRoutes && query.trim()) { onSelectRoute({ routeCode: query.trim().toUpperCase() }); setOpen(false); } }}
        type="text"
        placeholder="Search route number..."
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {open && results.length > 0 && (
        <ul className="search-results">
          {results.map((route) => (
            <li
              key={route.id}
              // onMouseDown fires before the input's onBlur closes the list
              onMouseDown={() => {
                onSelectRoute(route);
                setQuery(route.routeCode);
                setOpen(false);
              }}
            >
              <strong>{route.routeCode}</strong> {route.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
