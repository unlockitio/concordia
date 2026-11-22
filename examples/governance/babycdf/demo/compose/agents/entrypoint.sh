#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
fail() { echo "BLOCKED: $*" >&2; exit 2; }
if [ "${1:-}" = intake ]; then READBACK_FILE=${READBACK_FILE:-/state/readback-intake};
else READBACK_FILE=${READBACK_FILE:-/state/readback}; fi
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
# Presentation-first logs are the container default: a demo audience reads the
# agent's conclusions, not its JSON. The flag only changes what is printed; it
# touches no check, no digest verification and no capability. DEMO_AGENT_VERBOSE
# (any value other than empty or 0) restores the full machine-readable output,
# and the agents' own CLIs still default to the original behaviour when invoked
# directly without --demo.
DEMO_FLAGS='--demo'
if [ -n "${DEMO_AGENT_VERBOSE:-}" ] && [ "${DEMO_AGENT_VERBOSE}" != 0 ]; then
  DEMO_FLAGS='--demo --verbose'
fi
case "${AGENT_EVIDENCE_MODE:-}" in
  ''|offline) ;;
  *) fail 'AGENT_EVIDENCE_MODE must be empty or offline' ;;
esac
# File auth selects live evidence; offline is an explicit fallback.
EVIDENCE_FLAGS='--offline-pr184-file /state/proposal-blob.txt'
if [ -n "${INTAKE_GITHUB_TOKEN_FILE:-}" ] && [ "${AGENT_EVIDENCE_MODE:-live}" != offline ]; then
  EVIDENCE_FLAGS=''
fi
# Shared Canton namespace uses internal port 6865, not host readback port.
case "${1:-}" in
  intake)
    META=${INTAKE_PACKAGE_FILE:-/state/intake-demo-package}
    [ -r "$META" ] && [ ! -L "$META" ] || fail 'Intake demo package metadata unavailable'
    demo_package=$(LC_ALL=C awk 'NR != 1 || $0 !~ /^[0123456789abcdef]+$/ || length($0) != 64 {bad=1} END {if(bad || NR != 1) exit 1; print $0}' "$META") || fail 'invalid Intake demo package metadata'
    case "$user" in *-intake) ;; *) fail 'Intake requires dedicated intake user';; esac
    JOURNAL=${INTAKE_JOURNAL:-/journal}
    [ -d "$JOURNAL" ] && [ ! -L "$JOURNAL" ] && [ -w "$JOURNAL" ] || fail 'existing writable Intake journal required'
    exec java -cp '/app/classes:/app/lib/*' org.concordia.babycdf.Intake --offline-fixture /state/proposal-blob.txt 6865 "$party" "$package" "$demo_package" "$governor" "$user" "$JOURNAL" --poll "${INTAKE_POLL_ATTEMPTS:-86400}" "${INTAKE_POLL_MILLIS:-1000}" ;;
  reviewer)
    # shellcheck disable=SC2086  # DEMO_FLAGS is a script-controlled literal word list
    exec java -cp '/app/classes:/app/lib/*' org.concordia.babycdf.Reviewer --local-sandbox 6865 "$party" "$package" $EVIDENCE_FLAGS --interval "$INTERVAL" $DEMO_FLAGS ;;
  advisory) fail 'Advisory retired: use reviewer; no second poller is supported' ;;
  *) fail 'unknown agent';;
esac
