import { useEffect, useId, useRef, useState } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import Icon from './ui/Icon';
import { useConnection, useStore } from '../hooks/useStores';
import { themePreference, cycleTheme } from '../store/theme';
import { reportBrowserOnline } from '../api/connection';

const PRIMARY = [
  { to: '/', label: 'Explore', icon: 'map', end: true },
  { to: '/nearby', label: 'Nearby', icon: 'pin' },
  { to: '/journey', label: 'Plan', icon: 'route' },
  { to: '/saved', label: 'Saved', icon: 'star' },
];

// Older tools kept available. Several read the legacy database, which on the hosted server only
// holds the ticketing demo data, so they are labelled for what they are.
const MORE = [
  {
    heading: 'More tools',
    items: [
      { to: '/search', label: 'Find a bus by vehicle number', hint: 'Live position of one vehicle, e.g. DL1PC1234' },
      { to: '/replay', label: 'Journey replay', hint: 'Recorded positions of one vehicle' },
      { to: '/passing', label: 'Buses passing near me', hint: 'Older nearby view' },
      { to: '/route', label: 'Route details (classic)', hint: 'Older route view' },
      { to: '/accessibility', label: 'Wheelchair space reports', hint: 'Shown only where a bus reports it; most buses do not' },
    ],
  },
  {
    heading: 'Demo & staff',
    items: [
      { to: '/seats', label: 'Seat availability (demo)', hint: 'Demonstration data, not real buses' },
      { to: '/conductor', label: 'Conductor console (demo)', hint: 'Staff login, demonstration data' },
      { to: '/admin', label: 'Admin dashboard', hint: 'Staff login' },
    ],
  },
];

const THEME_LABEL = { system: 'Theme: follow system', light: 'Theme: light', dark: 'Theme: dark' };
const THEME_ICON = { system: 'monitor', light: 'sun', dark: 'moon' };

function MoreMenu() {
  const [open, setOpen] = useState(false);
  const id = useId();
  const ref = useRef(null);
  const { pathname } = useLocation();

  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    if (!open) return undefined;
    const onKey = e => { if (e.key === 'Escape') { setOpen(false); ref.current?.querySelector('button')?.focus(); } };
    const onClick = e => { if (!ref.current?.contains(e.target)) setOpen(false); };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onClick);
    return () => { document.removeEventListener('keydown', onKey); document.removeEventListener('mousedown', onClick); };
  }, [open]);

  return (
    <div className="menu" ref={ref}>
      <button type="button" className="icon-btn" aria-expanded={open} aria-controls={id}
        aria-label="More tools" title="More tools" onClick={() => setOpen(o => !o)}>
        <Icon name="more" />
      </button>
      {open && (
        <nav className="menu-pop" id={id} aria-label="More tools">
          {MORE.map(group => (
            <div key={group.heading}>
              <div className="menu-heading">{group.heading}</div>
              {group.items.map(item => (
                <Link key={item.to} className="menu-item" to={item.to}>
                  <strong>{item.label}</strong>
                  <span>{item.hint}</span>
                </Link>
              ))}
            </div>
          ))}
        </nav>
      )}
    </div>
  );
}

function ThemeButton() {
  const pref = useStore(themePreference);
  return (
    <button type="button" className="icon-btn" onClick={cycleTheme}
      aria-label={`${THEME_LABEL[pref]}. Change theme`} title={THEME_LABEL[pref]}>
      <Icon name={THEME_ICON[pref]} />
    </button>
  );
}

const CONNECTION_TEXT = {
  waking: 'Connecting to the live server. It sleeps when idle and can take a minute or two to wake up — retrying automatically.',
  reconnecting: 'Lost contact with the live server. Reconnecting… Information shown may be out of date.',
  offline: 'You are offline. Live bus positions and arrivals will update when you reconnect.',
};

function ConnectionBanner() {
  const { state } = useConnection();
  const text = CONNECTION_TEXT[state];
  if (!text) return null;
  return (
    <div className={`conn-banner conn-banner-${state}`} role="status">
      <Icon name={state === 'offline' ? 'wifiOff' : 'refresh'} size={18} />
      <span>{text}</span>
      {state === 'offline' && (
        <button type="button" className="btn btn-sm" onClick={() => reportBrowserOnline(navigator.onLine)}>Check again</button>
      )}
    </div>
  );
}

export default function AppShell({ children, fill }) {
  return (
    <div className="shell">
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="topbar">
        <Link to="/" className="brand" aria-label="Delhi Bus Live, home">
          <span className="brand-mark"><Icon name="bus" size={20} /></span>
          <span>Delhi Bus Live<small>DTC routes · North &amp; North-West Delhi live</small></span>
        </Link>
        <nav className="topnav" aria-label="Main">
          {PRIMARY.map(l => (
            <NavLink key={l.to} to={l.to} end={l.end} className="topnav-link">
              <Icon name={l.icon} size={18} />{l.label}
            </NavLink>
          ))}
        </nav>
        <div className="topbar-end">
          <ThemeButton />
          <MoreMenu />
        </div>
      </header>
      <ConnectionBanner />
      <main id="main" tabIndex={-1} className={`main${fill ? ' main-fill' : ''}`}>
        {children}
      </main>
      <nav className="tabbar" aria-label="Main">
        {PRIMARY.map(l => (
          <NavLink key={l.to} to={l.to} end={l.end} className="tab">
            <span className="tab-icon"><Icon name={l.icon} /></span>
            {l.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
