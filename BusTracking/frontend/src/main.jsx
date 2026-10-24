import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import 'leaflet/dist/leaflet.css'
import './index.css'
import './styles/components.css'
import './styles/pages.css'
import { initTheme } from './store/theme'
import { initLanguage } from './i18n'
import App from './App.jsx'

initTheme()
initLanguage()

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
