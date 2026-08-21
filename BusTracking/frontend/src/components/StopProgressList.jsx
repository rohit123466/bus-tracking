import { useEffect, useRef, useState } from 'react';
import { speak } from '../utils/voiceAnnouncer';

const PROGRESS_LABEL = {
  PASSED: 'Passed',
  CURRENT: 'Current',
  NEXT: 'Next',
  UPCOMING: 'Upcoming',
};

export default function StopProgressList({ stops }) {
  const [arrivalBanner, setArrivalBanner] = useState(null);
  const prevCurrentRef = useRef(undefined);
  const prevNextRef = useRef(undefined);
  const bannerTimeoutRef = useRef(null);

  const currentStop = stops?.find((s) => s.progress === 'CURRENT') || null;
  const nextStop = stops?.find((s) => s.progress === 'NEXT') || null;

  // Announce (voice + a visual banner) only on genuine changes to the
  // tracked bus's current/next stop, not on every poll re-render.
  useEffect(() => {
    if (currentStop && currentStop.stopId !== prevCurrentRef.current) {
      if (prevCurrentRef.current !== undefined) {
        speak(`Now arriving: ${currentStop.name}`);
        setArrivalBanner(`🚏 Now arriving: ${currentStop.name}`);
        clearTimeout(bannerTimeoutRef.current);
        bannerTimeoutRef.current = setTimeout(() => setArrivalBanner(null), 8000);
      }
      prevCurrentRef.current = currentStop.stopId;
    }
    return () => clearTimeout(bannerTimeoutRef.current);
  }, [currentStop]);

  useEffect(() => {
    if (nextStop && nextStop.stopId !== prevNextRef.current) {
      if (prevNextRef.current !== undefined) {
        speak(`Next stop: ${nextStop.name}`);
      }
      prevNextRef.current = nextStop.stopId;
    }
  }, [nextStop]);

  if (!stops?.length) return <p>No stop data for this route.</p>;

  return (
    <>
      {arrivalBanner && (
        <div
          className="banner banner-arrival"
          style={{ position: 'static', marginBottom: 10 }}
          role="alert"
          aria-live="assertive"
        >
          {arrivalBanner}
        </div>
      )}
      <ol className="stop-progress-list" aria-label="Stops on this route">
        {stops.map((stop) => {
          const progressLabel = stop.progress ? PROGRESS_LABEL[stop.progress] : null;
          const accessibleLabel =
            `Stop ${stop.sequence}: ${stop.name}` +
            (progressLabel ? `, ${progressLabel}` : '') +
            (stop.wheelchairBoarding ? ', wheelchair accessible boarding' : '');
          return (
            <li
              key={stop.stopId}
              className={stop.progress ? `stop-item stop-${stop.progress.toLowerCase()}` : 'stop-item'}
              aria-label={accessibleLabel}
              aria-current={stop.progress === 'CURRENT' ? 'step' : undefined}
            >
              <span className="stop-seq" aria-hidden="true">{stop.sequence}</span>
              <span className="stop-name">{stop.name}</span>
              {stop.wheelchairBoarding && (
                <span role="img" aria-label="Wheelchair accessible boarding" title="Wheelchair accessible boarding">
                  ♿
                </span>
              )}
              {stop.progress && <span className="stop-badge">{progressLabel}</span>}
            </li>
          );
        })}
      </ol>
    </>
  );
}
