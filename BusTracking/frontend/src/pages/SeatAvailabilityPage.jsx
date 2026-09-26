import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiErrorMessage, getTicketingBuses } from '../api/client';
import SeatMap from '../components/SeatMap';
import RouteTimeline from '../components/RouteTimeline';
import PassengerCategoryPicker from '../components/PassengerCategoryPicker';
import useSeatMap from '../hooks/useSeatMap';
import BusPickerCards from '../components/BusPickerCards';
import SeatCountChips from '../components/SeatCountChips';
import '../components/Ticketing.css';

export default function SeatAvailabilityPage() {
  const [buses, setBuses] = useState([]);
  const [busesError, setBusesError] = useState(null);
  const [busNumber, setBusNumber] = useState(null);
  const [category, setCategory] = useState('ADULT');
  const [selectedSeat, setSelectedSeat] = useState(null);
  const { seatMap, error, fromStopId, toStopId, pickFrom, pickTo } = useSeatMap(busNumber, category);

  useEffect(() => {
    const load = () =>
      getTicketingBuses()
        .then((data) => {
          setBuses(data);
          setBusesError(null);
          setBusNumber((current) => current || data[0]?.busNumber || null);
        })
        .catch((err) => setBusesError(apiErrorMessage(err, 'Failed to load buses.')));
    load();
    const interval = setInterval(load, 15000);
    return () => clearInterval(interval);
  }, []);

  // A selection is only meaningful for the segment/category it was made for.
  useEffect(() => setSelectedSeat(null), [busNumber, fromStopId, toStopId, category]);

  const seat = seatMap?.seats.find((s) => s.seatNumber === selectedSeat);
  // Drop the selection if a refresh shows someone else took the seat.
  const selection = seat && seat.selectable ? seat : null;

  return (
    <div className="page-panel page-panel-wide ticketing-page">
      <header className="ticketing-hero">
        <div>
          <span className="hero-kicker">🎟️ Live seat availability</span>
          <h2>Pick your stops, see your seat</h2>
          <p>
            Seats are shown for <em>your</em> part of the route: a seat taken from A to C is free again from C
            onwards. Tickets are issued by the conductor on board.
          </p>
        </div>
      </header>

      {busesError && <div className="banner banner-error banner-static">{busesError}</div>}
      <BusPickerCards buses={buses} selected={busNumber} onSelect={setBusNumber} />

      {seatMap && (
        <>
          <section className="t-card">
            <h3 className="t-card-title">
              Route {seatMap.routeCode} <span className="muted">· {seatMap.routeName}</span>
            </h3>
            <RouteTimeline
              stops={seatMap.stops}
              fromStopId={fromStopId}
              toStopId={toStopId}
              onPickFrom={(s) => pickFrom(s.stopId)}
              onPickTo={(s) => pickTo(s.stopId)}
            />
            <div className="segment-controls">
              <label>
                Boarding
                <select value={fromStopId ?? ''} onChange={(e) => pickFrom(Number(e.target.value))}>
                  {seatMap.stops.slice(0, -1).map((s) => (
                    <option key={s.stopId} value={s.stopId} disabled={s.progress === 'PASSED'}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Destination
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
            <PassengerCategoryPicker value={category} onChange={setCategory} />
          </section>

          {error && <div className="banner banner-error banner-static">{error}</div>}

          {seatMap.soldOut && (
            <div className="soldout-banner" role="status">
              🚫 {seatMap.soldOutMessage} Other segments of this bus may still have seats — try a different
              boarding or destination stop.
            </div>
          )}

          <div className="seat-layout">
            <section className="t-card">
              <h3 className="t-card-title">
                {seatMap.busNumber} · {seatMap.fromStop.name} → {seatMap.toStop.name}
              </h3>
              <SeatCountChips seatMap={seatMap} />
              <SeatMap seatMap={seatMap} selectedSeat={selection?.seatNumber} onSelect={(s) => setSelectedSeat(s.seatNumber)} />
            </section>

            <aside className="t-card seat-aside">
              <h3 className="t-card-title">Your seat</h3>
              <div className="kpi-inline">
                <strong>{seatMap.counts.availableForCategory}</strong>
                <span>places you can book as {seatMap.categoryLabel}</span>
              </div>
              {selection ? (
                <div className="ticket-preview">
                  <div className="ticket-preview-seat">
                    {selection.seatType === 'WHEELCHAIR' ? '♿ Wheelchair Space' : `Seat ${selection.seatNumber}`}
                  </div>
                  <div>
                    {seatMap.fromStop.name} → {seatMap.toStop.name}
                  </div>
                  {selection.note && <div className="muted">{selection.note}</div>}
                  <div className="fare-line">
                    {seatMap.fare.fareLabel}: <strong>₹{seatMap.fare.amount}</strong>
                  </div>
                  <div className="muted small">{seatMap.fare.ticketTypeLabel}</div>
                  <p className="small">Show this to the conductor to get your ticket.</p>
                </div>
              ) : (
                <p className="muted">Tap a green (or, if eligible, yellow / ♿) seat to select it.</p>
              )}
              <p className="disclaimer">⚠️ {seatMap.fare.disclaimer}</p>
              {category === 'DIVYANGJAN' && (
                <p className="access-note">
                  ♿ The wheelchair space and priority seats can be booked with a Divyangjan ticket. The conductor
                  will issue a separate accessibility ticket.
                </p>
              )}
              <p className="small">
                Conductor? <Link to="/conductor">Open the conductor console</Link>
              </p>
            </aside>
          </div>
        </>
      )}
      {!seatMap && busNumber && !error && <p className="muted">Loading seat map…</p>}
      {!seatMap && error && <div className="banner banner-error banner-static">{error}</div>}
    </div>
  );
}
