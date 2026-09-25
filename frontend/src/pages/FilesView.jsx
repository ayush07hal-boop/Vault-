import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import { FilterPill, MenuItem, PageHero, Popover } from '../components/DriveUi';
import ObjectDrawer from '../components/ObjectDrawer';
import { Modal, useToast } from '../components/ui';
import { useDrive } from '../drive';
import { FileIcon, Icon } from '../icons';
import {
  MODIFIED_OPTIONS, STORAGE_GROUPS, TYPE_OPTIONS, formatDate, formatSize, modifiedRange, useDebounced, usePolling,
} from '../utils';

const PAGE_SIZE = 25;

const MODES = {
  storage: { title: 'Storage', subtitle: 'Reliable, redundant storage for your files: every file is kept as several verified copies on independent servers.', sort: 'size', dir: 'desc' },
  recent: { title: 'Recent', subtitle: 'Files you uploaded or changed most recently.', sort: 'modified', dir: 'desc' },
  trash: { title: 'Trash', subtitle: 'Deleted files stay here for 30 days before they are removed from every server.', sort: 'modified', dir: 'desc' },
  project: { title: '', subtitle: 'Files grouped in this project.', sort: 'name', dir: 'asc' },
};

function StorageSummary() {
  const { usage } = useDrive();
  if (!usage) return <div className="storage-summary" style={{ minHeight: 132 }} />;
  const groups = STORAGE_GROUPS.map((g) => ({ ...g, bytes: g.types.reduce((s, t) => s + (usage.bytesByType[t] || 0), 0) }));
  const [amount, unit] = formatSize(usage.usedBytes).split(' ');
  const quota = usage.quotaBytes || 1;
  return (
    <div className="storage-summary">
      <div className="storage-figure">
        <span className="storage-amount">{amount}</span> <span className="storage-unit">{unit}</span>
        <span className="storage-of"> of {formatSize(usage.quotaBytes)} used</span>
      </div>
      <div className="storage-bar" role="img" aria-label="Storage used by file type">
        {groups.filter((g) => g.bytes > 0).map((g) => (
          <span key={g.key} style={{ width: `${Math.max(0.6, (g.bytes / quota) * 100)}%`, background: g.color }} title={`${g.label}: ${formatSize(g.bytes)}`} />
        ))}
      </div>
      <div className="storage-legend">
        {groups.map((g) => (
          <span key={g.key}><i style={{ background: g.color }} /> {g.label}{g.bytes > 0 && <span className="muted"> · {formatSize(g.bytes)}</span>}</span>
        ))}
      </div>
    </div>
  );
}

function MoveDialog({ file, onClose, onDone }) {
  const { projects } = useDrive();
  const toast = useToast();
  async function move(projectId) {
    try {
      await api.moveToProject(file.objectId, projectId);
      toast(projectId ? 'Moved to project' : 'Removed from project', 'success');
      onDone();
      onClose();
    } catch (e) {
      toast(e.message, 'error');
    }
  }
  return (
    <Modal onClose={onClose}>
      <h2>Move “{file.fileName}”</h2>
      {projects.length === 0 && <p className="muted">You have no projects yet. Create one from the Projects page.</p>}
      <div className="move-list">
        {file.projectId && (
          <button className="menu-item" onClick={() => move(null)}><Icon name="close" size={20} /><span>Remove from project</span></button>
        )}
        {projects.map((p) => (
          <button key={p.projectId} className="menu-item" onClick={() => move(p.projectId)} disabled={p.projectId === file.projectId}>
            <Icon name="folder" size={20} /><span>{p.name}</span>
            {p.projectId === file.projectId && <span className="muted"> · current</span>}
          </button>
        ))}
      </div>
      <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}><button onClick={onClose}>Cancel</button></div>
    </Modal>
  );
}

/** One component behind Storage, Recent, Trash and each Project: same list, different query and actions. */
export default function FilesView({ mode }) {
  const { projectId } = useParams();
  const cfg = MODES[mode];
  const { search, version, refresh, openUpload, projects } = useDrive();
  const toast = useToast();
  const q = useDebounced(search);

  const [type, setType] = useState('');
  const [modified, setModified] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState(cfg.sort);
  const [dir, setDir] = useState(cfg.dir);
  const [selected, setSelected] = useState(null);
  const [moving, setMoving] = useState(null);
  const [confirmEmpty, setConfirmEmpty] = useState(false);
  const [over, setOver] = useState(false);

  const project = mode === 'project' ? projects.find((p) => p.projectId === projectId) : null;
  const params = { page, size: PAGE_SIZE, q, type, sort, dir, trashed: mode === 'trash', projectId: mode === 'project' ? projectId : '', ...modifiedRange(modified) };
  const { data, error, loading, reload } = usePolling(
    () => api.listFiles(params), 10000, [page, q, type, modified, sort, dir, mode, projectId, version],
  );
  const items = data?.items || [];
  const filtered = !!(q || type || modified);
  const inTrash = mode === 'trash';

  function setFilter(setter) {
    return (v) => { setter(v); setPage(0); };
  }

  function toggleSort(field) {
    setPage(0);
    if (sort === field) setDir(dir === 'asc' ? 'desc' : 'asc');
    else { setSort(field); setDir(field === 'name' ? 'asc' : 'desc'); }
  }

  const act = async (fn, okMessage) => {
    try {
      await fn();
      if (okMessage) toast(okMessage, 'success');
      refresh();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  function onDrop(e) {
    e.preventDefault();
    setOver(false);
    if (!inTrash && e.dataTransfer.files.length) openUpload(e.dataTransfer.files, mode === 'project' ? projectId : undefined);
  }

  const SortHead = ({ field, children, right }) => (
    <button className={`sort-head ${right ? 'right' : ''} ${sort === field ? 'active' : ''}`} onClick={() => toggleSort(field)}>
      {children}
      {sort === field && <Icon name={dir === 'asc' ? 'up' : 'down'} size={16} />}
    </button>
  );

  const showModified = mode !== 'storage';

  return (
    <div
      className={`files-view ${over ? 'drop-over' : ''}`}
      onDragOver={(e) => { if (!inTrash) { e.preventDefault(); setOver(true); } }}
      onDragLeave={(e) => e.currentTarget === e.target && setOver(false)}
      onDrop={onDrop}
    >
      <PageHero
        crumbs={mode === 'project' ? [['Vault', '/'], ['Projects', '/projects'], [project?.name || '…']] : [['Vault', '/'], [cfg.title]]}
        title={mode === 'project' ? project?.name || 'Project' : cfg.title}
        subtitle={cfg.subtitle}
        actions={!inTrash && (
          <>
            <button className="pill-ink lg" onClick={() => openUpload([], mode === 'project' ? projectId : undefined)}>
              <Icon name="upload" size={20} />Upload files{mode === 'project' ? ' to this project' : ''}
            </button>
            {mode === 'storage' && <Link to="/projects" className="pill-outline lg">Organise into projects</Link>}
          </>
        )}
      >
        {mode === 'storage' && <StorageSummary />}
      </PageHero>

      <div className="container page-body">
      {inTrash && (
        <div className="trash-banner">
          <span>Items in trash are deleted forever after 30 days.</span>
          <button className="text-btn" onClick={() => setConfirmEmpty(true)} disabled={items.length === 0}>Empty trash</button>
        </div>
      )}

      <div className="filter-row">
        <FilterPill label="Type" options={TYPE_OPTIONS} value={type} onChange={setFilter(setType)} />
        <FilterPill label="Modified" options={MODIFIED_OPTIONS} value={modified} onChange={setFilter(setModified)} />
        {filtered && (
          <button className="text-btn" onClick={() => { setType(''); setModified(''); setPage(0); }}>Clear filters</button>
        )}
      </div>

      {error && <div className="error-box">{error.message} <button className="small" onClick={reload}>Retry</button></div>}

      <div className={`file-list ${showModified ? 'with-modified' : ''}`}>
        <div className="file-head">
          <SortHead field="name">Name</SortHead>
          {showModified && <SortHead field="modified">{inTrash ? 'Last modified' : 'Last modified'}</SortHead>}
          <span className="head-static">Copies</span>
          <SortHead field="size" right>{mode === 'storage' ? 'Storage used' : 'File size'}</SortHead>
          <span />
        </div>

        {items.map((f) => (
          <div key={f.objectId} className="file-row" onClick={() => !inTrash && setSelected(f.objectId)}>
            <div className="file-name">
              <FileIcon category={f.category} />
              <span className="file-title" title={f.fileName}>{f.fileName}</span>
              {f.projectId && mode !== 'project' && <Icon name="folder" size={16} className="muted-icon" />}
            </div>
            {showModified && <div className="muted">{formatDate(f.updatedAt)}</div>}
            <div className="file-copies" title={f.healthyReplicas >= f.replicationFactor ? 'All copies are stored and verified' : 'Some copies are being restored'}>
              <i className={f.healthyReplicas >= f.replicationFactor ? 'ok' : f.healthyReplicas > 0 ? 'warn' : 'bad'} />
              {f.healthyReplicas} of {f.replicationFactor}
            </div>
            <div className="file-size">{formatSize(f.size)}</div>
            <div className="file-actions" onClick={(e) => e.stopPropagation()}>
              {inTrash ? (
                <>
                  <button className="icon-btn" title="Restore" onClick={() => act(() => api.restore(f.objectId), 'Restored')}><Icon name="restore" size={20} /></button>
                  <button className="icon-btn" title="Delete forever" onClick={() => act(() => api.remove(f.objectId, true), 'Deleted forever')}><Icon name="trash" size={20} /></button>
                </>
              ) : (
                <>
                  <button className="icon-btn" title="Download" onClick={() => api.download(f.objectId, f.fileName).catch((e) => toast(e.message, 'error'))}><Icon name="download" size={20} /></button>
                  <button className="icon-btn" title="Move to trash" onClick={() => act(() => api.remove(f.objectId), 'Moved to trash')}><Icon name="trash" size={20} /></button>
                  <Popover align="right" trigger={({ toggle }) => <button className="icon-btn" title="More actions" onClick={toggle}><Icon name="more" size={20} /></button>}>
                    <MenuItem icon="info" onClick={() => setSelected(f.objectId)}>Details</MenuItem>
                    <MenuItem icon="folder" onClick={() => setMoving(f)}>Move to project</MenuItem>
                    <MenuItem icon="download" onClick={() => api.download(f.objectId, f.fileName).catch((e) => toast(e.message, 'error'))}>Download</MenuItem>
                    <MenuItem icon="trash" onClick={() => act(() => api.remove(f.objectId), 'Moved to trash')}>Move to trash</MenuItem>
                  </Popover>
                </>
              )}
            </div>
          </div>
        ))}

        {!loading && items.length === 0 && (
          <div className="files-empty">
            <Icon name={inTrash ? 'trash' : filtered ? 'search' : 'cloud'} size={64} />
            <h2>
              {filtered ? 'No results found' : inTrash ? 'Trash is empty' : mode === 'project' ? 'This project is empty' : mode === 'recent' ? 'Nothing here yet' : 'A place for all of your files'}
            </h2>
            <p className="muted">
              {filtered ? 'Try different keywords or remove filters.'
                : inTrash ? 'Items you move to trash stay here for 30 days.'
                : 'Drag files anywhere on this page, or use the Upload button.'}
            </p>
          </div>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="pager">
          <span className="muted">Page {data.page + 1} of {data.totalPages}</span>
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
      </div>

      {over && <div className="drop-hint">Drop files to upload{mode === 'project' && project ? ` to “${project.name}”` : ''}</div>}

      {selected && <ObjectDrawer objectId={selected} onClose={() => setSelected(null)} onChanged={refresh} />}
      {moving && <MoveDialog file={moving} onClose={() => setMoving(null)} onDone={refresh} />}
      {confirmEmpty && (
        <Modal onClose={() => setConfirmEmpty(false)}>
          <h2>Delete all items in trash forever?</h2>
          <p className="muted">You can't undo this. Every copy is removed from all storage servers.</p>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button onClick={() => setConfirmEmpty(false)}>Cancel</button>
            <button className="danger solid" onClick={() => { setConfirmEmpty(false); act(() => api.emptyTrash(), 'Trash emptied'); }}>Delete forever</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
