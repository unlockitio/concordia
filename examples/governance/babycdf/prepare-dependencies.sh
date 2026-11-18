#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Build pinned candidate credentials without switching or editing its checkout.
# Requires existing dpm 1.0.22 and SDK 3.5.11; installs nothing.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/../../.." && pwd)
CIPS_REPO=${CIPS_REPO:-"$ROOT/../cips"}
# Same explicit-override contract as run-demo.sh, enforced before any creation:
# unset defaults to the gitignored .build; an explicit value must be absolute and
# already exist, and an invalid one creates nothing.
. "$HERE/build-root.sh"
babycdf_build_root "$HERE"
CREDENTIALS_SHA=8e36c20e5106014efb8348f749d9535afbc48921
CONCORDIA_SHA=ac556495684c0c713f6752763b483110ab167935
SOURCE=$(mktemp -d "$BABYCDF_BUILD_ROOT/babycdf-credentials-XXXXXXXX")
# Both pinned commits must already exist locally; this script never fetches.
# Separate steps preserve archive failure under POSIX sh (no pipefail needed).
git -C "$CIPS_REPO" cat-file -e "$CREDENTIALS_SHA^{commit}"
git -C "$ROOT" cat-file -e "$CONCORDIA_SHA^{commit}"
git -C "$CIPS_REPO" archive --output="$SOURCE/source.tar" "$CREDENTIALS_SHA"
tar -xf "$SOURCE/source.tar" -C "$SOURCE"
DEMO="$SOURCE/cn-credentials-standard/demo"
mkdir -p "$DEMO/.deps" "$HERE/.daml/dependencies"
cp "$ROOT/lib/splice-api-token-metadata-v1-1.0.0.dar" "$DEMO/.deps/"
(cd "$DEMO/.deps" && printf '%s\n' '455eb160cb5abd4ae9918a6fbb9dad471f721adda39f0e5c76feef08d05637fc  splice-api-token-metadata-v1-1.0.0.dar' | shasum -a 256 -c -)
# dpm walks up to the nearest ancestor multi-package.yaml. Inside Concordia that
# is the repository root, which does not list this export, so the build fails.
# Point dpm at a generated manifest in the build root that names exactly this
# package. The root manifest and `dpm build --all` are unaffected.
# Keep upstream LF2.2.
# Isolated, script-owned manifest directory: never touches a caller-owned
# $BABYCDF_BUILD_ROOT/multi-package.yaml, and concurrent preparations cannot
# overwrite each other. dpm 1.0.22 expects the directory (it appends the
# filename); passing the file itself fails.
# Run from the isolated manifest directory: dpm still resolves an ancestor
# multi-package.yaml from the CWD, and the export ships its own nested one.
CREDENTIALS_MANIFEST=$(babycdf_manifest_dir "$BABYCDF_BUILD_ROOT" "$DEMO/interface")
(cd "$CREDENTIALS_MANIFEST" && DAML_PACKAGE="$DEMO/interface" \
  dpm build --enable-multi-package=yes --multi-package-path "$CREDENTIALS_MANIFEST")
cp "$DEMO/interface/.daml/dist/canton-network-credentials-interfaces-0.2.0.dar" "$HERE/.daml/dependencies/"
# Same-repository pinned binary; upstream Splice revision remains unverified.
git -C "$ROOT" show "$CONCORDIA_SHA:examples/lib/splice-amulet-0.1.22.dar" > "$HERE/.daml/dependencies/splice-amulet-0.1.22.dar"
# Package identity is stable even when DAR ZIP metadata is not byte-reproducible.
dpm inspect-dar "$HERE/.daml/dependencies/canton-network-credentials-interfaces-0.2.0.dar" --json > "$SOURCE/credentials-inspect.json"
grep -q '71cd9032bcba882996ea7c36b02d677ae05c4b9b83327e1a5e2f412caddad409' "$SOURCE/credentials-inspect.json"
(cd "$HERE/.daml/dependencies" && printf '%s\n' 'bcfcd6a9250172a8384d1326a2990b374c66a367ca7d4120b243aecfd372761e  splice-amulet-0.1.22.dar' | shasum -a 256 -c -)
printf 'Credentials source retained at %s\n' "$SOURCE"
