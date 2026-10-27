import { Fragment } from 'react';
import Icon from '../../ui/Icon';
import { useAdmin, useAdminPolling } from '../adminContext';
import { countdownText } from '../../../utils/admin';
import { SocketPill } from '../AdminWidgets';
import usePolling from '../../../hooks/usePolling';
import { useConnection, useNow } from '../../../hooks/useStores';
import { getActuatorHealth, getFeedHealth, getLearningStatus } from '../../../api/client';
import { ageText, clockText, plural } from '../../../utils/format';

const TONE_TEXT = { ok: 'OK', warn: 'Warning', bad: 'Problem', unknown: 'Unknown' };
const API_TEXT = { online: 'Reachable', connecting: 'Connecting…', waking: 'Waking up', reconnecting: 'Reconnecting', offline: 'Browser offline' };

function Card({ title, tone, children, icon }) {
  return (
    <section className={`panel health-card health-${tone}`} aria-labelledby={`h-${title}`}>
      <div className="panel-head">
        <Icon name={icon} size={18} />
        <h2 id={`h-${title}`}>{title}</h2>
        <span className={`badge health-badge-${tone}`}>{TONE_TEXT[tone]}</span>
      </div>
      <div className="panel-pad">{children}</div>
    </section>
  );
}

const when = (epochSeconds, now) => (epochSeconds ? `${ageText(epochSeconds, now)} (${clockText(epochSeconds)})` : 'Never');

export default function HealthSection() {
  const { socket, positions, expiresAt } = useAdmin();
  const connection = useConnection();
  const now = useNow(1000);
  const feed = useAdminPolling('feed-health', getFeedHealth, { interval: 15000, keepPrevious: true });
  const learning = usePolling('learning-status', getLearningStatus, { interval: 60000, keepPrevious: true });
  const actuator = usePolling('actuator-health', getActuatorHealth, { interval: 30000, keepPrevious: true });

  const f = feed.data;
  const feedTone = !f ? (feed.error ? 'bad' : 'unknown') : f.healthy ? 'ok' : f.lastFetchAt ? 'warn' : 'bad';
  const socketTone = socket.status === 'connected' ? 'ok' : socket.status === 'connecting' ? 'warn' : 'bad';
  const apiTone = connection.state === 'online' ? 'ok' : connection.state === 'offline' ? 'bad' : 'warn';
  const act = actuator.data;
  const actTone = act ? (act.status === 'UP' ? 'ok' : 'bad') : 'unknown';
  const l = learning.data;
  const sessionLeft = expiresAt ? expiresAt - now * 1000 : null;

  return (
    <div className="admin-pad">
      <div className="health-grid">
        <Card title="Live feed (GTFS-realtime)" tone={feedTone} icon="activity">
          {!f && feed.error && <p className="small">Couldn&apos;t read feed health: {feed.error.message}</p>}
          {!f && !feed.error && <p className="small muted">Loading…</p>}
          {f && (
            <dl className="admin-facts">
              <dt>Last successful poll</dt><dd>{when(f.lastFetchAt, now)}</dd>
              <dt>Poll interval</dt><dd>{(f.pollIntervalMs / 1000).toFixed(0)} s (unchanged feeds answer 304)</dd>
              <dt>Vehicles in feed</dt><dd>{f.vehicleCount} · {f.freshVehicleCount} with a fix under 2 min old</dd>
              <dt>Snapshot generation</dt><dd className="mono">{f.generation}</dd>
              <dt>Last published</dt><dd>{f.lastPublishAt ? `${plural(f.lastPublishCount, 'bus', 'buses')} ${ageText(f.lastPublishAt, now)}` : 'Nothing yet'}</dd>
              <dt>Current problem</dt><dd>{f.feedError ?? 'None'}</dd>
              <dt>Last error</dt><dd>{f.lastError ? <>{f.lastError} <span className="muted">— {when(f.lastErrorAt, now)}</span></> : 'None since start-up'}</dd>
            </dl>
          )}
        </Card>

        <Card title="WebSocket (/topic/buses)" tone={socketTone} icon="bell">
          <p><SocketPill status={socket.status} /></p>
          <dl className="admin-facts">
            <dt>Last push</dt><dd>{socket.lastMessageAt ? ageText(socket.lastMessageAt / 1000, now) : 'None yet'}</dd>
            <dt>Buses tracked here</dt><dd>{Object.keys(positions).length} (pushes plus the fleet snapshot)</dd>
            <dt>Fallback</dt><dd>While the socket is down, the live map polls the fleet list every 10 s.</dd>
          </dl>
        </Card>

        <Card title="API" tone={apiTone} icon="monitor">
          <dl className="admin-facts">
            <dt>State</dt><dd>{API_TEXT[connection.state] ?? connection.state}</dd>
            <dt>Last answer</dt><dd>{connection.lastOkAt ? ageText(connection.lastOkAt / 1000, now) : 'None yet'}</dd>
            <dt>Session</dt><dd>{sessionLeft == null ? 'Expiry unknown' : `Ends ${clockText(expiresAt)} (${countdownText(sessionLeft)} left)`}</dd>
          </dl>
        </Card>

        <Card title="Server (actuator)" tone={actTone} icon="activity">
          {act && (
            <dl className="admin-facts">
              <dt>Status</dt><dd>{act.status}</dd>
              {act.components && Object.entries(act.components).map(([name, c]) => (
                <Fragment key={name}><dt>{name}</dt><dd>{c.status}</dd></Fragment>
              ))}
            </dl>
          )}
          {!act && actuator.error && (
            <p className="small">
              Not readable from this page. /actuator/health is outside /api, so the server&apos;s CORS rules don&apos;t cover it
              when the console runs on another origin. Open it directly to check.
            </p>
          )}
          {!act && !actuator.error && <p className="small muted">Loading…</p>}
        </Card>

        <Card title="Route learning" tone={l ? 'ok' : learning.error ? 'bad' : 'unknown'} icon="route">
          {l ? (
            <>
              <dl className="admin-facts">
                <dt>Routes learned</dt><dd>{l.learnedRoutes}</dd>
                <dt>Running now</dt><dd>{l.routesRunningNow} routes · {l.learnedOfRunning} of them learned</dd>
                <dt>Recording</dt><dd>{plural(l.vehiclesRecording, 'vehicle')}</dd>
                <dt>Since</dt><dd>{when(l.startedAt, now)}</dd>
              </dl>
              {l.note && <p className="small muted">{l.note}</p>}
            </>
          ) : (
            <p className="small muted">{learning.error ? `Couldn't load: ${learning.error.message}` : 'Loading…'}</p>
          )}
        </Card>
      </div>
      <p className="small muted" style={{ marginTop: 12 }}>
        The static GTFS timetable is from mid-2024, so arrival estimates are often unavailable; that is a data gap, not a fault.
      </p>
    </div>
  );
}
