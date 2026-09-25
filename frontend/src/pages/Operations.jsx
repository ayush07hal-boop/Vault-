import { useState } from 'react';
import { api } from '../api';
import { useToast } from '../components/ui';

const OPS = [
  { action: 'heartbeat', title: 'Run health check now', text: 'Pings every storage node immediately instead of waiting for the next heartbeat.' },
  { action: 'repair/scan', title: 'Scan for repairs', text: 'Finds objects with missing, corrupted or outdated replicas and queues them for repair.' },
  { action: 'integrity/verify', title: 'Verify data integrity', text: 'Has nodes re-read replicas from disk and recompute SHA-256 to catch silent corruption.' },
  { action: 'rebalance/run', title: 'Rebalance storage', text: 'Moves replicas from overfull nodes to emptier ones (copy, verify, then delete the old copy).' },
];

export default function Operations() {
  const toast = useToast();
  const [busy, setBusy] = useState(null);
  const [results, setResults] = useState({});

  async function run(action) {
    setBusy(action);
    try {
      const r = await api.admin(action);
      setResults((s) => ({ ...s, [action]: r }));
      toast('Done', 'success');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Operations</h1>
          <div className="sub">Background jobs already run automatically; trigger them by hand here.</div>
        </div>
      </div>
      <div className="card">
        {OPS.map((op) => (
          <div key={op.action} className="op">
            <div>
              <strong>{op.title}</strong>
              <div className="muted">{op.text}</div>
              {results[op.action] && <pre className="result">{JSON.stringify(results[op.action], null, 2)}</pre>}
            </div>
            <button className="primary" disabled={busy !== null} onClick={() => run(op.action)}>
              {busy === op.action ? 'Running…' : 'Run'}
            </button>
          </div>
        ))}
      </div>
    </>
  );
}
