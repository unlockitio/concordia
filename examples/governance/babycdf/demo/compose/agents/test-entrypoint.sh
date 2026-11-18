#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${BABYCDF_BUILD_ROOT:?Set approved temporary directory for test artifacts}"
DIR=$(mktemp -d "$BABYCDF_BUILD_ROOT/entrypoint-test-XXXXXXXX")
PKG=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
# CASE_LOCALE empty means "run with the locale this script inherited", which is
# the user's real environment. Any other value is exported for that case only.
CASE_LOCALE=
check() {
  expected=$1; name=$2; line=$3
  printf '%s\n' "$line" > "$DIR/$name"
  code=0
  if [ -n "$CASE_LOCALE" ]; then
    READBACK_FILE="$DIR/$name" LC_ALL="$CASE_LOCALE" sh "$HERE/entrypoint.sh" validate \
      > "$DIR/$name.log" 2>&1 || code=$?
  else
    READBACK_FILE="$DIR/$name" sh "$HERE/entrypoint.sh" validate \
      > "$DIR/$name.log" 2>&1 || code=$?
  fi
  if [ "$expected" = valid ]; then
    [ "$code" -eq 0 ] || { echo "FAIL: $name exit=$code expected0"; exit 1; }
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

# Locale regression: a bracket range like [0-9a-f] is matched by COLLATION
# order, so under almost every non-C locale macOS /bin/sh also admits A-E and an
# uppercase or mixed-case package ID silently validated. Each case is rerun
# under the inherited locale plus every locale below that is actually installed.
UPPER=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
MIXED=aAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaA
FFULL=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
DIGITS=0123456789012345678901234567890123456789012345678901234567890123
HEXMIX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
for loc in '' C en_US.UTF-8 pt_PT.UTF-8 de_DE.UTF-8 en_US.ISO8859-1 C.UTF-8; do
  if [ -n "$loc" ] && [ "$loc" != C ]; then
    locale -a 2>/dev/null | grep -qx "$loc" || continue
  fi
  CASE_LOCALE=$loc
  tag=${loc:-inherited}
  check invalid "upper-$tag"      "41010 party $UPPER governor user"
  check invalid "mixed-$tag"      "41010 party $MIXED governor user"
  check invalid "upper-x-$tag"    "41010 party ${PKG%?}X governor user"
  check invalid "badchar-$tag"    "41010 party ${PKG%?}g governor user"
  check invalid "punct-$tag"      "41010 party ${PKG%?}- governor user"
  check invalid "badport-$tag"    "4101O party $PKG governor user"
  check invalid "highport-$tag"   "65536 party $PKG governor user"
  check valid   "lower-$tag"      "41010 party $PKG governor user"
  check valid   "allf-$tag"       "41010 party $FFULL governor user"
  check valid   "digits-$tag"     "41010 party $DIGITS governor user"
  check valid   "hexmix-$tag"     "41010 party $HEXMIX governor user"
  check valid   "port1-$tag"      "1 party $HEXMIX governor user"
  check valid   "port65535-$tag"  "65535 party $HEXMIX governor user"
  CASE_LOCALE=
  printf -- '--- locale %s: package/port validation is ASCII-exact ---\n' "$tag"
done
printf 'All entrypoint cases passed; evidence retained at %s\n' "$DIR"
