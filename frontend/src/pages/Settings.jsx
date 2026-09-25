import { useState } from 'react';
import { api, getApiKey, setApiKey } from '../api';
import { useToast } from '../components/ui';

export default function Settings() {
  const toast = useToast();
  const [key, setKey] = useState(getApiKey());

  async function save(e) {
    e.preventDefault();
    setApiKey(key.trim());
    try {
      await api.list(0, 1);
      toast(key.trim() ? 'API key saved and accepted' : 'API key cleared', 'success');
    } catch (err) {
      toast(err.status === 401 ? 'The backend rejected this API key' : err.message, 'error');
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Connection</h1>
          <div className="sub">How this browser talks to Vault</div>
        </div>
      </div>
      <form className="card" style={{ maxWidth: 520 }} onSubmit={save}>
        <h2>API key</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          Only needed if the backend was started with <span className="mono">VAULT_API_KEY</span>. It is stored in this
          browser and sent as the <span className="mono">X-API-Key</span> header.
        </p>
        <div className="row">
          <input type="password" value={key} onChange={(e) => setKey(e.target.value)} placeholder="(none)" style={{ flex: 1 }} />
          <button className="primary" type="submit">Save</button>
        </div>
      </form>
    </>
  );
}
