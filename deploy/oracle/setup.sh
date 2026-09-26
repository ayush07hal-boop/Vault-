#!/usr/bin/env bash
# One-command setup for a fresh Ubuntu VM (tested target: Oracle Cloud Always Free, Ubuntu 22.04/24.04, ARM or x86).
# Usage on the VM:   curl -fsSL https://raw.githubusercontent.com/ayush07hal-boop/Vault-/main/deploy/oracle/setup.sh | bash
# Safe to run again: it keeps your answers in deploy/oracle/.env and just rebuilds/starts the stack.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/ayush07hal-boop/Vault-.git}"
APP_DIR="${APP_DIR:-$HOME/vault}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

say "1/5  Installing Docker (if missing)"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo systemctl enable --now docker >/dev/null 2>&1 || true

say "2/5  Opening ports 80 and 443 in the VM firewall"
# Oracle's Ubuntu images ship with iptables rules that reject everything except SSH.
for port in 80 443; do
  if ! sudo iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; then
    sudo iptables -I INPUT 5 -p tcp --dport "$port" -j ACCEPT
  fi
done
if ! dpkg -s iptables-persistent >/dev/null 2>&1; then
  echo iptables-persistent iptables-persistent/autosave_v4 boolean true | sudo debconf-set-selections
  echo iptables-persistent iptables-persistent/autosave_v6 boolean true | sudo debconf-set-selections
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent >/dev/null
fi
sudo netfilter-persistent save >/dev/null 2>&1 || true

say "3/5  Getting the code"
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" pull --ff-only
else
  sudo apt-get install -y git >/dev/null 2>&1 || true
  git clone "$REPO_URL" "$APP_DIR"
fi
cd "$APP_DIR/deploy/oracle"

say "4/5  Configuration"
if [ -f .env ]; then
  echo "Found existing .env, keeping it (delete deploy/oracle/.env to answer the questions again)."
else
  echo "Answer a few questions (you can change them later by editing deploy/oracle/.env):"
  read -rp "  Backend hostname (e.g. vault-yourname.duckdns.org): " DOMAIN </dev/tty
  read -rp "  Google OAuth client ID: " GCLIENT </dev/tty
  read -rp "  Admin Google email(s), comma-separated: " ADMINS </dev/tty
  read -rsp "  Admin password (typing is hidden): " ADMINPW </dev/tty; echo
  read -rp "  Your website address on Vercel (e.g. https://vault-xyz.vercel.app): " SITE </dev/tty
  {
    echo "DOMAIN=$DOMAIN"
    echo "VAULT_GOOGLE_CLIENT_ID=$GCLIENT"
    echo "VAULT_ADMIN_EMAILS=$ADMINS"
    echo "VAULT_ADMIN_PASSWORD=$ADMINPW"
    echo "VAULT_CORS_ORIGINS=${SITE%/}"
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "VAULT_TOKEN_SECRET=$(openssl rand -hex 32)"
  } > .env
  chmod 600 .env
fi

say "5/5  Building and starting Vault (the first build takes 5-10 minutes)"
sudo docker compose up -d --build

DOMAIN_VALUE="$(grep '^DOMAIN=' .env | cut -d= -f2-)"
say "Done. Waiting for the API to answer..."
for i in $(seq 1 40); do
  if curl -fsS "https://$DOMAIN_VALUE/api/v1/auth/config" >/dev/null 2>&1; then
    echo
    echo "Vault is live:  https://$DOMAIN_VALUE"
    echo "In Vercel set  VITE_API_URL = https://$DOMAIN_VALUE  and redeploy once."
    exit 0
  fi
  sleep 5
done
echo
echo "The stack is running but https://$DOMAIN_VALUE is not answering yet."
echo "Most common causes: the hostname does not point at this VM's public IP yet, or ports 80/443 are not open"
echo "in the Oracle console (VCN > Security List > Ingress rules). Check with:  sudo docker compose logs caddy"
