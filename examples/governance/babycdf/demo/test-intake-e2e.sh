#!/bin/sh
# Explicit opt-in live command driver, NOT an asserting E2E acceptance test.
# Never invoked by offline tests or up.
set -eu
[ "${BABYCDF_ALLOW_LIVE_E2E:-0}" = 1 ] || { echo 'REFUSED: set BABYCDF_ALLOW_LIVE_E2E=1 only with authorization for local runtime mutations' >&2; exit 2; }
HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
# Deliberately leave the network/evidence available on failure; no automatic cleanup.
sh "$HERE/run-demo.sh" up
sh "$HERE/run-demo.sh" all
sh "$HERE/ledger.sh" grpc contracts
sh "$HERE/ledger.sh" grpc history
printf '%s\n' 'Live commands completed. Inspect Intake OpenAgenda transactions, both approvals and journal evidence before accepting; no automatic acceptance claim.'
