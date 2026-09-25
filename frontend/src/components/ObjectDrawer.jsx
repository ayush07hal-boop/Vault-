import { useRef, useState } from 'react';
import { api } from '../api';
import { Bar, Drawer, ErrorBox, Modal, StatusBadge, useToast } from './ui';
import { formatBytes, timeAgo, usePolling } from '../utils';

export default function ObjectDrawer({ objectId, admin = false, onClose, onChanged }) {
  const toast = useToast();
  const { data: o, error, reload } = usePolling(() => api.metadata(objectId), 3000, [objectId]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [progress, setProgress] = useState(null);
  const [conflict, setConflict] = useState(false);
  const fileInput = useRef(null);

  // If the object was deleted elsewhere, the poll starts returning 404.
  const gone = error?.status === 404;

  async function newVersion(file) {
    setConflict(false);
    setProgress(0);
    try {
      // expectedVersion = the version this screen is showing (optimistic concurrency control)
      await api.update(objectId, file, o.version, setProgress);
      toast(`Uploaded a new version of ${o.fileName}`, 'success');
      reload();
      onChanged?.();
    } catch (e) {
      if (e.status === 409) setConflict(true);
      else toast(e.message, 'error');
    } finally {
      setProgress(null);
    }
  }

  async function remove() {
    try {
      await api.remove(objectId); // users: moves to trash (restorable); admins: permanent
      toast(admin ? 'Object deleted' : 'Moved to trash', 'success');
      onChanged?.();
      onClose();
    } catch (e) {
      toast(e.message, 'error');
      setConfirmDelete(false);
    }
  }

  return (
    <Drawer onClose={onClose}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h1 style={{ wordBreak: 'break-all' }}>{o?.fileName || 'Object'}</h1>
        <button className="ghost" onClick={onClose}>✕</button>
      </div>

      {gone && <div className="empty">This object no longer exists.</div>}
      {!gone && <ErrorBox error={error} onRetry={reload} />}

      {o && (
        <>
          <dl className="kv">
            {admin && <><dt>Owner</dt><dd>{o.ownerEmail || '—'}</dd></>}
            <dt>Object ID</dt><dd className="mono">{o.objectId}</dd>
            <dt>Size</dt><dd>{formatBytes(o.size)}</dd>
            <dt>Version</dt><dd>v{o.version}</dd>
            <dt>SHA-256</dt><dd className="mono">{o.checksum}</dd>
            <dt>Copies</dt><dd>{o.healthyReplicas} of {o.replicationFactor} stored and verified</dd>
            {admin && <><dt>Healthy copies</dt>
            <dd>
              {o.healthyReplicas} of {o.replicationFactor}{' '}
              {o.healthyReplicas < o.replicationFactor && <span className="muted">— repair in progress or not enough nodes</span>}
            </dd></>}
            <dt>Created</dt><dd>{new Date(o.createdAt).toLocaleString()}</dd>
            <dt>Updated</dt><dd>{timeAgo(o.updatedAt)}</dd>
          </dl>

          {!admin && (
            <div className="card" style={{ marginBottom: 18 }}>
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <strong>Data protection</strong>
                <StatusBadge status={o.healthyReplicas >= o.replicationFactor ? 'HEALTHY' : 'DEGRADED'} />
              </div>
              <div className="muted" style={{ marginTop: 6 }}>
                {o.healthyReplicas >= o.replicationFactor
                  ? `All ${o.replicationFactor} copies are stored and verified.`
                  : 'Some copies are being restored automatically. Your file stays available.'}
              </div>
            </div>
          )}

          {admin && <h2>Where the copies live</h2>}
          {admin && <div className="card" style={{ padding: 0, marginBottom: 18 }}>
            <table>
              <thead>
                <tr><th>Node</th><th>Copy</th><th>Node status</th><th>Verified</th></tr>
              </thead>
              <tbody>
                {o.replicas.map((r) => (
                  <tr key={r.nodeId}>
                    <td><strong>{r.nodeId}</strong> <span className="muted">v{r.version}</span></td>
                    <td><StatusBadge status={r.status} /></td>
                    <td><StatusBadge status={r.nodeStatus} /></td>
                    <td className="muted">{timeAgo(r.lastVerified)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>}

          {conflict && (
            <div className="error-box">
              Someone else updated this object first (version conflict). Nothing was overwritten — review the latest
              version shown here, then try again.
              <div style={{ marginTop: 8 }}>
                <button className="small" onClick={() => { setConflict(false); reload(); }}>Reload latest</button>
              </div>
            </div>
          )}

          {progress !== null && <div style={{ marginBottom: 14 }}><Bar ratio={progress} warnAt={2} badAt={2} /></div>}

          <div className="row">
            <button className="primary" onClick={() => api.download(o.objectId, o.fileName).catch((e) => toast(e.message, 'error'))}>
              Download
            </button>
            {!admin && <button disabled={progress !== null} onClick={() => fileInput.current.click()}>Upload new version</button>}
            {!admin && <input ref={fileInput} type="file" hidden onChange={(e) => { if (e.target.files[0]) newVersion(e.target.files[0]); e.target.value = ''; }} />}
            <button className="danger" style={{ marginLeft: 'auto' }} onClick={() => (admin ? setConfirmDelete(true) : remove())}>{admin ? 'Delete' : 'Move to trash'}</button>
          </div>
        </>
      )}

      {confirmDelete && (
        <Modal onClose={() => setConfirmDelete(false)}>
          <h2>Delete “{o?.fileName}”?</h2>
          <p className="muted">All copies are removed from every storage node. This cannot be undone.</p>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button onClick={() => setConfirmDelete(false)}>Cancel</button>
            <button className="danger solid" onClick={remove}>Delete</button>
          </div>
        </Modal>
      )}
    </Drawer>
  );
}
