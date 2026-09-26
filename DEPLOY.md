# Deploying Vault

Vault has two parts, deployed separately:

| Part | Where | Why |
|---|---|---|
| Website (React) | **Vercel** | Static files; Vercel is ideal for this |
| Backend (API + 4 storage nodes + database) | **Render** (Docker) | Needs a long-running server with a disk. Vercel cannot run this. |

Both deploy automatically from this GitHub repo on every push to `main`.

## 1. Backend on Render

1. Go to https://render.com, sign in with GitHub.
2. **New → Blueprint** → select the `Vault-` repo → it reads `render.yaml`.
3. Fill in the prompted values:
   - `VAULT_GOOGLE_CLIENT_ID`: your Google OAuth client ID
   - `VAULT_ADMIN_EMAILS`: the Google account(s) allowed to open the admin area (comma-separated)
   - `VAULT_ADMIN_PASSWORD`: the admin password (choose a strong one)
   - `VAULT_CORS_ORIGINS`: your Vercel site URL (step 2 below; you can fill it in afterwards)
4. Click **Apply**. The first build takes ~5–8 minutes. Note the service URL, e.g. `https://vault-api.onrender.com`.

Cost: the blueprint uses Render's `standard` plan (2 GB RAM, about $25/month) plus a 1 GB disk. The whole backend needs about 450 MB, so the 512 MB plan is too tight.

What this runs: one container with the API, four logical storage nodes (one JVM) and an embedded database, all stored on the one persistent disk `/data`. It is fully functional (replication, repair, integrity checks, sign-in, trash, projects), but because it is one machine it does not survive that machine failing. For real fault tolerance, run the storage nodes on separate machines (see `docker-compose.yml` for the multi-container layout).

## 2. Website on Vercel

1. Go to https://vercel.com, sign in with GitHub → **Add New → Project** → import the `Vault-` repo.
2. Set **Root Directory** to `frontend`. Vercel detects Vite automatically (`frontend/vercel.json`).
3. Add one **Environment Variable**: `VITE_API_URL` = your Render URL (no trailing slash), e.g. `https://vault-api.onrender.com`.
4. **Deploy**. Note the site URL, e.g. `https://vault-xyz.vercel.app`.
5. Go back to Render → the service → **Environment** → set `VAULT_CORS_ORIGINS` to that Vercel URL → save (the service restarts).

## 3. Google sign-in

In Google Cloud Console → APIs & Services → Credentials → your OAuth client → **Authorized JavaScript origins**, add your Vercel URL (keep `http://localhost:5173` for local development). Changes can take a few minutes to apply.

## Checking it worked

- `https://<your-render-url>/actuator/health` shows `"status":"UP"`.
- Open the Vercel URL, click **Sign in with Google**, upload a file with 3 copies.
- Your admin email sees an **Admin** link (needs `VAULT_ADMIN_PASSWORD`).

## Notes

- Free Render plans sleep and have no disk, so they are not suitable; data would be lost.
- Per-account storage limit in this deployment is 100 MB (small disk). Change `vault.user-quota.bytes` in `application-demo.yml`.
- Continuous integration (`.github/workflows/ci.yml`) builds and tests every push.
