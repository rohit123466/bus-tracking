import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export const WS_BASE = API_BASE.replace(/\/api\/?$/, '') + '/ws';

const client = axios.create({ baseURL: API_BASE });

const ADMIN_TOKEN_KEY = 'dtc_admin_token';

client.interceptors.request.use((config) => {
  if (config.url?.startsWith('/admin')) {
    const token = localStorage.getItem(ADMIN_TOKEN_KEY);
    if (token) config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const getAdminToken = () => localStorage.getItem(ADMIN_TOKEN_KEY);
export const setAdminToken = (token) => localStorage.setItem(ADMIN_TOKEN_KEY, token);
export const clearAdminToken = () => localStorage.removeItem(ADMIN_TOKEN_KEY);

export const getNearbyBuses = (lat, lng, radiusMeters = 5000, limit = 30) =>
  client
    .get('/buses/nearby', { params: { lat, lng, radiusMeters, limit } })
    .then((res) => res.data);

export const getBusByVehicleId = (vehicleId) =>
  client.get(`/buses/${encodeURIComponent(vehicleId)}`).then((res) => res.data);

export const getBusesPassingNear = (lat, lng, radiusMeters = 1000, limit = 20) =>
  client
    .get('/buses/passing-near', { params: { lat, lng, radiusMeters, limit } })
    .then((res) => res.data);

export const getBusHistory = (vehicleId) =>
  client.get(`/buses/${encodeURIComponent(vehicleId)}/history`).then((res) => res.data);

export const getRoutes = () => client.get('/routes').then((res) => res.data);

export const searchRoutes = (query, limit = 8) =>
  client
    .get('/routes/search', { params: { query, limit } })
    .then((res) => res.data);

export const getRouteDetail = (routeId, vehicleId) =>
  client
    .get(`/routes/${routeId}/detail`, { params: vehicleId ? { vehicleId } : {} })
    .then((res) => res.data);

// Stops are paginated on the backend; pull a large page once so routes/stop
// clicks can be resolved to lat/lng entirely on the client.
export const getAllStops = (size = 1000) =>
  client.get('/stops', { params: { page: 0, size } }).then((res) => res.data.content);

export const getNearbyStops = (lat, lng, radiusMeters = 5000, limit = 20) =>
  client
    .get('/stops/nearby', { params: { lat, lng, radiusMeters, limit } })
    .then((res) => res.data);

export const getRoutesServingStop = (stopId) =>
  client.get(`/stops/${stopId}/routes`).then((res) => res.data);

export const planJourney = (sourceLat, sourceLng, destinationLat, destinationLng) =>
  client
    .post('/journey/plan', { sourceLat, sourceLng, destinationLat, destinationLng })
    .then((res) => res.data);

const NOMINATIM_URL = 'https://nominatim.openstreetmap.org/search';
// Biases geocoding results toward Delhi/NCR, the region this tracker covers.
const DELHI_VIEWBOX = '76.70,28.95,77.55,28.30';

// Place-name search for the journey planner, backed by OpenStreetMap's public
// Nominatim geocoder (no backend involvement needed for this).
export const searchPlaces = (query, limit = 5) =>
  axios
    .get(NOMINATIM_URL, {
      params: {
        q: query,
        format: 'jsonv2',
        limit,
        viewbox: DELHI_VIEWBOX,
        bounded: 1,
        countrycodes: 'in',
      },
    })
    .then((res) =>
      res.data.map((place) => ({
        label: place.display_name,
        lat: Number(place.lat),
        lng: Number(place.lon),
      }))
    );

export const adminLogin = (username, password) =>
  client.post('/auth/login', { username, password }).then((res) => res.data);

export const getAdminStats = () => client.get('/admin/stats').then((res) => res.data);

export const getWeatherAlert = () => client.get('/weather/alert').then((res) => res.data);

export default client;
