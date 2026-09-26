import { useState } from 'react';
import Logo from '../components/Logo';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';

/** Second step of admin access. Only allowlisted accounts ever reach this form. */
export default function AdminLogin() {
  const { user, ready, isAdmin, adminSignIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (!ready) return null;
  if (!user) return <Navigate to="/" replace />;
  if (!user.canAdmin) return <Navigate to="/" replace />; // no hint that an admin area exists
  const target = location.state?.from || '/admin';
  if (isAdmin) return <Navigate to={target} replace />;

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await adminSignIn(password);
      navigate(target, { replace: true });
    } catch (err) {
      setError(err.status === 401 ? 'Wrong admin password.' : err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-page">
      <form className="card login-card" onSubmit={submit}>
        <div className="brand" style={{ padding: 0, marginBottom: 6 }}>
          <Logo size={34} /> Vault
        </div>
        <h1>Admin access</h1>
        <p className="muted" style={{ margin: '4px 0 18px' }}>
          Signed in as <strong>{user.email}</strong>. Enter the admin password to continue.
        </p>
        {error && <div className="error-box">{error}</div>}
        <label className="field">
          <span>Admin password</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" autoFocus />
        </label>
        <button className="primary" type="submit" disabled={busy || !password} style={{ width: '100%', marginTop: 6 }}>
          {busy ? 'Checking…' : 'Unlock admin area'}
        </button>
        <div style={{ marginTop: 16, textAlign: 'center' }}>
          <Link to="/">← Back to my files</Link>
        </div>
      </form>
    </div>
  );
}
