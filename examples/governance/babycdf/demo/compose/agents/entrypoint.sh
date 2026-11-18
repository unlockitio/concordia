#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
fail() { echo "BLOCKED: $*" >&2; exit 2; }
READBACK_FILE=${READBACK_FILE:-/state/readback}
if [ ! -r "$READBACK_FILE" ]; then fail 'readback unavailable'; fi
# Validate under LC_ALL=C with explicit ASCII whitelists. A bracket RANGE such
# as [0-9a-f] is matched by collation order, not ASCII order, so on macOS
# /bin/sh (bash 3.2) it also accepts A-E in nearly every non-C locale and an
# uppercase package ID would pass. Byte-exact ${#var} lengths need LC_ALL=C too.
# The caller's locale is restored before any JVM is started.
if [ -n "${LC_ALL+x}" ]; then babycdf_lc_all_set=1; babycdf_lc_all_was=$LC_ALL
else babycdf_lc_all_set=0; babycdf_lc_all_was=''; fi
LC_ALL=C
export LC_ALL
# Exactly one line, five single-space-delimited fields; preserve empty columns.
if ! awk 'NR != 1 || NF != 5 || $0 !~ /^[^ \t]+ [^ \t]+ [^ \t]+ [^ \t]+ [^ \t]+$/ {bad=1} END {exit (bad || NR != 1)}' "$READBACK_FILE"; then
  fail 'readback must contain exactly five non-empty fields on one line'
fi
IFS=' ' read -r port party package governor user < "$READBACK_FILE" || fail 'unterminated readback'
case "$port" in ''|0*|*[!0123456789]*) fail 'invalid port';; esac
if [ "${#port}" -gt 5 ] || [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then fail 'port out of range'; fi
if [ "${#package}" -ne 64 ]; then fail 'invalid package length'; fi
case "$package" in *[!0123456789abcdef]*) fail 'invalid package ID';; esac
if [ -z "$party" ] || [ -z "$governor" ] || [ -z "$user" ]; then fail 'empty identity field'; fi
# Validation complete: restore the caller's locale for the agent process.
if [ "$babycdf_lc_all_set" = 1 ]; then LC_ALL=$babycdf_lc_all_was; export LC_ALL; else unset LC_ALL; fi
unset babycdf_lc_all_set babycdf_lc_all_was
# Test-only validation mode never starts a JVM or submits anything.
if [ "${1:-}" = validate ]; then echo 'readback valid'; exit 0; fi
INTERVAL=${AGENT_INTERVAL:-10}
# Shared Canton namespace uses internal port 6865, not host readback port.
case "${1:-}" in
  reviewer)
    exec java -cp '/app/classes:/app/lib/*' org.concordia.babycdf.Reviewer --local-sandbox 6865 "$party" "$package" --offline-pr184-file /state/proposal-blob.txt --interval "$INTERVAL" ;;
  advisory)
    # Text output only. The ledger-submission invocation (--local-sandbox) is
    # deliberately not reachable from this demo entrypoint, and ADVISORY_SUBMIT
    # is no longer honoured, so no configuration here can make an agent publish.
    if [ -n "${ADVISORY_SUBMIT:-}" ] && [ "${ADVISORY_SUBMIT}" != 0 ]; then
      fail 'ADVISORY_SUBMIT is not supported: demo agents respond in text only'
    fi
    exec java -cp '/app/classes:/app/lib/*' org.concordia.babycdf.Advisory --offline-pr184-file /state/proposal-blob.txt --interval "$INTERVAL" ;;
  *) fail 'unknown agent';;
esac
