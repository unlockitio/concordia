#!/bin/sh
# Source-only helper for run-demo.sh; raw_log_path must be defined by caller.
# Execute in a subshell so a stage cannot overwrite the runner's variables.
# Contract: stage functions must explicitly return the relevant command status.
# "$@" is on the left of ||, so errexit is NOT relied on inside stage functions.
up_stage() (
  stage_key=$1
  stage_title=$2
  shift 2
  stage_log=$(raw_log_path "up-$stage_key") || exit 2
  stage_code=0
  # The descriptor is established before command invocation. A failure to open
  # it refuses the stage, rather than running an unrecorded command.
  if ! exec 3>>"$stage_log"; then
    printf '[up] %s ... REFUSED (cannot capture %s)\n' "$stage_title" "$stage_log" >&2 || :
    exit 2
  fi
  "$@" >&3 2>&1 || stage_code=$?
  if [ "${VERBOSE:-0}" = 1 ]; then
    cat "$stage_log" || :
  fi
  if [ "$stage_code" -ne 0 ]; then
    printf '[up] %s ... FAILED (exit %s)\n' "$stage_title" "$stage_code" >&2 || :
    tail -n 30 "$stage_log" >&2 || :
    printf 'Full stage log: %s\n' "$stage_log" >&2 || :
    exit "$stage_code"
  fi
  printf '[up] %s ... ok\n' "$stage_title" || :
  exit "$stage_code"
)
