import { useEffect, useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import Icon from '../../ui/Icon';
import { EmptyState, ErrorState, Notice, SkeletonList } from '../../ui/States';
import { SortHeader, StatusBadge, UpdatedAgo } from '../AdminWidgets';
import { sectionPath, useAdmin, useAdminPolling } from '../adminContext';
import { useNow } from '../../../hooks/useStores';
import { getAdminBuses, getAllAdminBuses } from '../../../api/client';
import { routeLabel, STATUS_META, STATUSES, toCsv } from '../../../utils/admin';
import { ageText } from '../../../utils/format';

const PAGE_SIZES = [25, 50, 100, 200];
const YES_NO = { true: 'Yes', false: 'No' };
const TYPE_LABEL = { LOW_FLOOR: 'Low floor', STANDARD: 'Standard' };

const CSV_COLUMNS = [
  { key: 'vehicleId', label: 'Vehicle ID' },
  { key: 'registrationNumber', label: 'Registration' },
  { key: 'routeName', label: 'Route number' },
  { key: 'routeCode', label: 'GTFS route_id' },
  { key: 'liveStatus', label: 'Live status', value: (r) => STATUS_META[r.liveStatus]?.label ?? r.liveStatus },
  { key: 'lastSeen', label: 'Last seen (UTC)', value: (r) => (r.lastSeen ? new Date(r.lastSeen * 1000).toISOString() : '') },
  { key: 'speedKmh', label: 'Speed (km/h)', value: (r) => (r.speedKmh == null ? '' : r.speedKmh.toFixed(1)) },
  { key: 'bearing', label: 'Bearing (deg)' },
  { key: 'latitude', label: 'Latitude' },
  { key: 'longitude', label: 'Longitude' },
  { key: 'busType', label: 'Bus type' },
  { key: 'wheelchairAccessible', label: 'Wheelchair accessible' },
  { key: 'cameraInstalled', label: 'Camera installed' },
  { key: 'status', label: 'Registry status' },
];

function download(text, filename) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Registered buses joined with their latest position (GET /admin/buses): server-side search, filter, sort, pages. */
export default function FleetSection() {
  const { toast, stats, expireSession } = useAdmin();
  const id = useId();
  const now = useNow(5000);
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const status = params.get('status') ?? '';
  const route = params.get('route') ?? '';
  const sortParam = params.get('sort') ?? 'lastSeen,desc';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const size = PAGE_SIZES.includes(Number(params.get('size'))) ? Number(params.get('size')) : 50;
  const [sortKey, sortDir = 'asc'] = sortParam.split(',');

  const [qInput, setQInput] = useState(q);
  const [routeInput, setRouteInput] = useState(route);
  const [exporting, setExporting] = useState(false);

  // Any filter change goes back to the first page.
  const update = (patch) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    for (const [k, v] of Object.entries(patch)) {
      if (v === '' || v == null) next.delete(k); else next.set(k, String(v));
    }
    if (!('page' in patch)) next.delete('page');
    return next;
  }, { replace: true });

  useEffect(() => {
    const t = setTimeout(() => { if (qInput.trim() !== q) update({ q: qInput.trim() }); }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qInput]);
  useEffect(() => {
    const t = setTimeout(() => { if (routeInput.trim() !== route) update({ route: routeInput.trim() }); }, 400);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeInput]);

  const filter = { q, status, route, sort: sortParam };
  const query = { ...filter, page, size };
  const res = useAdminPolling(JSON.stringify(query), (signal) => getAdminBuses(query, signal), { interval: 15000, keepPrevious: true });
  const data = res.data;
  const counts = data?.statusCounts ?? {};
  const countAll = STATUSES.reduce((sum, s) => sum + (counts[s] ?? 0), 0);

  const exportCsv = async () => {
    setExporting(true);
    try {
      const { rows, total, complete } = await getAllAdminBuses(filter);
      const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '');
      download(toCsv(rows, CSV_COLUMNS), `fleet-${stamp}.csv`);
      toast(complete
        ? { tone: 'success', title: `Exported ${rows.length} buses`, text: 'CSV of the current filter.' }
        : { tone: 'warn', title: `Exported the first ${rows.length} buses`, text: `${total}+ match; narrow the filter to export the rest.` });
    } catch (err) {
      if (err?.response?.status === 401 || err?.response?.status === 403) expireSession();
      else toast({ tone: 'error', title: 'Export failed', text: err?.message ?? 'Request failed.' });
    } finally {
      setExporting(false);
    }
  };

  const sort = { key: sortKey, dir: sortDir };
  const onSort = ({ key, dir }) => update({ sort: `${key},${dir}` });
  const from = data ? data.page * data.size + 1 : 0;
  const to = data ? data.page * data.size + data.content.length : 0;

  return (
    <div className="admin-pad stack">
      <div className="admin-toolbar">
        <div className="field admin-toolbar-search">
          <label className="vh" htmlFor={`${id}-q`}>Search buses</label>
          <div className="input-wrap">
            <Icon name="search" size={18} />
            <input id={`${id}-q`} className="input" type="search" placeholder="Vehicle ID, registration or route"
              value={qInput} onChange={(e) => setQInput(e.target.value)} />
          </div>
        </div>
        <div className="field admin-toolbar-route">
          <label className="vh" htmlFor={`${id}-route`}>Route (exact number or route_id)</label>
          <div className="input-wrap">
            <Icon name="route" size={18} />
            <input id={`${id}-route`} className="input" type="search" placeholder="Exact route" list={`${id}-routes`}
              value={routeInput} onChange={(e) => setRouteInput(e.target.value)} />
          </div>
          <datalist id={`${id}-routes`}>
            {(stats?.routeStats ?? []).map((r) => <option key={r.routeCode} value={r.routeName || r.routeCode} />)}
          </datalist>
        </div>
        <div className="admin-toolbar-end">
          <UpdatedAgo updatedAt={res.updatedAt} error={res.error} staleAfterMs={45000} />
          <button type="button" className="btn btn-sm" onClick={exportCsv} disabled={exporting || !data?.totalElements}>
            <Icon name="download" size={16} />{exporting ? 'Exporting…' : 'Export CSV'}
          </button>
        </div>
      </div>

      <div className="filter-group" role="group" aria-label="Filter by live status">
        <button type="button" className="filter-btn" aria-pressed={!status} onClick={() => update({ status: '' })}>
          All <strong>{data ? countAll : '…'}</strong>
        </button>
        {STATUSES.map((s) => (
          <button key={s} type="button" className="filter-btn" aria-pressed={status === s} title={STATUS_META[s].hint}
            onClick={() => update({ status: status === s ? '' : s })}>
            <span className={`status-swatch status-swatch-${STATUS_META[s].tone}`} aria-hidden="true" />
            {STATUS_META[s].label} <strong>{data ? counts[s] ?? 0 : '…'}</strong>
          </button>
        ))}
      </div>

      {!data && res.loading && <SkeletonList rows={6} label="Loading fleet" />}
      {!data && res.error && <div className="panel"><ErrorState error={res.error} onRetry={res.refresh} what="the fleet list" /></div>}
      {data && res.error && <Notice tone="warn" role="status">Showing the last list received. {res.error.message}</Notice>}

      {data && data.totalElements === 0 && (
        <div className="panel">
          <EmptyState icon="bus" title="No buses match">
            {q || status || route ? 'Try a different search, status or route.' : 'No bus has been registered yet; buses are added as the live feed reports them.'}
          </EmptyState>
        </div>
      )}

      {data && data.totalElements > 0 && (
        <div className="panel table-wrap" aria-busy={res.loading}>
          <table className="admin-table">
            <caption className="vh">Fleet, sorted by {sortKey} {sortDir === 'desc' ? 'descending' : 'ascending'}; buses {from} to {to} of {data.totalElements}.</caption>
            <thead>
              <tr>
                <SortHeader label="Vehicle" field="vehicleId" sort={sort} onSort={onSort} />
                <SortHeader label="Route" field="route" sort={sort} onSort={onSort} />
                <SortHeader label="Status" field="status" sort={sort} onSort={onSort} />
                <SortHeader label="Speed" field="speed" sort={sort} onSort={onSort} className="num" />
                <SortHeader label="Last seen" field="lastSeen" sort={sort} onSort={onSort} />
                <th scope="col" className="hide-sm">Type</th>
                <th scope="col" className="hide-sm" title="Wheelchair accessible (registry)">♿<span className="vh">Wheelchair accessible</span></th>
                <th scope="col" className="hide-sm">Camera</th>
                <th scope="col"><span className="vh">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((b) => (
                <tr key={b.vehicleId}>
                  <td>
                    <span className="mono">{b.vehicleId}</span>
                    {b.registrationNumber && b.registrationNumber !== b.vehicleId && <small className="cell-sub">{b.registrationNumber}</small>}
                  </td>
                  <td>{b.routeCode || b.routeName ? <span className="route-chip route-chip-quiet">{routeLabel(b)}</span> : <span className="muted">—</span>}</td>
                  <td><StatusBadge status={b.liveStatus} /></td>
                  <td className="num">{b.speedKmh == null ? <span className="muted">—</span> : `${b.speedKmh.toFixed(0)} km/h`}</td>
                  <td>{b.lastSeen ? ageText(b.lastSeen, now) : <span className="muted">never</span>}</td>
                  <td className="hide-sm">{TYPE_LABEL[b.busType] ?? <span className="muted">—</span>}</td>
                  <td className="hide-sm">{YES_NO[b.wheelchairAccessible] ?? <span className="muted">—</span>}</td>
                  <td className="hide-sm">{YES_NO[b.cameraInstalled] ?? <span className="muted">—</span>}</td>
                  <td className="cell-actions">
                    {b.latitude != null && (
                      <Link className="btn btn-sm btn-ghost" to={sectionPath('map', { bus: b.vehicleId })} aria-label={`Show ${b.vehicleId} on the map`}>
                        <Icon name="map" size={16} />
                      </Link>
                    )}
                    <Link className="btn btn-sm btn-ghost" to={`/replay?vehicle=${encodeURIComponent(b.vehicleId)}`} aria-label={`Journey replay for ${b.vehicleId}`}>
                      <Icon name="clock" size={16} />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pager">
            <span className="small muted" role="status">{from}–{to} of {data.totalElements}</span>
            <label className="small row">
              Rows
              <select className="select-input select-sm" value={size} onChange={(e) => update({ size: e.target.value })}>
                {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
            <div className="row">
              <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => update({ page: page - 1 })}>
                <Icon name="chevronLeft" size={16} />Previous
              </button>
              <button type="button" className="btn btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => update({ page: page + 1 })}>
                Next<Icon name="chevronRight" size={16} />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
