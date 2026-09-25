import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, /api and /actuator are proxied to the Vault API, so the browser sees a single origin.
// To call the API directly instead, set VITE_API_URL (CORS is enabled for :5173 and :3000 on the backend).
const target = process.env.VAULT_API_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': target,
      '/actuator': target,
    },
  },
});
