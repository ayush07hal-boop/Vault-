import { Link } from 'react-router-dom';
import { api } from '../api';
import { Bar, ErrorBox, Stat, StatusBadge } from '../components/ui';
import { formatBytes, timeAgo, usePolling } from '../utils';

export default function Dashboard() {
  const stats = usePolling(api.stats, 5000);
  const health = usePolling(api.health, 5000);
  const nodes = usePolling(api.nodes, 5000);

  const s = stats.data;
  const h = health.data;
  const nodeList = nodes.data || [];
  const healthyNodes = nodeList.filter((n) => n.status === 'HEALTHY').length;
  const error = stats.error || health.error || nodes.error;

  const bannerText = {
    UP: 'All systems healthy — every object has its full number of verified replicas.',
    DEGRADED: 'Degraded — Vault is repairing data or a node is down. Data stays readable.',
    DOWN: 'Down — no storage node is reachable.',
  };

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Dashboard</h1>
          <div className="sub">Live cluster overview · refreshes every 5s</div>
        </div>
      </div>

      <ErrorBox error={error} onRetry={() => { stats.reload(); health.reload(); nodes.reload(); }} />

      {h && (
        <div className={`banner ${h.status}`}>
          <span className="dot" />
          <strong>{h.status}</strong>
          <span className="muted">{bannerText[h.status]}</span>
        </div>
      )}

      {s && (
        <div className="grid stats">
          <Stat label="Objects" value={s.activeObjects} hint={`${formatBytes(s.storage.logicalBytes)} of data`} />
          <Stat
            label="Storage used"
            value={`${Math.round(s.storage.usedRatio * 100)}%`}
            hint={`${formatBytes(s.storage.usedBytes)} of ${formatBytes(s.storage.totalCapacityBytes)} (all replicas)`}
          />
          <Stat label="Nodes healthy" value={`${healthyNodes} / ${nodeList.length}`} hint={`${s.unhealthyNodes} not healthy`} />
          <Stat
            label="Needing repair"
            value={s.objectsNeedingRepair}
            hint={`${s.repairQueueDepth} queued`}
          />
        </div>
      )}

      <div className="grid two">
        <div className="card">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2>Storage nodes</h2>
            <Link to="/admin/nodes">View all →</Link>
          </div>
          {nodeList.length === 0 && <div className="muted">No nodes registered.</div>}
          {nodeList.map((n) => (
            <div key={n.nodeId} style={{ marginBottom: 14 }}>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                <span><strong>{n.nodeId}</strong> <span className="muted">{n.zone}</span></span>
                <StatusBadge status={n.status} />
              </div>
              <Bar ratio={n.utilization} />
              <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                {formatBytes(n.usedCapacity)} / {formatBytes(n.totalCapacity)}
              </div>
            </div>
          ))}
        </div>

        <div className="grid" style={{ alignContent: 'start' }}>
          {s && (
            <div className="card">
              <h2>Replicas by state</h2>
              {Object.keys(s.replicasByStatus).length === 0 && <div className="muted">No replicas yet.</div>}
              {Object.entries(s.replicasByStatus).map(([status, count]) => (
                <div key={status} className="row" style={{ justifyContent: 'space-between', padding: '5px 0' }}>
                  <StatusBadge status={status} />
                  <strong>{count}</strong>
                </div>
              ))}
            </div>
          )}
          {s && (
            <div className="card">
              <h2>Activity since API start</h2>
              <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: '8px 20px' }}>
                {[
                  ['Uploads', s.counters.uploads],
                  ['Downloads', s.counters.downloads],
                  ['Updates', s.counters.updates],
                  ['Deletes', s.counters.deletes],
                  ['Repairs', s.counters.repairs],
                  ['Corruptions found', s.counters.corruptionsDetected],
                  ['Node failures', s.counters.nodeFailures],
                  ['Rebalanced', s.counters.rebalances],
                ].map(([k, v]) => (
                  <div key={k} className="row" style={{ justifyContent: 'space-between' }}>
                    <span className="muted">{k}</span>
                    <strong>{v}</strong>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
      {nodeList.length > 0 && (
        <div className="muted" style={{ marginTop: 14, fontSize: 12 }}>
          Last heartbeat: {timeAgo(nodeList.map((n) => n.lastHeartbeat).filter(Boolean).sort().at(-1))}
        </div>
      )}
    </>
  );
}
