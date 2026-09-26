import { useState } from 'react';
import { adminLogin, setAdminToken } from '../api/client';

/** Shared login for the admin dashboard and conductor console (same backend account). */
export default function AdminLoginForm({ title = 'Admin Login', hint, onLoggedIn }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);

  const login = (e) => {
    e.preventDefault();
    setError(null);
    adminLogin(username, password)
      .then((res) => {
        setAdminToken(res.token);
        onLoggedIn(res.token);
      })
      .catch((err) => setError(err.response?.data?.message || 'Login failed.'));
  };

  return (
    <div className="page-panel">
      <h2>{title}</h2>
      {hint && <p>{hint}</p>}
      <form className="inline-form" onSubmit={login}>
        <input type="text" placeholder="Username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <input type="password" placeholder="Password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <button type="submit">Log in</button>
      </form>
      {error && <div className="banner banner-error" style={{ position: 'static', marginTop: 12 }}>{error}</div>}
    </div>
  );
}
