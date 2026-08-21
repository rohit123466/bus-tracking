import { NavLink } from 'react-router-dom';
import useVoiceAnnouncements from '../hooks/useVoiceAnnouncements';

const LINKS = [
  { to: '/', label: '🗺️ Live Map', end: true },
  { to: '/search', label: '🔍 Search' },
  { to: '/route', label: '🚌 Route' },
  { to: '/stops', label: '📍 Stops' },
  { to: '/passing', label: '⏱️ Passing' },
  { to: '/journey', label: '🧭 Planner' },
  { to: '/replay', label: '▶️ Replay' },
  { to: '/admin', label: '⚙️ Admin' },
];

export default function NavBar() {
  const [voiceEnabled, toggleVoice] = useVoiceAnnouncements();

  return (
    <nav className="nav-bar">
      <span className="nav-brand">🚍 Delhi Bus Tracker</span>
      <div className="nav-links">
        {LINKS.map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.end}
            className={({ isActive }) => 'nav-link' + (isActive ? ' nav-link-active' : '')}
          >
            {link.label}
          </NavLink>
        ))}
      </div>
      <button
        type="button"
        className={'voice-toggle' + (voiceEnabled ? ' voice-toggle-on' : '')}
        onClick={toggleVoice}
        aria-pressed={voiceEnabled}
        aria-label={voiceEnabled ? 'Voice stop announcements on. Click to turn off.' : 'Voice stop announcements off. Click to turn on.'}
        title={voiceEnabled ? 'Voice announcements: on' : 'Voice announcements: off'}
      >
        {voiceEnabled ? '🔊' : '🔇'}
      </button>
    </nav>
  );
}
