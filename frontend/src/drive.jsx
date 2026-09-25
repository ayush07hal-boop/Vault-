import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api';
import { FileIcon, Icon } from './icons';
import { useToast } from './components/ui';
import { categoryOfName, formatSize } from './utils';

export const DriveCtx = createContext(null);
export const useDrive = () => useContext(DriveCtx);

/** Stand-in for signed-out visitors: same shape as the real context, but nothing is loaded or uploaded. */
export function GuestDriveProvider({ children }) {
  const toast = useToast();
  const value = useMemo(() => ({
    guest: true,
    search: '', setSearch: () => {}, version: 0, refresh: () => {}, usage: null, projects: [],
    startUploads: () => {}, copies: 3, setCopies: () => {}, maxCopies: 4,
    openUpload: () => toast('Sign in with Google to upload files.', 'info'),
  }), [toast]);
  return <DriveCtx.Provider value={value}>{children}</DriveCtx.Provider>;
}

// ---- protection levels -------------------------------------------------------------------------

function levelInfo(n, max, recommended) {
  const label = n === 1 ? 'Single copy' : n === max ? 'Maximum' : n === 2 ? 'Basic' : 'High';
  const survives = n === 1 ? 'No protection if its server fails' : `Survives ${n - 1} server failure${n - 1 === 1 ? '' : 's'}`;
  return { label, survives };
}

function ServerDots({ used, total }) {
  return (
    <span className="server-dots" aria-hidden="true">
      {Array.from({ length: total }, (_, i) => <i key={i} className={i < used ? 'on' : ''} />)}
    </span>
  );
}

function CopiesPicker({ value, onChange, max, recommended }) {
  const options = Array.from({ length: max }, (_, i) => i + 1);
  return (
    <div className="copies-cards" role="radiogroup" aria-label="Number of copies">
      {options.map((n) => {
        const { label, survives } = levelInfo(n, max, recommended);
        return (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={value === n}
            className={`copies-card ${value === n ? 'active' : ''} ${n === 1 ? 'risky' : ''}`}
            onClick={() => onChange(n)}
          >
            <span className="copies-flag-row">
              {n === recommended ? <span className="rec-badge">Recommended</span> : <span className="rec-spacer" aria-hidden="true" />}
              <span className="copies-radio" aria-hidden="true">{value === n && <Icon name="check" size={14} />}</span>
            </span>
            <span className="copies-card-top">
              <span className="copies-num">{n}</span>
              <span className="copies-unit">cop{n === 1 ? 'y' : 'ies'}</span>
            </span>
            <span className="copies-label">{label}</span>
            <ServerDots used={n} total={max} />
            <span className="copies-survives">{survives}</span>
          </button>
        );
      })}
    </div>
  );
}

// ---- upload dialog -----------------------------------------------------------------------------

function UploadDialog({ initialFiles, projectId, onCancel, onStart }) {
  const { copies, setCopies, maxCopies, defaultCopies, usage, projects } = useDrive();
  const [files, setFiles] = useState(initialFiles);
  const [over, setOver] = useState(false);
  const input = useRef(null);
  const add = (list) => setFiles((f) => [...f, ...[...list].filter((n) => !f.some((x) => x.name === n.name && x.size === n.size))]);

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const total = files.reduce((s, f) => s + f.size, 0);
  const free = usage ? usage.quotaBytes - usage.usedBytes : Infinity;
  const overQuota = total > free;
  const project = projects.find((p) => p.projectId === projectId);

  return (
    <div className="overlay center" onClick={onCancel}>
      <div className="upload-dialog" role="dialog" aria-modal="true" aria-labelledby="upload-title" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-head">
          <div>
            <h2 id="upload-title">Upload files</h2>
            <div className="muted">{project ? <>Into project <strong>{project.name}</strong></> : 'Into your storage'}</div>
          </div>
          <button className="icon-btn" onClick={onCancel} aria-label="Close"><Icon name="close" size={22} /></button>
        </div>

        <div className="dialog-body">
          <section>
            <div className="step-title"><span className="step-num">1</span>Choose files</div>
            <div
              className={`upload-drop ${over ? 'over' : ''} ${files.length ? 'compact' : ''}`}
              onDragOver={(e) => { e.preventDefault(); setOver(true); }}
              onDragLeave={() => setOver(false)}
              onDrop={(e) => { e.preventDefault(); setOver(false); add(e.dataTransfer.files); }}
              onClick={() => input.current.click()}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current.click()}
            >
              <Icon name="upload" size={files.length ? 22 : 36} />
              <span>{files.length ? 'Add more files' : <>Drag and drop files here, or <u>browse</u></>}</span>
              <input ref={input} type="file" multiple hidden onChange={(e) => { add(e.target.files); e.target.value = ''; }} />
            </div>

            {files.length > 0 && (
              <ul className="upload-pick">
                {files.map((f, i) => (
                  <li key={`${f.name}-${f.size}-${i}`}>
                    <FileIcon category={categoryOfName(f.name)} size={20} />
                    <span className="upload-name" title={f.name}>{f.name}</span>
                    <span className="muted">{formatSize(f.size)}</span>
                    <button type="button" className="icon-btn small-icon" aria-label={`Remove ${f.name}`} onClick={() => setFiles((l) => l.filter((_, j) => j !== i))}>
                      <Icon name="close" size={18} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <div className="step-title"><span className="step-num">2</span>Choose protection</div>
            <p className="muted step-help">Each copy is stored on a different server. More copies means your file survives more server failures.</p>
            <CopiesPicker value={copies} onChange={setCopies} max={maxCopies} recommended={defaultCopies} />
          </section>
        </div>

        <div className="dialog-foot">
          <div className="upload-summary">
            {files.length === 0 ? (
              <span className="muted">No files selected</span>
            ) : (
              <>
                <strong>{files.length} file{files.length === 1 ? '' : 's'} · {formatSize(total)}</strong>
                <span className="muted"> · {copies} cop{copies === 1 ? 'y' : 'ies'} on {copies} server{copies === 1 ? '' : 's'}</span>
                {overQuota
                  ? <div className="quota-error">Not enough storage: {formatSize(free)} left of your {formatSize(usage.quotaBytes)}.</div>
                  : <div className="muted small-text">Uses {formatSize(total)} of your storage. Extra copies don't count against it.</div>}
              </>
            )}
          </div>
          <div className="row">
            <button className="pill-outline" onClick={onCancel}>Cancel</button>
            <button className="pill-ink" disabled={files.length === 0 || overQuota} onClick={() => onStart(files, projectId, copies)}>
              Upload{files.length > 0 ? ` ${files.length} file${files.length === 1 ? '' : 's'}` : ''}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ---- upload progress panel ---------------------------------------------------------------------

function UploadPanel({ uploads, onCancel, onRetry, onClear }) {
  const [open, setOpen] = useState(true);
  const active = uploads.filter((u) => u.state === 'uploading' || u.state === 'replicating');
  const failed = uploads.filter((u) => u.state === 'error').length;
  const sent = uploads.reduce((s, u) => s + u.size * (u.state === 'done' ? 1 : u.progress || 0), 0);
  const totalBytes = uploads.reduce((s, u) => s + u.size, 0) || 1;
  const title = active.length
    ? `Uploading ${active.length} file${active.length === 1 ? '' : 's'} · ${Math.round((sent / totalBytes) * 100)}%`
    : failed
      ? `${failed} upload${failed === 1 ? '' : 's'} failed`
      : `${uploads.length} file${uploads.length === 1 ? '' : 's'} stored safely`;

  return (
    <div className="upload-panel" role="status" aria-live="polite">
      <div className="upload-head">
        <strong>{title}</strong>
        <span className="row" style={{ gap: 2 }}>
          {active.length > 0 && <button className="head-link" onClick={() => active.forEach((u) => onCancel(u.id))}>Cancel all</button>}
          <button className="icon-btn" onClick={() => setOpen((o) => !o)} aria-label={open ? 'Minimise' : 'Expand'}>
            <Icon name="caret" size={20} style={{ transform: open ? 'none' : 'rotate(180deg)' }} />
          </button>
          {active.length === 0 && <button className="icon-btn" onClick={onClear} aria-label="Close"><Icon name="close" size={20} /></button>}
        </span>
      </div>
      {active.length > 0 && <div className="upload-overall"><span style={{ width: `${(sent / totalBytes) * 100}%` }} /></div>}
      {open && (
        <ul className="upload-list">
          {uploads.map((u) => (
            <li key={u.id} className={u.state}>
              <FileIcon category={categoryOfName(u.name)} size={20} />
              <div className="upload-main">
                <span className="upload-name" title={u.name}>{u.name}</span>
                <span className="upload-phase">
                  {u.state === 'uploading' && `Uploading · ${Math.round(u.progress * 100)}% of ${formatSize(u.size)}`}
                  {u.state === 'replicating' && `Writing ${u.copies} verified cop${u.copies === 1 ? 'y' : 'ies'}…`}
                  {u.state === 'done' && `Stored · ${u.stored} of ${u.copies} cop${u.copies === 1 ? 'y' : 'ies'} verified`}
                  {u.state === 'error' && u.error}
                  {u.state === 'cancelled' && 'Cancelled'}
                </span>
                {(u.state === 'uploading' || u.state === 'replicating') && (
                  <span className={`upload-bar ${u.state === 'replicating' ? 'indeterminate' : ''}`}>
                    <span style={{ width: u.state === 'replicating' ? '100%' : `${u.progress * 100}%` }} />
                  </span>
                )}
              </div>
              <span className="upload-end">
                {u.state === 'uploading' && <button className="icon-btn small-icon" onClick={() => onCancel(u.id)} aria-label={`Cancel ${u.name}`}><Icon name="close" size={18} /></button>}
                {u.state === 'done' && <Icon name="check" size={20} style={{ color: 'var(--success)' }} />}
                {(u.state === 'error' || u.state === 'cancelled') && <button className="head-link dark" onClick={() => onRetry(u.id)}>Retry</button>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---- provider ----------------------------------------------------------------------------------

/**
 * State shared by every signed-in screen: search text, storage usage, projects, the upload dialog (with the
 * number-of-copies choice) and the upload manager.
 */
export function DriveProvider({ children }) {
  const toast = useToast();
  const [search, setSearch] = useState('');
  const [version, setVersion] = useState(0); // bumps whenever files change, so lists reload
  const [usage, setUsage] = useState(null);
  const [projects, setProjects] = useState([]);
  const [limits, setLimits] = useState({ maxCopies: 4, defaultCopies: 3 });
  const [uploads, setUploads] = useState([]);
  const [dialog, setDialog] = useState(null); // {files, projectId}
  const xhrs = useRef({});
  const [savedCopies, setSavedCopies] = useState(() => Number(localStorage.getItem('vault.copies')) || 0);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    api.authConfig().then((c) => c.maxCopies && setLimits({ maxCopies: c.maxCopies, defaultCopies: c.defaultCopies })).catch(() => {});
  }, []);

  useEffect(() => {
    api.usage().then(setUsage).catch(() => {});
    api.projects().then(setProjects).catch(() => {});
  }, [version]);

  const copies = Math.min(limits.maxCopies, savedCopies >= 1 ? savedCopies : limits.defaultCopies);
  const setCopies = useCallback((n) => {
    setSavedCopies(n);
    try { localStorage.setItem('vault.copies', String(n)); } catch { /* storage unavailable */ }
  }, []);

  const patch = (id, p) => setUploads((s) => s.map((u) => (u.id === id ? { ...u, ...p } : u)));

  const runUpload = useCallback(async (item) => {
    patch(item.id, { state: 'uploading', progress: 0, error: '' });
    try {
      const res = await api.upload(
        item.file, item.copies,
        (p) => patch(item.id, p >= 1 ? { progress: 1, state: 'replicating' } : { progress: p }),
        item.projectId,
        (xhr) => { xhrs.current[item.id] = xhr; },
      );
      patch(item.id, { state: 'done', progress: 1, stored: res?.healthyReplicas ?? item.copies });
      refresh();
    } catch (e) {
      if (e.code === 'ABORTED') {
        patch(item.id, { state: 'cancelled' });
      } else {
        patch(item.id, { state: 'error', error: e.message });
        toast(`${item.name}: ${e.message}`, 'error');
      }
    } finally {
      delete xhrs.current[item.id];
    }
  }, [refresh, toast]);

  const startUploads = useCallback((files, projectId, copyCount) => {
    const items = [...files].map((file) => ({
      id: crypto.randomUUID(), file, name: file.name, size: file.size, copies: copyCount, projectId, progress: 0, state: 'uploading',
    }));
    setUploads((s) => [...s.filter((u) => u.state !== 'done'), ...items]);
    items.forEach(runUpload);
  }, [runUpload]);

  const cancel = useCallback((id) => xhrs.current[id]?.abort(), []);
  const uploadsRef = useRef(uploads);
  uploadsRef.current = uploads;
  const retry = useCallback((id) => {
    const item = uploadsRef.current.find((u) => u.id === id);
    if (item) runUpload(item);
  }, [runUpload]);

  const openUpload = useCallback((files = [], projectId) => setDialog({ files: [...files], projectId }), []);

  const value = useMemo(
    () => ({
      guest: false, search, setSearch, version, refresh, usage, projects, startUploads, openUpload,
      copies, setCopies, maxCopies: limits.maxCopies, defaultCopies: limits.defaultCopies,
    }),
    [search, version, refresh, usage, projects, startUploads, openUpload, copies, setCopies, limits],
  );

  return (
    <DriveCtx.Provider value={value}>
      {children}
      {dialog && (
        <UploadDialog
          initialFiles={dialog.files}
          projectId={dialog.projectId}
          onCancel={() => setDialog(null)}
          onStart={(files, projectId, n) => { setDialog(null); startUploads(files, projectId, n); }}
        />
      )}
      {uploads.length > 0 && (
        <UploadPanel
          uploads={uploads}
          onCancel={cancel}
          onRetry={retry}
          onClear={() => setUploads((s) => s.filter((u) => u.state === 'uploading' || u.state === 'replicating'))}
        />
      )}
    </DriveCtx.Provider>
  );
}
