import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { api, getAdminToken, getUserToken, setAdminToken, setUserToken } from './api';

const AuthCtx = createContext(null);
export const useAuth = () => useContext(AuthCtx);

/**
 * Two layers: a signed-in account (Google) and, only for allowlisted accounts, an admin elevation (password).
 * The backend enforces every rule; this state only decides what the UI shows.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null); // {email, name, picture, canAdmin}
  const [isAdmin, setIsAdmin] = useState(!!getAdminToken());
  const [ready, setReady] = useState(!getUserToken());

  useEffect(() => {
    if (!getUserToken()) return;
    api
      .me()
      .then((u) => {
        setUser(u);
        if (!u.admin) setIsAdmin(false);
      })
      .catch(() => {
        setUserToken('');
        setAdminToken('');
      })
      .finally(() => setReady(true));
  }, []);

  useEffect(() => {
    const onUserExpired = () => { setUser(null); setIsAdmin(false); };
    const onAdminExpired = () => setIsAdmin(false);
    window.addEventListener('vault:user-expired', onUserExpired);
    window.addEventListener('vault:admin-expired', onAdminExpired);
    return () => {
      window.removeEventListener('vault:user-expired', onUserExpired);
      window.removeEventListener('vault:admin-expired', onAdminExpired);
    };
  }, []);

  /** session = response of /auth/google or /auth/dev-login */
  const signIn = useCallback((session) => {
    setUserToken(session.token);
    setUser(session.user);
    setReady(true);
  }, []);

  const signOut = useCallback(() => {
    setUserToken('');
    setAdminToken('');
    setUser(null);
    setIsAdmin(false);
    window.google?.accounts?.id?.disableAutoSelect?.();
  }, []);

  const adminSignIn = useCallback(async (password) => {
    const res = await api.adminLogin(password);
    setAdminToken(res.token);
    setIsAdmin(true);
  }, []);

  const adminSignOut = useCallback(() => {
    setAdminToken('');
    setIsAdmin(false);
  }, []);

  return (
    <AuthCtx.Provider value={{ user, ready, isAdmin, signIn, signOut, adminSignIn, adminSignOut }}>
      {children}
    </AuthCtx.Provider>
  );
}

/**
 * Non-allowlisted accounts are bounced to the home page with no hint that an admin area exists;
 * allowlisted ones without an admin session go to the password step.
 */
export function RequireAdmin({ children }) {
  const { user, ready, isAdmin } = useAuth();
  const location = useLocation();
  if (!ready) return <div className="empty">Loading…</div>;
  if (!user) return <Navigate to="/" replace />;
  if (!user.canAdmin) return <Navigate to="/" replace />;
  if (!isAdmin) return <Navigate to="/admin/login" replace state={{ from: location.pathname }} />;
  return children;
}
