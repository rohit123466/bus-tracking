import { useEffect, useId, useMemo, useRef, useState } from 'react';
import Icon from '../ui/Icon';
import { searchItems } from '../../utils/admin';

/**
 * Ctrl/Cmd+K jump list: sections, routes and buses. A modal dialog holding one combobox; focus
 * stays in it while open and returns to where it was on close.
 * `items`: [{ id, label, sub?, group, icon, keywords? }].
 */
export default function CommandPalette({ items, onPick, onClose }) {
  const id = useId();
  const inputRef = useRef(null);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const results = useMemo(() => searchItems(items, query, 14), [items, query]);
  // Live positions can shrink the list while it is open.
  const current = Math.min(active, results.length - 1);

  useEffect(() => {
    const previous = document.activeElement;
    inputRef.current?.focus();
    return () => previous?.focus?.();
  }, []);
  useEffect(() => { document.getElementById(`${id}-opt-${current}`)?.scrollIntoView({ block: 'nearest' }); }, [current, id]);

  const pick = (item) => { if (item) { onClose(); onPick(item); } };

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!results.length) return;
      setActive((a) => (e.key === 'ArrowDown' ? (a + 1) % results.length : (a - 1 + results.length) % results.length));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      pick(results[current]);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    } else if (e.key === 'Tab') {
      e.preventDefault(); // the input is the only stop in the dialog
    }
  };


  return (
    <div className="palette-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="palette" role="dialog" aria-modal="true" aria-label="Command palette">
        <div className="input-wrap">
          <Icon name="search" size={18} />
          <input
            ref={inputRef}
            className="input"
            type="text"
            role="combobox"
            aria-label="Jump to a section, route or bus"
            aria-expanded={results.length > 0}
            aria-controls={`${id}-list`}
            aria-activedescendant={current >= 0 ? `${id}-opt-${current}` : undefined}
            aria-autocomplete="list"
            autoComplete="off"
            placeholder="Jump to a section, route or bus…"
            value={query}
            onChange={(e) => { setQuery(e.target.value); setActive(0); }}
            onKeyDown={onKeyDown}
          />
        </div>
        <ul className="palette-list list-reset" id={`${id}-list`} role="listbox" aria-label="Results">
          {results.map((item, i) => {
            const heading = i === 0 || results[i - 1].group !== item.group ? item.group : null;
            return [
              heading && <li key={`g-${heading}`} role="presentation" className="combo-group">{heading}</li>,
              <li
                key={item.id}
                id={`${id}-opt-${i}`}
                role="option"
                aria-selected={i === current}
                className="combo-option"
                onMouseDown={(e) => e.preventDefault()}
                onMouseMove={() => setActive(i)}
                onClick={() => pick(item)}
              >
                <Icon name={item.icon} size={18} />
                <span className="grow">
                  {item.label}
                  {item.sub && <small>{item.sub}</small>}
                </span>
              </li>,
            ];
          })}
        </ul>
        {!results.length && <p className="combo-status">No section, route or bus matches “{query}”.</p>}
        <p className="palette-hint small muted" aria-hidden="true">↑ ↓ move · Enter open · Esc close</p>
      </div>
    </div>
  );
}
