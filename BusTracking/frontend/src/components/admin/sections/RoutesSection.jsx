import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Icon from '../../ui/Icon';
import { EmptyState, ErrorState, SkeletonList } from '../../ui/States';
import { SortHeader, UpdatedAgo } from '../AdminWidgets';
import { sectionPath, useAdmin } from '../adminContext';
import { routeLabel, sortBy } from '../../../utils/admin';

/** Routes with buses reporting now (from GET /admin/stats); a route opens the map filtered to it. */
export default function RoutesSection() {
  const { stats, statsUpdatedAt, statsError, refreshStats, paused } = useAdmin();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState({ key: 'activeBusCount', dir: 'desc' });

  const rows = useMemo(() => (stats?.routeStats ?? []).map((r) => ({ ...r, label: routeLabel(r) })), [stats]);
  const max = Math.max(1, ...rows.map((r) => r.activeBusCount));
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matches = q ? rows.filter((r) => r.label.toLowerCase().includes(q) || r.routeCode.toLowerCase().includes(q)) : rows;
    return sortBy(matches, sort.key, sort.dir);
  }, [rows, query, sort]);
  const total = rows.reduce((sum, r) => sum + r.activeBusCount, 0);

  return (
    <div className="admin-pad stack">
      <div className="admin-toolbar">
        <div className="field admin-toolbar-search">
          <label className="vh" htmlFor="routes-q">Search routes</label>
          <div className="input-wrap">
            <Icon name="search" size={18} />
            <input id="routes-q" className="input" type="search" placeholder="Search route number or route_id"
              value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
        </div>
        <div className="admin-toolbar-end">
          <UpdatedAgo updatedAt={statsUpdatedAt} error={statsError} paused={paused} />
        </div>
      </div>

      {!stats && !statsError && <SkeletonList rows={6} label="Loading routes" />}
      {!stats && statsError && <div className="panel"><ErrorState error={statsError} onRetry={refreshStats} what="route statistics" /></div>}

      {stats && rows.length === 0 && (
        <div className="panel"><EmptyState icon="route" title="No routes reporting">No bus is reporting a live position right now.</EmptyState></div>
      )}
      {stats && rows.length > 0 && (
        <div className="panel table-wrap">
          <table className="admin-table">
            <caption className="vh">Routes with live buses, {shown.length} shown. Select a route to see it on the map.</caption>
            <thead>
              <tr>
                <SortHeader label="Route" field="label" sort={sort} onSort={setSort} />
                <SortHeader label="route_id" field="routeCode" sort={sort} onSort={setSort} className="hide-sm" />
                <SortHeader label="Buses reporting" field="activeBusCount" sort={sort} onSort={setSort} className="col-bar" />
                <th scope="col"><span className="vh">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.routeCode} className="row-link" onClick={() => navigate(sectionPath('map', { route: r.routeCode }))}>
                  <td><span className="route-chip">{r.label}</span></td>
                  <td className="hide-sm mono muted">{r.routeCode}</td>
                  <td className="col-bar">
                    <span className="bar-cell">
                      <span className="bar" aria-hidden="true"><span style={{ width: `${(r.activeBusCount / max) * 100}%` }} /></span>
                      <span className="bar-num">{r.activeBusCount}</span>
                    </span>
                  </td>
                  <td className="cell-actions">
                    <Link className="btn btn-sm btn-ghost" to={sectionPath('map', { route: r.routeCode })}
                      onClick={(e) => e.stopPropagation()} aria-label={`Show route ${r.label} on the live map`}>
                      <Icon name="map" size={16} />Map
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {shown.length === 0 && <EmptyState icon="search" title="No matching route">Nothing matches “{query}”.</EmptyState>}
          <p className="table-foot small muted">
            {rows.length} routes · {total} buses reporting. Route numbers come from the imported GTFS data; a bare
            route_id means the route is not in it.
          </p>
        </div>
      )}
    </div>
  );
}
