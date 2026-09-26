# Deploy Vault on Oracle Cloud (Always Free): always on, permanent address, $0

Result: your Vercel website talks to a Vault backend that runs 24/7 on a free Oracle server, at a permanent HTTPS
address. No tunnels, no restarts, no re-deploying Vercel when things change.

What runs on the VM (all in Docker): the API, **4 separate storage-node containers**, PostgreSQL, and Caddy
(automatic HTTPS). Data lives on the VM's disk and survives reboots and updates.

Total time: about 45 minutes, most of it waiting. You need: a credit/debit card for Oracle's identity check
(Always Free resources are not charged), your Vercel site address, and your Google OAuth client ID.

---

## Step 1: Create the free Oracle server

1. Go to https://www.oracle.com/cloud/free/ and click **Start for free**. Create the account.
   - Pick the **home region** carefully; it can't be changed later. Choose one close to you.
   - The card is only for verification. Stay on the Always Free resources and nothing is charged.
2. In the console: **Compute → Instances → Create instance**.
   - **Name:** `vault`
   - **Image:** click *Change image* → **Canonical Ubuntu** → 24.04 (or 22.04).
   - **Shape:** click *Change shape* → **Ampere** → **VM.Standard.A1.Flex** → **2 OCPUs, 12 GB memory**
     (the free allowance is up to 4 OCPUs and 24 GB in total).
   - **Networking:** keep *Create new virtual cloud network* and make sure **Assign a public IPv4 address** is ON.
   - **SSH keys:** choose **Generate a key pair for me** and **download the private key** (a `.key` file). Keep it safe.
   - **Boot volume:** tick *Specify a custom boot volume size* and set **100 GB** (free allowance is 200 GB total).
   - Click **Create**. In a minute the instance shows **Running**; note its **Public IP address**.
   - If you get **"Out of capacity"** for the Ampere shape, try again in a few minutes or later in the day, or pick a
     different *Availability domain* in the same dialog. This is common and only temporary.

## Step 2: Open the web ports in Oracle's network

1. Open your instance → click its **Subnet** link → click the **Default Security List**.
2. **Add Ingress Rules** (two rules):
   - Source CIDR `0.0.0.0/0`, IP protocol **TCP**, Destination port **80**
   - Source CIDR `0.0.0.0/0`, IP protocol **TCP**, Destination port **443**

## Step 3: Get a free permanent hostname

Browsers need HTTPS, and HTTPS needs a name (not just an IP address).

1. Go to https://www.duckdns.org and sign in (Google login works).
2. Choose a subdomain, e.g. `vault-yourname`, and click **add domain**.
3. In the *current ip* box for that domain, paste your VM's **Public IP** from Step 1 and click **update ip**.

Your backend address will be `https://vault-yourname.duckdns.org`.

## Step 4: Connect to the server and run the setup

1. On your PC open **PowerShell** and fix the key file's permissions (Windows requires this once). Replace the path
   with where you saved the key:
   ```powershell
   $key = "C:\Users\hp\Downloads\ssh-key-2026-xx-xx.key"
   icacls $key /inheritance:r
   icacls $key /grant:r "$($env:USERNAME):(R)"
   ```
2. Connect (replace the IP):
   ```powershell
   ssh -i $key ubuntu@YOUR_VM_PUBLIC_IP
   ```
   Type `yes` if asked. You are now typing commands *on the server*.
3. Run the one-command setup:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/ayush07hal-boop/Vault-/main/deploy/oracle/setup.sh | bash
   ```
4. Answer the questions it asks:
   - Backend hostname: `vault-yourname.duckdns.org`
   - Google OAuth client ID: the one from Google Cloud Console
   - Admin Google email(s): comma-separated
   - Admin password: choose a strong one (typing is hidden)
   - Your website address: `https://vault-lovat-five.vercel.app`
5. Wait 5 to 10 minutes while it builds. At the end it prints **"Vault is live: https://…"**.

## Step 5: Point your website at it (one time only)

1. **Vercel:** Project → Settings → Environment Variables → set `VITE_API_URL` to
   `https://vault-yourname.duckdns.org` (no trailing slash) → **Deployments → ⋯ → Redeploy** (untick the build cache).
2. **Google Cloud Console:** your OAuth client → **Authorized JavaScript origins** must include
   `https://vault-lovat-five.vercel.app` (already there if sign-in worked before).

Open your Vercel site and sign in with Google. Done. This address never changes, so you won't repeat Step 5.

---

## Everyday operations (on the server, via `ssh` as in Step 4)

| I want to… | Command |
|---|---|
| Update to the latest code from GitHub | `bash ~/vault/deploy/oracle/update.sh` |
| See if everything is running | `cd ~/vault/deploy/oracle && sudo docker compose ps` |
| Read the API's logs | `cd ~/vault/deploy/oracle && sudo docker compose logs -f vault-api` |
| Restart everything | `cd ~/vault/deploy/oracle && sudo docker compose restart` |
| Change a setting (admin password, etc.) | edit `~/vault/deploy/oracle/.env`, then `sudo docker compose up -d` |

After a **server reboot** everything starts by itself (`restart: unless-stopped`).

## Things to know (honest notes)

- **Idle reclamation:** Oracle may reclaim Always Free instances that sit almost completely idle for 7 days
  (very low CPU, network and memory). If Vault gets little use, upgrading the account to **Pay As You Go** removes
  that risk, and you are still not charged while staying inside the free limits. Do that in the console under
  *Billing → Upgrade*. Optional, but recommended if you rely on this.
- **One machine:** the four storage nodes are separate containers, but they share one physical server. Vault
  handles a *node* failing, but not the whole VM being lost. Back up important data.
- **Capacity:** each node reports 8 GiB, so about 32 GiB of raw space, which is roughly 10 GiB of files at 3 copies.
  Per-account limit is 4 GiB (`VAULT_USER_QUOTA_BYTES` in `.env`).
- **Security:** only ports 80/443 are open, Postgres and the storage nodes are not reachable from the internet, and
  admin access needs an allowlisted Google account plus the admin password. Keep the `.env` on the server private.
- **Logs contain no passwords.** Do not paste `.env` anywhere.

## If something doesn't work

| Symptom | Likely cause and fix |
|---|---|
| Setup says the address is "not answering yet" | DuckDNS IP wrong, or ports 80/443 not opened in Step 2. Check `sudo docker compose logs caddy`. |
| Site loads but "Sign-in unavailable" | `VITE_API_URL` not set/redeployed on Vercel, or `VAULT_CORS_ORIGINS` doesn't include your Vercel address. |
| Google popup says `origin_mismatch` | Add your Vercel address to Authorized JavaScript origins. |
| "Out of capacity" creating the VM | Retry later or try another availability domain. |
| `ssh: Permission denied (publickey)` | Wrong key file, or the `icacls` step was skipped. |
