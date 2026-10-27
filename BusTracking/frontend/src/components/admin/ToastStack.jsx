import Icon from '../ui/Icon';

const TONE_ICON = { success: 'check', warn: 'alert', error: 'alert', info: 'info' };

/** Admin notifications, newest last. Announced politely; each can be dismissed. */
export default function ToastStack({ toasts, onDismiss }) {
  return (
    <div className="toasts admin-toasts" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast admin-toast admin-toast-${t.tone}`}>
          <Icon name={TONE_ICON[t.tone] ?? 'info'} size={18} />
          <div className="grow">
            <strong>{t.title}</strong>
            {t.text && <p>{t.text}</p>}
          </div>
          <button type="button" className="icon-btn admin-toast-close" aria-label={`Dismiss: ${t.title}`} onClick={() => onDismiss(t.id)}>
            <Icon name="x" size={16} />
          </button>
        </div>
      ))}
    </div>
  );
}
