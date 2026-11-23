# Anonymous public Concordia evidence capture

R3: scanner recursively inventories all JSON; nested/unexpected/missing captures
and duplicate checksum entries are rejected. Sensitive field names are rejected
case-insensitively even when values look harmless. Pattern scanning is NOT proof
that no secret exists; public-source capture and human review remain necessary.
`python3 test-check-fixtures.py` runs adversarial checks in disposable copies.

R2 reproducibility: `python3 check-fixtures.py --sanitize` rewrites sanitized JSON
and SHA256SUMS, `python3 check-fixtures.py` scans every JSON for emails/token forms/
Bearer/Authorization/token=/IPv4/IPv6/private URLs, verifies all checksums and the
explicit PUBLIC-LOGINS.json allowlist. Retained logins are public evidence only.
concordia-amendments.json is intentionally EMPTY: no explicit checked evidence for
an amendment link was captured, including475; unlinked PRs retain their own round.

Captured 2026-11-23 from canton-foundation/canton-dev-fund, no Authorization/PAT.
PRs 184,473,474,475,1028: metadata, full first files page and recursive head tree.
Issues538..545: issue, comments, events and labels.47 requests attempted of60;
13 local budget and13 server remaining. capture-budget.json records every request
and pagination gap indicator. No rel=next observed. Recursive trees must still be
checked for truncated=true by consumers. No removed-file base-tree proof captured.

JSON envelope retains retrieval time/source/ETag/Link. Emails, token-shaped text,
patch/verification metadata removed; IDs, times, logins and comment evidence kept.
Sanitization means fixture body bytes are NOT independently original raw-body hash
proof if redaction occurred. Historical edited issue text cannot be recovered from
current API metadata: mark blocked_pin rather than substitute today's text/head.
No historical branch reachability capture claimed. No delivery commit proofs
captured; source enrichment must fail closed where they are absent.

#540 includes command6038684918 Oct7 and6606684361 Nov3, bot vote-created comments,
and passed result6792262767 Nov11. #541 command7048515679 has vote-created but no
closing result in this capture. GitVote parsing must not equate merged/closed to
vote result. See exact bodies for failed round format; unparsed/conflicting evidence
remains unresolved. SHA256SUMS covers all JSON captures including budget receipt.
