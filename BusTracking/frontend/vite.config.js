import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: "0.0.0.0",
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/ws": { target: "http://127.0.0.1:8080", ws: true },
    },
  },
  // sockjs-client assumes a Node-style `global` (used for its WebSocket
  // feature-detection); Vite's browser build doesn't provide one.
  define: {
    global: 'globalThis',
  },
})
