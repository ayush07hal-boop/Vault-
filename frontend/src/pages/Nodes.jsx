import { api } from '../api';
import { Bar, ErrorBox, StatusBadge } from '../components/ui';
import { formatBytes, timeAgo, usePolling } from '../utils';

const EXPLAIN = {
  HEALTHY: 'Answering heartbeats; receives new data.',
  SUSPECTED: 'Missed a heartbeat or request. Not yet considered dead; no repair triggered.',
  UNHEALTHY: 'Failed repeatedly. Its replicas no longer count; Vault re-replicates elsewhere.',
  RECOVERING: 'Answering again, but must prove stable before it is trusted with new data.',
};

export default function Nodes() {
  const { data, error, reload } = usePolling(api.nodes, 3000);
  const nodes = data || [];

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Storage nodes</h1>
          <div className="sub">Independent servers holding the replicas · refreshes every 3s</div>
        </div>
      </div>
      <ErrorBox error={error} onRetry={reload} />
      <div className="grid nodes">
        {nodes.map((n) => (
          <div key={n.nodeId} className="card">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <h2 style={{ margin: 0 }}>{n.nodeId}</h2>
              <StatusBadge status={n.status} />
            </div>
            <div className="muted mono" style={{ margin: '6px 0 12px' }}>{n.address}:{n.port} · {n.zone || 'no zone'}</div>
            <Bar ratio={n.utilization} />
            <div className="row" style={{ justifyContent: 'space-between', margin: '6px 0 12px' }}>
              <span>{formatBytes(n.usedCapacity)} used</span>
              <span className="muted">{formatBytes(n.totalCapacity)} total · {Math.round(n.utilization * 100)}%</span>
            </div>
            <div className="muted" style={{ fontSize: 12 }}>
              Last heartbeat {timeAgo(n.lastHeartbeat)}
              {n.consecutiveFailures > 0 && ` · ${n.consecutiveFailures} consecutive missed`}
            </div>
            <div style={{ fontSize: 12, marginTop: 8 }}>{EXPLAIN[n.status]}</div>
          </div>
        ))}
      </div>
      {nodes.length === 0 && !error && <div className="empty">No storage nodes registered.</div>}
    </>
  );
}
