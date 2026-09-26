import { useEffect, useRef, useState } from 'react';
import Logo from './Logo';
import { Link, NavLink, Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { useDrive } from '../drive';
import { Icon } from '../icons';
import { formatSize } from '../utils';
import { ProjectDialog } from '../pages/ProjectsView';
import { MenuItem, Popover } from './DriveUi';

const NAV = [
  ['/', 'Storage'],
  ['/recent', 'Recent'],
  ['/projects', 'Projects'],
  ['/trash', 'Trash'],
  ['/computers', 'Computers'],
];

function Avatar({ user, size = 32 }) {
  const initial = (user.name || user.email || '?')[0].toUpperCase();
  return user.picture ? (
    <img className="avatar" style={{ width: size, height: size }} src={user.picture} alt="" referrerPolicy="no-referrer" />
  ) : (
    <span className="avatar" style={{ width: size, height: size, fontSize: size * 0.45 }}>{initial}</span>
  );
}

/** Search that collapses to an icon in the nav bar and expands on click (or "/" key). */
function NavSearch() {
  const { search, setSearch, guest } = useDrive();
  const [open, setOpen] = useState(!!search);
  const input = useRef(null);

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === '/' && !guest && !['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [guest]);

  useEffect(() => { if (open) input.current?.focus(); }, [open]);

  if (guest) return null;
  if (!open) {
    return (
      <button className="nav-search-btn" onClick={() => setOpen(true)} title="Search (/)">
        <Icon name="search" size={20} /><span>Search</span>
      </button>
    );
  }
  return (
    <label className="nav-search">
      <Icon name="search" size={20} />
      <input
        ref={input}
        type="search"
        placeholder="Search your files"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        onBlur={() => !search && setOpen(false)}
        onKeyDown={(e) => e.key === 'Escape' && (setSearch(''), setOpen(false))}
        aria-label="Search files"
      />
      {search && (
        <button type="button" className="icon-btn small-icon" onMouseDown={(e) => e.preventDefault()} onClick={() => setSearch('')} aria-label="Clear search">
          <Icon name="close" size={18} />
        </button>
      )}
    </label>
  );
}

/**
 * Everyone lands here. A dark utility bar (account, storage, admin) sits above a white navigation bar that holds
 * what used to be the sidebar: the section links, search and the primary Upload action.
 */
export default function DriveShell() {
  const { user, signOut } = useAuth();
  const { guest, setSearch, usage, openUpload, refresh } = useDrive();
  const navigate = useNavigate();
  const location = useLocation();
  const inProject = useMatch('/projects/:projectId');
  const [newProject, setNewProject] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  // Section links (Storage, Recent, ...) exist only for signed-in users; visitors just see the Vault logo and sign-in.
  const signedIn = !guest && !!user;

  useEffect(() => setMenuOpen(false), [location.pathname]);

  return (
    <div className="shell">
      <div className="utility-bar">
        <div className="utility-inner">
          <span className="utility-tag">Fault-tolerant object storage</span>
          <div className="utility-links">
            {!guest && user && (
              <>
                {usage && (
                  <Link to="/" className="utility-link" title="Storage used">
                    <span className="utility-meter"><span style={{ width: `${Math.min(100, (usage.usedBytes / (usage.quotaBytes || 1)) * 100)}%` }} /></span>
                    {formatSize(usage.usedBytes)} of {formatSize(usage.quotaBytes)}
                  </Link>
                )}
                {user.canAdmin && <Link to="/admin" className="utility-link">Admin</Link>}
                <Popover
                  align="right"
                  className="account-pop"
                  trigger={({ toggle }) => (
                    <button className="utility-link account-trigger" onClick={toggle} aria-label="Account">
                      <span className="utility-name">{user.name || user.email}</span>
                      <Icon name="caret" size={18} />
                      <Avatar user={user} size={28} />
                    </button>
                  )}
                >
                  <div className="account-card" onClick={(e) => e.stopPropagation()}>
                    <Avatar user={user} size={56} />
                    <div className="account-name">{user.name || user.email}</div>
                    <div className="muted">{user.email}</div>
                  </div>
                  {user.canAdmin && <MenuItem icon="shield" onClick={() => navigate('/admin')}>Admin area</MenuItem>}
                  <MenuItem icon="logout" onClick={() => { signOut(); navigate('/'); }}>Sign out</MenuItem>
                </Popover>
              </>
            )}
          </div>
        </div>
      </div>

      <header className="main-nav">
        <div className="main-nav-inner">
          <Link to="/" className={`nav-logo ${signedIn ? '' : 'solo'}`} onClick={() => setSearch('')}>
            <Logo size={42} />
            <span>Vault</span>
          </Link>

          {signedIn && (
          <button className="icon-btn nav-toggle" onClick={() => setMenuOpen((o) => !o)} aria-label="Menu" aria-expanded={menuOpen}>
            <Icon name={menuOpen ? 'close' : 'menu'} size={24} />
          </button>
          )}

          {signedIn && (
          <nav className={`nav-links ${menuOpen ? 'open' : ''}`}>
            {NAV.map(([to, label], i) => (
              <NavLink
                key={to}
                to={to}
                end={to === '/'}
                className={({ isActive }) => `nav-link-item ${isActive || (to === '/projects' && inProject) ? 'active' : ''} ${i === 0 ? 'first' : ''}`}
                onClick={() => setSearch('')}
              >
                {label}
              </NavLink>
            ))}
          </nav>
          )}

          <div className="nav-actions">
            <NavSearch />
            {/* signed out: nothing here; sign-in lives in the page hero */}
            {!guest && user && (
              <>
                <button className="pill-outline" onClick={() => setNewProject(true)}>New project</button>
                <button className="pill-ink" onClick={() => openUpload([], inProject?.params.projectId)}>
                  <Icon name="upload" size={18} />Upload
                </button>
              </>
            )}
          </div>
        </div>
      </header>

      <main className="shell-main">
        <Outlet />
      </main>

      {newProject && <ProjectDialog onClose={() => setNewProject(false)} onDone={() => { refresh(); navigate('/projects'); }} />}
    </div>
  );
}
