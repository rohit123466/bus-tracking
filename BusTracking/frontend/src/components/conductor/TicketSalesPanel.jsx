import { fmtClock, money } from './consoleFormat';

/** D. Ticket sales totals for the bus's current trip, from the live backend view. */
export default function TicketSalesPanel({ live }) {
  if (!live) return null;
  return (
    <section className="t-card">
      <h3 className="t-card-title">Ticket sales</h3>
      <div className="cc-metrics">
        <div className="cc-metric"><span>Tickets sold</span><strong>{live.ticketsSold}</strong></div>
        <div className="cc-metric"><span>Collected</span><strong>{money(live.fareCollected, live.currency)}</strong></div>
        <div className="cc-metric"><span>Active</span><strong>{live.activeTickets}</strong></div>
        <div className="cc-metric"><span>Completed</span><strong>{live.completedTickets}</strong></div>
      </div>
      <p className="muted small">
        {live.lastTicketNumber
          ? `Last ticket #${live.lastTicketNumber} at ${fmtClock(live.lastTicketAt)}`
          : 'No tickets issued on this trip yet.'}
        {live.cancelledTickets > 0 && ` · ${live.cancelledTickets} cancelled`}
      </p>
    </section>
  );
}
