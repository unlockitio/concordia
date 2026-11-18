#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${BABYCDF_BUILD_ROOT:?Set approved temporary directory for test artifacts}"
DIR=$(mktemp -d "$BABYCDF_BUILD_ROOT/entrypoint-test-XXXXXXXX")
PKG=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
check() {
  expected=$1; name=$2; line=$3
  printf '%s\n' "$line" > "$DIR/$name"
  code=0
  READBACK_FILE="$DIR/$name" sh "$HERE/entrypoint.sh" validate > "$DIR/$name.log" 2>&1 || code=$?
  if [ "$expected" = valid ]; then
    [ "$code" -eq 0 ] || exit 1
  else
    [ "$code" -eq 2 ] || { echo "FAIL: $name exit=$code expected2"; exit 1; }
    grep -q 'BLOCKED:' "$DIR/$name.log" || { echo "FAIL: missing blocker $name"; exit 1; }
  fi
  printf '%s exit=%s\n' "$name" "$code"
}
check invalid fewer "41010 party $PKG governor"
check invalid zero "0 party $PKG governor user"
check invalid empty-party "41010  $PKG governor user"
check invalid empty-governor "41010 party $PKG  user"
check invalid bad-package '41010 party bad governor user'
check invalid extra "41010 party $PKG governor user extra"
check invalid leading-zero "041010 party $PKG governor user"
check invalid high-port "65536 party $PKG governor user"
check invalid tab "41010	party $PKG governor user"
check invalid uppercase '41010 party AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA governor user'
check invalid long-package "41010 party ${PKG}a governor user"
check invalid empty ''
code=0
READBACK_FILE="$DIR/missing" sh "$HERE/entrypoint.sh" validate > "$DIR/missing.log" 2>&1 || code=$?
[ "$code" -eq 2 ] && grep -q 'BLOCKED:' "$DIR/missing.log" || exit 1
printf 'missing exit=%s\n' "$code"
check valid valid "41010 party $PKG governor user"
printf 'All entrypoint cases passed; evidence retained at %s\n' "$DIR"
