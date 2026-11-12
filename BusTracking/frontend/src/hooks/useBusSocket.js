import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { WS_BASE } from '../api/client';

// Frames arrive one per bus, hundreds per feed poll; they are applied in one render per batch.
const FLUSH_MS = 250;

// Subscribes to /topic/buses (see WebSocketConfig + BusLocationIngestService
// on the backend) and keeps a live vehicleId -> latest event map, so any
// screen can show positions pushed in real time instead of polling.
// `status`: connecting (never connected yet), connected, or reconnecting (lost; retrying).
export default function useBusSocket({ enabled = true } = {}) {
  const [positions, setPositions] = useState({});
  const [connected, setConnected] = useState(false);
  const [status, setStatus] = useState('connecting');
  const [lastMessageAt, setLastMessageAt] = useState(null);
  const clientRef = useRef(null);

  useEffect(() => {
    if (!enabled) return undefined;

    let pending = {};
    let timer = null;
    let everConnected = false;
    // A replaced client still reports its own close after cleanup; it must not mark the new one down.
    let disposed = false;
    const hidden = () => typeof document !== 'undefined' && document.hidden;
    const flush = () => {
      timer = null;
      // A hidden tab draws nothing: frames keep collecting (latest per bus) and are applied in
      // one go when the tab is shown again, instead of re-rendering the map unseen 4 times a second.
      if (hidden()) return;
      const batch = pending;
      pending = {};
      setPositions((prev) => ({ ...prev, ...batch }));
      setLastMessageAt(Date.now());
    };
    const shown = () => {
      if (!hidden() && !timer && Object.keys(pending).length) flush();
    };
    document.addEventListener('visibilitychange', shown);
    const lost = () => {
      if (disposed) return;
      setConnected(false);
      setStatus(everConnected ? 'reconnecting' : 'connecting');
    };

    const stompClient = new Client({
      webSocketFactory: () => new SockJS(WS_BASE),
      reconnectDelay: 4000,
      onConnect: () => {
        everConnected = true;
        setConnected(true);
        setStatus('connected');
        stompClient.subscribe('/topic/buses', (message) => {
          try {
            const event = JSON.parse(message.body);
            pending[event.vehicleId] = event;
            if (!timer) timer = setTimeout(flush, FLUSH_MS);
          } catch {
            // ignore malformed frame
          }
        });
      },
      onDisconnect: lost,
      onWebSocketClose: lost,
    });

    clientRef.current = stompClient;
    stompClient.activate();

    return () => {
      disposed = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', shown);
      stompClient.deactivate();
    };
  }, [enabled]);

  return { positions, connected, status, lastMessageAt };
}
