import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { clearAdminToken, getAdminStats, getAdminToken } from '../api/client';
import AdminLoginForm from '../components/AdminLoginForm';

export default function AdminDashboardPage() {
  const [token, setToken] = useState(getAdminToken());
  const [stats, setStats] = useState(null);
  const [statsError, setStatsError] = useState(null);

  const logout = () => {
    clearAdminToken();
    setToken(null);
    setStats(null);
  };

  const loadStats = () => {
    getAdminStats()
      .then((data) => {
        setStats(data);
        setStatsError(null);
      })
      .catch((err) => {
        if (err.response?.status === 401 || err.response?.status === 403) {
          logout();
        } else {
          setStatsError('Failed to load admin stats.');
        }
      });
  };

  useEffect(() => {
    if (!token) return undefined;
    loadStats();
    const interval = setInterval(loadStats, 10000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  if (!token) {
    return <AdminLoginForm onLoggedIn={setToken} />;
  }

  return (
    <div className="page-panel">
      <div className="card-row">
        <h2>Admin Dashboard</h2>
        <button onClick={logout}>Log out</button>
      </div>
      <p>
        Seat occupancy, ticket sales and camera monitoring: <Link to="/conductor">🧾 Conductor console</Link>
      </p>

      {statsError && <div className="banner banner-error" style={{ position: 'static' }}>{statsError}</div>}

      {stats && (
        <>
          <div className="kpi-grid">
            <div className="kpi-card">
              <div className="kpi-value">{stats.liveBusCount}</div>
              <div className="kpi-label">Live Buses</div>
            </div>
            <div className="kpi-card">
              <div className="kpi-value">{stats.offlineBusCount}</div>
              <div className="kpi-label">Offline Buses</div>
            </div>
            <div className="kpi-card">
              <div className="kpi-value">{stats.delayedBusCount}</div>
              <div className="kpi-label">Delayed Buses</div>
            </div>
            <div className="kpi-card">
              <div className="kpi-value">{stats.averageSpeedKmh}</div>
              <div className="kpi-label">Avg Speed (km/h)</div>
            </div>
          </div>

          <h3 style={{ marginTop: 20 }}>Route Statistics</h3>
          <table className="data-table">
            <thead>
              <tr>
                <th>Route</th>
                <th>Active Buses</th>
              </tr>
            </thead>
            <tbody>
              {stats.routeStats.map((r) => (
                <tr key={r.routeCode}>
                  <td>{r.routeCode}</td>
                  <td>{r.activeBusCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {stats.routeStats.length === 0 && <p>No routes currently reporting live buses.</p>}
        </>
      )}
    </div>
  );
}
