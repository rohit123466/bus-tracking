import './Ticketing.css';

const fmtTime = (iso) => (iso ? new Date(iso).toLocaleTimeString() : '—');

/**
 * Camera-observed occupancy shown next to (never merged with) ticket-based
 * occupancy. Data is simulated in this project and labelled as such.
 */
export default function CameraMonitorPanel({ report, onScan, onReport, busy }) {
  if (!report) return null;

  if (!report.cameraInstalled) {
    return (
      <section className="t-card">
        <h3 className="t-card-title">📷 Camera Occupancy Monitoring</h3>
        <p className="camera-status">
          <span className="status-dot status-dot--off" /> No occupancy camera installed on {report.busNumber}
        </p>
        <p className="muted small">Occupancy for this bus comes from tickets only.</p>
      </section>
    );
  }

  return (
    <section className="t-card">
      <div className="t-card-row">
        <h3 className="t-card-title">
          📷 Camera Occupancy Monitoring {report.simulated && <span className="demo-badge">DEMO · SIMULATED</span>}
        </h3>
        <button type="button" className="btn-primary" disabled={busy} onClick={onScan}>
          📸 Simulate Camera Scan
        </button>
      </div>
      {report.disclaimer && <p className="disclaimer">{report.disclaimer}</p>}

      <div className="camera-meta">
        <span className="camera-status">
          <span className={'status-dot' + (report.cameraStatus === 'CONNECTED' ? ' status-dot--on' : ' status-dot--off')} />
          {report.cameraStatus === 'CONNECTED' ? 'Connected' : report.cameraStatus}
        </span>
        <span>Last scan: {fmtTime(report.lastScanAt)}</span>
        <span>Scan #{report.scanNumber || '—'}{report.scanTakenAtStop ? ` at ${report.scanTakenAtStop}` : ''}</span>
        <span>
          Camera sees {report.cameraOccupied} occupied · Tickets say {report.ticketOccupied} on board
        </span>
      </div>
      {report.scanStale && (
        <p className="warn-note">
          ⏱️ The bus has moved to {report.currentStop} since this scan. Run a new scan to compare against the current
          tickets.
        </p>
      )}

      {report.mismatches.length > 0 ? (
        <ul className="mismatch-list">
          {report.mismatches.map((m) => (
            <li key={m.seatNumber + m.type} className="mismatch">
              ⚠️ {m.message}
            </li>
          ))}
        </ul>
      ) : (
        report.scanNumber > 0 && <p className="ok-note">✅ Camera view matches ticket occupancy.</p>
      )}

      {report.observations.length > 0 && (
        <div className="table-scroll">
          <table className="data-table camera-table">
            <thead>
              <tr>
                <th>Seat</th>
                <th>Ticket says</th>
                <th>Camera sees</th>
                <th>Confidence</th>
                <th aria-label="Correct reading" />
              </tr>
            </thead>
            <tbody>
              {report.observations.map((o) => (
                <tr key={o.seatNumber} className={o.mismatch ? 'row-mismatch' : undefined}>
                  <td>{o.seatType === 'WHEELCHAIR' ? `♿ ${o.seatNumber}` : o.seatNumber}</td>
                  <td>{o.ticketStatus === 'OCCUPIED' ? `#${o.ticketNumber}` : 'No ticket'}</td>
                  <td>
                    <span className={'badge ' + (o.cameraStatus === 'OCCUPIED' ? 'badge--red' : 'badge--green')}>
                      {o.cameraStatus === 'OCCUPIED' ? 'Occupied' : 'Empty'}
                    </span>
                  </td>
                  <td title={`Source: ${o.source}`}>
                    {Math.round(o.confidence * 100)}%{' '}
                    <span className="muted small">{o.source === 'SIMULATED' ? 'sim' : o.source.toLowerCase()}</span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn-ghost btn-small"
                      disabled={busy}
                      title="Correct the camera reading for this seat"
                      onClick={() => onReport(o.seatNumber, o.cameraStatus === 'OCCUPIED' ? 'EMPTY' : 'OCCUPIED')}
                    >
                      Mark {o.cameraStatus === 'OCCUPIED' ? 'empty' : 'occupied'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
