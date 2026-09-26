import './Ticketing.css';

/**
 * Horizontal route timeline: passed / current (bus here) / upcoming stops,
 * with the selected boarding -> destination segment highlighted. Clicking a
 * stop at or before the boarding stop moves boarding; a later one sets the
 * destination.
 */
export default function RouteTimeline({ stops = [], fromStopId, toStopId, onPickFrom, onPickTo }) {
  const fromSeq = stops.find((s) => s.stopId === fromStopId)?.sequence;
  const toSeq = stops.find((s) => s.stopId === toStopId)?.sequence;
  const interactive = Boolean(onPickFrom || onPickTo);

  const pick = (stop) => {
    if (stop.progress === 'PASSED') return;
    if (fromSeq == null || stop.sequence <= fromSeq) onPickFrom?.(stop);
    else onPickTo?.(stop);
  };

  return (
    <ol className="route-timeline" aria-label="Route stops">
      {stops.map((stop) => {
        const inSegment = fromSeq != null && toSeq != null && stop.sequence >= fromSeq && stop.sequence <= toSeq;
        const classes = [
          'rt-stop',
          `rt-stop--${stop.progress.toLowerCase()}`,
          inSegment && 'rt-stop--segment',
          stop.sequence === fromSeq && 'rt-stop--from',
          stop.sequence === toSeq && 'rt-stop--to',
        ]
          .filter(Boolean)
          .join(' ');
        const [letter, ...rest] = stop.name.split(' · ');
        return (
          <li key={stop.stopId} className={classes}>
            <button
              type="button"
              disabled={!interactive || stop.progress === 'PASSED'}
              onClick={() => pick(stop)}
              title={stop.name}
            >
              <span className="rt-dot">{stop.progress === 'CURRENT' ? '🚌' : letter.length <= 2 ? letter : ''}</span>
              <span className="rt-name">{rest.length ? rest.join(' · ') : stop.name}</span>
              {stop.sequence === fromSeq && <span className="rt-tag rt-tag--from">Board</span>}
              {stop.sequence === toSeq && <span className="rt-tag rt-tag--to">Get down</span>}
              {stop.progress === 'CURRENT' && stop.sequence !== fromSeq && <span className="rt-tag">Bus here</span>}
            </button>
          </li>
        );
      })}
    </ol>
  );
}
