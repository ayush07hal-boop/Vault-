import { useRef, useState } from 'react';
import { api } from '../api';
import ObjectDrawer from '../components/ObjectDrawer';
import { Bar, ErrorBox, StatusBadge, useToast } from '../components/ui';
import { formatBytes, shortId, timeAgo, useDebounced, usePolling } from '../utils';

const PAGE_SIZE = 15;

export function ReplicaCount({ o }) {
  const ok = o.healthyReplicas >= o.replicationFactor;
  return (
    <span className={`badge ${ok ? 'ok' : o.healthyReplicas > 0 ? 'warn' : 'bad'}`}>
      {o.healthyReplicas}/{o.replicationFactor} copies
    </span>
  );
}

export default function Objects({ admin = false }) {
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const q = useDebounced(search);
  const [rf, setRf] = useState('');
  const [selected, setSelected] = useState(null);
  const [uploads, setUploads] = useState([]);
  const [over, setOver] = useState(false);
  const fileInput = useRef(null);

  const { data, error, loading, reload } = usePolling(() => api.list(page, PAGE_SIZE, q, admin ? 'all' : 'mine'), 5000, [page, q, admin]);

  function patchUpload(id, patch) {
    setUploads((s) => s.map((u) => (u.id === id ? { ...u, ...patch } : u)));
  }

  function startUploads(files) {
    [...files].forEach(async (file) => {
      const id = crypto.randomUUID();
      setUploads((s) => [...s, { id, name: file.name, progress: 0, state: 'uploading' }]);
      try {
        await api.upload(file, rf || undefined, (p) => patchUpload(id, { progress: p }));
        patchUpload(id, { progress: 1, state: 'done' });
        toast(`Uploaded ${file.name}`, 'success');
        setPage(0);
        reload();
        setTimeout(() => setUploads((s) => s.filter((u) => u.id !== id)), 3000);
      } catch (e) {
        patchUpload(id, { state: 'error', error: e.message });
        toast(`${file.name}: ${e.message}`, 'error');
      }
    });
  }

  function onDrop(e) {
    e.preventDefault();
    setOver(false);
    if (e.dataTransfer.files.length) startUploads(e.dataTransfer.files);
  }

  const items = data?.items || [];

  return (
    <>
      <div className="page-head">
        <div>
          <h1>{admin ? 'Stored objects' : 'My files'}</h1>
          <div className="sub">{data ? `${data.totalItems} ${admin ? 'object' : 'file'}${data.totalItems === 1 ? '' : 's'} stored${admin ? ' · read-only inspection' : ''}` : 'Loading…'}</div>
        </div>
        <div className="row">
          <input
            type="search"
            placeholder="Search by file name…"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0); }}
          />
          {!admin && (
            <>
              <select value={rf} onChange={(e) => setRf(e.target.value)} title="Copies to keep for new uploads">
                <option value="">Default copies</option>
                {[1, 2, 3, 4].map((n) => <option key={n} value={n}>{n} cop{n === 1 ? 'y' : 'ies'}</option>)}
              </select>
              <button className="primary" onClick={() => fileInput.current.click()}>Upload</button>
              <input ref={fileInput} type="file" multiple hidden onChange={(e) => { startUploads(e.target.files); e.target.value = ''; }} />
            </>
          )}
        </div>
      </div>

      {!admin && <div
        className={`dropzone ${over ? 'over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
      >
        Drag & drop files here to upload — each is checksummed and replicated across storage nodes
        {uploads.map((u) => (
          <div key={u.id} className="upload-item" style={{ textAlign: 'left' }}>
            <span style={{ width: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{u.name}</span>
            <Bar ratio={u.progress} warnAt={2} badAt={2} />
            <span style={{ width: 120 }} className={u.state === 'error' ? 'mono' : 'muted'}>
              {u.state === 'uploading' && `${Math.round(u.progress * 100)}%`}
              {u.state === 'done' && 'Stored ✓'}
              {u.state === 'error' && 'Failed'}
            </span>
            {u.state === 'error' && <button className="small" onClick={() => setUploads((s) => s.filter((x) => x.id !== u.id))}>Dismiss</button>}
          </div>
        ))}
      </div>}

      <ErrorBox error={error} onRetry={reload} />

      <div className="card table-wrap" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Name</th>
              {admin && <th>Owner</th>}
              <th>Size</th>
              <th>Version</th>
              <th>{admin ? 'Replicas' : 'Protection'}</th>
              <th>Updated</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((o) => (
              <tr key={o.objectId} className="clickable" onClick={() => setSelected(o.objectId)}>
                <td>
                  <div>{o.fileName}</div>
                  <div className="muted mono">{shortId(o.objectId)}</div>
                </td>
                {admin && <td className="muted">{o.ownerEmail || '—'}</td>}
                <td>{formatBytes(o.size)}</td>
                <td>v{o.version}</td>
                <td><ReplicaCount o={o} /></td>
                <td className="muted">{timeAgo(o.updatedAt)}</td>
                <td className="right" onClick={(e) => e.stopPropagation()}>
                  <button
                    className="small"
                    onClick={() => api.download(o.objectId, o.fileName).catch((e2) => toast(e2.message, 'error'))}
                  >
                    Download
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!loading && items.length === 0 && (
          <div className="empty">{q ? `No objects match “${q}”.` : (admin ? 'No objects stored yet.' : 'No files yet — upload your first file above.')}</div>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="pager">
          <span className="muted">Page {data.page + 1} of {data.totalPages}</span>
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}

      {selected && (
        <ObjectDrawer
          objectId={selected}
          admin={admin}
          onClose={() => setSelected(null)}
          onChanged={reload}
        />
      )}
    </>
  );
}

