import { useCallback, useState } from 'react';
import { clearAdminToken, getAdminToken } from '../api/client';
import AdminLoginForm from '../components/AdminLoginForm';
import AdminConsole from '../components/admin/AdminConsole';
import { tokenExpiry } from '../utils/admin';
import '../styles/admin.css';

// A token that expired while the tab was closed is dropped before any request is made with it.
function initialSession() {
  const token = getAdminToken();
  const expiresAt = token ? tokenExpiry(token) : null;
  if (token && expiresAt && expiresAt <= Date.now()) {
    clearAdminToken();
    return { token: null, notice: 'Your session expired. Log in again to continue.' };
  }
  return { token, notice: null };
}

/** /admin/:section? — staff login, then the operations console. */
export default function AdminDashboardPage() {
  const [session, setSession] = useState(initialSession);

  const logout = useCallback((notice = null) => {
    clearAdminToken();
    setSession({ token: null, notice });
  }, []);

  if (!session.token) {
    return (
      <div className="admin-login">
        <AdminLoginForm
          title="Admin console"
          hint="Staff login for live bus operations in North and North-West Delhi."
          notice={session.notice}
          onLoggedIn={(token) => setSession({ token, notice: null })}
        />
      </div>
    );
  }
  return <AdminConsole token={session.token} onLogout={logout} />;
}
