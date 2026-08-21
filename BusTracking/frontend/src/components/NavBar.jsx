import { NavLink } from 'react-router-dom';

const LINKS = [
  { to: '/', label: '🗺️ Live Map', end: true },
  { to: '/accessibility', label: '♿ Accessibility' },
  { to: '/search', label: '🔍 Search' },
  { to: '/route', label: '🚌 Route' },
  { to: '/stops', label: '📍 Stops' },
  { to: '/passing', label: '⏱️ Passing' },
  { to: '/journey', label: '🧭 Planner' },
  { to: '/replay', label: '▶️ Replay' },
  { to: '/admin', label: '⚙️ Admin' },
];

export default function NavBar() {
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
    </nav>
  );
}
