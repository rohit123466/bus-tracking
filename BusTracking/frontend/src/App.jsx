import { BrowserRouter, Routes, Route } from 'react-router-dom';
import NavBar from './components/NavBar';
import WeatherAlertBanner from './components/WeatherAlertBanner';
import HomePage from './pages/HomePage';
import BusSearchPage from './pages/BusSearchPage';
import RouteDetailPage from './pages/RouteDetailPage';
import StopsNearbyPage from './pages/StopsNearbyPage';
import PassingNearMePage from './pages/PassingNearMePage';
import JourneyPlannerPage from './pages/JourneyPlannerPage';
import ReplayPage from './pages/ReplayPage';
import AdminDashboardPage from './pages/AdminDashboardPage';
import './App.css';

function App() {
  return (
    <BrowserRouter>
      <div className="app-shell">
        <NavBar />
        <WeatherAlertBanner />
        <div className="page-outlet">
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/search" element={<BusSearchPage />} />
            <Route path="/route" element={<RouteDetailPage />} />
            <Route path="/stops" element={<StopsNearbyPage />} />
            <Route path="/passing" element={<PassingNearMePage />} />
            <Route path="/journey" element={<JourneyPlannerPage />} />
            <Route path="/replay" element={<ReplayPage />} />
            <Route path="/admin" element={<AdminDashboardPage />} />
          </Routes>
        </div>
      </div>
    </BrowserRouter>
  );
}

export default App;
