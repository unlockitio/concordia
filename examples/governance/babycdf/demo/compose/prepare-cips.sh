#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/../../../../.." && pwd)
CIPS_REPO=${CIPS_REPO:-"$ROOT/../cips"}
SHA=8e36c20e5106014efb8348f749d9535afbc48921
if ! git -C "$CIPS_REPO" cat-file -e "$SHA^{commit}" 2>/dev/null; then
  printf 'Required local CIPS commit %s is missing from %s; provision that object before running preparation. No fetch or checkout is performed.\n' "$SHA" "$CIPS_REPO" >&2
  exit 1
fi
DEST="$HERE/.cips/$SHA"
# Unique extraction avoids stale files, with no recursive deletion. Only the
# service's source subtree is exported; sibling API services are not used.
mkdir -p "$HERE/.cips"
STAGE=$(mktemp -d "$HERE/.cips/export-XXXXXXXX")
git -C "$CIPS_REPO" archive --output="$STAGE/source.tar" "$SHA" cn-credentials-standard
tar -xf "$STAGE/source.tar" -C "$STAGE"
if [ -e "$DEST" ]; then
  # Immutable commit export already prepared: validate relevant source parity.
  cmp "$STAGE/cn-credentials-standard/demo/compose.yaml" "$DEST/cn-credentials-standard/demo/compose.yaml"
  cmp "$STAGE/cn-credentials-standard/Dockerfile.canton" "$DEST/cn-credentials-standard/Dockerfile.canton"
  cmp "$STAGE/cn-credentials-standard/demo/canton/sandbox.conf" "$DEST/cn-credentials-standard/demo/canton/sandbox.conf"
  cmp "$STAGE/cn-credentials-standard/demo/canton/bootstrap.canton" "$DEST/cn-credentials-standard/demo/canton/bootstrap.canton"
else
  mv "$STAGE" "$DEST"
fi
printf 'Prepared pinned CIPS %s (temporary exports retained)\n' "$SHA"
