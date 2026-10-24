import Icon from './Icon';

const NOTICE_ICON = { warn: 'alert', error: 'alert', info: 'info' };

export function Notice({ tone = 'info', children, role, className = '' }) {
  return (
    <div className={`notice notice-${tone} ${className}`} role={role}>
      <Icon name={NOTICE_ICON[tone] ?? 'info'} size={18} />
      <div>{children}</div>
    </div>
  );
}

export function EmptyState({ icon = 'info', title, children, action }) {
  return (
    <div className="empty">
      <span className="empty-icon"><Icon name={icon} /></span>
      {title && <h3>{title}</h3>}
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

const ERROR_TITLE = {
  offline: 'You are offline',
  timeout: 'The server is slow to respond',
  unreachable: "Can't reach the server",
  starting: 'The server is starting up',
  'not-found': 'Not available',
  'bad-request': 'Request not accepted',
  server: 'Server problem',
};

/** A failed load, with what happened and a retry button. `error` comes from classifyError(). */
export function ErrorState({ error, onRetry, what = 'this information' }) {
  if (!error) return null;
  const automatic = error.retryable && error.kind !== 'offline';
  return (
    <div className="empty" role="alert">
      <span className="empty-icon"><Icon name={error.kind === 'offline' ? 'wifiOff' : 'alert'} /></span>
      <h3>{ERROR_TITLE[error.kind] ?? 'Something went wrong'}</h3>
      <p>
        Couldn&apos;t load {what}. {error.message}
        {automatic && ' Retrying automatically.'}
      </p>
      {onRetry && (
        <button type="button" className="btn btn-sm" onClick={onRetry}>
          <Icon name="refresh" size={16} /> Try again
        </button>
      )}
    </div>
  );
}

/** Placeholder rows while the first response is loading. */
export function SkeletonList({ rows = 3, label = 'Loading' }) {
  return (
    <div role="status" aria-label={label} className="stack" style={{ padding: '4px 0' }}>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="row" style={{ gap: 12 }}>
          <span className="sk" style={{ width: 44, height: 28 }} />
          <span className="grow stack" style={{ gap: 6 }}>
            <span className="sk" style={{ width: `${70 - i * 10}%`, height: 14 }} />
            <span className="sk" style={{ width: '40%', height: 12 }} />
          </span>
        </div>
      ))}
    </div>
  );
}
