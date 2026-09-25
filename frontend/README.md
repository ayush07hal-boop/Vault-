# Vault frontend

React 19 + Vite. No UI library: plain CSS driven by a token block at the top of `src/theme.css`
(**change the colours there** — nothing else hard-codes a colour).

## Run

```bash
# 1. backend (repo root):  .\scripts\run-local.ps1      -> API on http://localhost:8080
# 2. frontend:
cd frontend
npm install
npm run dev                                             # http://localhost:5173
```

Dev requests to `/api` are proxied to the backend (`VAULT_API_TARGET` overrides the target), so there is no CORS
to configure. To call a backend on another origin, set `VITE_API_URL` (the backend allows :5173 and :3000; extend with
`VAULT_CORS_ORIGINS`). `npm run build` produces `dist/`.

Everyone signs in first (Google, or dev sign-in when the backend runs the `dev` profile). The **Admin** link only appears for allowlisted accounts; see the root README ("Accounts", "Admin").
If the backend runs with `VAULT_API_KEY`, enter the key under **Connection**.

## Screens

The signed-in interface follows Google Drive's layout (light theme, Google Sans): search bar, **New** button, sidebar with Projects / Computers / Recent / Trash / Storage.

| Route | What it does |
|---|---|
| `/` Storage | Landing page when signed out. Signed in: storage used vs quota with a per-type bar, **Type** and **Modified** filters, sortable file list (Storage used ↓), download / trash / move to project, details drawer (new version, 409 handling), drag-and-drop upload |
| `/recent` | Files by last-modified time, with the same filters |
| `/trash` | Trashed files: restore, delete forever, Empty trash; auto-deleted after 30 days |
| `/projects`, `/projects/:id` | Named groups of files: create, rename, delete (files stay), upload into a project |
| `/computers` | Placeholder: Vault has no desktop sync yet |
| `/admin` Dashboard | Health banner (UP/DEGRADED/DOWN), object/storage/node/repair stats, per-node usage, replica states, activity counters |
| `/admin/objects` | Read-only inspection of everyone's objects (owner, node placement); download and delete, no upload |
| `/admin/nodes` | Node cards with status, capacity, last heartbeat, plain-language explanation of each state |
| `/admin/operations` | Trigger health check, repair scan, integrity verification, rebalance |
| Navbar (signed out) | "Sign in with Google" button (dev email sign-in in the dev profile) |
| `/settings` | API key (gateway) |
| `/admin/login` | Admin password step, allowlisted accounts only |

## Structure

```
src/api.js            REST client (fetch + XHR for upload progress, ApiError)
src/utils.js          formatters, usePolling, useDebounced
src/theme.css         design tokens + all styles
src/components/       ui.jsx (badges, bars, drawer, modal, toasts), ObjectDrawer.jsx
src/pages/            Dashboard, Objects, Nodes, Operations, Settings
```
