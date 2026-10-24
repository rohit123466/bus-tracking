import { useEffect, useId, useRef, useState } from 'react';
import Icon from './Icon';

/**
 * Text input with a suggestion list (WAI-ARIA 1.2 combobox). Arrow keys move through options,
 * Enter picks, Escape closes. `options`: [{ id, label, sub?, group?, icon? }]; options sharing a
 * `group` are listed under that heading in the given order.
 */
export default function Combobox({
  label, hideLabel = false, value, onInputChange, options, onSelect, onEnter,
  placeholder, icon = 'search', loading = false, status, minChars = 1, autoFocus = false, inputRef,
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const listRef = useRef(null);

  useEffect(() => setActive(-1), [options]);
  useEffect(() => {
    if (open && active >= 0) document.getElementById(`${id}-opt-${active}`)?.scrollIntoView({ block: 'nearest' });
  }, [active, open, id]);

  const showList = open && value.trim().length >= minChars && (options.length > 0 || loading || status);

  const choose = (option) => {
    setOpen(false);
    setActive(-1);
    onSelect(option);
  };

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setOpen(true);
      if (!options.length) return;
      setActive(a => e.key === 'ArrowDown' ? (a + 1) % options.length : (a <= 0 ? options.length - 1 : a - 1));
    } else if (e.key === 'Enter') {
      if (showList && active >= 0 && options[active]) { e.preventDefault(); choose(options[active]); }
      else if (onEnter) { e.preventDefault(); setOpen(false); onEnter(value); }
    } else if (e.key === 'Escape') {
      if (open) { e.preventDefault(); setOpen(false); }
    }
  };

  let lastGroup;
  return (
    <div className="field combo" onBlur={e => { if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false); }}>
      <label htmlFor={`${id}-input`} className={hideLabel ? 'vh' : 'field-label'}>{label}</label>
      <div className="input-wrap">
        <Icon name={icon} size={18} />
        <input
          id={`${id}-input`}
          ref={inputRef}
          className="input"
          type="text"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={!!showList}
          aria-controls={`${id}-list`}
          aria-activedescendant={showList && active >= 0 ? `${id}-opt-${active}` : undefined}
          autoComplete="off"
          autoFocus={autoFocus}
          placeholder={placeholder}
          value={value}
          onChange={e => { onInputChange(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
      </div>
      {showList && (
        <ul className="combo-list" id={`${id}-list`} role="listbox" aria-label={`${label} suggestions`} ref={listRef}>
          {options.map((o, i) => {
            const heading = o.group && o.group !== lastGroup ? o.group : null;
            lastGroup = o.group;
            return [
              heading && <li key={`g-${heading}`} role="presentation" className="combo-group">{heading}</li>,
              <li
                key={o.id}
                id={`${id}-opt-${i}`}
                role="option"
                aria-selected={active === i}
                className="combo-option"
                onMouseDown={e => e.preventDefault()}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(o)}
              >
                {o.icon && <Icon name={o.icon} size={18} />}
                <span className="grow">
                  {o.label}
                  {o.sub && <small>{o.sub}</small>}
                </span>
              </li>,
            ];
          })}
          {(loading || (status && !options.length)) && (
            <li role="presentation" className="combo-status">{loading ? 'Searching…' : status}</li>
          )}
        </ul>
      )}
    </div>
  );
}
