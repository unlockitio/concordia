#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Export local pinned CIPS source; never git-fetch or switch checkouts.
# Downloads only the missing official, checksum-pinned HoldingV1 DAR.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/../../.." && pwd)
CIPS_REPO=${CIPS_REPO:-"$ROOT/../cips"}
. "$HERE/build-root.sh"
babydro_build_root "$HERE"
CREDENTIALS_SHA=8e36c20e5106014efb8348f749d9535afbc48921
CONCORDIA_SHA=ac556495684c0c713f6752763b483110ab167935
SOURCE=$(mktemp -d "$BABYDRO_BUILD_ROOT/babydro-credentials-XXXXXXXX")
git -C "$CIPS_REPO" cat-file -e "$CREDENTIALS_SHA^{commit}"
git -C "$ROOT" cat-file -e "$CONCORDIA_SHA^{commit}"
git -C "$CIPS_REPO" archive --output="$SOURCE/source.tar" "$CREDENTIALS_SHA"
tar -xf "$SOURCE/source.tar" -C "$SOURCE"
DEMO="$SOURCE/cn-credentials-standard/demo"
mkdir -p "$DEMO/.deps" "$HERE/.daml/dependencies"
cp "$ROOT/lib/splice-api-token-metadata-v1-1.0.0.dar" "$DEMO/.deps/"
# Official immutable Splice artifact; not a host tool installation.
HOLDING="$HERE/.daml/dependencies/splice-api-token-holding-v1-1.0.0.dar"
if [ ! -f "$HOLDING" ]; then
  # A failed download remains a separate ignored artifact, never the final DAR.
  # A retry allocates a new file; no destructive cleanup is required.
  HOLDING_PART=$(mktemp "$HERE/.daml/dependencies/holding-download-XXXXXXXX")
  curl --fail --location --output "$HOLDING_PART" 'https://raw.githubusercontent.com/canton-network/splice/a710980b66875547e1104e41750cea385620a3ff/daml/dars/splice-api-token-holding-v1-1.0.0.dar'
  printf '%s  %s\n' 'ef75f8eb41a65810221784fdb78bb9dfac7cb22245aba14fa7cb7f69c34e0175' "$HOLDING_PART" | shasum -a 256 -c -
  mv "$HOLDING_PART" "$HOLDING"
fi
printf '%s  %s\n' 'ef75f8eb41a65810221784fdb78bb9dfac7cb22245aba14fa7cb7f69c34e0175' "$HOLDING" | shasum -a 256 -c -
cp "$HOLDING" "$DEMO/.deps/"
(cd "$DEMO/.deps" && printf '%s\n' '455eb160cb5abd4ae9918a6fbb9dad471f721adda39f0e5c76feef08d05637fc  splice-api-token-metadata-v1-1.0.0.dar' | shasum -a 256 -c -)
MANIFEST=$(babydro_manifest_dir "$BABYDRO_BUILD_ROOT" "$DEMO/interface" "$DEMO/model")
(cd "$MANIFEST" && DAML_PACKAGE="$DEMO/interface" dpm build --enable-multi-package=yes --multi-package-path "$MANIFEST")
(cd "$MANIFEST" && DAML_PACKAGE="$DEMO/model" dpm build --enable-multi-package=yes --multi-package-path "$MANIFEST")
cp "$DEMO/interface/.daml/dist/canton-network-credentials-interfaces-0.2.0.dar" "$HERE/.daml/dependencies/"
cp "$DEMO/model/.daml/dist/canton-network-credentials-demo-0.1.0.dar" "$HERE/.daml/dependencies/"
dpm inspect-dar "$HERE/.daml/dependencies/canton-network-credentials-interfaces-0.2.0.dar" --json > "$SOURCE/credentials-inspect.json"
grep -q '71cd9032bcba882996ea7c36b02d677ae05c4b9b83327e1a5e2f412caddad409' "$SOURCE/credentials-inspect.json"
printf 'Pinned credentials source retained at %s\n' "$SOURCE"
