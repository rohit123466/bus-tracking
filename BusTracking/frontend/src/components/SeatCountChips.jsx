import './Ticketing.css';

/** Seat tallies for the seat map's route segment. */
export default function SeatCountChips({ seatMap }) {
  const c = seatMap.counts;
  return (
    <div className="chip-row">
      <span className="chip chip--green">🟢 {c.availableRegular} regular free</span>
      <span className="chip chip--yellow">🟡 {c.availablePriority} priority free</span>
      {c.wheelchairSpaces > 0 && (
        <span className="chip chip--blue">♿ Wheelchair space {c.availableWheelchair > 0 ? 'free' : 'taken'}</span>
      )}
      <span className="chip chip--red">🔴 {c.occupiedSeats} occupied</span>
      {c.unavailableSeats > 0 && <span className="chip">⚫ {c.unavailableSeats} unavailable</span>}
    </div>
  );
}
