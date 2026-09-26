import { useCallback, useEffect, useState } from 'react';
import { apiErrorMessage, getSeatMap } from '../api/client';

/**
 * Loads the backend seat map for a bus + journey segment + passenger
 * category, and keeps the boarding/destination selection consistent.
 * Stops default (null) to the bus's current stop -> next stop.
 */
export default function useSeatMap(busNumber, category, refreshMs = 10000) {
  const [fromStopId, setFromStopId] = useState(null);
  const [toStopId, setToStopId] = useState(null);
  const [seatMap, setSeatMap] = useState(null);
  const [error, setError] = useState(null);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    setFromStopId(null);
    setToStopId(null);
    setSeatMap(null);
  }, [busNumber]);

  useEffect(() => {
    if (!busNumber) return undefined;
    let cancelled = false;
    getSeatMap(busNumber, { fromStopId, toStopId, category })
      .then((data) => {
        if (cancelled) return;
        setSeatMap(data);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        const message = apiErrorMessage(err, 'Failed to load seat map.');
        // The bus moved past the chosen boarding stop: fall back to "now".
        if (/has already left/.test(message) && fromStopId) {
          setFromStopId(null);
          setToStopId(null);
          return;
        }
        setError(message);
      });
    return () => {
      cancelled = true;
    };
  }, [busNumber, fromStopId, toStopId, category, tick]);

  useEffect(() => {
    if (!busNumber || !refreshMs) return undefined;
    const interval = setInterval(reload, refreshMs);
    return () => clearInterval(interval);
  }, [busNumber, refreshMs, reload]);

  const stops = seatMap?.stops || [];
  const effectiveFrom = fromStopId ?? seatMap?.fromStop?.stopId ?? null;
  const effectiveTo = toStopId ?? seatMap?.toStop?.stopId ?? null;

  const pickFrom = (stopId) => {
    const fromSeq = stops.find((s) => s.stopId === stopId)?.sequence;
    const toSeq = stops.find((s) => s.stopId === effectiveTo)?.sequence;
    setFromStopId(stopId);
    if (fromSeq != null && (toSeq == null || toSeq <= fromSeq)) {
      const next = stops.find((s) => s.sequence > fromSeq);
      setToStopId(next ? next.stopId : null);
    } else {
      setToStopId(effectiveTo);
    }
  };

  const pickTo = (stopId) => {
    setFromStopId(effectiveFrom);
    setToStopId(stopId);
  };

  return {
    seatMap,
    error,
    reload,
    fromStopId: effectiveFrom,
    toStopId: effectiveTo,
    pickFrom,
    pickTo,
  };
}
