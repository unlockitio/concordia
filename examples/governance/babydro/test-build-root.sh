#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Hermetic contract tests: no dpm, network or ledger.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/build-root.sh"
WORK=$(mktemp -d "$HERE/.build-root-test-XXXXXXXX")
# Retain fixture for inspection rather than recursively deleting anything.
printf 'Fixture retained at %s\n' "$WORK"
mkdir -p "$WORK/package" "$WORK/with spaces"
(unset BABYDRO_BUILD_ROOT; babydro_build_root "$WORK/package"; test "$BABYDRO_BUILD_ROOT" = "$WORK/package/.build")
RC=0
(BABYDRO_BUILD_ROOT=relative; babydro_build_root "$WORK/package") >/dev/null 2>&1 || RC=$?
[ "$RC" -eq 2 ]
[ ! -e "$WORK/package/relative" ]
RC=0
(BABYDRO_BUILD_ROOT="$WORK/missing"; babydro_build_root "$WORK/package") >/dev/null 2>&1 || RC=$?
[ "$RC" -eq 2 ]
[ ! -e "$WORK/missing" ]
(BABYDRO_BUILD_ROOT="$WORK/with spaces"; babydro_build_root "$WORK/package"; test "$BABYDRO_BUILD_ROOT" = "$WORK/with spaces")
printf 'caller-owned\n' > "$WORK/multi-package.yaml"
FIRST=$(babydro_manifest_dir "$WORK" "$WORK/package one")
SECOND=$(babydro_manifest_dir "$WORK" "$WORK/package two")
[ "$FIRST" != "$SECOND" ]
[ "$(cat "$WORK/multi-package.yaml")" = caller-owned ]
grep -F -- "- \"$WORK/package one\"" "$FIRST/multi-package.yaml"
grep -F -- "- \"$WORK/package two\"" "$SECOND/multi-package.yaml"
RC=0
babydro_manifest_dir "$WORK" '/bad"path' >/dev/null 2>&1 || RC=$?
[ "$RC" -eq 2 ]
RC=0
babydro_manifest_dir "$WORK" '/bad\path' >/dev/null 2>&1 || RC=$?
[ "$RC" -eq 2 ]
for VALUE in relative "$WORK/missing-entrypoint"; do
  RC=0
  BABYDRO_BUILD_ROOT="$VALUE" sh "$HERE/prepare-dependencies.sh" >/dev/null 2>&1 || RC=$?
  [ "$RC" -eq 2 ]
done
[ ! -e "$WORK/missing-entrypoint" ]
printf 'All build-root contract checks passed\n'
