import { useId, useState } from 'react';
import { adminLogin, setAdminToken } from '../api/client';
import { loginErrorMessage } from '../utils/admin';
import Icon from './ui/Icon';
import { Notice } from './ui/States';

/**
 * Shared login for the admin dashboard and conductor console (same backend account).
 * `notice`: why the person is seeing this (e.g. their session expired).
 */
export default function AdminLoginForm({ title = 'Admin Login', hint, notice, onLoggedIn }) {
  const id = useId();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const login = (e) => {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    adminLogin(username.trim(), password)
      .then((res) => {
        setAdminToken(res.token);
        onLoggedIn(res.token);
      })
      .catch((err) => {
        setSubmitting(false);
        setError(loginErrorMessage(err));
      });
  };

  return (
    <div className="login-wrap">
      <form className="panel panel-pad login-card" onSubmit={login} aria-labelledby={`${id}-title`} aria-busy={submitting}>
        <h2 id={`${id}-title`}>{title}</h2>
        {hint && <p className="muted small">{hint}</p>}
        {notice && <Notice tone="warn" role="status">{notice}</Notice>}
        <div className="field">
          <label className="field-label" htmlFor={`${id}-user`}>Username</label>
          <input id={`${id}-user`} className="input" type="text" autoComplete="username" required autoFocus
            autoCapitalize="none" spellCheck={false} value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={`${id}-pass`}>Password</label>
          <div className="password-wrap">
            <input id={`${id}-pass`} className="input" type={showPassword ? 'text' : 'password'}
              autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
            <button type="button" className="icon-btn" aria-controls={`${id}-pass`} aria-pressed={showPassword}
              aria-label={showPassword ? 'Hide password' : 'Show password'} title={showPassword ? 'Hide password' : 'Show password'}
              onClick={() => setShowPassword((s) => !s)}>
              <Icon name={showPassword ? 'eyeOff' : 'eye'} />
            </button>
          </div>
        </div>
        {error && <Notice tone="error" role="alert">{error}</Notice>}
        <button type="submit" className="btn btn-primary btn-block" disabled={submitting}>
          {submitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>
    </div>
  );
}
