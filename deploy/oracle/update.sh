#!/usr/bin/env bash
# Pull the latest code from GitHub and rebuild/restart Vault. Your data (database + stored files) is kept.
# Usage on the VM:   bash ~/vault/deploy/oracle/update.sh
set -euo pipefail
cd "$(dirname "$0")"
git -C ../.. pull --ff-only
sudo docker compose up -d --build
sudo docker image prune -f >/dev/null
echo "Updated. Status:"
sudo docker compose ps
