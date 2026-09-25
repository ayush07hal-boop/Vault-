import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { MenuItem, PageHero, Popover } from '../components/DriveUi';
import { Modal, useToast } from '../components/ui';
import { useDrive } from '../drive';
import { Icon } from '../icons';

/** Create / rename dialog shared by the New menu and this page. */
export function ProjectDialog({ project, onClose, onDone }) {
  const toast = useToast();
  const [name, setName] = useState(project?.name || '');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    try {
      if (project) await api.renameProject(project.projectId, name);
      else await api.createProject(name);
      onDone();
      onClose();
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal onClose={onClose}>
      <form onSubmit={submit}>
        <h2>{project ? 'Rename project' : 'New project'}</h2>
        <label className="field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Untitled project" maxLength={100} autoFocus />
        </label>
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <button type="button" onClick={onClose}>Cancel</button>
          <button className="primary" type="submit" disabled={busy || !name.trim()}>{project ? 'Save' : 'Create'}</button>
        </div>
      </form>
    </Modal>
  );
}

export default function ProjectsView() {
  const { projects, refresh } = useDrive();
  const toast = useToast();
  const navigate = useNavigate();
  const [dialog, setDialog] = useState(null); // {} = new, {project} = rename
  const [removing, setRemoving] = useState(null);

  async function remove(p) {
    try {
      await api.deleteProject(p.projectId);
      toast('Project deleted. Its files are still in your storage.', 'success');
      refresh();
    } catch (e) {
      toast(e.message, 'error');
    }
    setRemoving(null);
  }

  return (
    <div className="files-view">
      <PageHero
        crumbs={[['Vault', '/'], ['Projects']]}
        title="Projects"
        subtitle="Group related files together. Deleting a project never deletes its files."
        actions={<button className="pill-ink lg" onClick={() => setDialog({})}><Icon name="add" size={20} />New project</button>}
      />
      <div className="container page-body">

      {projects.length === 0 ? (
        <div className="files-empty">
          <Icon name="projects" size={64} />
          <h2>Group your files into projects</h2>
          <p className="muted">Create a project, then upload files into it or move existing files there.</p>
          <button className="primary" onClick={() => setDialog({})}>New project</button>
        </div>
      ) : (
        <div className="project-grid">
          {projects.map((p) => (
            <div key={p.projectId} className="project-card" onClick={() => navigate(`/projects/${p.projectId}`)}>
              <Icon name="folder" size={24} className="muted-icon" />
              <div className="project-info">
                <div className="project-name" title={p.name}>{p.name}</div>
                <div className="muted">{p.fileCount} file{p.fileCount === 1 ? '' : 's'}</div>
              </div>
              <div onClick={(e) => e.stopPropagation()}>
                <Popover align="right" trigger={({ toggle }) => <button className="icon-btn" onClick={toggle} title="More actions"><Icon name="more" size={20} /></button>}>
                  <MenuItem icon="edit" onClick={() => setDialog({ project: p })}>Rename</MenuItem>
                  <MenuItem icon="trash" onClick={() => setRemoving(p)}>Delete project</MenuItem>
                </Popover>
              </div>
            </div>
          ))}
        </div>
      )}

      </div>

      {dialog && <ProjectDialog project={dialog.project} onClose={() => setDialog(null)} onDone={refresh} />}
      {removing && (
        <Modal onClose={() => setRemoving(null)}>
          <h2>Delete “{removing.name}”?</h2>
          <p className="muted">Only the project is removed. Its {removing.fileCount} file(s) stay in your storage.</p>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button onClick={() => setRemoving(null)}>Cancel</button>
            <button className="danger solid" onClick={() => remove(removing)}>Delete project</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
