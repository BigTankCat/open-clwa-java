#!/usr/bin/env bash
# Sync vendored Node/gateway reference TypeScript from the OpenClaw main repo into
# vendor/openclaw-node-ref/ (for a standalone Java-only checkout).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/vendor/openclaw-node-ref"

UPSTREAM="${1:-${OPENCLAW_UPSTREAM_ROOT:-}}"
if [[ -z "${UPSTREAM}" ]]; then
  echo "Usage: $0 /path/to/openclaw   (or set OPENCLAW_UPSTREAM_ROOT)" >&2
  exit 1
fi
if [[ ! -f "${UPSTREAM}/src/gateway/server-methods/browser.ts" ]]; then
  echo "Upstream path does not look like openclaw: ${UPSTREAM}" >&2
  exit 1
fi

mkdir -p "${DEST}/src/gateway/server-methods" "${DEST}/src/infra"

cp "${UPSTREAM}/src/gateway/server-methods/browser.ts" "${DEST}/src/gateway/server-methods/"
cp "${UPSTREAM}/src/gateway/server-methods/tts.ts" "${DEST}/src/gateway/server-methods/"
cp "${UPSTREAM}/src/gateway/server-methods/nodes.helpers.ts" "${DEST}/src/gateway/server-methods/"
cp "${UPSTREAM}/src/infra/node-commands.ts" "${DEST}/src/infra/"
cp "${UPSTREAM}/src/gateway/node-command-policy.ts" "${DEST}/src/gateway/"
cp "${UPSTREAM}/src/gateway/device-metadata-normalization.ts" "${DEST}/src/gateway/"

echo "Synced into ${DEST} from ${UPSTREAM}"
