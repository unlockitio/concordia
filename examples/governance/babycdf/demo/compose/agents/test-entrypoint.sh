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

# Presentation flags. The entrypoint must hand the agents --demo by default and
# add --verbose only when DEMO_AGENT_VERBOSE asks for it, without weakening any
# validation and without ever enabling publication. The agent command line is
# captured with a stub `java` on PATH, so no JVM and no ledger are involved.
STUB=$DIR/stub
mkdir -p "$STUB"
cat > "$STUB/java" <<'STUBEOF'
#!/bin/sh
printf '%s\n' "$*" > "$ARGS_FILE"
STUBEOF
chmod +x "$STUB/java"
READBACK=$DIR/flags-readback
printf '%s\n' "41010 party $PKG governor user" > "$READBACK"

flags_case() {
  name=$1; agent=$2; expect_verbose=$3
  ARGS_FILE="$DIR/$name.args"
  export ARGS_FILE
  code=0
  if [ "$name" = verbose-on" " ] || [ "$expect_verbose" = yes ]; then
    READBACK_FILE="$READBACK" PATH="$STUB:$PATH" DEMO_AGENT_VERBOSE=1 \
      sh "$HERE/entrypoint.sh" "$agent" >/dev/null 2>&1 || code=$?
  else
    READBACK_FILE="$READBACK" PATH="$STUB:$PATH" \
      sh "$HERE/entrypoint.sh" "$agent" >/dev/null 2>&1 || code=$?
  fi
  unset ARGS_FILE
  [ "$code" -eq 0 ] || { echo "FAIL: $name exit=$code"; exit 1; }
  args=$(cat "$DIR/$name.args")
  case "$args" in
    *--demo*) ;;
    *) echo "FAIL: $name is missing --demo: $args"; exit 1 ;;
  esac
  if [ "$expect_verbose" = yes ]; then
    case "$args" in
      *--verbose*) ;;
      *) echo "FAIL: $name should carry --verbose: $args"; exit 1 ;;
    esac
  else
    case "$args" in
      *--verbose*) echo "FAIL: $name must not carry --verbose by default: $args"; exit 1 ;;
    esac
  fi
  # The safety-relevant arguments must survive the presentation change.
  case "$agent" in
    reviewer)
      case "$args" in
        *"--local-sandbox 6865 party $PKG"*) ;;
        *) echo "FAIL: $name lost its read-only sandbox arguments: $args"; exit 1 ;;
      esac ;;
    advisory)
      case "$args" in
        *--local-sandbox*) echo "FAIL: advisory must never be given a submission adapter: $args"; exit 1 ;;
      esac
      case "$args" in
        *"--read-only-sandbox 6865 party $PKG"*) ;;
        *) echo "FAIL: advisory lost its read-only party filter: $args"; exit 1 ;;
      esac ;;
  esac
  case "$args" in
    *"--integrated-agenda"*) ;;
    *) echo "FAIL: $name lost its exact integrated agenda mode: $args"; exit 1 ;;
  esac
  printf '%s flags ok: %s\n' "$name" "$args"
}

# Baseline assertions are intentionally overlay-free, regardless of caller env.
unset INTAKE_GITHUB_TOKEN_FILE AGENT_EVIDENCE_MODE
flags_case reviewer-default reviewer no
flags_case reviewer-verbose reviewer yes

for agent in reviewer; do
  ARGS_FILE="$DIR/$agent-live.args"; export ARGS_FILE
  READBACK_FILE="$READBACK" PATH="$STUB:$PATH" INTAKE_GITHUB_TOKEN_FILE=/synthetic/not-read sh "$HERE/entrypoint.sh" "$agent"
  case "$(cat "$ARGS_FILE")" in *--offline-pr184-file*) echo 'FAIL: live auth selects offline'; exit 1;; esac
  READBACK_FILE="$READBACK" PATH="$STUB:$PATH" INTAKE_GITHUB_TOKEN_FILE=/synthetic/not-read AGENT_EVIDENCE_MODE=offline sh "$HERE/entrypoint.sh" "$agent"
  case "$(cat "$ARGS_FILE")" in *--offline-pr184-file*) ;; *) echo 'FAIL: explicit offline lost'; exit 1;; esac
  unset ARGS_FILE
done

code=0
READBACK_FILE="$READBACK" PATH="$STUB:$PATH" AGENT_EVIDENCE_MODE=unknown sh "$HERE/entrypoint.sh" reviewer > "$DIR/unknown-mode.log" 2>&1 || code=$?
[ "$code" -eq 2 ] || { echo 'FAIL: unknown evidence mode accepted'; exit 1; }
grep -q 'AGENT_EVIDENCE_MODE must be empty or offline' "$DIR/unknown-mode.log" || exit 1

# DEMO_AGENT_VERBOSE=0 means "no", like unset: the demo default stays quiet.
ARGS_FILE="$DIR/verbose-zero.args"
export ARGS_FILE
READBACK_FILE="$READBACK" PATH="$STUB:$PATH" DEMO_AGENT_VERBOSE=0 \
  sh "$HERE/entrypoint.sh" reviewer >/dev/null 2>&1 || { echo 'FAIL: verbose-zero'; exit 1; }
unset ARGS_FILE
case "$(cat "$DIR/verbose-zero.args")" in
  *--verbose*) echo 'FAIL: DEMO_AGENT_VERBOSE=0 must not enable --verbose'; exit 1 ;;
esac
printf 'verbose-zero flags ok\n'

# Publication must still be refused, whatever the presentation settings are.
code=0
READBACK_FILE="$READBACK" PATH="$STUB:$PATH" ADVISORY_SUBMIT=1 \
  sh "$HERE/entrypoint.sh" advisory > "$DIR/submit.log" 2>&1 || code=$?
[ "$code" -eq 2 ] || { echo "FAIL: ADVISORY_SUBMIT=1 exit=$code expected 2"; exit 1; }
grep -q 'BLOCKED:' "$DIR/submit.log" || { echo 'FAIL: missing publication blocker'; exit 1; }
printf 'advisory-submit still blocked exit=%s\n' "$code"

# An invalid readback must still fail closed before any agent is started.
code=0
printf '%s\n' '41010 party bad governor user' > "$DIR/flags-bad"
READBACK_FILE="$DIR/flags-bad" PATH="$STUB:$PATH" \
  sh "$HERE/entrypoint.sh" reviewer > "$DIR/flags-bad.log" 2>&1 || code=$?
[ "$code" -eq 2 ] || { echo "FAIL: invalid readback with agent start exit=$code"; exit 1; }
printf 'invalid readback still blocks agent start exit=%s\n' "$code"

printf 'All entrypoint cases passed; evidence retained at %s\n' "$DIR"
