import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  advanceBus,
  apiErrorMessage,
  cancelTicket,
  clearAdminToken,
  completeTicket,
  getAdminToken,
  getBusTickets,
  getCameraReport,
  getPassengers,
  getTicketingBuses,
  reportCameraObservations,
  resetTicketingDemo,
  sellTicket,
  simulateCameraScan,
} from '../api/client';
import AdminLoginForm from '../components/AdminLoginForm';
import SeatMap from '../components/SeatMap';
import RouteTimeline from '../components/RouteTimeline';
import PassengerCategoryPicker from '../components/PassengerCategoryPicker';
import TicketCard from '../components/TicketCard';
import CameraMonitorPanel from '../components/CameraMonitorPanel';
import OccupancyDashboard from '../components/OccupancyDashboard';
import BusPickerCards from '../components/BusPickerCards';
import SeatCountChips from '../components/SeatCountChips';
import { PASSENGER_CATEGORIES } from '../utils/passengerCategories';
import useSeatMap from '../hooks/useSeatMap';
import '../components/Ticketing.css';

const NEW_PASSENGER = 'new';
const TICKET_FILTERS = [
  { key: 'onboard', label: 'On board now', test: (t) => t.onBoardNow },
  { key: 'upcoming', label: 'Boarding later', test: (t) => t.status === 'ACTIVE' && !t.onBoardNow },
  { key: 'done', label: 'Completed / cancelled', test: (t) => t.status !== 'ACTIVE' },
  { key: 'all', label: 'All', test: () => true },
];

const newRequestId = () =>
  globalThis.crypto?.randomUUID?.() || `req-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export default function ConductorPage() {
  const [token, setToken] = useState(getAdminToken());
  const [buses, setBuses] = useState([]);
  const [busNumber, setBusNumber] = useState(null);
  const [tickets, setTickets] = useState([]);
  const [passengers, setPassengers] = useState([]);
  const [camera, setCamera] = useState(null);
  const [pageError, setPageError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  // Sale form
  const [passengerKey, setPassengerKey] = useState(NEW_PASSENGER);
  const [newName, setNewName] = useState('');
  const [newCategory, setNewCategory] = useState('ADULT');
  const [selectedSeat, setSelectedSeat] = useState(null);
  const [saleError, setSaleError] = useState(null);
  const [lastTicket, setLastTicket] = useState(null);
  const [saleCount, setSaleCount] = useState(0);
  const [filter, setFilter] = useState('onboard');

  const existingPassenger = passengers.find((p) => String(p.id) === passengerKey);
  const category = existingPassenger ? existingPassenger.category : newCategory;
  const { seatMap, error: seatMapError, reload: reloadSeatMap, fromStopId, toStopId, pickFrom, pickTo } =
    useSeatMap(token ? busNumber : null, category, 8000);

  // Same form contents => same id, so a double-clicked "Issue ticket" is
  // rejected by the backend as a duplicate instead of selling twice.
  const requestId = useMemo(
    () => newRequestId(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [busNumber, passengerKey, newName, category, fromStopId, toStopId, selectedSeat, saleCount]
  );

  const logout = useCallback(() => {
    clearAdminToken();
    setToken(null);
  }, []);

  const handleAuth = useCallback(
    (err) => {
      if (err?.response?.status === 401 || err?.response?.status === 403) {
        logout();
        return true;
      }
      return false;
    },
    [logout]
  );

  const refresh = useCallback(() => {
    getTicketingBuses()
      .then((data) => {
        setBuses(data);
        setBusNumber((current) => current || data[0]?.busNumber || null);
      })
      .catch((err) => setPageError(apiErrorMessage(err, 'Failed to load buses.')));
    if (!busNumber) return;
    getBusTickets(busNumber)
      .then(setTickets)
      .catch((err) => handleAuth(err) || setPageError(apiErrorMessage(err, 'Failed to load tickets.')));
    getCameraReport(busNumber)
      .then(setCamera)
      .catch((err) => handleAuth(err) || setPageError(apiErrorMessage(err, 'Failed to load camera data.')));
    getPassengers()
      .then(setPassengers)
      .catch((err) => handleAuth(err));
  }, [busNumber, handleAuth]);

  useEffect(() => {
    if (!token) return undefined;
    refresh();
    const interval = setInterval(refresh, 8000);
    return () => clearInterval(interval);
  }, [token, refresh]);

  useEffect(() => {
    setSelectedSeat(null);
    setSaleError(null);
  }, [busNumber, fromStopId, toStopId, category]);

  useEffect(() => {
    setLastTicket(null);
    setNotice(null);
    setCamera(null);
    setTickets([]);
  }, [busNumber]);

  const refreshAll = () => {
    refresh();
    reloadSeatMap();
  };

  const run = (promise, onDone) => {
    setBusy(true);
    setPageError(null);
    return promise
      .then(onDone)
      .catch((err) => handleAuth(err) || setPageError(apiErrorMessage(err)))
      .finally(() => {
        setBusy(false);
        refreshAll();
      });
  };

  const submitSale = (e) => {
    e.preventDefault();
    setSaleError(null);
    const request = {
      busNumber,
      fromStopId,
      toStopId,
      seatNumber: selectedSeat || undefined,
      requestId,
      ...(existingPassenger
        ? { passengerId: existingPassenger.id }
        : { passengerName: newName.trim(), category: newCategory }),
    };
    setBusy(true);
    sellTicket(request)
      .then((ticket) => {
        setLastTicket(ticket);
        setSaleCount((c) => c + 1);
        setSelectedSeat(null);
        setNewName('');
        setPassengerKey(NEW_PASSENGER);
      })
      .catch((err) => handleAuth(err) || setSaleError(apiErrorMessage(err, 'Ticket could not be issued.')))
      .finally(() => {
        setBusy(false);
        refreshAll();
      });
  };

  if (!token) {
    return (
      <AdminLoginForm
        title="Conductor Login"
        hint="The conductor console uses the admin account configured for this deployment."
        onLoggedIn={setToken}
      />
    );
  }

  const bus = buses.find((b) => b.busNumber === busNumber);
  const visibleTickets = tickets
    .filter(TICKET_FILTERS.find((f) => f.key === filter).test)
    .slice()
    .reverse();
  const canSell = seatMap && !busy && (existingPassenger || newName.trim());

  return (
    <div className="page-panel page-panel-wide ticketing-page">
      <header className="ticketing-hero ticketing-hero--conductor">
        <div>
          <span className="hero-kicker">🧾 Conductor console</span>
          <h2>Sell tickets &amp; monitor occupancy</h2>
          <p>Tickets hold a seat only between boarding and destination stops.</p>
        </div>
        <div className="hero-actions">
          <button
            type="button"
            className="btn-ghost"
            disabled={busy}
            onClick={() => {
              if (window.confirm('Reset all demo tickets, passengers, camera scans and bus positions?')) {
                run(resetTicketingDemo(), () => setNotice('Demo data reset: every bus is back at stop A.'));
              }
            }}
          >
            ↺ Reset demo data
          </button>
          <button type="button" className="btn-ghost" onClick={logout}>
            Log out
          </button>
        </div>
      </header>

      {pageError && <div className="banner banner-error banner-static">{pageError}</div>}
      {notice && <div className="notice">{notice}</div>}

      <BusPickerCards buses={buses} selected={busNumber} onSelect={setBusNumber} />

      <div className="conductor-grid">
        <div className="conductor-main">
          <OccupancyDashboard bus={bus} />

          {bus && (
            <section className="t-card">
              <div className="t-card-row">
                <h3 className="t-card-title">🛣️ Journey</h3>
                <button
                  type="button"
                  className="btn-primary"
                  disabled={busy || !bus.nextStop}
                  onClick={() =>
                    run(advanceBus(busNumber), (res) => {
                      const names = res.alighted.map((t) => `${t.passengerName} (#${t.ticketNumber}, seat ${t.seatNumber})`);
                      setNotice(
                        `${busNumber} arrived at ${res.bus.currentStop.name}. ` +
                          (names.length ? `Got down: ${names.join(', ')} — seats freed.` : 'Nobody got down here.')
                      );
                    })
                  }
                >
                  {bus.nextStop ? `▶ Move to ${bus.nextStop.name}` : 'Final stop reached'}
                </button>
              </div>
              <RouteTimeline stops={bus.stops} />
            </section>
          )}

          {seatMap && (
            <section className="t-card">
              <h3 className="t-card-title">🎫 Sell a ticket</h3>
              <form className="sale-form" onSubmit={submitSale}>
                <div className="sale-row">
                  <label>
                    Passenger
                    <select value={passengerKey} onChange={(e) => setPassengerKey(e.target.value)}>
                      <option value={NEW_PASSENGER}>➕ New walk-in passenger</option>
                      {passengers.map((p) => (
                        <option key={p.id} value={String(p.id)}>
                          {p.name} — {p.categoryLabel}
                        </option>
                      ))}
                    </select>
                  </label>
                  {!existingPassenger && (
                    <label>
                      Name
                      <input
                        type="text"
                        maxLength={80}
                        placeholder="Passenger name"
                        value={newName}
                        onChange={(e) => setNewName(e.target.value)}
                      />
                    </label>
                  )}
                </div>

                <div className="sale-field">
                  <span className="field-label">Passenger category</span>
                  {existingPassenger ? (
                    <span className="chip">
                      {PASSENGER_CATEGORIES.find((c) => c.value === category)?.icon} {existingPassenger.categoryLabel}{' '}
                      (registered)
                    </span>
                  ) : (
                    <PassengerCategoryPicker value={newCategory} onChange={setNewCategory} />
                  )}
                </div>

                <RouteTimeline
                  stops={seatMap.stops}
                  fromStopId={fromStopId}
                  toStopId={toStopId}
                  onPickFrom={(s) => pickFrom(s.stopId)}
                  onPickTo={(s) => pickTo(s.stopId)}
                />
                <div className="segment-controls">
                  <label>
                    Boarding stop
                    <select value={fromStopId ?? ''} onChange={(e) => pickFrom(Number(e.target.value))}>
                      {seatMap.stops.slice(0, -1).map((s) => (
                        <option key={s.stopId} value={s.stopId} disabled={s.progress === 'PASSED'}>
                          {s.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Destination stop
                    <select value={toStopId ?? ''} onChange={(e) => pickTo(Number(e.target.value))}>
                      {seatMap.stops
                        .filter((s) => s.sequence > seatMap.fromStop.sequence)
                        .map((s) => (
                          <option key={s.stopId} value={s.stopId}>
                            {s.name}
                          </option>
                        ))}
                    </select>
                  </label>
                </div>

                {seatMapError && <div className="banner banner-error banner-static">{seatMapError}</div>}
                {seatMap.soldOut && <div className="soldout-banner">🚫 {seatMap.soldOutMessage}</div>}
                <SeatCountChips seatMap={seatMap} />
                <SeatMap
                  seatMap={seatMap}
                  selectedSeat={selectedSeat}
                  onSelect={(s) => setSelectedSeat((cur) => (cur === s.seatNumber ? null : s.seatNumber))}
                />

                <div className="sale-summary">
                  <div>
                    <strong>
                      {selectedSeat
                        ? selectedSeat.startsWith('WC')
                          ? `♿ Wheelchair space ${selectedSeat}`
                          : `Seat ${selectedSeat}`
                        : 'No seat picked — best free seat is auto-assigned'}
                    </strong>
                    <div className="muted small">
                      {seatMap.fromStop.name} → {seatMap.toStop.name} · {seatMap.fare.fareLabel} ₹{seatMap.fare.amount}
                      {category === 'DIVYANGJAN' && ' · Divyangjan / accessibility ticket'}
                    </div>
                  </div>
                  <button type="submit" className="btn-primary btn-large" disabled={!canSell}>
                    Issue ticket
                  </button>
                </div>
                <p className="disclaimer">⚠️ {seatMap.fare.disclaimer}</p>
              </form>

              {saleError && (
                <div className="banner banner-error banner-static" role="alert">
                  ❌ {saleError}
                </div>
              )}
              {lastTicket && (
                <div className="sale-success">
                  <p>✅ Ticket issued</p>
                  <TicketCard ticket={lastTicket} highlight />
                </div>
              )}
            </section>
          )}
        </div>

        <div className="conductor-side">
          <CameraMonitorPanel
            report={camera}
            busy={busy}
            onScan={() => run(simulateCameraScan(busNumber), setCamera)}
            onReport={(seatNumber, status) =>
              run(reportCameraObservations(busNumber, [{ seatNumber, status }]), setCamera)
            }
          />

          <section className="t-card">
            <h3 className="t-card-title">🎟️ Tickets on {busNumber}</h3>
            <div className="filter-tabs" role="tablist">
              {TICKET_FILTERS.map((f) => (
                <button
                  key={f.key}
                  type="button"
                  role="tab"
                  aria-selected={filter === f.key}
                  className={'filter-tab' + (filter === f.key ? ' filter-tab--active' : '')}
                  onClick={() => setFilter(f.key)}
                >
                  {f.label} ({tickets.filter(f.test).length})
                </button>
              ))}
            </div>
            <div className="ticket-list">
              {visibleTickets.map((t) => (
                <TicketCard
                  key={t.ticketNumber}
                  ticket={t}
                  busy={busy}
                  onComplete={(ticket) =>
                    run(completeTicket(ticket.ticketNumber), (res) =>
                      setNotice(`Ticket #${res.ticketNumber}: ${res.passengerName} got down at ${res.alightedAtStop}. Seat ${res.seatNumber} is free from there.`)
                    )
                  }
                  onCancel={(ticket) =>
                    window.confirm(`Cancel ticket #${ticket.ticketNumber} for ${ticket.passengerName}?`) &&
                    run(cancelTicket(ticket.ticketNumber), (res) => setNotice(`Ticket #${res.ticketNumber} cancelled.`))
                  }
                />
              ))}
              {visibleTickets.length === 0 && <p className="muted">No tickets in this view.</p>}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
