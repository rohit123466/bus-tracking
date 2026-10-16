import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import NavBar from './components/NavBar';
import ErrorBoundary from './components/ErrorBoundary';
import NotFoundPage from './pages/NotFoundPage';
const HomePage = lazy(() => import('./pages/HomePage'));
const BusSearchPage = lazy(() => import('./pages/BusSearchPage'));
const RouteDetailPage = lazy(() => import('./pages/RouteDetailPage'));
const StopsNearbyPage = lazy(() => import('./pages/StopsNearbyPage'));
const PassingNearMePage = lazy(() => import('./pages/PassingNearMePage'));
const JourneyPlannerPage = lazy(() => import('./pages/JourneyPlannerPage'));
const ReplayPage = lazy(() => import('./pages/ReplayPage'));
const AdminDashboardPage = lazy(() => import('./pages/AdminDashboardPage'));
const AccessibilityPage = lazy(() => import('./pages/AccessibilityPage'));
const SeatAvailabilityPage = lazy(() => import('./pages/SeatAvailabilityPage'));
const ConductorPage = lazy(() => import('./pages/ConductorPage'));
import './App.css';

function App() {
  return (
    <BrowserRouter>
      <div className="app-shell">
        <NavBar />
        <div className="page-outlet">
          <ErrorBoundary><Suspense fallback={<p role="status">Loading…</p>}><Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/accessibility" element={<AccessibilityPage />} />
            <Route path="/search" element={<BusSearchPage />} />
            <Route path="/route" element={<RouteDetailPage />} />
            <Route path="/stops" element={<StopsNearbyPage />} />
            <Route path="/passing" element={<PassingNearMePage />} />
            <Route path="/journey" element={<JourneyPlannerPage />} />
            <Route path="/replay" element={<ReplayPage />} />
            <Route path="/seats" element={<SeatAvailabilityPage />} />
            <Route path="/conductor" element={<ConductorPage />} />
            <Route path="/admin" element={<AdminDashboardPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes></Suspense></ErrorBoundary>
        </div>
      </div>
    </BrowserRouter>
  );
}

export default App;
