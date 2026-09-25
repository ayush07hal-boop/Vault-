// Thin client for the Vault REST API. All calls send X-API-Key when one is saved in Settings.
const BASE = import.meta.env.VITE_API_URL || '';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const getApiKey = () => localStorage.getItem('vault.apiKey') || '';
export const setApiKey = (k) => (k ? localStorage.setItem('vault.apiKey', k) : localStorage.removeItem('vault.apiKey'));

// Signed-in account: survives reloads (localStorage). Admin elevation: this tab only (sessionStorage).
export const getUserToken = () => localStorage.getItem('vault.userToken') || '';
export const setUserToken = (t) => (t ? localStorage.setItem('vault.userToken', t) : localStorage.removeItem('vault.userToken'));
export const getAdminToken = () => sessionStorage.getItem('vault.adminToken') || '';
export const setAdminToken = (t) => (t ? sessionStorage.setItem('vault.adminToken', t) : sessionStorage.removeItem('vault.adminToken'));

function authHeaders(extra = {}) {
  const key = getApiKey();
  const user = getUserToken();
  const admin = getAdminToken();
  return {
    ...(key ? { 'X-API-Key': key } : {}),
    ...(user ? { Authorization: `Bearer ${user}` } : {}),
    ...(admin ? { 'X-Admin-Token': admin } : {}),
    ...extra,
  };
}

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(BASE + path, { ...options, headers: authHeaders(options.headers) });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the Vault API. Is the backend running?');
  }
  if (!res.ok) {
    let body = null;
    try {
      body = await res.json();
    } catch {
      /* not JSON */
    }
    if (res.status === 401 && body?.error === 'ADMIN_REQUIRED') {
      setAdminToken(''); // admin session expired: the admin area sends the user back to the admin login
      window.dispatchEvent(new Event('vault:admin-expired'));
    }
    if (res.status === 401 && body?.error === 'USER_REQUIRED') {
      setUserToken('');
      setAdminToken('');
      window.dispatchEvent(new Event('vault:user-expired'));
    }
    throw new ApiError(res.status, body?.error, body?.message || res.statusText);
  }
  return res;
}

const json = async (path, options) => (await request(path, options)).json();
const post = (path, body) =>
  json(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });


/** XHR (not fetch) so uploads can report progress. */
function xhrUpload(method, path, file, headers, onProgress, onXhr) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    onXhr?.(xhr); // lets the caller abort()
    xhr.onabort = () => reject(new ApiError(0, 'ABORTED', 'Upload cancelled'));
    xhr.open(method, BASE + path);
    Object.entries(authHeaders(headers)).forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total);
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', 'Cannot reach the Vault API. Is the backend running?'));
    xhr.onload = () => {
      let body = null;
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        /* empty */
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body);
      else reject(new ApiError(xhr.status, body?.error, body?.message || xhr.statusText));
    };
    const form = new FormData();
    form.append('file', file);
    xhr.send(form);
  });
}

export const api = {
  authConfig: () => json('/api/v1/auth/config'),
  googleLogin: (credential) => post('/api/v1/auth/google', { credential }),
  devLogin: (email) => post('/api/v1/auth/dev-login', { email }),
  adminLogin: (password) => post('/api/v1/auth/admin-login', { password }),
  me: () => json('/api/v1/auth/me'),

  stats: () => json('/api/v1/stats'),
  health: () => json('/api/v1/health'),
  nodes: () => json('/api/v1/nodes'),
  /** scope 'all' (admin only) lists everyone's objects; default is the signed-in user's own. */
  list: (page, size, q, scope = 'mine') =>
    json(`/api/v1/objects?page=${page}&size=${size}&scope=${scope}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  metadata: (id) => json(`/api/v1/objects/${id}/metadata`),

  upload: (file, replicationFactor, onProgress, projectId, onXhr) =>
    xhrUpload(
      'POST',
      `/api/v1/objects?${new URLSearchParams({ ...(replicationFactor ? { replicationFactor } : {}), ...(projectId ? { projectId } : {}) })}`,
      file,
      { 'Idempotency-Key': crypto.randomUUID() },
      onProgress,
      onXhr,
    ),
  /** New version; fails with 409 VERSION_CONFLICT if expectedVersion is stale. */
  update: (id, file, expectedVersion, onProgress) =>
    xhrUpload('PUT', `/api/v1/objects/${id}?expectedVersion=${expectedVersion}`, file, {}, onProgress),
  /** Admins always delete permanently; for users this moves the file to the trash unless permanent is true. */
  remove: (id, permanent = false) =>
    request(`/api/v1/objects/${id}${permanent ? '?permanent=true' : ''}`, { method: 'DELETE' }),
  restore: (id) => request(`/api/v1/objects/${id}/restore`, { method: 'POST' }),
  emptyTrash: () => json('/api/v1/objects/trash/empty', { method: 'POST' }),
  usage: () => json('/api/v1/objects/usage'),

  /** Full listing with filters: {page,size,q,trashed,type,modifiedAfter,modifiedBefore,projectId,sort,dir}. */
  listFiles: (params) => {
    const qs = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => v !== undefined && v !== null && v !== '' && qs.set(k, String(v)));
    return json(`/api/v1/objects?${qs}`);
  },

  projects: () => json('/api/v1/projects'),
  createProject: (name) => post('/api/v1/projects', { name }),
  renameProject: (id, name) =>
    json(`/api/v1/projects/${id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) }),
  deleteProject: (id) => request(`/api/v1/projects/${id}`, { method: 'DELETE' }),
  moveToProject: (objectId, projectId) =>
    json(`/api/v1/objects/${objectId}/project`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId }),
    }),

  /** Downloads via fetch so the API key header is sent, then hands the blob to the browser. */
  async download(id, fileName) {
    const res = await request(`/api/v1/objects/${id}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName || id;
    a.click();
    URL.revokeObjectURL(url);
  },

  admin: (action) => json(`/api/v1/admin/${action}`, { method: 'POST' }),
};

