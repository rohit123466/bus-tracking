import './Ticketing.css';
import './SeatMap.css';
import { referenceSeatLayout } from '../utils/seatLayout.js';

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
  const { seats } = seatMap;
  const { positions, rows } = referenceSeatLayout(seats);

  return (
    <div className="seatmap seatmap--reference">
      <div className="bus-shell" role="group" aria-label={`Seat map of bus ${seatMap.busNumber}`}>
        <div className="bus-cockpit">
          <div className="bus-windscreen" aria-hidden="true" />
          <span className="bus-front-label">FRONT</span>
          <div className="bus-driver-station" aria-label="Driver seat, right-hand drive">
            <svg viewBox="0 0 80 100" aria-hidden="true">
              <rect x="8" y="2" width="64" height="18" rx="6" fill="#374151" />
              <path d="M17 8h12m7 0h7m7 0h12" stroke="#94a3b8" strokeWidth="3" />
              <circle cx="40" cy="34" r="17" fill="#fff" stroke="#374151" strokeWidth="5" />
              <path d="M25 27l15 8 15-8M40 35v15" fill="none" stroke="#374151" strokeWidth="4" />
              <rect x="17" y="57" width="46" height="32" rx="10" fill="#64748b" stroke="#334155" strokeWidth="3" />
              <rect x="14" y="81" width="52" height="13" rx="6" fill="#334155" />
              <path d="M11 64v15m58-15v15" stroke="#334155" strokeWidth="6" strokeLinecap="round" />
            </svg>
            <span>Driver seat</span>
          </div>
        </div>

        <div
          className="seat-grid"
          style={{
            gridTemplateColumns: 'repeat(2, minmax(0, 1fr)) minmax(26px, .85fr) repeat(2, minmax(0, 1fr))',
            gridTemplateRows: `repeat(${rows}, minmax(44px, auto))`,
          }}
        >
          <span className="bus-entry bus-entry--front" style={{ gridRow: 1, gridColumn: '1 / 3' }}>Front door</span>
          <span className="bus-entry bus-entry--middle" style={{ gridRow: 5, gridColumn: '1 / 3' }}>Middle door</span>
          <span className="bus-aisle-label" style={{ gridRow: '3 / 6', gridColumn: 3 }} aria-hidden="true">AISLE</span>
          {seats.map((seat) => {
            const position = positions.get(seat.seatId);
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
                  gridRow: `${position.row + 1} / span ${position.rowSpan}`,
                  gridColumn: `${position.column + 1} / span ${position.columnSpan}`,
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
        <div className="bus-rear">REAR</div>
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
