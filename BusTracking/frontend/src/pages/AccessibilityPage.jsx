import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getNearbyBuses } from '../api/client';
import useBusSocket from '../hooks/useBusSocket';
import WheelchairBadge from '../components/WheelchairBadge';
import './AccessibilityPage.css';

export default function AccessibilityPage() {
  const navigate = useNavigate();
  const [userLocation, setUserLocation] = useState(null);
  const [locationError, setLocationError] = useState(null);
  const [buses, setBuses] = useState([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('all'); // 'all' | 'available' | 'unavailable' | 'unknown'
  const [viewMode, setViewMode] = useState('grid'); // 'grid' | 'table'
  const [lastRefreshed, setLastRefreshed] = useState(null);

  const { positions: livePositions, connected } = useBusSocket();
  const userLocationRef = useRef(userLocation);

  useEffect(() => {
    userLocationRef.current = userLocation;
  }, [userLocation]);

  // Request user location with fallback to Delhi coordinates
  useEffect(() => {
    if (!navigator.geolocation) {
      setUserLocation({ lat: 28.6139, lng: 77.209 }); // Delhi default
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setUserLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setLocationError(null);
      },
      (err) => {
        console.warn('Geolocation error:', err.message);
        setLocationError('Using default Delhi NCR location (Location access denied)');
        setUserLocation({ lat: 28.6139, lng: 77.209 });
      },
      { timeout: 8000 }
    );
  }, []);

  const fetchBuses = useCallback(() => {
    const loc = userLocationRef.current || { lat: 28.6139, lng: 77.209 };
    setLoading(true);
    getNearbyBuses(loc.lat, loc.lng, 10000, 50)
      .then((data) => {
        setBuses(data || []);
        setLastRefreshed(new Date());
      })
      .catch((err) => {
        console.error('Failed to load buses:', err);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    if (userLocation) {
      fetchBuses();
    }
  }, [userLocation, fetchBuses]);

  // Auto poll every 15s
  useEffect(() => {
    const interval = setInterval(fetchBuses, 15000);
    return () => clearInterval(interval);
  }, [fetchBuses]);

  // Merge live socket updates
  const liveBuses = useMemo(() => {
    return buses.map((bus) => {
      const live = livePositions[bus.vehicleId];
      if (!live) return bus;
      return {
        ...bus,
        busLatitude: live.latitude,
        busLongitude: live.longitude,
        speedKmh: live.speedKmh,
        wheelchairSpaceAvailable:
          live.wheelchairSpaceAvailable !== undefined
            ? live.wheelchairSpaceAvailable
            : bus.wheelchairSpaceAvailable,
      };
    });
  }, [buses, livePositions]);

  // Accessibility statistics calculations
  const stats = useMemo(() => {
    let available = 0;
    let unavailable = 0;
    let unknown = 0;

    liveBuses.forEach((b) => {
      const status = b.wheelchairSpaceAvailable;
      if (status === true || status === 1 || status === '1' || status === 'true') {
        available++;
      } else if (status === false || status === 0 || status === '0' || status === 'false') {
        unavailable++;
      } else {
        unknown++;
      }
    });

    return {
      total: liveBuses.length,
      available,
      unavailable,
      unknown,
    };
  }, [liveBuses]);

  // Filter & search logic
  const filteredBuses = useMemo(() => {
    return liveBuses.filter((bus) => {
      // Status filter
      const st = bus.wheelchairSpaceAvailable;
      const isAvailable = st === true || st === 1 || st === '1' || st === 'true';
      const isUnavailable = st === false || st === 0 || st === '0' || st === 'false';
      const isUnknown = st == null || st === '';

      if (filterStatus === 'available' && !isAvailable) return false;
      if (filterStatus === 'unavailable' && !isUnavailable) return false;
      if (filterStatus === 'unknown' && !isUnknown) return false;

      // Search query
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase().trim();
        const matchRoute = bus.routeCode && bus.routeCode.toLowerCase().includes(q);
        const matchName = bus.routeName && bus.routeName.toLowerCase().includes(q);
        const matchVehicle = bus.vehicleId && bus.vehicleId.toLowerCase().includes(q);
        const matchStop = bus.stopName && bus.stopName.toLowerCase().includes(q);
        if (!matchRoute && !matchName && !matchVehicle && !matchStop) {
          return false;
        }
      }

      return true;
    });
  }, [liveBuses, filterStatus, searchQuery]);

  return (
    <div className="accessibility-page">
      {/* Hero Header */}
      <header className="access-hero">
        <div className="access-hero__content">
          <div className="access-hero__badge">
            <span className="access-hero__icon">♿</span> Accessible Transit Delhi
          </div>
          <h1 className="access-hero__title">DTC Wheelchair Accessibility Tracker</h1>
          <p className="access-hero__subtitle">
            Real-time tracking of wheelchair-accessible DTC buses across Delhi NCR with live availability indicators.
          </p>
        </div>

        <div className="access-hero__meta">
          <button
            type="button"
            className="access-btn access-btn--primary"
            onClick={fetchBuses}
            disabled={loading}
          >
            <span className={loading ? 'access-spinner' : ''}>🔄</span> Refresh Status
          </button>
          <div className="access-hero__live">
            <span
              className={`live-dot ${connected ? 'live-dot-on' : ''}`}
              title={connected ? 'Connected to live telemetry' : 'Offline'}
            />
            <span>{connected ? 'Live Feed' : 'Connecting'}</span>
            {lastRefreshed && (
              <span className="access-hero__time">
                · {lastRefreshed.toLocaleTimeString()}
              </span>
            )}
          </div>
        </div>
      </header>

      {locationError && (
        <div className="access-alert access-alert--info">
          ℹ️ {locationError}
        </div>
      )}

      {/* KPI Stats Cards */}
      <section className="access-stats-grid" aria-label="Accessibility Metrics">
        <button
          type="button"
          className={`access-stat-card access-stat-card--total ${filterStatus === 'all' ? 'is-active' : ''}`}
          onClick={() => setFilterStatus('all')}
        >
          <div className="access-stat-card__icon">🚌</div>
          <div className="access-stat-card__info">
            <div className="access-stat-card__val">{stats.total}</div>
            <div className="access-stat-card__lbl">Total Tracked Buses</div>
          </div>
        </button>

        <button
          type="button"
          className={`access-stat-card access-stat-card--available ${filterStatus === 'available' ? 'is-active' : ''}`}
          onClick={() => setFilterStatus(filterStatus === 'available' ? 'all' : 'available')}
        >
          <div className="access-stat-card__icon">🟢</div>
          <div className="access-stat-card__info">
            <div className="access-stat-card__val">{stats.available}</div>
            <div className="access-stat-card__lbl">Space Available</div>
          </div>
          <WheelchairBadge status={true} size="sm" />
        </button>

        <button
          type="button"
          className={`access-stat-card access-stat-card--unavailable ${filterStatus === 'unavailable' ? 'is-active' : ''}`}
          onClick={() => setFilterStatus(filterStatus === 'unavailable' ? 'all' : 'unavailable')}
        >
          <div className="access-stat-card__icon">🔴</div>
          <div className="access-stat-card__info">
            <div className="access-stat-card__val">{stats.unavailable}</div>
            <div className="access-stat-card__lbl">Space Not Available</div>
          </div>
          <WheelchairBadge status={false} size="sm" />
        </button>

        <button
          type="button"
          className={`access-stat-card access-stat-card--unknown ${filterStatus === 'unknown' ? 'is-active' : ''}`}
          onClick={() => setFilterStatus(filterStatus === 'unknown' ? 'all' : 'unknown')}
        >
          <div className="access-stat-card__icon">⚫</div>
          <div className="access-stat-card__info">
            <div className="access-stat-card__val">{stats.unknown}</div>
            <div className="access-stat-card__lbl">Status Unknown</div>
          </div>
          <WheelchairBadge status={null} size="sm" />
        </button>
      </section>

      {/* Control Bar: Search, Filters, View Modes */}
      <section className="access-controls">
        <div className="access-search-box">
          <span className="access-search-icon">🔍</span>
          <input
            type="text"
            placeholder="Search by Route (e.g. 534, 764), Bus ID, or Stop..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="access-search-input"
          />
          {searchQuery && (
            <button
              type="button"
              className="access-search-clear"
              onClick={() => setSearchQuery('')}
            >
              ✕
            </button>
          )}
        </div>

        <div className="access-filter-pills">
          <button
            type="button"
            className={`access-pill ${filterStatus === 'all' ? 'access-pill--active' : ''}`}
            onClick={() => setFilterStatus('all')}
          >
            All ({stats.total})
          </button>
          <button
            type="button"
            className={`access-pill access-pill--green ${filterStatus === 'available' ? 'access-pill--active' : ''}`}
            onClick={() => setFilterStatus('available')}
          >
            <span className="pill-dot pill-dot--green"></span> Available ({stats.available})
          </button>
          <button
            type="button"
            className={`access-pill access-pill--red ${filterStatus === 'unavailable' ? 'access-pill--active' : ''}`}
            onClick={() => setFilterStatus('unavailable')}
          >
            <span className="pill-dot pill-dot--red"></span> Not Available ({stats.unavailable})
          </button>
          <button
            type="button"
            className={`access-pill access-pill--grey ${filterStatus === 'unknown' ? 'access-pill--active' : ''}`}
            onClick={() => setFilterStatus('unknown')}
          >
            <span className="pill-dot pill-dot--grey"></span> Unknown ({stats.unknown})
          </button>
        </div>

        <div className="access-view-switch">
          <button
            type="button"
            className={`access-view-btn ${viewMode === 'grid' ? 'is-active' : ''}`}
            onClick={() => setViewMode('grid')}
            title="Grid Card View"
            aria-label="Grid Card View"
          >
            🎛️ Cards
          </button>
          <button
            type="button"
            className={`access-view-btn ${viewMode === 'table' ? 'is-active' : ''}`}
            onClick={() => setViewMode('table')}
            title="Table View"
            aria-label="Table View"
          >
            📋 Table
          </button>
        </div>
      </section>

      {/* Main Content Area */}
      {loading && buses.length === 0 ? (
        <div className="access-loading-state">
          <div className="access-spinner-large">♿</div>
          <h3>Scanning nearby DTC buses for accessibility status...</h3>
          <p>Connecting to live GTFS-RT feeds and vehicle telemetry.</p>
        </div>
      ) : filteredBuses.length === 0 ? (
        <div className="access-empty-state">
          <div className="access-empty-icon">♿</div>
          <h3>No buses match your criteria</h3>
          <p>Try resetting filters or expanding your search query.</p>
          <button
            type="button"
            className="access-btn access-btn--secondary"
            onClick={() => {
              setSearchQuery('');
              setFilterStatus('all');
            }}
          >
            Reset Filters
          </button>
        </div>
      ) : viewMode === 'grid' ? (
        /* GRID VIEW */
        <div className="access-grid">
          {filteredBuses.map((bus, idx) => {
            const hasSpace = bus.wheelchairSpaceAvailable === true;
            const noSpace = bus.wheelchairSpaceAvailable === false;
            const cardVariant = hasSpace
              ? 'card--available'
              : noSpace
              ? 'card--unavailable'
              : 'card--unknown';

            return (
              <article
                key={`${bus.vehicleId}-${idx}`}
                className={`access-bus-card ${cardVariant}`}
                style={{ animationDelay: `${Math.min(idx * 0.04, 0.6)}s` }}
              >
                {/* Header: Route + Wheelchair Icon */}
                <div className="access-bus-card__top">
                  <div className="access-bus-card__route-wrap">
                    <span className="access-bus-card__route-badge">
                      {bus.routeCode || 'DTC'}
                    </span>
                    <WheelchairBadge
                      status={bus.wheelchairSpaceAvailable}
                      showLabel={false}
                      size="lg"
                    />
                  </div>

                  <div className="access-bus-card__eta">
                    <span className="eta-value">
                      {bus.etaMinutes != null ? `${bus.etaMinutes} min` : 'Live'}
                    </span>
                    <span className="eta-lbl">ETA</span>
                  </div>
                </div>

                {/* Status Pill */}
                <div className="access-bus-card__status-row">
                  <WheelchairBadge
                    status={bus.wheelchairSpaceAvailable}
                    showLabel={true}
                    size="md"
                  />
                </div>

                {/* Details */}
                <div className="access-bus-card__details">
                  {bus.routeName && (
                    <div className="access-bus-card__detail-row">
                      <span className="detail-icon">🛣️</span>
                      <span className="detail-text detail-name">{bus.routeName}</span>
                    </div>
                  )}

                  {bus.stopName && (
                    <div className="access-bus-card__detail-row">
                      <span className="detail-icon">📍</span>
                      <span className="detail-text">Approaching {bus.stopName}</span>
                    </div>
                  )}

                  <div className="access-bus-card__meta-tags">
                    <span className="meta-tag">
                      🆔 {bus.vehicleId || 'DTC-BUS'}
                    </span>
                    {bus.distanceToUser != null && (
                      <span className="meta-tag">
                        📏 {Math.round(bus.distanceToUser)} m away
                      </span>
                    )}
                  </div>
                </div>

                {/* Action footer */}
                <div className="access-bus-card__actions">
                  <button
                    type="button"
                    className="access-card-btn"
                    onClick={() => navigate(`/search?vehicle=${encodeURIComponent(bus.vehicleId)}`)}
                  >
                    🔍 Details
                  </button>
                  <button
                    type="button"
                    className="access-card-btn access-card-btn--highlight"
                    onClick={() => navigate('/')}
                  >
                    🗺️ View on Map
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      ) : (
        /* TABLE VIEW */
        <div className="access-table-wrapper">
          <table className="access-table">
            <thead>
              <tr>
                <th>Bus / Vehicle ID</th>
                <th>Route</th>
                <th>Wheelchair Status</th>
                <th>Approaching Stop</th>
                <th>Distance</th>
                <th>ETA</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {filteredBuses.map((bus, idx) => (
                <tr key={`${bus.vehicleId}-${idx}`}>
                  <td>
                    <div className="table-bus-id">
                      <span className="table-bus-icon">🚍</span>
                      <strong>{bus.vehicleId || '—'}</strong>
                    </div>
                  </td>
                  <td>
                    <span className="table-route-badge">
                      {bus.routeCode || '—'}
                    </span>
                    {bus.routeName && (
                      <div className="table-route-name">{bus.routeName}</div>
                    )}
                  </td>
                  <td>
                    <WheelchairBadge
                      status={bus.wheelchairSpaceAvailable}
                      showLabel={true}
                      size="md"
                    />
                  </td>
                  <td>{bus.stopName || '—'}</td>
                  <td>
                    {bus.distanceToUser != null
                      ? `${Math.round(bus.distanceToUser)} m`
                      : '—'}
                  </td>
                  <td>
                    <span className="table-eta-badge">
                      {bus.etaMinutes != null ? `${bus.etaMinutes} min` : 'Live'}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="access-table-action-btn"
                      onClick={() => navigate('/')}
                    >
                      Track
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
