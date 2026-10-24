import { useCallback, useEffect, useState } from 'react';

const supported = typeof window !== 'undefined' && 'Notification' in window;

/**
 * Browser notification permission. Never asks on its own: request() must run from a user action.
 * permission: 'unsupported' | 'default' | 'granted' | 'denied'.
 */
export default function useNotificationPermission() {
  const [permission, setPermission] = useState(supported ? Notification.permission : 'unsupported');

  // The user can change it in site settings while the app is open.
  useEffect(() => {
    if (!supported) return undefined;
    let status;
    const sync = () => setPermission(Notification.permission);
    navigator.permissions?.query({ name: 'notifications' })
      .then(s => { status = s; s.addEventListener?.('change', sync); })
      .catch(() => {});
    window.addEventListener('focus', sync);
    return () => {
      status?.removeEventListener?.('change', sync);
      window.removeEventListener('focus', sync);
    };
  }, []);

  const request = useCallback(async () => {
    if (!supported) return 'unsupported';
    if (Notification.permission !== 'default') { setPermission(Notification.permission); return Notification.permission; }
    try {
      const result = await Notification.requestPermission();
      setPermission(result);
      return result;
    } catch {
      return Notification.permission;
    }
  }, []);

  return { supported, permission, request };
}

export const NOTIFICATION_TEXT = {
  unsupported: 'This browser cannot show notifications. Alerts will appear inside the app while it is open.',
  denied: 'Notifications are blocked for this site. Alerts will appear inside the app only; allow notifications in your browser’s site settings to get them outside the tab.',
  default: 'Your browser will ask for permission to show notifications.',
  granted: 'Notifications are on.',
};
