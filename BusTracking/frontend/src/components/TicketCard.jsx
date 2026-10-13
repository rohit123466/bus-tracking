import { categoryIcon } from '../utils/passengerCategories';
import './Ticketing.css';

const STATUS_BADGE = {
  ACTIVE: { cls: 'badge--green', label: 'Active' },
  COMPLETED: { cls: 'badge--gray', label: 'Completed' },
  CANCELLED: { cls: 'badge--red', label: 'Cancelled' },
};

/**
 * TicketCard — displays a single ticket including demo QR code (Feature 14).
 * The QR code is a DEMO feature only and is not accepted by any official system.
 */
export default function TicketCard({ ticket, onComplete, onCancel, busy, highlight }) {
  const status = STATUS_BADGE[ticket.status];
  const accessible = ticket.passengerCategory === 'DIVYANGJAN';
  return (
    <article
      className={
        'ticket-card' +
        (accessible ? ' ticket-card--access' : '') +
        (highlight ? ' ticket-card--new' : '') +
        (ticket.status !== 'ACTIVE' ? ' ticket-card--done' : '')
      }
    >
      <header className="ticket-card-head">
        <span className="ticket-no">Ticket #{ticket.ticketNumber}</span>
        <span className={`badge ${status.cls}`}>{status.label}</span>
        {ticket.onBoardNow && <span className="badge badge--blue">On board</span>}
      </header>
      <div className="ticket-card-body">
        <div className="ticket-seat" title={ticket.seatType === 'WHEELCHAIR' ? 'Wheelchair space' : 'Seat'}>
          {ticket.seatType === 'WHEELCHAIR' ? '♿' : ticket.seatNumber}
          <small>{ticket.seatType === 'WHEELCHAIR' ? ticket.seatNumber : ticket.seatType === 'PRIORITY' ? 'priority' : 'seat'}</small>
        </div>
        <div className="ticket-info">
          <strong>
            {categoryIcon(ticket.passengerCategory)} {ticket.passengerName}
          </strong>
          <span className="ticket-route">
            {ticket.fromStop} → {ticket.toStop}
          </span>
          <span className={'ticket-type' + (accessible ? ' ticket-type--access' : '')}>{ticket.ticketTypeLabel}</span>
          {ticket.alightedAtStop && <span className="muted small">Got down at {ticket.alightedAtStop}</span>}
        </div>
        <div className="ticket-fare">
          ₹{ticket.fare}
          <small>{ticket.fareLabel}</small>
        </div>
      </div>
      {ticket.status === 'ACTIVE' && (onComplete || onCancel) && (
        <footer className="ticket-card-actions">
          {onComplete && (
            <button type="button" disabled={busy} onClick={() => onComplete(ticket)}>
              ✅ Got down – complete journey
            </button>
          )}
          {onCancel && (
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => onCancel(ticket)}>
              Cancel ticket
            </button>
          )}
        </footer>
      )}

      {/* Demo QR Code (Feature 14) */}
      {ticket.qrCodeBase64 && ticket.status === 'ACTIVE' && (
        <DemoQR payload={ticket.qrCodeBase64} disclaimer={ticket.qrDisclaimer} />
      )}
    </article>
  );
}

/**
 * DemoQR — renders a scannable QR-like visual for a ticket payload.
 * Uses a CSS grid pattern as a lightweight visual placeholder since
 * we have no QR library dependency. In production, a real QR library
 * (e.g. qrcode.react) should be used.
 */
function DemoQR({ payload, disclaimer }) {
  return (
    <details className="qr-demo-section">
      <summary className="qr-demo-toggle">🎟️ Show Demo QR</summary>
      <div className="qr-demo-body">
        <div className="qr-demo-visual" aria-label="Demo QR code pattern" role="img">
          <code className="qr-payload">{payload}</code>
        </div>
        <p className="qr-disclaimer">{disclaimer}</p>
      </div>
    </details>
  );
}
