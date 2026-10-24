/**
 * Journey planner over the DTC timetable (GET /api/transit/journey/plan): direct buses and journeys
 * with one change, each as a step-by-step timeline and drawn on the map. Every duration says how it
 * was obtained; waiting time is not included because current frequencies are not published.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CircleMarker, Marker, Polyline, Tooltip } from 'react-leaflet';
import BaseMap, { FitBounds } from '../components/map/MapBase';
import { UserMarker } from '../components/map/TransitLayers';
import PlaceField from '../components/journey/PlaceField';
import Icon from '../components/ui/Icon';
import { Notice, ErrorState, SkeletonList } from '../components/ui/States';
import useUserLocation from '../hooks/useUserLocation';
import { useStore } from '../hooks/useStores';
import { planTransitJourney } from '../api/client';
import { classifyError, isAbort } from '../api/errors';
import { savedHome, savedOffice } from '../store/favourites';
import { themePreference } from '../store/theme';
import { endPin, themeColors } from '../utils/mapIcons';
import { distanceText, durationText, plural } from '../utils/format';
import {
  PREFERENCES, BASIS_TEXT, STATUS_TEXT, busSegments, journeyPoints, parsePlaceParam, placeParam,
} from '../utils/journey';

export default function JourneyPage() {
  const [params, setParams] = useSearchParams();
  const gps = useUserLocation();
  const home = useStore(savedHome), office = useStore(savedOffice);
  useStore(themePreference);
  const colors = themeColors();

  const [from, setFrom] = useState(() => parsePlaceParam(params.get('from')));
  const [to, setTo] = useState(() => parsePlaceParam(params.get('to')));
  const [preference, setPreference] = useState('FASTEST');
  const [result, setResult] = useState(null); // { plan } | { error }
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState(0);
  const ctrlRef = useRef(null);
  const resultsRef = useRef(null);

  const saved = [
    home && { key: 'home', label: home.label || 'Home', icon: 'home', lat: home.lat, lng: home.lng, kind: 'place' },
    office && { key: 'office', label: office.label || 'Work', icon: 'briefcase', lat: office.lat, lng: office.lng, kind: 'place' },
  ].filter(Boolean);

  const plan = async (pref = preference) => {
    if (!from || !to) return;
    ctrlRef.current?.abort();
    const ctrl = new AbortController();
    ctrlRef.current = ctrl;
    setLoading(true);
    setParams({ from: placeParam(from), to: placeParam(to) }, { replace: true });
    try {
      const data = await planTransitJourney({ from, to, preference: pref }, ctrl.signal);
      setResult({ plan: data });
      setSelected(0);
      requestAnimationFrame(() => resultsRef.current?.focus());
    } catch (err) {
      if (!isAbort(err)) setResult({ error: classifyError(err) });
    } finally {
      if (ctrlRef.current === ctrl) setLoading(false);
    }
  };

  useEffect(() => () => ctrlRef.current?.abort(), []);
  // A shared link with both ends plans straight away.
  useEffect(() => { if (from && to) plan(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const options = result?.plan?.options ?? [];
  const journey = options[selected] ?? null;

  const swap = () => { setFrom(to); setTo(from); setResult(null); };

  return (
    <div className="journey">
      <div className="journey-side">
        <header className="page-head">
          <h1>Plan a journey</h1>
          <p>Direct buses and journeys with one change, from the DTC timetable.</p>
        </header>

        <form className="jr-form" onSubmit={e => { e.preventDefault(); plan(); }}>
          <div className="jr-ends">
            <div className="stack">
              <PlaceField label="From" place={from} onChange={p => { setFrom(p); setResult(null); }} gps={gps} saved={saved} />
              <PlaceField label="To" place={to} onChange={p => { setTo(p); setResult(null); }} gps={gps} saved={saved} />
            </div>
            <button type="button" className="icon-btn" onClick={swap} aria-label="Swap start and destination" title="Swap">
              <Icon name="swap" />
            </button>
          </div>
          <div className="seg" role="group" aria-label="Prefer">
            {PREFERENCES.map(p => (
              <button key={p.value} type="button" aria-pressed={preference === p.value}
                onClick={() => { setPreference(p.value); if (result?.plan) plan(p.value); }}>
                {p.label}
              </button>
            ))}
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={!from || !to || loading}>
            {loading ? 'Finding journeys…' : 'Find journeys'}
          </button>
          {gps.status === 'denied' && <p className="small muted" style={{ margin: 0 }}>Location is blocked, so “My current location” is unavailable. Choose a stop or place instead.</p>}
        </form>

        <div ref={resultsRef} tabIndex={-1} aria-live="polite" style={{ outline: 'none' }}>
          {loading && !result && <SkeletonList rows={3} label="Finding journeys" />}
          {result?.error && (
            result.error.kind === 'not-found'
              ? <Notice tone="warn" role="alert">Journey planning isn&apos;t available on this server yet. Live buses and nearby arrivals still work.</Notice>
              : <ErrorState error={result.error} onRetry={() => plan()} what="journeys" />
          )}
          {result?.plan && result.plan.status !== 'OK' && (
            <Notice tone="info" role="alert">
              <p>{STATUS_TEXT[result.plan.status] ?? result.plan.message}</p>
              {result.plan.status === 'NO_CONNECTION' && <p>Try a nearby landmark or a major stop, or split the trip in two.</p>}
            </Notice>
          )}
          {options.length > 0 && (
            <section aria-label="Journey options" className="stack">
              <h2 className="section-title" style={{ margin: 0 }}>{plural(options.length, 'option')}</h2>
              {options.map((j, i) => (
                <JourneyCard key={i} journey={j} index={i} selected={i === selected} onSelect={() => setSelected(i)}
                  from={from} to={to} />
              ))}
              <AboutTimes notes={result.plan.notes} />
            </section>
          )}
        </div>
      </div>

      <section className="journey-map" aria-label="Map of the selected journey">
        <BaseMap>
          <UserMarker location={gps.location} />
          {journey && <JourneyLayer journey={journey} colors={colors} />}
          {from && <Marker position={[from.lat, from.lng]} icon={endPin('A', `Start: ${from.label}`)} zIndexOffset={1900} title={`Start: ${from.label}`} />}
          {to && <Marker position={[to.lat, to.lng]} icon={endPin('B', `Destination: ${to.label}`)} zIndexOffset={1900} title={`Destination: ${to.label}`} />}
          <FitBounds
            fitKey={journey ? `j|${selected}|${result?.plan?.options?.length}|${placeParam(from)}|${placeParam(to)}` : from && to ? `ends|${placeParam(from)}|${placeParam(to)}` : null}
            points={journey ? journeyPoints(journey) : [from, to].filter(Boolean).map(p => [p.lat, p.lng])}
          />
        </BaseMap>
      </section>
    </div>
  );
}

function JourneyCard({ journey, index, selected, onSelect, from, to }) {
  const buses = busSegments(journey);
  const id = `jr-${index}`;
  return (
    <article className="panel jr-card" data-selected={selected}>
      <button type="button" className="jr-summary" aria-expanded={selected} aria-controls={id} onClick={onSelect}>
        <span className="row" style={{ justifyContent: 'space-between' }}>
          <span className="jr-total">≈ {durationText(journey.totalMinutes)}</span>
          <span className="small muted">+ waiting for {plural(buses.length, 'bus', 'buses')}</span>
        </span>
        <span className="jr-legs" aria-label={`Buses: ${buses.map(b => b.routeNumber).join(', then ')}`}>
          <Icon name="walk" size={16} />
          {buses.map((b, i) => (
            <span key={i} className="row" style={{ gap: 6 }}>
              <Icon name="chevronRight" size={14} />
              <span className="route-chip">{b.routeNumber}</span>
            </span>
          ))}
          <Icon name="chevronRight" size={14} />
          <Icon name="walk" size={16} />
        </span>
        <span className="meta">
          <span>{journey.transfers === 0 ? 'Direct' : plural(journey.transfers, 'change')}</span>
          <span>Walk {distanceText(journey.walkMeters)}</span>
          <span>Ride {durationText(journey.rideMinutes)}</span>
        </span>
      </button>
      {selected && <Timeline id={id} journey={journey} from={from} to={to} />}
    </article>
  );
}

function Timeline({ id, journey, from, to }) {
  const segs = journey.segments;
  return (
    <ol className="timeline" id={id}>
      {segs.map((s, i) => {
        if (s.type === 'WALK') {
          const first = i === 0, last = i === segs.length - 1;
          const title = first ? `Walk to ${s.to.name}` : last ? `Walk to ${to?.label ?? 'your destination'}` : `Change: walk to ${s.to.name}`;
          if (s.meters < 5) return null; // same stop, or a start/end right at the stop
          return (
            <li key={i} className="tl-step tl-step-walk">
              <span className="tl-icon"><Icon name="walk" size={16} /></span>
              <div>
                <strong>{title}</strong>
                <p>{distanceText(s.meters)} · about {durationText(s.minutes)} ({BASIS_TEXT[s.basis]})</p>
              </div>
            </li>
          );
        }
        const intermediate = s.stops?.slice(1, -1) ?? [];
        return (
          <li key={i} className="tl-step tl-step-bus">
            <span className="tl-icon"><Icon name="bus" size={16} /></span>
            <div>
              <strong>
                <span className="route-chip" style={{ marginRight: 8 }}>{s.routeNumber}</span>
                {s.towards ? `towards ${s.towards}` : `Route ${s.routeNumber}`}
              </strong>
              <p>Board at <strong>{s.from.name}</strong></p>
              <p>Get off at <strong>{s.to.name}</strong> after {plural(s.stopCount, 'stop')} · about {durationText(s.minutes)} ({BASIS_TEXT[s.basis]})</p>
              {intermediate.length > 0 && (
                <details>
                  <summary className="small" style={{ cursor: 'pointer', minHeight: 32 }}>Show {plural(intermediate.length, 'stop')} in between</summary>
                  <ol className="tl-stops">{intermediate.map(st => <li key={st.id}>{st.name}</li>)}</ol>
                </details>
              )}
            </div>
          </li>
        );
      })}
      <li className="tl-step">
        <span className="tl-icon"><Icon name="pin" size={16} /></span>
        <div><strong>Arrive at {to?.label ?? 'destination'}</strong>{from && <p className="vh">Journey from {from.label}</p>}</div>
      </li>
    </ol>
  );
}

function AboutTimes({ notes }) {
  return (
    <details className="data-notes">
      <summary>How these journeys are worked out</summary>
      <ul>
        {(notes ?? []).map(n => <li key={n}>{n}</li>)}
        <li>Lines on the map join consecutive stops; they are not the exact road path.</li>
        <li>Step-free or wheelchair options aren&apos;t offered: the timetable has no verified accessibility information for these stops or buses.</li>
      </ul>
    </details>
  );
}

function JourneyLayer({ journey, colors }) {
  const parts = useMemo(() => journey.segments.map((s, i) => ({ s, i })), [journey]);
  return parts.map(({ s, i }) => {
    if (s.type === 'WALK') {
      return (
        <Polyline key={`w${i}`} positions={[[s.from.lat, s.from.lng], [s.to.lat, s.to.lng]]}
          pathOptions={{ color: colors.walk, weight: 3, dashArray: '2 8' }} interactive={false} />
      );
    }
    const line = s.stops.map(p => [p.lat, p.lng]);
    return [
      <Polyline key={`b${i}`} positions={line} pathOptions={{ color: colors.brand, weight: 6, opacity: 0.85 }} interactive={false} />,
      ...[s.from, s.to].map((p, k) => (
        <CircleMarker key={`b${i}-${k}`} center={[p.lat, p.lng]} radius={7}
          pathOptions={{ color: colors.brand, weight: 3, fillColor: colors.surface, fillOpacity: 1 }}>
          <Tooltip direction="top" offset={[0, -6]} permanent={false}>{k === 0 ? `Board ${s.routeNumber} at ${p.name}` : `Get off at ${p.name}`}</Tooltip>
        </CircleMarker>
      )),
    ];
  });
}
