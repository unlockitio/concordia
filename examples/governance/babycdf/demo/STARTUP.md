# Bounded startup readiness

`BABYCDF_STARTUP_TIMEOUT` accepts decimal seconds 180..1800 (default 600).
Invalid values fail before cleanup, Docker or bootstrap. The same bound controls
Compose readiness waiting and Canton healthcheck startup grace. The healthcheck
still requires the connected `credential-synchronizer`; grace never means healthy.
Warnings and failed stage logs remain visible. No automatic bootstrap replay or
resume is implemented: failed readiness prevents bootstrap and agent startup.

The observed 180-second failure left a running, non-OOM, zero-restart Canton that
later became healthy and had its bootstrap-ready marker. This distinguishes a
slow cold start from a crash, but retained health history does not establish the
exact first-healthy instant. A 600-second default is a bounded cold-start allowance,
not a guarantee; investigate repeated failures rather than raising it indefinitely.

Do not edit `run-demo.sh` while it is executing. The host Bash 3.2 `/bin/sh`
parses a case branch before executing it, then resumes reading at its prior byte
offset. Inserting bytes during the long-running branch can cause a spurious syntax
error after successful startup. Finish the invocation before applying edits.
