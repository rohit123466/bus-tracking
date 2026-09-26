import './Ticketing.css';

const STATUS_LABEL = {
  AVAILABLE: 'Available',
  VACATED: 'Available (vacated)',
  OCCUPIED: 'Occupied',
  RESERVED: 'Reserved / priority',
  WHEELCHAIR: 'Wheelchair space',
  UNAVAILABLE: 'Unavailable',
};

const LEGEND = [
  { key: 'available', icon: '🟢', label: 'Available' },
  { key: 'vacated', icon: '↺', label: 'Vacated – free again' },
  { key: 'occupied', icon: '🔴', label: 'Occupied' },
  { key: 'reserved', icon: '🟡', label: 'Reserved (priority)' },
  { key: 'wheelchair', icon: '♿', label: 'Wheelchair space' },
  { key: 'unavailable', icon: '⚫', label: 'Unavailable' },
];

function seatTitle(seat) {
  const name = seat.seatType === 'WHEELCHAIR' ? `Wheelchair space ${seat.seatNumber}` : `Seat ${seat.seatNumber}`;
  return [name, STATUS_LABEL[seat.status], seat.note].filter(Boolean).join(' — ');
}

/**
 * Bus-shaped seat map. Every status comes from the backend seat-map response
 * for the requested route segment; this component only draws it.
 * `badges` optionally maps seatNumber -> short text (e.g. camera view).
 */
export default function SeatMap({ seatMap, selectedSeat, onSelect, badges = {}, highlight = {} }) {
  if (!seatMap) return null;
  const { seats, layoutRows, layoutColumns } = seatMap;

  return (
    <div className="seatmap">
      <div className="bus-shell" role="group" aria-label={`Seat map of bus ${seatMap.busNumber}`}>
        <div className="bus-front">
          <span className="bus-door" title="Front door (left side)">🚪 Door</span>
          <span className="bus-front-label">Front of bus</span>
          <span className="bus-driver" title="Driver (right-hand drive)">🧑‍✈️ Driver</span>
        </div>

        <div
          className="seat-grid"
          style={{
            gridTemplateColumns: `repeat(${layoutColumns}, minmax(0, 1fr))`,
            gridTemplateRows: `repeat(${layoutRows}, auto)`,
          }}
        >
          {seats.map((seat) => {
            const wheelchair = seat.seatType === 'WHEELCHAIR';
            const free = seat.status !== 'OCCUPIED' && seat.status !== 'UNAVAILABLE';
            const locked = free && !seat.selectable;
            const selected = selectedSeat === seat.seatNumber;
            const classes = [
              'seat',
              `seat--${seat.status.toLowerCase()}`,
              wheelchair && 'seat--wc',
              wheelchair && seat.status === 'OCCUPIED' && 'seat--wc-occupied',
              locked && 'seat--locked',
              selected && 'seat--selected',
              highlight[seat.seatNumber] && `seat--flag-${highlight[seat.seatNumber]}`,
            ]
              .filter(Boolean)
              .join(' ');
            return (
              <button
                key={seat.seatId}
                type="button"
                className={classes}
                style={{
                  gridRow: `${seat.row + 1} / span ${seat.rowSpan}`,
                  gridColumn: `${seat.column + 1} / span ${seat.columnSpan}`,
                }}
                disabled={!onSelect || !seat.selectable}
                aria-pressed={selected}
                title={seatTitle(seat)}
                aria-label={seatTitle(seat)}
                onClick={() => onSelect?.(seat)}
              >
                {wheelchair ? (
                  <>
                    <span className="seat-wc-icon" aria-hidden="true">♿</span>
                    <span className="seat-wc-label">Wheelchair Space</span>
                    <span className="seat-num">{seat.seatNumber}</span>
                  </>
                ) : (
                  <span className="seat-num">{seat.seatNumber}</span>
                )}
                {seat.status === 'VACATED' && <span className="seat-mark" aria-hidden="true">↺</span>}
                {locked && !wheelchair && <span className="seat-mark" aria-hidden="true">🔒</span>}
                {badges[seat.seatNumber] && <span className="seat-badge">{badges[seat.seatNumber]}</span>}
              </button>
            );
          })}
        </div>
        <div className="bus-rear">Rear</div>
      </div>

      <ul className="seat-legend" aria-label="Seat legend">
        {LEGEND.map((l) => (
          <li key={l.key}>
            <span className={`legend-swatch seat--${l.key}`} aria-hidden="true">{l.icon}</span>
            {l.label}
          </li>
        ))}
      </ul>
    </div>
  );
}
