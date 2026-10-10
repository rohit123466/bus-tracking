export function distance(a, b) {
  const rad = n => n * Math.PI / 180;
  const h = Math.sin(rad(b.lat - a.lat) / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(rad(b.lng - a.lng) / 2) ** 2;
  return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}
export function progress(coordinates, location) {
  let offset = Infinity, along = 0, total = 0, segment = 0;
  for (let i = 1; i < coordinates.length; i++) {
    const a = { lat: coordinates[i - 1][0], lng: coordinates[i - 1][1] };
    const b = { lat: coordinates[i][0], lng: coordinates[i][1] };
    const scale = Math.cos(location.lat * Math.PI / 180);
    const x = (b.lng - a.lng) * scale, y = b.lat - a.lat;
    const t = Math.max(0, Math.min(1, (x * x + y * y) ? ((location.lng - a.lng) * scale * x + (location.lat - a.lat) * y) / (x * x + y * y) : 0));
    const length = distance(a, b);
    const d = distance(location, { lat: a.lat + t * (b.lat - a.lat), lng: a.lng + t * (b.lng - a.lng) });
    if (d < offset) { offset = d; along = total + t * length; segment = i - 1; }
    total += length;
  }
  return { offset, remaining: Math.max(0, total - along), segment, total };
}
export function visibleBuses(buses, routeNumber, now = Date.now() / 1000) {
  return [...new Map(buses.filter(b => b.vehicleId && (!routeNumber || b.routeCode === routeNumber) &&
    Number.isFinite(b.busLatitude) && Number.isFinite(b.busLongitude) &&
    b.lastUpdatedEpochSeconds > 0 && now - b.lastUpdatedEpochSeconds <= 120 && now - b.lastUpdatedEpochSeconds >= -30)
    .map(b => [b.vehicleId, b])).values()];
}
