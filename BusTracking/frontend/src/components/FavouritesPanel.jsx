/**
 * FavouritesPanel — quick-access shortcuts for saved routes, stops, and locations.
 * All data is localStorage-only (no backend sync). Designed to live in the HomePage sidebar.
 */
import './FavouritesPanel.css';

export default function FavouritesPanel({
  favourites,               // from useFavourites()
  onSelectRoute,            // (routeCode) => void
  onSelectStop,             // (stop) => void
  onUseHome,                // () => void — navigate to home location
  onUseOffice,              // () => void — navigate to office location
  onSaveCurrentAsHome,      // () => void
  onSaveCurrentAsOffice,    // () => void
}) {
  const { routes, stops, home, office, recent, clearRecent } = favourites;
  const hasAny = routes.length > 0 || stops.length > 0 || home || office || recent.length > 0;

  if (!hasAny) return (
    <div className="fav-panel fav-empty">
      <p>No favourites yet. Star a route or stop to save it here.</p>
    </div>
  );

  return (
    <div className="fav-panel">
      {(home || office) && (
        <section className="fav-section">
          <h4>📍 Saved Locations</h4>
          {home && (
            <button className="fav-chip fav-chip-home" onClick={onUseHome} title="Navigate to Home">
              🏠 Home
            </button>
          )}
          {office && (
            <button className="fav-chip fav-chip-office" onClick={onUseOffice} title="Navigate to Office">
              🏢 Office / College
            </button>
          )}
          <div className="fav-save-btns">
            <button className="fav-save-link" onClick={onSaveCurrentAsHome}>Save as Home</button>
            <button className="fav-save-link" onClick={onSaveCurrentAsOffice}>Save as Office</button>
          </div>
        </section>
      )}

      {!home && !office && (
        <div className="fav-save-btns">
          <button className="fav-save-link" onClick={onSaveCurrentAsHome}>📌 Save as Home</button>
          <button className="fav-save-link" onClick={onSaveCurrentAsOffice}>📌 Save as Office</button>
        </div>
      )}

      {routes.length > 0 && (
        <section className="fav-section">
          <h4>⭐ Favourite Routes</h4>
          <div className="fav-chips">
            {routes.map(r => (
              <button
                key={r.routeCode}
                className="fav-chip fav-chip-route"
                onClick={() => onSelectRoute(r.routeCode)}
              >
                {r.routeCode}
              </button>
            ))}
          </div>
        </section>
      )}

      {stops.length > 0 && (
        <section className="fav-section">
          <h4>🚏 Favourite Stops</h4>
          <div className="fav-chips">
            {stops.map(s => (
              <button
                key={s.id}
                className="fav-chip fav-chip-stop"
                onClick={() => onSelectStop(s)}
                title={s.name}
              >
                {s.name.length > 22 ? s.name.slice(0, 20) + '…' : s.name}
              </button>
            ))}
          </div>
        </section>
      )}

      {recent.length > 0 && (
        <section className="fav-section">
          <h4>🕐 Recent Searches</h4>
          <div className="fav-chips">
            {recent.slice(0, 6).map((r, i) => (
              <button
                key={i}
                className={`fav-chip fav-chip-recent fav-chip-${r.type}`}
                onClick={() => r.type === 'route' ? onSelectRoute(r.query) : null}
                title={`${r.type}: ${r.query}`}
              >
                {r.type === 'route' ? '🚌' : r.type === 'stop' ? '🚏' : '📍'} {r.query}
              </button>
            ))}
          </div>
          <button className="fav-save-link" onClick={clearRecent}>Clear history</button>
        </section>
      )}
    </div>
  );
}
