#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/../../../../.." && pwd)
CIPS_REPO=${CIPS_REPO:-"$ROOT/../cips"}
SHA=8e36c20e5106014efb8348f749d9535afbc48921
git -C "$CIPS_REPO" cat-file -e "$SHA^{commit}" || {
  echo "Required local CIPS commit $SHA missing; no fetch or checkout performed." >&2
  exit 1
}
mkdir -p "$HERE/.cips"
STAGE=$(mktemp -d "$HERE/.cips/export-XXXXXXXX")
git -C "$CIPS_REPO" archive --output="$STAGE/source.tar" "$SHA" cn-credentials-standard
tar -xf "$STAGE/source.tar" -C "$STAGE"
DEST="$HERE/.cips/$SHA"
if [ -e "$DEST" ]; then
  # Compare the entire exported source, not just the Compose entry point.
  diff -r "$STAGE/cn-credentials-standard" "$DEST/cn-credentials-standard"
else
  mv "$STAGE" "$DEST"
fi
printf 'Prepared pinned CIPS %s (temporary exports retained)\n' "$SHA"
