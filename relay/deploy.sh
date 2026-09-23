#!/usr/bin/env bash
# One-shot relay deploy: fatJar → fly deploy → ACCESS_TOKENS sync from
# ../relay.properties. The secret drift + stale-image failure mode this
# prevents cost a debugging session — see docs/on-device-validation.md.
# Run: ./deploy.sh   (from relay/; needs flyctl + gradlew on PATH)
set -euo pipefail
cd "$(dirname "$0")"

APP=simtether-relay
ROOT=..
PROP="$ROOT/relay.properties"

[ -f "$PROP" ] || { echo "missing $PROP — needs relayToken=…" >&2; exit 1; }
TOKEN=$(grep '^relayToken=' "$PROP" | cut -d= -f2 | tr -d '[:space:]')
[ -n "$TOKEN" ] || { echo "relayToken empty in $PROP" >&2; exit 1; }

echo "==> :relay:fatJar"
"$ROOT/gradlew" -p "$ROOT" :relay:fatJar

echo "==> fly deploy"
fly deploy -a "$APP"

# Secrets update restarts the machine — second roll is expected.
echo "==> sync ACCESS_TOKENS from relay.properties"
fly secrets set ACCESS_TOKENS="$TOKEN" ACCESS_TOKEN="$TOKEN" -a "$APP"

echo "==> done — relay deployed and token synced"
