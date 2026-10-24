import { useEffect, useRef, useState } from 'react';
import Combobox from '../ui/Combobox';
import useSearch from '../../hooks/useSearch';
import { searchPlaces, searchTransitStops } from '../../api/client';

const MY_LOCATION = { id: 'me', label: 'My current location', icon: 'locate', group: 'Your location' };

/**
 * Start/destination picker. Suggests bus stops from the transit data first, then named places from
 * OpenStreetMap (clearly grouped), plus "My current location".
 */
export default function PlaceField({ label, place, onChange, gps, saved = [] }) {
  const [text, setText] = useState(place?.label ?? '');
  // Follow the chosen place (selection, swap, URL) but not the reset caused by typing.
  const typed = useRef(false);
  useEffect(() => {
    if (typed.current) { typed.current = false; return; }
    setText(place?.label ?? '');
  }, [place]);

  const stops = useSearch(text, (t, s) => searchTransitStops(t, '', s), { minChars: 2 });
  const places = useSearch(text, (t, s) => searchPlaces(t, 4, s), { minChars: 3, delay: 600 });
  const typing = place?.label !== text;

  const options = [
    ...(gps.status !== 'unsupported' ? [MY_LOCATION] : []),
    ...saved.map(p => ({ id: `saved-${p.key}`, label: p.label, icon: p.icon, group: 'Saved places', place: p })),
    ...(typing ? stops.results.slice(0, 6).map(s => ({
      id: `stop-${s.id}`, label: s.name, sub: `Bus stop · ID ${s.id}`, icon: 'pin', group: 'Bus stops',
      place: { lat: s.lat, lng: s.lng, label: s.name, kind: 'stop', stopId: s.id },
    })) : []),
    ...(typing ? places.results.slice(0, 4).map((p, i) => ({
      id: `osm-${i}-${p.lat}`, label: p.label.split(',').slice(0, 2).join(','), sub: p.label, icon: 'map',
      group: 'Places (OpenStreetMap)', place: { lat: p.lat, lng: p.lng, label: p.label.split(',')[0], kind: 'place' },
    })) : []),
  ];

  const select = async (option) => {
    if (option.id === 'me') {
      setText('Finding your location…');
      const fix = gps.location ?? await gps.locate();
      if (fix) onChange({ lat: fix.lat, lng: fix.lng, label: 'My location', kind: 'me' });
      else { setText(''); onChange(null); }
      return;
    }
    onChange(option.place);
  };

  const failed = stops.error && places.error;
  return (
    <Combobox
      label={label}
      placeholder="Bus stop, place or address"
      icon="pin"
      value={text}
      minChars={0}
      onInputChange={v => { setText(v); if (place) { typed.current = true; onChange(null); } }}
      options={options}
      loading={typing && (stops.loading || places.loading)}
      status={failed ? 'Search is unavailable right now. Try again.' : text.trim().length >= 2 ? 'No matching stop or place.' : null}
      onSelect={select}
    />
  );
}
