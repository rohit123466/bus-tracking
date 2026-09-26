import './Ticketing.css';

/** One card per ticketing bus, with live occupancy from the backend summary. */
export default function BusPickerCards({ buses, selected, onSelect }) {
  return (
    <div className="bus-cards" role="tablist" aria-label="Buses">
      {buses.map((bus) => (
        <button
          key={bus.busNumber}
          type="button"
          role="tab"
          aria-selected={selected === bus.busNumber}
          className={'bus-card' + (selected === bus.busNumber ? ' bus-card--active' : '')}
          onClick={() => onSelect(bus.busNumber)}
        >
          <div className="bus-card-top">
            <strong className="bus-card-number">🚌 {bus.busNumber}</strong>
            <span className={'type-badge' + (bus.lowFloor ? ' type-badge--lowfloor' : '')}>
              {bus.lowFloor && '♿ '}
              {bus.busTypeLabel}
            </span>
          </div>
          <div className="bus-card-route">{bus.routeCode} · at {bus.currentStop?.name}</div>
          <div className="occupancy-bar" aria-label={`Occupancy ${bus.occupancyPercent}%`}>
            <span style={{ width: `${Math.min(100, bus.occupancyPercent)}%` }} />
          </div>
          <div className="bus-card-meta">
            <span>{bus.availableSeats + bus.reservedSeats} seats free now</span>
            <span>{bus.occupancyPercent}% full</span>
          </div>
        </button>
      ))}
    </div>
  );
}
