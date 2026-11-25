# Primary governor protocol

This change is for fresh setup only. Existing deployed contracts and credentials are not modified or extended automatically. Rebuild packages and regenerate their bindings before a fresh setup; the new opening choice arguments are incompatible with old callers.

## Seats and approval

The governor has five permanent primary seats and two credentialed substitutes. Substitute assignment is PENDING: no assignment or independent substitute voting authority is implemented. Substitutes may observe the governor; they do not add votes.

A resolution accounts for exactly the five primary ballots, including absent ballots. Three distinct valid primary YES votes approve immediately, including before the closing time. Two YES votes never approve, even if other primary governors abstain or are absent. Before the deadline, fewer than three YES votes leave the existing proposal pending. At the deadline, ordinary nonapproval applies. Early rejection is not supported.

An early approval authorizes execution immediately. Manual proposal and milestone commands must resolve and execute the same existing proposal contract ID. A pending resume must not open a new round, fabricate ballots, or repeat an existing cast. Milestone execution still depends on the actual approved proposal, not fabricated approval evidence.

## Credentials and opening

All seven bootstrap credentials use the voting-right factory and registered-right acceptance flow. Only the five primary credentials establish opening coverage. Opening presents exactly one actual rights reference for every primary governor, including primaries who will not cast a vote. Each right must be valid both now and at the proposed closing time. Expiry is exclusive: `expiresAt == closesAt` is invalid. Registered-right registration validity is checked at both times too.

Resolution independently checks actual participating primary rights at both resolution time and closing time. Holder, issuer, consortium, unit weight, and registry checks remain required. Ballots retain their round, action, electorate, credential, and controller checks. Duplicate voters, foreign ballots, alternate ballots, and extra ballots do not establish approval.

## Fresh bootstrap lifetime

`BABYCDF_CREDENTIAL_VALIDITY` defaults to `30d`. Values are whole positive hours or days, from `1h` through `365d`, for example `48h` or `2d`. Invalid configuration must be rejected before startup mutations. This setting applies only to newly issued bootstrap credentials and never renews an existing credential.

A fresh 30-day credential covers a fresh 21-day voting window. That is not a universal guarantee: an aged credential with exactly 21 days remaining cannot cover a new 21-day window because expiry is exclusive. A 365-day voting window cannot be opened using 30-day credentials. Obtain appropriately valid credentials through the existing authorized issuance process before opening; do not extend a deadline, replace authority, or reset a round automatically.

## Validation status

This document describes the requested protocol. It is not a claim that implementation, generated bindings, or tests have completed. Consult the final root `.build/three-yes-closure/REPORT.md` for revision-bound validation and package IDs.
