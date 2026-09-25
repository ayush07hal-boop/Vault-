import { createContext, useCallback, useContext, useState } from 'react';

const TONE = {
  HEALTHY: 'ok', UP: 'ok',
  SUSPECTED: 'warn', RECOVERING: 'warn', DEGRADED: 'warn', OUTDATED: 'warn', PENDING_DELETE: 'warn', DELETING: 'warn',
  UNHEALTHY: 'bad', DOWN: 'bad', CORRUPTED: 'bad', MISSING: 'bad',
};

export function StatusBadge({ status }) {
  const label = String(status || 'UNKNOWN').replace('_', ' ').toLowerCase();
  return <span className={`badge ${TONE[status] || ''}`} style={{ textTransform: 'capitalize' }}>{label}</span>;
}

export function Bar({ ratio, warnAt = 0.7, badAt = 0.9 }) {
  const r = Math.max(0, Math.min(1, ratio || 0));
  const tone = r >= badAt ? 'bad' : r >= warnAt ? 'warn' : '';
  return (
    <div className={`bar ${tone}`}>
      <span style={{ width: `${r * 100}%` }} />
    </div>
  );
}

export function Stat({ label, value, hint }) {
  return (
    <div className="card stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

export function ErrorBox({ error, onRetry }) {
  if (!error) return null;
  return (
    <div className="error-box row" style={{ justifyContent: 'space-between' }}>
      <span>{error.message}</span>
      {onRetry && <button className="small" onClick={onRetry}>Retry</button>}
    </div>
  );
}

export function Drawer({ onClose, children }) {
  return (
    <div className="overlay" onClick={onClose}>
      <aside className="drawer" onClick={(e) => e.stopPropagation()}>{children}</aside>
    </div>
  );
}

export function Modal({ onClose, children }) {
  return (
    <div className="overlay center" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>{children}</div>
    </div>
  );
}

// ---- toasts ------------------------------------------------------------------------------------

const ToastCtx = createContext(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastProvider({ children }) {
  const [items, setItems] = useState([]);
  const push = useCallback((message, kind = 'info') => {
    const id = crypto.randomUUID();
    setItems((s) => [...s, { id, message, kind }]);
    setTimeout(() => setItems((s) => s.filter((t) => t.id !== id)), 4500);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts">
        {items.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`}>{t.message}</div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}
