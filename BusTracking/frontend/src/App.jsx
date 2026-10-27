import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Routes, Route, useLocation } from 'react-router-dom';
import AppShell from './components/AppShell';
import ArrivalWatcher from './components/ArrivalWatcher';
import ErrorBoundary from './components/ErrorBoundary';
import NotFoundPage from './pages/NotFoundPage';
const ExplorePage = lazy(() => import('./pages/ExplorePage'));
const NearbyPage = lazy(() => import('./pages/NearbyPage'));
const JourneyPage = lazy(() => import('./pages/JourneyPage'));
const SavedPage = lazy(() => import('./pages/SavedPage'));
const BusSearchPage = lazy(() => import('./pages/BusSearchPage'));
const RouteDetailPage = lazy(() => import('./pages/RouteDetailPage'));
const PassingNearMePage = lazy(() => import('./pages/PassingNearMePage'));
const ReplayPage = lazy(() => import('./pages/ReplayPage'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));
const AccessibilityPage = lazy(() => import('./pages/AccessibilityPage'));
const SeatAvailabilityPage = lazy(() => import('./pages/SeatAvailabilityPage'));
const ConductorPage = lazy(() => import('./pages/ConductorPage'));
import './App.css';

// Pages that lay out their own full-height panes (map + side panel) instead of scrolling as a whole.
// The admin console (/admin/*) does too: sidebar plus its own scrolling body.
const FILL_PAGES = new Set(['/', '/journey']);

// The classic tool pages predate the theme tokens and assume light colours.
const Classic = ({ children }) => (
  <div className="legacy-page" data-theme="light">{children}</div>
);

function Shell() {
  const { pathname } = useLocation();
  return (
    <AppShell fill={FILL_PAGES.has(pathname) || pathname.startsWith('/admin')}>
      <ErrorBoundary>
        <Suspense fallback={<p role="status" style={{ padding: 16 }}>Loading…</p>}>
          <Routes>
            <Route path="/" element={<ExplorePage />} />
            <Route path="/nearby" element={<NearbyPage />} />
            <Route path="/stops" element={<Navigate to="/nearby" replace />} />
            <Route path="/journey" element={<JourneyPage />} />
            <Route path="/saved" element={<SavedPage />} />
            <Route path="/accessibility" element={<Classic><AccessibilityPage /></Classic>} />
            <Route path="/search" element={<Classic><BusSearchPage /></Classic>} />
            <Route path="/route" element={<Classic><RouteDetailPage /></Classic>} />
            <Route path="/passing" element={<Classic><PassingNearMePage /></Classic>} />
            <Route path="/replay" element={<Classic><ReplayPage /></Classic>} />
            <Route path="/seats" element={<Classic><SeatAvailabilityPage /></Classic>} />
            <Route path="/conductor" element={<Classic><ConductorPage /></Classic>} />
            <Route path="/admin/:section?" element={<AdminDashboardPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </Suspense>
      </ErrorBoundary>
    </AppShell>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Shell />
      <ArrivalWatcher />
    </BrowserRouter>
  );
}
