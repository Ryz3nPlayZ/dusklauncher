#!/bin/bash
# Deploy the Dusk cosmetics server to the box ~/ssh-connect.sh points at.
#
#   server/deploy.sh            # sync, build, (re)start, wire into Caddy
#   DUSK_HOST=1.2.3.4 ...       # a different box
#
# Idempotent: re-running just rebuilds the image and reloads Caddy.
set -euo pipefail
cd "$(dirname "$0")"

HOST="${DUSK_HOST:-129.213.43.152}"
USER="${DUSK_USER:-ubuntu}"
KEY="${DUSK_KEY:-$HOME/Downloads/ssh-key-2026-04-19.key}"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "$USER@$HOST")

echo "→ syncing server/ to $USER@$HOST:~/dusk"
rsync -az --delete -e "ssh -i $KEY" --exclude target --exclude '*.db' ./ "$USER@$HOST:dusk/"

# the box's login shell is fish, so run the remote steps through bash
echo "→ building + starting dusk_api, wiring Caddy"
"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/dusk
sudo docker compose up -d --build 2>&1 | tail -5
if ! grep -q "dusk.129-213-43-152.sslip.io" ~/cloud/Caddyfile; then
  printf "\n" >> ~/cloud/Caddyfile
  cat ~/dusk/Caddyfile.snippet >> ~/cloud/Caddyfile
  echo "  appended Caddyfile.snippet"
fi
sudo docker exec -w /etc/caddy cloud-caddy-1 caddy reload --config /etc/caddy/Caddyfile
REMOTE

echo "→ health"
sleep 2
curl -fsS "https://dusk.${HOST//./-}.sslip.io/health" && echo
