import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { useAuth } from '../auth';
import { Modal, useToast } from './ui';

let gisPromise;
/** Loads Google Identity Services once. */
function loadGoogle() {
  if (!gisPromise) {
    gisPromise = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://accounts.google.com/gsi/client';
      s.async = true;
      s.onload = () => resolve(window.google);
      s.onerror = () => {
        gisPromise = undefined; // allow a retry on the next render
        reject(new Error('Could not load Google sign-in. Check your connection or disable content blockers.'));
      };
      document.head.appendChild(s);
    });
  }
  return gisPromise;
}

/**
 * Sign-in control for the navbar: the Google button, plus (only when the backend enables it for local demos)
 * a "Dev sign-in" fallback that accepts any email.
 */
export default function SignIn({ large = false }) {
  const { signIn } = useAuth();
  const toast = useToast();
  const [config, setConfig] = useState(null);
  const [failed, setFailed] = useState('');
  const [devOpen, setDevOpen] = useState(false);
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const buttonRef = useRef(null);

  useEffect(() => {
    api.authConfig().then(setConfig).catch(() => setFailed('Server unreachable'));
  }, []);

  useEffect(() => {
    if (!config?.googleClientId || !buttonRef.current) return undefined;
    let cancelled = false;
    loadGoogle()
      .then((google) => {
        if (cancelled || !buttonRef.current) return;
        google.accounts.id.initialize({
          client_id: config.googleClientId,
          callback: async ({ credential }) => {
            try {
              signIn(await api.googleLogin(credential));
            } catch (e) {
              toast(e.message, 'error');
            }
          },
        });
        google.accounts.id.renderButton(buttonRef.current, large
          ? { theme: 'outline', size: 'large', text: 'continue_with', shape: 'pill' }
          : { theme: 'outline', size: 'medium', text: 'signin_with', shape: 'pill' });
      })
      .catch((e) => !cancelled && setFailed(e.message));
    return () => { cancelled = true; };
  }, [config, signIn, toast, large]);

  async function devSignIn(e) {
    e.preventDefault();
    setBusy(true);
    try {
      signIn(await api.devLogin(email));
      setDevOpen(false);
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      setBusy(false);
    }
  }

  const notConfigured = config && !config.googleClientId && !config.devLogin;

  return (
    <div className="row" style={{ gap: 10 }}>
      {config?.devLogin && (
        <button className="small" onClick={() => setDevOpen(true)} title="Local demo only: no Google check">Dev sign-in</button>
      )}
      {config?.googleClientId && <div ref={buttonRef} className="google-slot" />}
      {(failed || notConfigured) && (
        <span className="muted" style={{ fontSize: 12 }} title={failed}>
          {notConfigured ? 'Sign-in not configured' : 'Sign-in unavailable'}
        </span>
      )}

      {devOpen && (
        <Modal onClose={() => setDevOpen(false)}>
          <form onSubmit={devSignIn}>
            <h2>Development sign-in</h2>
            <div className="dev-note">No Google check. For local demos only — never enable this in production.</div>
            <label className="field">
              <span>Email</span>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" autoFocus />
            </label>
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <button type="button" onClick={() => setDevOpen(false)}>Cancel</button>
              <button className="primary" type="submit" disabled={busy || !email}>{busy ? 'Signing in…' : 'Continue'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
