#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Focused behavioural tests for the build-root contract shared by both public
# entrypoints (run-demo.sh and prepare-dependencies.sh).
#
# These are cheap and hermetic: they use temporary fixtures and a stub `dpm`, so
# nothing is built and no network or Docker is touched. Real builds and the live
# sequence are validated separately.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/build-root.sh"

PASS=0
FAIL=0
check() { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then PASS=$((PASS + 1)); echo "  ok   $1"
  else FAIL=$((FAIL + 1)); echo "  FAIL $1: expected '$2', got '$3'"; fi
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/babycdf-build-root-test-XXXXXX")
trap 'rm -f -- "$WORK"/pkg/.build/mp-*/multi-package.yaml "$WORK"/stub/dpm 2>/dev/null || true' EXIT HUP INT TERM

echo 'build-root contract'

# 1. Unset -> defaults to <package>/.build and creates it.
mkdir -p "$WORK/pkg"
(
  unset BABYCDF_BUILD_ROOT
  babycdf_build_root "$WORK/pkg" >/dev/null 2>&1
  printf '%s\n' "$BABYCDF_BUILD_ROOT" > "$WORK/default-root"
)
EXPECT=$(CDPATH= cd -- "$WORK/pkg/.build" && pwd -P)
check 'unset defaults to .build' "$EXPECT" "$(cat "$WORK/default-root")"
check 'unset creates the directory' 'yes' "$([ -d "$WORK/pkg/.build" ] && echo yes || echo no)"

# 2. Relative explicit override is rejected, and creates nothing.
BEFORE=$(ls -A "$WORK" | wc -l | tr -d ' ')
RC=0
( BABYCDF_BUILD_ROOT='relative/path'; babycdf_build_root "$WORK/pkg" ) >/dev/null 2>&1 || RC=$?
check 'relative override rejected' '2' "$RC"
check 'relative override creates nothing' 'no' "$([ -e "$WORK/relative" ] && echo yes || echo no)"
check 'relative override leaves work dir unchanged' "$BEFORE" "$(ls -A "$WORK" | wc -l | tr -d ' ')"

# 3. Non-existent absolute override is rejected, and is NOT created.
MISSING="$WORK/does-not-exist-$$"
RC=0
( BABYCDF_BUILD_ROOT="$MISSING"; babycdf_build_root "$WORK/pkg" ) >/dev/null 2>&1 || RC=$?
check 'nonexistent override rejected' '2' "$RC"
check 'nonexistent override is not created' 'no' "$([ -e "$MISSING" ] && echo yes || echo no)"

# 4. Existing absolute override with SPACES is accepted and canonicalized.
SPACED="$WORK/with spaces/build root"
mkdir -p "$SPACED"
(
  BABYCDF_BUILD_ROOT="$SPACED"
  babycdf_build_root "$WORK/pkg" >/dev/null 2>&1
  printf '%s\n' "$BABYCDF_BUILD_ROOT" > "$WORK/spaced-root"
)
check 'spaced existing override accepted' "$(CDPATH= cd -- "$SPACED" && pwd -P)" "$(cat "$WORK/spaced-root")"

echo 'manifest isolation'

# 5. A caller-owned $BUILD/multi-package.yaml is preserved byte-for-byte.
BUILD="$WORK/pkg/.build"
printf 'packages:\n- /caller/owned\n' > "$BUILD/multi-package.yaml"
CALLER_SUM=$(shasum -a 256 "$BUILD/multi-package.yaml" | awk '{print $1}')
DIR_A=$(babycdf_manifest_dir "$BUILD" "/tmp/pkg one" "/tmp/pkg-two")
check 'caller manifest preserved' "$CALLER_SUM" "$(shasum -a 256 "$BUILD/multi-package.yaml" | awk '{print $1}')"
check 'generated manifest is elsewhere' 'no' "$([ "$DIR_A" = "$BUILD" ] && echo yes || echo no)"

# 6. Spaces in package paths survive as quoted YAML scalars.
check 'spaced path quoted' '- "/tmp/pkg one"' "$(sed -n '2p' "$DIR_A/multi-package.yaml")"

# 7. Concurrent preparations get unique directories and cannot collide.
DIR_B=$(babycdf_manifest_dir "$BUILD" "/tmp/other")
check 'isolated dirs are unique' 'no' "$([ "$DIR_A" = "$DIR_B" ] && echo yes || echo no)"
check 'first manifest untouched by second' '- "/tmp/pkg one"' "$(sed -n '2p' "$DIR_A/multi-package.yaml")"
check 'second manifest independent' '- "/tmp/other"' "$(sed -n '2p' "$DIR_B/multi-package.yaml")"

# 8. Unsupported characters are rejected rather than emitted unescaped.
RC=0
babycdf_manifest_dir "$BUILD" '/tmp/has"quote' >/dev/null 2>&1 || RC=$?
check 'double quote rejected' '2' "$RC"
RC=0
babycdf_manifest_dir "$BUILD" '/tmp/has\backslash' >/dev/null 2>&1 || RC=$?
check 'backslash rejected' '2' "$RC"

echo 'entrypoint wiring (stub dpm, no real build)'

# 9. Both public entrypoints reject the same invalid overrides, creating nothing.
mkdir -p "$WORK/stub"
printf '#!/bin/sh\nexit 0\n' > "$WORK/stub/dpm"
chmod +x "$WORK/stub/dpm"
for script in "$HERE/demo/run-demo.sh up" "$HERE/prepare-dependencies.sh"; do
  name=$(basename "${script%% *}")
  RC=0
  OUT=$(unset INTAKE_GITHUB_TOKEN_FILE LLM_API_KEY_FILE BABYCDF_LLM_ENABLED; export INTAKE_DISCOVERY_MODE=fixture; PATH="$WORK/stub:$PATH" BABYCDF_BUILD_ROOT='relative/path' sh $script 2>&1) || RC=$?
  check "$name rejects relative" '2' "$RC"
  REASON=no
  case "$OUT" in *'must be absolute'*) REASON=yes ;; esac
  check "$name names the reason" 'yes' "$REASON"
  MISS="$WORK/missing-for-$name"
  RC=0
  OUT=$(unset INTAKE_GITHUB_TOKEN_FILE LLM_API_KEY_FILE BABYCDF_LLM_ENABLED; export INTAKE_DISCOVERY_MODE=fixture; PATH="$WORK/stub:$PATH" BABYCDF_BUILD_ROOT="$MISS" sh $script 2>&1) || RC=$?
  check "$name rejects nonexistent" '2' "$RC"
  check "$name does not create it" 'no' "$([ -e "$MISS" ] && echo yes || echo no)"
done

printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
