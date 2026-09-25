import { Link, NavLink, Navigate, Outlet, Route, Routes, useNavigate } from 'react-router-dom';
import { AuthProvider, RequireAdmin, useAuth } from './auth';
import DriveShell from './components/DriveShell';
import { ToastProvider } from './components/ui';
import { DriveProvider, GuestDriveProvider } from './drive';
import AdminLogin from './pages/AdminLogin';
import ComputersView from './pages/ComputersView';
import Dashboard from './pages/Dashboard';
import FilesView from './pages/FilesView';
import GuestView from './pages/GuestView';
import Nodes from './pages/Nodes';
import Objects from './pages/Objects';
import Operations from './pages/Operations';
import ProjectsView from './pages/ProjectsView';
import Settings from './pages/Settings';

/** Everyone lands in the Drive-style interface; signed-out visitors get a guest version of it. */
function SiteLayout() {
  const { user, ready } = useAuth();
  if (!ready) return <div className="empty">Loading…</div>;
  return user ? <DriveProvider><DriveShell /></DriveProvider> : <GuestDriveProvider><DriveShell /></GuestDriveProvider>;
}

/** "/" is Storage for signed-in users and the landing page for everyone else. */
function Home() {
  const { user } = useAuth();
  return user ? <FilesView mode="storage" /> : <GuestView />;
}

/** Pages that only make sense signed in: visitors see the guest view of the same screen. */
function Private({ children, title, icon }) {
  const { user } = useAuth();
  return user ? children : <GuestView title={title} icon={icon} />;
}

const ADMIN_NAV = [
  ['/admin', 'Dashboard', '◧', true],
  ['/admin/objects', 'Stored objects', '▤'],
  ['/admin/nodes', 'Nodes', '◉'],
  ['/admin/operations', 'Operations', '⚙'],
];

function AdminLayout() {
  const { user, adminSignOut } = useAuth();
  const navigate = useNavigate();
  return (
    <div className="app">
      <nav className="sidebar">
        <div className="brand"><span className="brand-mark">V</span> Vault <span className="pill">Admin</span></div>
        {ADMIN_NAV.map(([to, label, icon, end]) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <span>{icon}</span> {label}
          </NavLink>
        ))}
        <div className="sidebar-foot">
          <div className="muted" style={{ fontSize: 12, wordBreak: 'break-all' }}>{user?.email}</div>
          <Link to="/" className="nav-link" style={{ padding: '6px 2px' }}>← My files</Link>
          <button className="ghost small" onClick={() => { adminSignOut(); navigate('/'); }}>Leave admin</button>
        </div>
      </nav>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Routes>
          <Route element={<SiteLayout />}>
            <Route path="/" element={<Home />} />
            <Route path="/recent" element={<Private title="Recent" icon="recent"><FilesView mode="recent" /></Private>} />
            <Route path="/trash" element={<Private title="Trash" icon="trash"><FilesView mode="trash" /></Private>} />
            <Route path="/projects" element={<Private title="Projects" icon="projects"><ProjectsView /></Private>} />
            <Route path="/projects/:projectId" element={<Private title="Projects" icon="projects"><FilesView mode="project" /></Private>} />
            <Route path="/computers" element={<Private title="Computers" icon="computer"><ComputersView /></Private>} />
            <Route path="/settings" element={<Private title="Connection"><div className="container page-body"><Settings /></div></Private>} />
          </Route>

          <Route path="/admin/login" element={<AdminLogin />} />
          <Route path="/admin" element={<RequireAdmin><AdminLayout /></RequireAdmin>}>
            <Route index element={<Dashboard />} />
            <Route path="objects" element={<Objects admin />} />
            <Route path="nodes" element={<Nodes />} />
            <Route path="operations" element={<Operations />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </AuthProvider>
  );
}
